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
    subscribers from one test would leak into the next.

    Calls `.reset()` rather than clearing `_subscribers` by hand: as of
    2026-09-19 the broadcaster carries TWO maps (driver-keyed and
    tenant-keyed) and clearing only the driver one would leave dispatcher
    queues alive across tests — a cross-test leak that shows up as a flake in
    whichever test runs next, not in the one that caused it."""
    job_offer_broadcaster.reset()
    yield
    job_offer_broadcaster.reset()


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

    eligible_driver, _eligible_headers = await _available_driver(session=session, client=client, tenant_id=tenant_id)

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
    _tenant_id, admin_headers = await _tenant_and_headers(client, session, tenant_name="Jobs Tenant No Drivers")

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
    _driver_a, headers_a = await _available_driver(session=session, client=client, tenant_id=tenant_id, name="A")

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
    _driver_a, headers_a = await _available_driver(session=session, client=client, tenant_id=tenant_id, name="A")

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
    _tenant_a_id, headers_a = await _tenant_and_headers(client, session, tenant_name="Jobs Tenant A")
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


async def test_admin_can_accept_and_decline_an_offer_on_behalf_of_a_driver(client: AsyncClient, session: AsyncSession):
    """Admin panel plan (2026-09-15): dispatch accepts/declines FOR a driver by naming driver_id;
    a driver token naming another driver is still refused."""
    tenant_id, admin_headers = await _tenant_and_headers(client, session, tenant_name="Jobs Tenant On Behalf")
    driver_a, headers_a = await _available_driver(session=session, client=client, tenant_id=tenant_id, name="A")
    driver_b, headers_b = await _available_driver(session=session, client=client, tenant_id=tenant_id, name="B")

    job_id = (await client.post("/v1/jobs", json=_job_body(), headers=admin_headers)).json()["id"]
    offers = (await client.get(f"/v1/jobs/{job_id}/offers", headers=admin_headers)).json()
    offer_a = next(o for o in offers if o["driver_id"] == driver_a.id)
    offer_b = next(o for o in offers if o["driver_id"] == driver_b.id)

    # A driver may not act for another driver.
    resp = await client.post(
        f"/v1/jobs/{job_id}/offers/{offer_a['id']}/accept", json={"driver_id": driver_a.id}, headers=headers_b
    )
    assert resp.status_code == 403

    # The desk declines B's offer for B, then accepts A's for A.
    resp = await client.post(
        f"/v1/jobs/{job_id}/offers/{offer_b['id']}/decline", json={"driver_id": driver_b.id}, headers=admin_headers
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["status"] == "declined"
    resp = await client.post(
        f"/v1/jobs/{job_id}/offers/{offer_a['id']}/accept", json={"driver_id": driver_a.id}, headers=admin_headers
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["status"] == "accepted"
    assert (await client.get(f"/v1/jobs/{job_id}", headers=admin_headers)).json()["accepted_by_driver_id"] == driver_a.id

    # An admin with no driver_id is not a driver: the identity-scoped path 403s as before.
    resp = await client.post(f"/v1/jobs/{job_id}/offers/{offer_a['id']}/accept", json={}, headers=admin_headers)
    assert resp.status_code in (403, 409)


# --- dispatch-side live feed + state-transition pushes -----------------------------
#
# Added 2026-09-19 with the two-channel broadcaster. Two defects are pinned here:
#
#   1. `WS /v1/jobs/live` subscribed EVERY connection to its own token `sub`, and
#      the broadcaster was keyed by driver_id only. A dispatcher has no offers
#      addressed to their user id, so their socket connected and then stayed
#      permanently silent — the dashboard hook `useJobsLive.ts` says so in its own
#      comment and keeps a safety poll running because of it.
#   2. `create_job_and_broadcast` was the ONLY publish site in the whole jobs
#      service. Accept, decline, lazy expiry and cancellation pushed nothing, so
#      every state change after the offer itself was invisible until the next poll.
#
# The driver-isolation test below is the important one: the fix must not widen what
# a DRIVER hears. There is no client-side filter in the Android app and tablets
# update on the drivers' own schedule, so a tablet that started receiving other
# drivers' offers would be a tenant-data leak on devices nobody can patch.


def _ws_dispatch_setup(driver_names=("A", "B")):
    """Builds a tenant with one dispatcher and N drivers, each with an open
    shift (the availability toggle is left to the test, since 'eligible' vs
    'connected but not eligible' is exactly what the isolation test turns on).
    Synchronous, because these tests use starlette's `TestClient` — httpx's
    ASGITransport cannot do a websocket upgrade, same rationale as
    `test_job_offer_pushed_to_connected_driver` above."""
    import asyncio

    async def _setup():
        async with AsyncSessionLocal() as db:
            tenant = Tenant(name=f"WS Dispatch Tenant {uuid.uuid4()}", plan="standard")
            db.add(tenant)
            await db.commit()
            await db.refresh(tenant)

            desk = User(
                tenant_id=tenant.id,
                role="dispatcher",
                name="Test Dispatcher",
                email=f"{uuid.uuid4()}@example.com",
                pin_hash=security.hash_password("Test-Passw0rd!"),
                status="active",
            )
            db.add(desk)
            drivers = [
                User(
                    tenant_id=tenant.id,
                    role=ROLE_DRIVER,
                    name=f"Test Driver {name}",
                    email=f"{uuid.uuid4()}@example.com",
                    status="active",
                )
                for name in driver_names
            ]
            db.add_all(drivers)
            await db.commit()
            await db.refresh(desk)
            for driver in drivers:
                await db.refresh(driver)
                db.add(
                    Shift(
                        tenant_id=tenant.id,
                        driver_id=driver.id,
                        vehicle_id=str(uuid.uuid4()),
                        start_at=datetime.now(UTC),
                        end_at=None,
                    )
                )
            await db.commit()

            return {
                "tenant_id": tenant.id,
                "desk_id": desk.id,
                "desk_token": security.create_access_token(
                    user_id=desk.id, tenant_id=tenant.id, role=desk.role
                ),
                "driver_ids": [d.id for d in drivers],
                "driver_tokens": [
                    security.create_access_token(user_id=d.id, tenant_id=tenant.id, role=d.role)
                    for d in drivers
                ],
            }

    return asyncio.run(_setup())


def _bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def test_dispatcher_socket_hears_any_drivers_offer_and_drivers_stay_isolated(app):
    """The two halves of the websocket fix, in one ordering-deterministic test.

    A dispatcher's socket must carry a job offer made to SOMEONE ELSE (that is
    the whole point — the desk has no offers of its own). A driver's socket
    must carry only offers made to them, and the check is built so that a leak
    cannot hide: driver B connects BEFORE the first job exists but is not yet
    available, so no offer is addressed to B for it. If the driver channel had
    been widened to the tenant, B's very first frame would be job 1's offer for
    driver A. The assertion is that B's first frame is instead B's own offer on
    job 2 — no timeout or sleep needed to prove the absence."""
    from fastapi.testclient import TestClient

    env = _ws_dispatch_setup()
    driver_a, driver_b = env["driver_ids"]
    token_a, token_b = env["driver_tokens"]

    with TestClient(app) as tc:
        assert (
            tc.post("/v1/jobs/availability", json={"is_available": True}, headers=_bearer(token_a)).status_code
            == 200
        )

        with tc.websocket_connect(f"/v1/jobs/live?token={env['desk_token']}") as ws_desk, tc.websocket_connect(
            f"/v1/jobs/live?token={token_b}"
        ) as ws_b:
            job_one = tc.post("/v1/jobs", json=_job_body(), headers=_bearer(env["desk_token"]))
            assert job_one.status_code == 201, job_one.text
            job_one_id = job_one.json()["id"]

            # The desk hears an offer addressed to a driver who is not the desk.
            desk_frame = ws_desk.receive_json()
            assert desk_frame["type"] == "job_offer"
            assert desk_frame["job"]["id"] == job_one_id
            assert desk_frame["offer"]["driver_id"] == driver_a

            # Now make B eligible and create a second job, so B legitimately has
            # a frame coming and we can read its FIRST one without blocking.
            assert (
                tc.post(
                    "/v1/jobs/availability", json={"is_available": True}, headers=_bearer(token_b)
                ).status_code
                == 200
            )
            job_two = tc.post("/v1/jobs", json=_job_body(), headers=_bearer(env["desk_token"]))
            assert job_two.status_code == 201, job_two.text
            job_two_id = job_two.json()["id"]

            first_b_frame = ws_b.receive_json()
            assert first_b_frame["offer"]["driver_id"] == driver_b, (
                "driver B received a frame addressed to another driver — the driver "
                "channel must stay keyed by driver_id"
            )
            assert first_b_frame["job"]["id"] == job_two_id, (
                "driver B heard about job 1, which was never offered to B"
            )

            # The desk hears BOTH of job 2's offers, one frame per offer.
            desk_second = [ws_desk.receive_json(), ws_desk.receive_json()]
            assert {f["job"]["id"] for f in desk_second} == {job_two_id}
            assert {f["offer"]["driver_id"] for f in desk_second} == {driver_a, driver_b}


def test_accept_and_decline_push_to_the_driver_and_the_desk(app):
    """Before this pass the only publish site in `app.services.jobs` was
    `create_job_and_broadcast`; accept and decline moved rows and told nobody."""
    from fastapi.testclient import TestClient

    env = _ws_dispatch_setup()
    driver_a, driver_b = env["driver_ids"]
    token_a, token_b = env["driver_tokens"]
    desk_headers = _bearer(env["desk_token"])

    with TestClient(app) as tc:
        for token in (token_a, token_b):
            assert (
                tc.post(
                    "/v1/jobs/availability", json={"is_available": True}, headers=_bearer(token)
                ).status_code
                == 200
            )

        with tc.websocket_connect(f"/v1/jobs/live?token={env['desk_token']}") as ws_desk, tc.websocket_connect(
            f"/v1/jobs/live?token={token_a}"
        ) as ws_a, tc.websocket_connect(f"/v1/jobs/live?token={token_b}") as ws_b:
            job_id = tc.post("/v1/jobs", json=_job_body(), headers=desk_headers).json()["id"]

            # Drain the two creation frames from the desk and one from each driver.
            ws_desk.receive_json()
            ws_desk.receive_json()
            assert ws_a.receive_json()["offer"]["driver_id"] == driver_a
            assert ws_b.receive_json()["offer"]["driver_id"] == driver_b

            offers = tc.get(f"/v1/jobs/{job_id}/offers", headers=desk_headers).json()
            offer_a = next(o for o in offers if o["driver_id"] == driver_a)
            offer_b = next(o for o in offers if o["driver_id"] == driver_b)

            # --- decline -------------------------------------------------------
            declined = tc.post(
                f"/v1/jobs/{job_id}/offers/{offer_b['id']}/decline", json={}, headers=_bearer(token_b)
            )
            assert declined.status_code == 200, declined.text

            b_decline = ws_b.receive_json()
            assert b_decline["type"] == "job_offer_declined"
            assert b_decline["offer"]["id"] == offer_b["id"]
            assert b_decline["offer"]["status"] == "declined"
            assert b_decline["job"]["id"] == job_id

            desk_decline = ws_desk.receive_json()
            assert desk_decline["type"] == "job_offer_declined"
            assert desk_decline["offer"]["driver_id"] == driver_b

            # --- accept --------------------------------------------------------
            accepted = tc.post(
                f"/v1/jobs/{job_id}/offers/{offer_a['id']}/accept", json={}, headers=_bearer(token_a)
            )
            assert accepted.status_code == 200, accepted.text

            a_accept = ws_a.receive_json()
            assert a_accept["type"] == "job_offer_accepted"
            assert a_accept["offer"]["id"] == offer_a["id"]
            assert a_accept["job"]["status"] == "accepted"
            assert a_accept["job"]["accepted_by_driver_id"] == driver_a

            desk_accept = ws_desk.receive_json()
            assert desk_accept["type"] == "job_offer_accepted"
            assert desk_accept["offer"]["driver_id"] == driver_a
            assert desk_accept["job"]["accepted_by_driver_id"] == driver_a


def test_cancel_pushes_the_job_and_every_dead_offer(app):
    """Cancellation is this domain's terminal transition (there is no
    `completed` job status — the ride itself completes in the `trips` domain).
    The desk hears the job die; the driver hears their own offer die, which is
    what stops a tablet ringing for a job the desk already killed."""
    from fastapi.testclient import TestClient

    env = _ws_dispatch_setup(driver_names=("A",))
    (driver_a,) = env["driver_ids"]
    (token_a,) = env["driver_tokens"]
    desk_headers = _bearer(env["desk_token"])

    with TestClient(app) as tc:
        assert (
            tc.post("/v1/jobs/availability", json={"is_available": True}, headers=_bearer(token_a)).status_code
            == 200
        )

        with tc.websocket_connect(f"/v1/jobs/live?token={env['desk_token']}") as ws_desk, tc.websocket_connect(
            f"/v1/jobs/live?token={token_a}"
        ) as ws_a:
            job_id = tc.post("/v1/jobs", json=_job_body(), headers=desk_headers).json()["id"]
            ws_desk.receive_json()
            ws_a.receive_json()

            cancelled = tc.delete(f"/v1/jobs/{job_id}", headers=desk_headers)
            assert cancelled.status_code == 200, cancelled.text

            desk_cancel = ws_desk.receive_json()
            assert desk_cancel["type"] == "job_cancelled"
            assert desk_cancel["job"]["id"] == job_id
            assert desk_cancel["job"]["status"] == "cancelled"
            assert desk_cancel["offer"] is None

            a_expired = ws_a.receive_json()
            assert a_expired["type"] == "job_offer_expired"
            assert a_expired["offer"]["driver_id"] == driver_a
            assert a_expired["offer"]["status"] == "expired"


async def test_lazy_expiry_pushes_to_the_driver_and_the_desk(
    client: AsyncClient, session: AsyncSession
):
    """Expiry is the transition nobody requests: it happens inside whichever
    read path runs `expire_stale_offers`. Before this pass it moved the row
    silently, so a tablet kept counting down an offer the server had already
    lapsed. Subscribes to the broadcaster directly rather than over a socket
    because the websocket routes need starlette's synchronous TestClient while
    this path is driven by an ordinary async HTTP read."""
    tenant_id, admin_headers = await _tenant_and_headers(client, session, tenant_name="Jobs Tenant Expiry")
    driver, _driver_headers_ = await _available_driver(
        session=session, client=client, tenant_id=tenant_id, name="Expiring"
    )

    job_id = (await client.post("/v1/jobs", json=_job_body(), headers=admin_headers)).json()["id"]

    # Backdate the offer so the next read lapses it. Faster and more honest
    # than sleeping out the real 20-second window.
    offers = (await session.execute(select(JobOffer).where(JobOffer.job_id == job_id))).scalars().all()
    assert offers
    for offer in offers:
        offer.expires_at = datetime.now(UTC) - timedelta(seconds=1)
    await session.commit()

    driver_queue = await job_offer_broadcaster.subscribe(driver.id)
    tenant_queue = await job_offer_broadcaster.subscribe_tenant(tenant_id)

    resp = await client.get("/v1/jobs", headers=admin_headers)
    assert resp.status_code == 200, resp.text

    driver_frame = driver_queue.get_nowait()
    assert driver_frame["type"] == "job_offer_expired"
    assert driver_frame["offer"]["driver_id"] == driver.id
    assert driver_frame["offer"]["status"] == "expired"
    assert driver_frame["job"]["id"] == job_id

    tenant_frame = tenant_queue.get_nowait()
    assert tenant_frame["type"] == "job_offer_expired"
    assert tenant_frame["offer"]["driver_id"] == driver.id


async def test_a_drivers_channel_never_carries_another_tenants_or_drivers_events(
    client: AsyncClient, session: AsyncSession
):
    """Belt-and-braces on the leak the tenant channel could have caused: with
    two eligible drivers in one tenant, each driver's queue receives exactly
    ONE frame for a new job — their own — while the tenant queue receives two.
    A driver channel accidentally keyed by tenant would put two frames on each
    driver's queue and fail here."""
    tenant_id, admin_headers = await _tenant_and_headers(client, session, tenant_name="Jobs Tenant Isolation")
    driver_a, _ha = await _available_driver(session=session, client=client, tenant_id=tenant_id, name="Iso A")
    driver_b, _hb = await _available_driver(session=session, client=client, tenant_id=tenant_id, name="Iso B")

    queue_a = await job_offer_broadcaster.subscribe(driver_a.id)
    queue_b = await job_offer_broadcaster.subscribe(driver_b.id)
    tenant_queue = await job_offer_broadcaster.subscribe_tenant(tenant_id)

    resp = await client.post("/v1/jobs", json=_job_body(), headers=admin_headers)
    assert resp.status_code == 201, resp.text
    job_id = resp.json()["id"]

    assert queue_a.qsize() == 1, "driver A received more than their own offer"
    assert queue_b.qsize() == 1, "driver B received more than their own offer"
    assert tenant_queue.qsize() == 2, "the desk should hear one frame per offer"

    frame_a = queue_a.get_nowait()
    frame_b = queue_b.get_nowait()
    assert frame_a["offer"]["driver_id"] == driver_a.id
    assert frame_b["offer"]["driver_id"] == driver_b.id
    assert frame_a["job"]["id"] == frame_b["job"]["id"] == job_id


