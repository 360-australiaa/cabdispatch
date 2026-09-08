"""Tests for real PDF receipt generation + email/SMS delivery
(`POST /v1/trips/{id}/receipt/email` and `.../receipt/sms`, blueprint
5.2.6/8.5).

Mirrors test_trips.py's fixture pattern (seed a real tariffs-domain row,
create + close a trip via the HTTP API) plus test_payments.py's
mock-fallback assertions (no real SendGrid/Twilio credentials configured in
this dev environment -> every send must fall back to a clearly-flagged mock
response) and test_compliance.py's on-disk assertions (the PDF must actually
land on disk under uploads/{tenant_id}/receipts/ with real, non-trivial
content).
"""
from __future__ import annotations

import uuid
from datetime import UTC, datetime
from decimal import Decimal

import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.tariffs import Tariff as TariffRow
from app.models.tenant import Tenant
from app.models.trips import Trip  # noqa: F401 — registers the table, see test_trips.py
from app.services.receipts import BACKEND_ROOT, _lookup_tenant_branding, fare_line_items
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


# --- fixtures / helpers (same shape as test_trips.py) -----------------------


async def _seed_tariff(session: AsyncSession, *, tenant_id: str) -> TariffRow:
    row = TariffRow(
        tenant_id=tenant_id,
        name="Standard Urban",
        region="urban",
        effective_from=datetime(2025, 11, 3, tzinfo=UTC),
        booked=False,
        flag_fall=Decimal("5.00"),
        peak_charge=Decimal("2.56"),
        dist_rate_1=Decimal("2.52"),
        dist_rate_2=Decimal("2.29"),
        night_rate_1=Decimal("3.00"),
        night_rate_2=Decimal("2.73"),
        holiday_rate_1=Decimal(0),
        holiday_rate_2=Decimal(0),
        waiting_rate_per_min=Decimal("1.092"),
    )
    session.add(row)
    await session.commit()
    await session.refresh(row)
    return row


async def _tenant_of(headers: dict) -> str:
    from app.core import security

    token = headers["Authorization"].split(" ", 1)[1]
    return security.decode_token(token)["tenant_id"]


def _user_id_of(headers: dict) -> str:
    """The authenticated caller's own user id, straight out of the bearer
    token. `POST /v1/trips` now enforces that a driver-role caller may only
    open a trip in their OWN name (app.api.v1.trips._require_trip_write_access
    - backend audit S4, "No role/ownership check on any trip write"), so a
    trip fixture built for a driver must be attributed to that driver rather
    than to a fresh random uuid."""
    from app.core import security

    token = headers["Authorization"].split(" ", 1)[1]
    return security.decode_token(token)["sub"]


def _trip_payload(*, tariff_id: str, **overrides) -> dict:
    payload = {
        "client_uuid": str(uuid.uuid4()),
        "vehicle_id": str(uuid.uuid4()),
        "driver_id": str(uuid.uuid4()),
        "tariff_id": tariff_id,
        "type": "rank_hail",
        "start_lat": -33.8688,
        "start_lng": 151.2093,
        "start_at": datetime.now(UTC).isoformat(),
        "payment_method": "card",
    }
    payload.update(overrides)
    return payload


