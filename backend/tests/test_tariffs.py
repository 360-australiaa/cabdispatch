"""Tests for the tariffs domain: CRUD on tariffs/extras, the append-only
change log, `/v1/tariffs/active`, `/v1/fares-order/current`, and the
Fares Order 422 validation rule on POST/PATCH.

NOTE: `app/api/v1/tariffs.py`'s `router` and `fares_order_router` are not yet
registered in `app.main` (that happens in a later integration step) — these
tests will 404 if the suite is run in total isolation right now. That is
expected; they're written correctly against the endpoints as built, for the
integration agent to run once routers are wired up.
"""
from __future__ import annotations

import base64
import json
from datetime import UTC, datetime, timedelta
from decimal import ROUND_HALF_UP, Decimal

import pytest
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ed25519

from app.core.database import AsyncSessionLocal
from app.models.tariffs import Tariff
from app.services.tariff_signing import RATE_FIELDS
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio

_RATE_QUANT = Decimal("0.0001")  # matches app.services.tariff_signing's quantization


def _urban_payload(**overrides) -> dict:
    payload = {
        "name": "Standard Urban",
        "region": "urban",
        "effective_from": datetime(2025, 11, 3, tzinfo=UTC).isoformat(),
        "effective_to": None,
        "booked": False,
        "flag_fall": "5.00",
        "peak_charge": "2.56",
        "dist_rate_1": "2.52",
        "dist_rate_2": "2.29",
        "night_rate_1": "3.00",
        "night_rate_2": "2.73",
        "holiday_rate_1": "0",
        "holiday_rate_2": "0",
        "waiting_rate_per_min": "1.092",
        "dist_km_threshold": "12",
        "speed_threshold_kmh": "26",
        "maxi_multiplier": "1.5",
        "multi_hire_pct": "0.75",
        "psl_amount": "1.32",
        "surcharge_pct_cap": "5.0",
    }
    payload.update(overrides)
    return payload


def _country_payload(**overrides) -> dict:
    payload = _urban_payload(
        name="Standard Country",
        region="country",
        peak_charge="0",
        dist_rate_1="2.41",
        dist_rate_2="3.30",
        night_rate_1="2.87",
        night_rate_2="3.93",
        holiday_rate_1="2.87",
        holiday_rate_2="3.93",
        waiting_rate_per_min="1.045",
    )
    payload.update(overrides)
    return payload


# --- CRUD: tariffs --------------------------------------------------------------