def test_the_websocket_subscribes_each_role_to_the_right_channel(app):
    """The role branch itself, asserted on the broadcaster's registries rather
    than by waiting for a frame -- so an un-branched handler FAILS here in
    milliseconds instead of blocking a socket read forever. Before the branch,
    a dispatcher was registered on the driver channel under their own user id
    (where nothing is ever published to them) and the tenant channel did not
    exist at all."""
    from fastapi.testclient import TestClient

    env = _ws_dispatch_setup(driver_names=("A",))
    (driver_a,) = env["driver_ids"]
    (token_a,) = env["driver_tokens"]
    tenant_id = env["tenant_id"]

    with TestClient(app) as tc:
        with tc.websocket_connect(f"/v1/jobs/live?token={env['desk_token']}"):
            assert job_offer_broadcaster.tenant_listener_count(tenant_id) == 1, (
                "a dispatcher must land on the tenant channel"
            )
            assert job_offer_broadcaster.listener_count(env["desk_id"]) == 0, (
                "a dispatcher must NOT be registered on a driver channel -- nothing "
                "is ever published to a dispatcher's own user id, which is exactly "
                "why their socket used to stay silent"
            )
        assert job_offer_broadcaster.tenant_listener_count(tenant_id) == 0, "tenant unsubscribe leaked"

        with tc.websocket_connect(f"/v1/jobs/live?token={token_a}"):
            assert job_offer_broadcaster.listener_count(driver_a) == 1, (
                "a driver must stay on their own driver channel, exactly as before"
            )
            assert job_offer_broadcaster.tenant_listener_count(tenant_id) == 0, (
                "a driver must NEVER be on the tenant channel: the Android app applies "
                "no client-side filter, so that would ship other drivers' job traffic "
                "to every tablet in the fleet"
            )
        assert job_offer_broadcaster.listener_count(driver_a) == 0, "driver unsubscribe leaked"