async def _create_and_close_trip(client: AsyncClient, headers: dict, tariff_id: str) -> dict:
    resp = await client.post(
        "/v1/trips",
        json=_trip_payload(tariff_id=tariff_id, driver_id=_user_id_of(headers)),
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    trip = resp.json()

    # A little telemetry so the fare isn't all zeros.
    tick_resp = await client.patch(
        f"/v1/trips/{trip['id']}/tick",
        json={
            "points": [
                {"lat": -33.87, "lng": 151.21, "speed_kmh": 40, "ts": datetime.now(UTC).isoformat()},
            ]
        },
        headers=headers,
    )
    assert tick_resp.status_code == 200, tick_resp.text

    close_resp = await client.post(
        f"/v1/trips/{trip['id']}/close",
        json={"end_lat": -33.88, "end_lng": 151.22, "payment_method": "card"},
        headers=headers,
    )
    assert close_resp.status_code == 200, close_resp.text
    return close_resp.json()


async def _open_trip(client: AsyncClient, headers: dict, tariff_id: str) -> dict:
    resp = await client.post(
        "/v1/trips",
        json=_trip_payload(tariff_id=tariff_id, driver_id=_user_id_of(headers)),
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


# --- email receipt ------------------------------------------------------------


async def test_email_receipt_falls_back_to_mock_without_sendgrid_key(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_and_close_trip(client, headers, tariff.id)

    resp = await client.post(
        f"/v1/trips/{trip['id']}/receipt/email",
        json={"to_email": "rider@example.com"},
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["mock"] is True
    assert body["would_send_to"] == "rider@example.com"
    assert body["to_email"] is None
    assert body["sendgrid_status_code"] is None
    assert body["receipt_ref"] == trip["receipt_ref"]
    assert body["pdf_generated_now"] is True
    assert body["pdf_relative_path"].startswith(f"uploads/{tenant_id}/receipts/")


async def test_email_receipt_writes_a_real_pdf_to_disk(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_and_close_trip(client, headers, tariff.id)

    resp = await client.post(
        f"/v1/trips/{trip['id']}/receipt/email",
        json={"to_email": "rider@example.com"},
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    relative_path = resp.json()["pdf_relative_path"]

    on_disk = BACKEND_ROOT / relative_path
    assert on_disk.is_file()
    content = on_disk.read_bytes()
    # Real PDF, not a stub: valid header + non-trivial size.
    assert content.startswith(b"%PDF-")
    assert len(content) > 1000


async def test_email_receipt_second_call_reuses_existing_pdf(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_and_close_trip(client, headers, tariff.id)

    first = await client.post(
        f"/v1/trips/{trip['id']}/receipt/email", json={"to_email": "a@example.com"}, headers=headers
    )
    assert first.json()["pdf_generated_now"] is True

    second = await client.post(
        f"/v1/trips/{trip['id']}/receipt/email", json={"to_email": "b@example.com"}, headers=headers
    )
    assert second.status_code == 200
    assert second.json()["pdf_generated_now"] is False
    assert second.json()["pdf_relative_path"] == first.json()["pdf_relative_path"]


async def test_email_receipt_rejects_open_trip(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _open_trip(client, headers, tariff.id)

    resp = await client.post(
        f"/v1/trips/{trip['id']}/receipt/email",
        json={"to_email": "rider@example.com"},
        headers=headers,
    )
    assert resp.status_code == 409


async def test_email_receipt_requires_auth(client: AsyncClient):
    resp = await client.post(
        f"/v1/trips/{uuid.uuid4()}/receipt/email", json={"to_email": "rider@example.com"}
    )
    assert resp.status_code in (401, 403)


async def test_email_receipt_404s_for_unknown_trip(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    resp = await client.post(
        f"/v1/trips/{uuid.uuid4()}/receipt/email",
        json={"to_email": "rider@example.com"},
        headers=headers,
    )
    assert resp.status_code == 404


async def test_email_receipt_is_tenant_isolated(client: AsyncClient, session: AsyncSession):
    headers_a = await auth_headers(client, session, role="driver", tenant_name="Tenant A")
    tenant_a = await _tenant_of(headers_a)
    tariff_a = await _seed_tariff(session, tenant_id=tenant_a)
    trip = await _create_and_close_trip(client, headers_a, tariff_a.id)

    headers_b = await auth_headers(client, session, role="driver", tenant_name="Tenant B")
    resp = await client.post(
        f"/v1/trips/{trip['id']}/receipt/email",
        json={"to_email": "rider@example.com"},
        headers=headers_b,
    )
    assert resp.status_code == 404


# --- sms receipt ----------------------------------------------------------------


async def test_sms_receipt_falls_back_to_mock_without_twilio_credentials(
    client: AsyncClient, session: AsyncSession
):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_and_close_trip(client, headers, tariff.id)

    resp = await client.post(
        f"/v1/trips/{trip['id']}/receipt/sms",
        json={"to_phone": "+61400000000"},
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["mock"] is True
    assert body["would_send_to"] == "+61400000000"
    assert body["to_phone"] is None
    assert body["twilio_sid"] is None
    assert body["message"]
    # No fabricated public URL — the receipt isn't hosted anywhere in this
    # dev environment (see app.services.receipts._receipt_sms_body).
    assert "http://" not in body["message"]
    assert "https://" not in body["message"]
    assert body["receipt_ref"] == trip["receipt_ref"]
    assert body["pdf_relative_path"].startswith(f"uploads/{tenant_id}/receipts/")

    on_disk = BACKEND_ROOT / body["pdf_relative_path"]
    assert on_disk.is_file()
    assert on_disk.read_bytes().startswith(b"%PDF-")


async def test_sms_receipt_rejects_open_trip(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _open_trip(client, headers, tariff.id)

    resp = await client.post(
        f"/v1/trips/{trip['id']}/receipt/sms",
        json={"to_phone": "+61400000000"},
        headers=headers,
    )
    assert resp.status_code == 409


async def test_sms_receipt_requires_auth(client: AsyncClient):
    resp = await client.post(f"/v1/trips/{uuid.uuid4()}/receipt/sms", json={"to_phone": "+61400000000"})
    assert resp.status_code in (401, 403)


# --- receipt branding uses the real tenant, not a hardcoded placeholder (blueprint 7.2.10) -------


async def test_receipt_branding_uses_real_tenant_name_and_abn(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver", tenant_name="Lilly Cabs Pty Ltd")
    tenant_id = await _tenant_of(headers)

    # auth_headers creates the tenant with a null ABN — set a real one directly, same shape as any
    # tenant that filled in its company profile via a future Settings page.
    result = await session.execute(select(Tenant).where(Tenant.id == tenant_id))
    tenant = result.scalar_one()
    tenant.abn = "12 345 678 901"
    await session.commit()

    name, abn = await _lookup_tenant_branding(session, tenant_id=tenant_id)
    assert name == "Lilly Cabs Pty Ltd"
    assert abn == "12 345 678 901"


async def test_receipt_branding_falls_back_for_unknown_tenant(session: AsyncSession):
    name, abn = await _lookup_tenant_branding(session, tenant_id="does-not-exist")
    assert name == "Cab Dispatch"
    assert abn is None


# --- fare-breakdown reconciliation (app.services.receipts.fare_line_items) ---
#
# A fixed-price receipt used to itemise tolls/PSL/extras as ordinary additive
# rows even though a negotiated fare absorbs them, so the visible lines did not
# sum to the printed Subtotal. These assert the reconciliation directly rather
# than by eyeballing a generated PDF.


class _FakeTrip:
    """Minimal stand-in carrying only the columns fare_line_items reads."""

    def __init__(self, **kw):
        defaults = {
            "type": "rank_hail",
            "flag_fall": Decimal(0),
            "dist_amount": Decimal(0),
            "wait_amount": Decimal(0),
            "peak_amount": Decimal(0),
            "tolls": Decimal(0),
            "psl": Decimal(0),
            "extras": Decimal(0),
            "subtotal": Decimal(0),
            "surcharge": Decimal(0),
            "total": Decimal(0),
            "payment_method": "cash",
            "negotiated_total": None,
        }
        defaults.update(kw)
        for k, v in defaults.items():
            setattr(self, k, v)


def test_metered_trip_line_items_sum_to_subtotal():
    trip = _FakeTrip(
        flag_fall=Decimal("5.00"),
        dist_amount=Decimal("12.40"),
        wait_amount=Decimal("2.18"),
        tolls=Decimal("8.11"),
        psl=Decimal("1.32"),
        subtotal=Decimal("29.01"),
    )
    line_items, included = fare_line_items(trip)
    assert included == []
    assert sum(amount for _, amount in line_items) == trip.subtotal


def test_fixed_price_line_items_sum_to_subtotal_and_disclose_absorbed_components():
    # $50 agreed, all-inclusive: a real toll and levy were incurred and must
    # still be disclosed, but neither may be added on top of the agreed price.
    trip = _FakeTrip(
        flag_fall=Decimal("5.00"),
        dist_amount=Decimal("12.40"),
        tolls=Decimal("8.11"),
        psl=Decimal("1.32"),
        negotiated_total=Decimal("50.00"),
        subtotal=Decimal("50.00"),
    )
    line_items, included = fare_line_items(trip)

    # The additive rows reconcile to exactly the agreed price.
    assert sum(amount for _, amount in line_items) == Decimal("50.00")
    assert sum(amount for _, amount in line_items) == trip.subtotal

    # The absorbed components are still disclosed, just never additive.
    disclosed = {label: amount for label, amount in included}
    assert disclosed["Tolls"] == Decimal("8.11")
    assert disclosed["Passenger Service Levy (PSL)"] == Decimal("1.32")


def test_fixed_price_omits_absorbed_rows_that_are_zero():
    trip = _FakeTrip(negotiated_total=Decimal("40.00"), subtotal=Decimal("40.00"))
    line_items, included = fare_line_items(trip)
    assert sum(amount for _, amount in line_items) == Decimal("40.00")
    assert included == []


def test_fixed_price_card_payment_discloses_absorbed_surcharge_not_additive():
    """2026-09 product ruling: the non-cash surcharge is absorbed into a
    negotiated fare, same as tolls/PSL/extras — it must be disclosed (the
    operator needs its own record of what it absorbed) but never counted as
    one of the additive rows that reconcile to trip.subtotal."""
    trip = _FakeTrip(
        negotiated_total=Decimal("50.00"),
        subtotal=Decimal("50.00"),
        total=Decimal("50.00"),  # surcharge absorbed -- total == subtotal exactly
        payment_method="card",
        surcharge=Decimal("2.50"),
    )
    line_items, included = fare_line_items(trip)

    assert sum(amount for _, amount in line_items) == Decimal("50.00")
    assert sum(amount for _, amount in line_items) == trip.subtotal

    disclosed = {label: amount for label, amount in included}
    assert disclosed["Non-cash surcharge"] == Decimal("2.50")


def test_fixed_price_cash_payment_never_discloses_a_surcharge_row():
    trip = _FakeTrip(negotiated_total=Decimal("50.00"), subtotal=Decimal("50.00"), payment_method="cash")
    _, included = fare_line_items(trip)
    assert included == []


def test_airport_fixed_line_items_sum_to_subtotal_and_absorb_surcharge():
    """The Sydney Airport Fixed Fare Trial has the same all-inclusive
    character as a negotiated fare (2026-09 consistency call — see
    fare_engine.FareEngine.close's fixed_fare branch) — the receipt must
    treat it the same way, never itemising the flat $60/$80 as a zeroed-out
    metered breakdown that wouldn't reconcile to trip.subtotal."""
    trip = _FakeTrip(
        type="airport_fixed",
        subtotal=Decimal("60.00"),
        total=Decimal("60.00"),
        payment_method="card",
        surcharge=Decimal("3.00"),
    )
    line_items, included = fare_line_items(trip)

    assert line_items == [("Fixed fare (all-inclusive)", Decimal("60.00"))]
    assert sum(amount for _, amount in line_items) == trip.subtotal
    disclosed = {label: amount for label, amount in included}
    assert disclosed["Non-cash surcharge"] == Decimal("3.00")
