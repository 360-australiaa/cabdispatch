"""Tests for the Jobs domain (`/v1/jobs`) — dispatch/job-offer matching,
first-accept-wins, decline, tenant isolation, and role gating.

NOTE: as of writing, this domain's router is not yet registered in app.main —
that happens in a later integration step that wires all 12 domain routers
together. These tests are written correctly against the endpoints as built and
will pass once that registration lands; run in isolation today they will 404.

Importing `app.models.jobs` below (even though nothing else in this file uses
`Job`/`JobOffer`/`DriverAvailability` by name in every test) is required so
those tables land on `Base.metadata` before the session-scoped `_test_database`
fixture in conftest.py runs `create_all` — `app/models/__init__.py` doesn't
import them yet either, for the same "integration step wires it up" reason
already established by the sibling `live_ops`/`shifts` domain test files.
"""
from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import security
from app.core.database import AsyncSessionLocal
from app.models.jobs import DriverAvailability, Job, JobOffer  # noqa: F401 (see module docstring)
from app.models.shift import Shift
from app.models.tenant import Tenant
from app.models.trips import Trip
from app.models.user import ROLE_DRIVER, User
from app.services.jobs import job_offer_broadcaster
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


# --- fixtures / helpers -----------------------------------------------------------


@pytest.fixture(autouse=True)
def _reset_broadcaster():
    """Module-level singleton (matching `app.services.live_ops.fleet_broadcaster`
    / `app.services.duress.gps_broadcaster`) — must be reset between tests or
    subscribers from one test would leak into the next."""
    job_offer_broadcaster._subscribers.clear()
    yield
    job_offer_broadcaster._subscribers.clear()