async def test_every_transition_publishes_to_the_driver_and_the_desk(
    client: AsyncClient, session: AsyncSession
):
    """Accept, decline and cancel, asserted on the broadcaster queues with
    `get_nowait()` so a transition that publishes NOTHING fails immediately
    (`QueueEmpty`) instead of blocking a socket read. The websocket tests
    above prove the same events reach a real client; this one is the fast,
    hang-proof control on the publish sites themselves -- all of which were
    silent before 2026-09-19, when `create_job_and_broadcast` was the only
    publisher in the module."""
    tenant_id, admin_headers = await _tenant_and_headers(
        client, session, role="dispatcher", tenant_name="Jobs Tenant Transitions"
    )
    driver_a, headers_a = await _available_driver(
        session=session, client=client, tenant_id=tenant_id, name="Trans A"
    )
    driver_b, headers_b = await _available_driver(
        session=session, client=client, tenant_id=tenant_id, name="Trans B"
    )

    job_id = (await client.post("/v1/jobs", json=_job_body(), headers=admin_headers)).json()["id"]
    offers = (await client.get(f"/v1/jobs/{job_id}/offers", headers=admin_headers)).json()
    offer_a = next(o for o in offers if o["driver_id"] == driver_a.id)
    offer_b = next(o for o in offers if o["driver_id"] == driver_b.id)

    # Subscribe AFTER creation so only transition frames land in these queues.
    queue_a = await job_offer_broadcaster.subscribe(driver_a.id)
    queue_b = await job_offer_broadcaster.subscribe(driver_b.id)
    desk_queue = await job_offer_broadcaster.subscribe_tenant(tenant_id)

    # --- decline ---------------------------------------------------------------
    resp = await client.post(f"/v1/jobs/{job_id}/offers/{offer_b['id']}/decline", json={}, headers=headers_b)
    assert resp.status_code == 200, resp.text
    declined = queue_b.get_nowait()
    assert declined["type"] == "job_offer_declined"
    assert declined["offer"]["id"] == offer_b["id"]
    assert desk_queue.get_nowait()["type"] == "job_offer_declined"
    assert queue_a.empty(), "driver A must not hear driver B's decline"

    # --- accept ----------------------------------------------------------------
    resp = await client.post(f"/v1/jobs/{job_id}/offers/{offer_a['id']}/accept", json={}, headers=headers_a)
    assert resp.status_code == 200, resp.text
    accepted = queue_a.get_nowait()
    assert accepted["type"] == "job_offer_accepted"
    assert accepted["job"]["accepted_by_driver_id"] == driver_a.id
    desk_accept = desk_queue.get_nowait()
    assert desk_accept["type"] == "job_offer_accepted"
    assert queue_b.empty(), "driver B's offer was already declined, nothing more is addressed to B"

    # --- cancel ----------------------------------------------------------------
    # A fresh job, because the accepted one is terminal and cannot be cancelled.
    second_id = (await client.post("/v1/jobs", json=_job_body(), headers=admin_headers)).json()["id"]
    while not desk_queue.empty():
        desk_queue.get_nowait()
    while not queue_a.empty():
        queue_a.get_nowait()

    resp = await client.delete(f"/v1/jobs/{second_id}", headers=admin_headers)
    assert resp.status_code == 200, resp.text
    desk_cancel = desk_queue.get_nowait()
    assert desk_cancel["type"] == "job_cancelled"
    assert desk_cancel["job"]["status"] == "cancelled"
    assert desk_cancel["offer"] is None
    dead_offer = queue_a.get_nowait()
    assert dead_offer["type"] == "job_offer_expired"
    assert dead_offer["offer"]["driver_id"] == driver_a.id