async def test_create_and_get_tariff(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await client.post("/v1/tariffs", json=_urban_payload(), headers=headers)
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["name"] == "Standard Urban"
    assert body["region"] == "urban"
    assert Decimal(body["flag_fall"]) == Decimal("5.00")
    assert body["tenant_id"] is not None

    get_resp = await client.get(f"/v1/tariffs/{body['id']}", headers=headers)
    assert get_resp.status_code == 200
    assert get_resp.json()["id"] == body["id"]


async def test_list_tariffs_paginated_and_filtered(client, session):
    headers = await auth_headers(client, session, role="admin")
    await client.post("/v1/tariffs", json=_urban_payload(name="Urban A"), headers=headers)
    await client.post("/v1/tariffs", json=_country_payload(name="Country A"), headers=headers)

    resp = await client.get("/v1/tariffs?region=urban", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] >= 1
    assert all(item["region"] == "urban" for item in body["items"])

    paged = await client.get("/v1/tariffs?limit=1&skip=0", headers=headers)
    assert paged.status_code == 200
    assert len(paged.json()["items"]) == 1


async def test_update_tariff_writes_change_log(client, session):
    headers = await auth_headers(client, session, role="admin")
    create_resp = await client.post(
        "/v1/tariffs", json=_urban_payload(booked=True, flag_fall="99.00"), headers=headers
    )
    assert create_resp.status_code == 201
    tariff_id = create_resp.json()["id"]

    update_resp = await client.patch(
        f"/v1/tariffs/{tariff_id}", json={"flag_fall": "120.00"}, headers=headers
    )
    assert update_resp.status_code == 200
    assert Decimal(update_resp.json()["flag_fall"]) == Decimal("120.00")

    log_resp = await client.get(f"/v1/tariffs/{tariff_id}/change-log", headers=headers)
    assert log_resp.status_code == 200
    log_body = log_resp.json()
    # one entry from create (before=None), one from update
    assert log_body["total"] == 2
    entries = {entry["before_json"] is None for entry in log_body["items"]}
    assert True in entries and False in entries


async def test_delete_tariff(client, session):
    headers = await auth_headers(client, session, role="admin")
    create_resp = await client.post("/v1/tariffs", json=_urban_payload(booked=True), headers=headers)
    tariff_id = create_resp.json()["id"]

    del_resp = await client.delete(f"/v1/tariffs/{tariff_id}", headers=headers)
    assert del_resp.status_code == 204

    get_resp = await client.get(f"/v1/tariffs/{tariff_id}", headers=headers)
    assert get_resp.status_code == 404


async def test_tariff_not_found_for_other_tenant(client, session):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Tenant A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Tenant B")

    create_resp = await client.post("/v1/tariffs", json=_urban_payload(booked=True), headers=headers_a)
    tariff_id = create_resp.json()["id"]

    resp = await client.get(f"/v1/tariffs/{tariff_id}", headers=headers_b)
    assert resp.status_code == 404


# --- Fares Order validation ------------------------------------------------------


async def test_create_rank_hail_tariff_exceeding_fares_order_is_rejected(client, session):
    headers = await auth_headers(client, session, role="admin")

    payload = _urban_payload(booked=False, flag_fall="999.00")  # way above the $5.00 cap
    resp = await client.post("/v1/tariffs", json=payload, headers=headers)
    assert resp.status_code == 422


async def test_create_rank_hail_tariff_within_fares_order_succeeds(client, session):
    headers = await auth_headers(client, session, role="admin")

    payload = _urban_payload(booked=False)  # exactly the Fares Order rates
    resp = await client.post("/v1/tariffs", json=payload, headers=headers)
    assert resp.status_code == 201


async def test_booked_tariff_skips_fares_order_validation(client, session):
    headers = await auth_headers(client, session, role="admin")

    payload = _urban_payload(booked=True, flag_fall="999.00")
    resp = await client.post("/v1/tariffs", json=payload, headers=headers)
    assert resp.status_code == 201


async def test_exempt_region_skips_fares_order_validation(client, session):
    headers = await auth_headers(client, session, role="admin")

    payload = _urban_payload(region="exempt", booked=False, flag_fall="999.00")
    resp = await client.post("/v1/tariffs", json=payload, headers=headers)
    assert resp.status_code == 201


async def test_update_that_would_violate_fares_order_is_rejected(client, session):
    headers = await auth_headers(client, session, role="admin")
    create_resp = await client.post("/v1/tariffs", json=_urban_payload(booked=False), headers=headers)
    tariff_id = create_resp.json()["id"]

    resp = await client.patch(
        f"/v1/tariffs/{tariff_id}", json={"flag_fall": "999.00"}, headers=headers
    )
    assert resp.status_code == 422

    # rejected update must not have been persisted
    get_resp = await client.get(f"/v1/tariffs/{tariff_id}", headers=headers)
    assert Decimal(get_resp.json()["flag_fall"]) == Decimal("5.00")


# --- /v1/tariffs/active ----------------------------------------------------------


async def test_get_active_tariff_resolves_currently_effective_row(client, session):
    headers = await auth_headers(client, session, role="admin")
    now = datetime.now(UTC)

    await client.post(
        "/v1/tariffs",
        json=_urban_payload(
            name="Old",
            booked=True,
            effective_from=(now - timedelta(days=30)).isoformat(),
            effective_to=(now - timedelta(days=1)).isoformat(),
        ),
        headers=headers,
    )
    await client.post(
        "/v1/tariffs",
        json=_urban_payload(name="Current", booked=True, effective_from=(now - timedelta(days=1)).isoformat()),
        headers=headers,
    )

    resp = await client.get("/v1/tariffs/active?region=urban", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["name"] == "Current"


async def test_get_active_tariff_404_when_none_effective(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Empty Tenant")

    resp = await client.get("/v1/tariffs/active?region=country", headers=headers)
    assert resp.status_code == 404


async def test_get_active_tariff_at_specific_instant(client, session):
    headers = await auth_headers(client, session, role="admin")
    anchor = datetime(2020, 1, 1, tzinfo=UTC)

    await client.post(
        "/v1/tariffs",
        json=_urban_payload(
            name="Y2020",
            booked=True,
            effective_from=anchor.isoformat(),
            effective_to=(anchor + timedelta(days=365)).isoformat(),
        ),
        headers=headers,
    )

    resp = await client.get(
        "/v1/tariffs/active",
        params={"region": "urban", "at": (anchor + timedelta(days=10)).isoformat()},
        headers=headers,
    )
    assert resp.status_code == 200
    assert resp.json()["name"] == "Y2020"

    resp_outside = await client.get(
        "/v1/tariffs/active",
        params={"region": "urban", "at": (anchor + timedelta(days=400)).isoformat()},
        headers=headers,
    )
    assert resp_outside.status_code == 404


# --- /v1/fares-order/current ------------------------------------------------------


async def test_fares_order_current_404_before_global_row_seeded(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await client.get("/v1/fares-order/current?region=urban", headers=headers)
    assert resp.status_code == 404


async def test_fares_order_current_returns_global_row_once_seeded(client, session):
    headers = await auth_headers(client, session, role="admin")

    async with AsyncSessionLocal() as raw_session:
        global_row = Tariff(
            tenant_id=None,
            name="NSW Fares Order 2025 (no.2) — urban",
            region="urban",
            effective_from=datetime(2025, 11, 3, tzinfo=UTC),
            effective_to=None,
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
        raw_session.add(global_row)
        await raw_session.commit()

    resp = await client.get("/v1/fares-order/current?region=urban", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["tenant_id"] is None
    assert body["region"] == "urban"


# --- Extras ------------------------------------------------------------------------


async def test_extras_crud(client, session):
    headers = await auth_headers(client, session, role="admin")
    tariff_resp = await client.post("/v1/tariffs", json=_urban_payload(booked=True), headers=headers)
    tariff_id = tariff_resp.json()["id"]

    create_resp = await client.post(
        f"/v1/tariffs/{tariff_id}/extras",
        json={"name": "Cleaning Fee", "amount": "15.00", "type": "fixed"},
        headers=headers,
    )
    assert create_resp.status_code == 201
    extra = create_resp.json()
    assert extra["tariff_id"] == tariff_id

    list_resp = await client.get(f"/v1/tariffs/{tariff_id}/extras", headers=headers)
    assert list_resp.status_code == 200
    assert list_resp.json()["total"] == 1

    update_resp = await client.patch(
        f"/v1/tariffs/{tariff_id}/extras/{extra['id']}",
        json={"amount": "20.00"},
        headers=headers,
    )
    assert update_resp.status_code == 200
    assert Decimal(update_resp.json()["amount"]) == Decimal("20.00")

    del_resp = await client.delete(f"/v1/tariffs/{tariff_id}/extras/{extra['id']}", headers=headers)
    assert del_resp.status_code == 204

    get_resp = await client.get(f"/v1/tariffs/{tariff_id}/extras/{extra['id']}", headers=headers)
    assert get_resp.status_code == 404


async def test_extras_scoped_to_tariff_tenant(client, session):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Tenant A2")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Tenant B2")

    tariff_resp = await client.post("/v1/tariffs", json=_urban_payload(booked=True), headers=headers_a)
    tariff_id = tariff_resp.json()["id"]

    resp = await client.get(f"/v1/tariffs/{tariff_id}/extras", headers=headers_b)
    assert resp.status_code == 404  # tariff itself isn't visible to tenant B


# --- Tariff signing (Ed25519) ------------------------------------------------------
#
# These tests round-trip through the wire contract only: fetch the public key
# from the HTTP endpoint, fetch a signed tariff from the HTTP endpoint, and
# verify using `cryptography`'s own Ed25519 verify (never re-deriving trust
# from the private key / internal helpers) against a canonical payload
# reconstructed independently here from the response JSON — proving the
# documented format in app.services.tariff_signing is actually what's on the
# wire, not just that the signing function agrees with itself. This cannot
# exercise the Android Kotlin verifier directly; see that file's own
# real-vs-mocked crypto note.


def _reconstruct_canonical_payload(tariff_json: dict) -> bytes:
    """Rebuilds the exact bytes app.services.tariff_signing.sign_tariff signs,
    from a `SignedTariffRead` response body — independent re-implementation
    of the documented wire format (not a call into the signing module), so
    this genuinely checks the contract rather than the function's self-
    consistency."""
    fields: list[tuple[str, object]] = [
        ("id", tariff_json["id"]),
        ("tenant_id", tariff_json["tenant_id"]),
        ("region", tariff_json["region"]),
        ("effective_from", datetime.fromisoformat(tariff_json["effective_from"]).isoformat()),
        (
            "effective_to",
            datetime.fromisoformat(tariff_json["effective_to"]).isoformat()
            if tariff_json["effective_to"]
            else None,
        ),
        ("booked", tariff_json["booked"]),
    ]
    fields.extend(
        (name, str(Decimal(tariff_json[name]).quantize(_RATE_QUANT, rounding=ROUND_HALF_UP)))
        for name in RATE_FIELDS
    )
    return json.dumps(dict(fields), separators=(",", ":"), ensure_ascii=True).encode("utf-8")


async def test_signing_public_key_endpoint_requires_no_auth(client, session):
    resp = await client.get("/v1/tariffs/signing-public-key")  # no Authorization header at all
    assert resp.status_code == 200
    body = resp.json()
    assert body["algorithm"] == "Ed25519"

    der = base64.b64decode(body["public_key"])
    public_key = serialization.load_der_public_key(der)
    assert isinstance(public_key, ed25519.Ed25519PublicKey)


async def test_active_tariff_signature_present_and_verifies_against_public_key(client, session):
    headers = await auth_headers(client, session, role="admin")
    await client.post("/v1/tariffs", json=_urban_payload(booked=True), headers=headers)

    key_resp = await client.get("/v1/tariffs/signing-public-key")
    assert key_resp.status_code == 200
    public_key = serialization.load_der_public_key(base64.b64decode(key_resp.json()["public_key"]))
    assert isinstance(public_key, ed25519.Ed25519PublicKey)

    active_resp = await client.get("/v1/tariffs/active?region=urban", headers=headers)
    assert active_resp.status_code == 200
    body = active_resp.json()
    assert body.get("signature")

    payload = _reconstruct_canonical_payload(body)
    signature_bytes = base64.b64decode(body["signature"])

    # Round-trip proof: signature really does validate against the payload
    # under the fetched public key (raises InvalidSignature on failure).
    public_key.verify(signature_bytes, payload)


async def test_active_tariff_signature_rejects_tampered_payload(client, session):
    headers = await auth_headers(client, session, role="admin")
    await client.post("/v1/tariffs", json=_urban_payload(booked=True), headers=headers)

    key_resp = await client.get("/v1/tariffs/signing-public-key")
    public_key = serialization.load_der_public_key(base64.b64decode(key_resp.json()["public_key"]))

    active_resp = await client.get("/v1/tariffs/active?region=urban", headers=headers)
    body = active_resp.json()

    tampered = dict(body)
    tampered["flag_fall"] = "999.9999"  # mutate a signed rate field
    payload = _reconstruct_canonical_payload(tampered)
    signature_bytes = base64.b64decode(body["signature"])

    with pytest.raises(InvalidSignature):
        public_key.verify(signature_bytes, payload)


async def test_active_tariff_signature_differs_per_tariff(client, session):
    headers = await auth_headers(client, session, role="admin")
    await client.post("/v1/tariffs", json=_urban_payload(booked=True, flag_fall="5.00"), headers=headers)
    await client.post("/v1/tariffs", json=_country_payload(booked=True), headers=headers)

    urban_resp = await client.get("/v1/tariffs/active?region=urban", headers=headers)
    country_resp = await client.get("/v1/tariffs/active?region=country", headers=headers)

    assert urban_resp.json()["signature"] != country_resp.json()["signature"]