async def _tenant_and_headers(client, session, *, role="admin", tenant_name="Jobs Tenant"):
    tenant = Tenant(name=tenant_name, plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    headers = await auth_headers(client, session, role=role, tenant_id=tenant.id)
    return tenant.id, headers


async def _make_driver(session, *, tenant_id, name="Driver"):
    driver = User(
        tenant_id=tenant_id, role=ROLE_DRIVER, name=name, email=f"{uuid.uuid4()}@example.com", status="active"
    )
    session.add(driver)
    await session.commit()
    await session.refresh(driver)
    return driver


def _driver_headers(driver: User, tenant_id: str) -> dict:
    token = security.create_access_token(user_id=driver.id, tenant_id=tenant_id, role=driver.role)
    return {"Authorization": f"Bearer {token}"}


async def _make_shift(session, *, tenant_id, driver_id, vehicle_id=None, end_at=None):
    shift = Shift(
        tenant_id=tenant_id,
        driver_id=driver_id,
        vehicle_id=vehicle_id or str(uuid.uuid4()),
        start_at=datetime.now(UTC),
        end_at=end_at,
    )
    session.add(shift)
    await session.commit()
    await session.refresh(shift)
    return shift


async def _make_open_trip(session, *, tenant_id, driver_id, vehicle_id=None):
    trip = Trip(
        tenant_id=tenant_id,
        client_uuid=str(uuid.uuid4()),
        vehicle_id=vehicle_id or str(uuid.uuid4()),
        driver_id=driver_id,
        tariff_id=str(uuid.uuid4()),
        type="rank_hail",
        status="open",
        start_at=datetime.now(UTC),
        start_lat=-33.87,
        start_lng=151.21,
    )
    session.add(trip)
    await session.commit()
    await session.refresh(trip)
    return trip


def _job_body(**overrides):
    body = {
        "origin_lat": -33.8688,
        "origin_lng": 151.2093,
        "origin_address": "1 Test St, Sydney",
        "dest_lat": -33.8568,
        "dest_lng": 151.2153,
        "dest_address": "2 Test Ave, Sydney",
        "fare_estimate_low": "20.00",
        "fare_estimate_high": "28.00",
    }
    body.update(overrides)
    return body


async def _available_driver(client, session, *, tenant_id, name="Available Driver"):
    """Creates a driver who is fully offer-eligible: available=True, has an
    open shift, and has no open trip."""
    driver = await _make_driver(session, tenant_id=tenant_id, name=name)
    headers = _driver_headers(driver, tenant_id)
    resp = await client.post("/v1/jobs/availability", json={"is_available": True}, headers=headers)
    assert resp.status_code == 200, resp.text
    await _make_shift(session, tenant_id=tenant_id, driver_id=driver.id)
    return driver, headers


# --- create + matching -------------------------------------------------------------


async def test_create_job_broadcasts_offers_only_to_available_drivers(client: AsyncClient, session: AsyncSession):
    tenant_id, admin_headers = await _tenant_and_headers(client, session)

    eligible_driver, eligible_headers = await _available_driver(session=session, client=client, tenant_id=tenant_id)

    # Available flag set, but never went on shift.
    off_shift_driver = await _make_driver(session, tenant_id=tenant_id, name="Off Shift Driver")
    await client.post(
        "/v1/jobs/availability", json={"is_available": True}, headers=_driver_headers(off_shift_driver, tenant_id)
    )

    # On shift and available, but mid-trip.
    busy_driver = await _make_driver(session, tenant_id=tenant_id, name="Busy Driver")
    await client.post(
        "/v1/jobs/availability", json={"is_available": True}, headers=_driver_headers(busy_driver, tenant_id)
    )
    await _make_shift(session, tenant_id=tenant_id, driver_id=busy_driver.id)
    await _make_open_trip(session, tenant_id=tenant_id, driver_id=busy_driver.id)

    # On shift, but never toggled available.
    unavailable_driver = await _make_driver(session, tenant_id=tenant_id, name="Unavailable Driver")
    await _make_shift(session, tenant_id=tenant_id, driver_id=unavailable_driver.id)

    create_resp = await client.post("/v1/jobs", json=_job_body(), headers=admin_headers)
    assert create_resp.status_code == 201, create_resp.text
    job = create_resp.json()
    assert job["status"] == "offered"

    offers_resp = await client.get(f"/v1/jobs/{job['id']}/offers", headers=admin_headers)
    assert offers_resp.status_code == 200
    offers = offers_resp.json()

    assert len(offers) == 1
    assert offers[0]["driver_id"] == eligible_driver.id
    assert offers[0]["status"] == "pending"


async def test_create_job_with_no_available_drivers_stays_queued(client: AsyncClient, session: AsyncSession):
    tenant_id, admin_headers = await _tenant_and_headers(client, session, tenant_name="Jobs Tenant No Drivers")

    resp = await client.post("/v1/jobs", json=_job_body(), headers=admin_headers)
    assert resp.status_code == 201
    body = resp.json()
    assert body["status"] == "queued"

    offers_resp = await client.get(f"/v1/jobs/{body['id']}/offers", headers=admin_headers)
    assert offers_resp.json() == []


async def test_create_job_rejects_inverted_fare_range(client: AsyncClient, session: AsyncSession):
    _tenant_id, admin_headers = await _tenant_and_headers(client, session, tenant_name="Jobs Tenant Bad Fare")
    resp = await client.post(
        "/v1/jobs",
        json=_job_body(fare_estimate_low="30.00", fare_estimate_high="10.00"),
        headers=admin_headers,
    )
    assert resp.status_code == 422


async def test_job_read_carries_a_real_straight_line_distance_km(client: AsyncClient, session: AsyncSession):
    """Real gap closed (2026-09-05 API-audit pass): Android's JobDto already
    declared/read `distance_km`, but no backend field ever backed it (always
    null). Proves it's now a real, non-null value -- and that it's the exact
    same haversine great-circle distance `app.services.trips.haversine_km`
    computes elsewhere in this codebase, not a second, independently-defined
    approximation."""
    from app.services.trips import haversine_km

    _tenant_id, admin_headers = await _tenant_and_headers(client, session, tenant_name="Jobs Tenant Distance")
    resp = await client.post("/v1/jobs", json=_job_body(), headers=admin_headers)
    assert resp.status_code == 201, resp.text
    body = resp.json()

    expected = haversine_km(-33.8688, 151.2093, -33.8568, 151.2153).quantize(Decimal("0.01"))
    assert Decimal(str(body["distance_km"])) == expected
    # Sanity: origin -> dest here is a real, non-trivial distance, not a
    # degenerate same-point 0.00 that would pass a sloppier assertion.
    assert expected > Decimal("0.5")

    # GET /v1/jobs/{id} must carry the same computed field, not just create.
    get_resp = await client.get(f"/v1/jobs/{body['id']}", headers=admin_headers)
    assert get_resp.status_code == 200
    assert Decimal(str(get_resp.json()["distance_km"])) == expected


# --- accept (first-accept-wins) -----------------------------------------------------


async def test_accept_flow_expires_sibling_offers(client: AsyncClient, session: AsyncSession):
    tenant_id, admin_headers = await _tenant_and_headers(client, session, tenant_name="Jobs Tenant Accept")

    driver_a, headers_a = await _available_driver(session=session, client=client, tenant_id=tenant_id, name="A")
    driver_b, headers_b = await _available_driver(session=session, client=client, tenant_id=tenant_id, name="B")

    create_resp = await client.post("/v1/jobs", json=_job_body(), headers=admin_headers)
    job_id = create_resp.json()["id"]

    offers = (await client.get(f"/v1/jobs/{job_id}/offers", headers=admin_headers)).json()
    assert len(offers) == 2
    offer_a = next(o for o in offers if o["driver_id"] == driver_a.id)
    offer_b = next(o for o in offers if o["driver_id"] == driver_b.id)

    accept_resp = await client.post(
        f"/v1/jobs/{job_id}/offers/{offer_a['id']}/accept", json={}, headers=headers_a
    )
    assert accept_resp.status_code == 200, accept_resp.text
    assert accept_resp.json()["status"] == "accepted"

    job_resp = await client.get(f"/v1/jobs/{job_id}", headers=admin_headers)
    job_body = job_resp.json()
    assert job_body["status"] == "accepted"
    assert job_body["accepted_by_driver_id"] == driver_a.id

    refreshed_offers = (await client.get(f"/v1/jobs/{job_id}/offers", headers=admin_headers)).json()
    refreshed_b = next(o for o in refreshed_offers if o["id"] == offer_b["id"])
    assert refreshed_b["status"] == "expired"

    # B can no longer accept — their offer is no longer pending.
    late_accept = await client.post(
        f"/v1/jobs/{job_id}/offers/{offer_b['id']}/accept", json={}, headers=headers_b
    )
    assert late_accept.status_code == 409


async def test_accept_wrong_driver_is_forbidden(client: AsyncClient, session: AsyncSession):
    tenant_id, admin_headers = await _tenant_and_headers(client, session, tenant_name="Jobs Tenant Wrong Driver")
    driver_a, _headers_a = await _available_driver(session=session, client=client, tenant_id=tenant_id, name="A")
    _driver_b, headers_b = await _available_driver(session=session, client=client, tenant_id=tenant_id, name="B")

    create_resp = await client.post("/v1/jobs", json=_job_body(), headers=admin_headers)
    job_id = create_resp.json()["id"]
    offers = (await client.get(f"/v1/jobs/{job_id}/offers", headers=admin_headers)).json()
    offer_a = next(o for o in offers if o["driver_id"] == driver_a.id)

    resp = await client.post(f"/v1/jobs/{job_id}/offers/{offer_a['id']}/accept", json={}, headers=headers_b)
    assert resp.status_code == 403


async def test_accept_expired_offer_conflicts(client: AsyncClient, session: AsyncSession):
    tenant_id, admin_headers = await _tenant_and_headers(client, session, tenant_name="Jobs Tenant Expired")
    driver_a, headers_a = await _available_driver(session=session, client=client, tenant_id=tenant_id, name="A")

    create_resp = await client.post("/v1/jobs", json=_job_body(), headers=admin_headers)
    job_id = create_resp.json()["id"]
    offer_id = (await client.get(f"/v1/jobs/{job_id}/offers", headers=admin_headers)).json()[0]["id"]

    # Force the offer into the past so lazy expiry flips it on the next read.
    result = await session.execute(select(JobOffer).where(JobOffer.id == offer_id))
    offer = result.scalar_one()
    offer.expires_at = datetime.now(UTC) - timedelta(seconds=1)
    await session.commit()

    resp = await client.post(f"/v1/jobs/{job_id}/offers/{offer_id}/accept", json={}, headers=headers_a)
    assert resp.status_code == 409

    offers_after = (await client.get(f"/v1/jobs/{job_id}/offers", headers=admin_headers)).json()
    assert offers_after[0]["status"] == "expired"


# --- decline -------------------------------------------------------------------------


async def test_decline_flow(client: AsyncClient, session: AsyncSession):
    tenant_id, admin_headers = await _tenant_and_headers(client, session, tenant_name="Jobs Tenant Decline")
    driver_a, headers_a = await _available_driver(session=session, client=client, tenant_id=tenant_id, name="A")

    create_resp = await client.post("/v1/jobs", json=_job_body(), headers=admin_headers)
    job_id = create_resp.json()["id"]
    offer_id = (await client.get(f"/v1/jobs/{job_id}/offers", headers=admin_headers)).json()[0]["id"]

    resp = await client.post(f"/v1/jobs/{job_id}/offers/{offer_id}/decline", json={}, headers=headers_a)
    assert resp.status_code == 200
    assert resp.json()["status"] == "declined"

    # Declining doesn't change the job itself (no auto re-broadcast in this pass).
    job_body = (await client.get(f"/v1/jobs/{job_id}", headers=admin_headers)).json()
    assert job_body["status"] == "offered"

    # Can't decline twice.
    second = await client.post(f"/v1/jobs/{job_id}/offers/{offer_id}/decline", json={}, headers=headers_a)
    assert second.status_code == 409


# --- tenant isolation ------------------------------------------------------------------


async def test_tenant_isolation_on_jobs(client: AsyncClient, session: AsyncSession):
    tenant_a_id, headers_a = await _tenant_and_headers(client, session, tenant_name="Jobs Tenant A")
    _tenant_b_id, headers_b = await _tenant_and_headers(client, session, tenant_name="Jobs Tenant B")

    create_resp = await client.post("/v1/jobs", json=_job_body(), headers=headers_a)
    job_id = create_resp.json()["id"]

    cross_tenant_resp = await client.get(f"/v1/jobs/{job_id}", headers=headers_b)
    assert cross_tenant_resp.status_code == 404

    cross_tenant_list = await client.get("/v1/jobs", headers=headers_b)
    assert all(item["id"] != job_id for item in cross_tenant_list.json()["items"])


# --- role gating on cancel --------------------------------------------------------------


async def test_cancel_requires_dispatch_role(client: AsyncClient, session: AsyncSession):
    tenant_id, admin_headers = await _tenant_and_headers(client, session, tenant_name="Jobs Tenant RBAC")
    driver_headers = await auth_headers(client, session, role="driver", tenant_id=tenant_id)

    create_resp = await client.post("/v1/jobs", json=_job_body(), headers=admin_headers)
    job_id = create_resp.json()["id"]

    forbidden = await client.delete(f"/v1/jobs/{job_id}", headers=driver_headers)
    assert forbidden.status_code == 403


async def test_cancel_as_dispatcher_expires_pending_offers(client: AsyncClient, session: AsyncSession):
    tenant_id, admin_headers = await _tenant_and_headers(client, session, tenant_name="Jobs Tenant Cancel")
    dispatcher_headers = await auth_headers(client, session, role="dispatcher", tenant_id=tenant_id)
    _driver, _headers = await _available_driver(session=session, client=client, tenant_id=tenant_id)

    create_resp = await client.post("/v1/jobs", json=_job_body(), headers=admin_headers)
    job_id = create_resp.json()["id"]
    offer_id = (await client.get(f"/v1/jobs/{job_id}/offers", headers=admin_headers)).json()[0]["id"]

    cancel_resp = await client.delete(f"/v1/jobs/{job_id}", headers=dispatcher_headers)
    assert cancel_resp.status_code == 200
    assert cancel_resp.json()["status"] == "cancelled"

    offer_resp = await client.get(f"/v1/jobs/{job_id}/offers", headers=admin_headers)
    offer = next(o for o in offer_resp.json() if o["id"] == offer_id)
    assert offer["status"] == "expired"

    second_cancel = await client.delete(f"/v1/jobs/{job_id}", headers=dispatcher_headers)
    assert second_cancel.status_code == 409


# --- live websocket relay ----------------------------------------------------------


def test_job_offer_pushed_to_connected_driver(app):
    """Uses starlette's synchronous TestClient (rather than the async `client`
    fixture) because httpx's ASGITransport does not support websocket upgrade —
    same rationale as `tests/test_duress.py`'s equivalent test."""
    import asyncio

    from fastapi.testclient import TestClient

    async def _setup():
        async with AsyncSessionLocal() as db:
            tenant = Tenant(name=f"WS Jobs Tenant {uuid.uuid4()}", plan="standard")
            db.add(tenant)
            await db.commit()
            await db.refresh(tenant)

            admin = User(
                tenant_id=tenant.id,
                role="admin",
                name="Test Admin",
                email=f"{uuid.uuid4()}@example.com",
                pin_hash=security.hash_password("Test-Passw0rd!"),
                status="active",
            )
            driver = User(
                tenant_id=tenant.id,
                role=ROLE_DRIVER,
                name="Test Driver",
                email=f"{uuid.uuid4()}@example.com",
                status="active",
            )
            db.add_all([admin, driver])
            await db.commit()
            await db.refresh(admin)
            await db.refresh(driver)

            shift = Shift(
                tenant_id=tenant.id,
                driver_id=driver.id,
                vehicle_id=str(uuid.uuid4()),
                start_at=datetime.now(UTC),
                end_at=None,
            )
            db.add(shift)
            await db.commit()

            admin_token = security.create_access_token(user_id=admin.id, tenant_id=tenant.id, role=admin.role)
            driver_token = security.create_access_token(user_id=driver.id, tenant_id=tenant.id, role=driver.role)
        return admin_token, driver_token

    admin_token, driver_token = asyncio.run(_setup())

    with TestClient(app) as test_client:
        avail_resp = test_client.post(
            "/v1/jobs/availability",
            json={"is_available": True},
            headers={"Authorization": f"Bearer {driver_token}"},
        )
        assert avail_resp.status_code == 200, avail_resp.text

        with test_client.websocket_connect(f"/v1/jobs/live?token={driver_token}") as ws:
            create_resp = test_client.post(
                "/v1/jobs",
                json=_job_body(),
                headers={"Authorization": f"Bearer {admin_token}"},
            )
            assert create_resp.status_code == 201, create_resp.text

            received = ws.receive_json()
            assert received["type"] == "job_offer"
            assert received["job"]["id"] == create_resp.json()["id"]
            assert received["offer"]["status"] == "pending"


def test_websocket_rejects_missing_token(app):
    from fastapi.testclient import TestClient
    from fastapi.websockets import WebSocketDisconnect as FastAPIWebSocketDisconnect

    with TestClient(app) as test_client:
        try:
            with test_client.websocket_connect("/v1/jobs/live"):
                pass
            raised = False
        except FastAPIWebSocketDisconnect:
            raised = True
        assert raised
