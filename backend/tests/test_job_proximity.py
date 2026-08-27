"""Tests for proximity-nearest-first job-offer matching (see
`app.services.jobs._haversine_km` / `_nearest_first_driver_ids` /
`create_job_and_broadcast`, and `app.services.live_ops.publish_position`'s
best-effort position persistence onto `DriverAvailability`).

Follows the conventions already established in `tests/test_jobs.py` (same
tenant/driver/shift helper shapes, same `_reset_broadcaster` pattern for the
module-level `job_offer_broadcaster` singleton) and `tests/test_live_ops.py`
(same `fleet_broadcaster` reset pattern, same Vehicle helper shape) since this
file spans both domains.
"""
from __future__ import annotations

import uuid
from datetime import UTC, datetime
from decimal import Decimal

import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import security
from app.models.fleet import Vehicle
from app.models.jobs import DriverAvailability  # noqa: F401 (see tests/test_jobs.py's module docstring)
from app.models.shift import Shift
from app.models.tenant import Tenant
from app.models.user import ROLE_DRIVER, User
from app.services.jobs import _haversine_km, create_job_and_broadcast, job_offer_broadcaster
from app.services.live_ops import fleet_broadcaster, publish_position
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio

# Sydney CBD-ish origin, matching tests/test_jobs.py's _job_body default.
_ORIGIN_LAT = -33.8688
_ORIGIN_LNG = 151.2093


@pytest.fixture(autouse=True)
def _reset_broadcasters():
    """Both broadcasters touched by this file are module-level singletons
    (see tests/test_jobs.py and tests/test_live_ops.py's identical fixtures)
    and must be reset between tests."""
    job_offer_broadcaster._subscribers.clear()
    fleet_broadcaster._latest.clear()
    fleet_broadcaster._subscribers.clear()
    yield
    job_offer_broadcaster._subscribers.clear()
    fleet_broadcaster._latest.clear()
    fleet_broadcaster._subscribers.clear()


# --- fixtures / helpers -----------------------------------------------------------


async def _tenant_and_headers(client, session, *, role="admin", tenant_name="Proximity Tenant"):
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


async def _available_driver_at(
    client, session, *, tenant_id, name, lat=None, lng=None, vehicle_id=None
):
    """Creates a driver who is fully offer-eligible (available + open shift +
    no open trip, same as tests/test_jobs.py's `_available_driver`), then
    optionally stamps a last-known position directly onto their
    `DriverAvailability` row (bypassing `publish_position` -- that path is
    covered separately by `test_publish_position_persists_onto_driver_row`
    below)."""
    driver = await _make_driver(session, tenant_id=tenant_id, name=name)
    token = security.create_access_token(user_id=driver.id, tenant_id=tenant_id, role=driver.role)
    headers = {"Authorization": f"Bearer {token}"}
    resp = await client.post("/v1/jobs/availability", json={"is_available": True}, headers=headers)
    assert resp.status_code == 200, resp.text
    await _make_shift(session, tenant_id=tenant_id, driver_id=driver.id, vehicle_id=vehicle_id)

    if lat is not None:
        from sqlalchemy import select

        result = await session.execute(
            select(DriverAvailability).where(
                DriverAvailability.tenant_id == tenant_id, DriverAvailability.driver_id == driver.id
            )
        )
        row = result.scalar_one()
        row.last_lat = lat
        row.last_lng = lng
        row.last_position_at = datetime.now(UTC)
        await session.commit()

    return driver, headers


def _job_kwargs(**overrides):
    kwargs = dict(
        origin_lat=_ORIGIN_LAT,
        origin_lng=_ORIGIN_LNG,
        origin_address="1 Test St, Sydney",
        dest_lat=-33.8568,
        dest_lng=151.2153,
        dest_address="2 Test Ave, Sydney",
        fare_estimate_low=Decimal("20.00"),
        fare_estimate_high=Decimal("28.00"),
    )
    kwargs.update(overrides)
    return kwargs


# --- _haversine_km -----------------------------------------------------------------


def test_haversine_km_zero_for_identical_points():
    assert _haversine_km(_ORIGIN_LAT, _ORIGIN_LNG, _ORIGIN_LAT, _ORIGIN_LNG) == pytest.approx(0.0, abs=1e-9)


def test_haversine_km_matches_known_distance():
    # Sydney CBD -> Parramatta, roughly 20km as the crow flies.
    km = _haversine_km(-33.8688, 151.2093, -33.8150, 151.0011)
    assert 18.0 < km < 22.0


# --- nearest-first offer ordering ---------------------------------------------------


async def test_offers_created_nearest_first(client: AsyncClient, session: AsyncSession):
    """Three offer-eligible drivers at increasing distance from the job's
    origin -- offers must be created (and returned in `create_job_and_broadcast`'s
    own `offers` list) nearest-first, regardless of the order the drivers were
    created/made-available in."""
    tenant_id, _admin_headers = await _tenant_and_headers(client, session)

    # Deliberately created far -> near -> mid, to prove sort order isn't just
    # creation order.
    far, _ = await _available_driver_at(
        client, session, tenant_id=tenant_id, name="Far", lat=-33.95, lng=151.30
    )
    near, _ = await _available_driver_at(
        client, session, tenant_id=tenant_id, name="Near", lat=-33.8690, lng=151.2095
    )
    mid, _ = await _available_driver_at(
        client, session, tenant_id=tenant_id, name="Mid", lat=-33.90, lng=151.25
    )

    job, offers = await create_job_and_broadcast(
        session, tenant_id=tenant_id, created_by_user_id=None, **_job_kwargs()
    )

    assert job.status == "offered"
    assert [o.driver_id for o in offers] == [near.id, mid.id, far.id]


async def test_driver_with_no_position_sorts_last_but_still_offered(client: AsyncClient, session: AsyncSession):
    tenant_id, _admin_headers = await _tenant_and_headers(client, session, tenant_name="Proximity Tenant No Pos")

    known, _ = await _available_driver_at(
        client, session, tenant_id=tenant_id, name="Known", lat=-33.90, lng=151.25
    )
    unknown, _ = await _available_driver_at(client, session, tenant_id=tenant_id, name="Unknown")  # no lat/lng

    job, offers = await create_job_and_broadcast(
        session, tenant_id=tenant_id, created_by_user_id=None, **_job_kwargs()
    )

    assert job.status == "offered"
    driver_order = [o.driver_id for o in offers]
    assert driver_order == [known.id, unknown.id]


async def test_all_drivers_unknown_position_falls_back_to_any_order(client: AsyncClient, session: AsyncSession):
    """No known positions at all -- every driver is still offered exactly
    once, just with no distance-based guarantee on order."""
    tenant_id, _admin_headers = await _tenant_and_headers(client, session, tenant_name="Proximity Tenant All Unk")

    a, _ = await _available_driver_at(client, session, tenant_id=tenant_id, name="A")
    b, _ = await _available_driver_at(client, session, tenant_id=tenant_id, name="B")

    job, offers = await create_job_and_broadcast(
        session, tenant_id=tenant_id, created_by_user_id=None, **_job_kwargs()
    )

    assert job.status == "offered"
    assert {o.driver_id for o in offers} == {a.id, b.id}
    assert len(offers) == 2


# --- publish_position persists onto DriverAvailability --------------------------------


async def test_publish_position_persists_onto_driver_row(client: AsyncClient, session: AsyncSession):
    tenant_id, _admin_headers = await _tenant_and_headers(client, session, tenant_name="Proximity Tenant Persist")

    vehicle = Vehicle(tenant_id=tenant_id, rego="TX-PROX", vehicle_class="standard", status="active")
    session.add(vehicle)
    await session.commit()
    await session.refresh(vehicle)

    driver, _headers = await _available_driver_at(
        client, session, tenant_id=tenant_id, name="Assigned", vehicle_id=vehicle.id
    )

    result = await publish_position(
        session, tenant_id=tenant_id, vehicle_id=vehicle.id, lat=-33.91, lng=151.26, status="available"
    )
    assert result["vehicle_id"] == vehicle.id  # existing broadcast behaviour untouched

    from sqlalchemy import select

    row_result = await session.execute(
        select(DriverAvailability).where(
            DriverAvailability.tenant_id == tenant_id, DriverAvailability.driver_id == driver.id
        )
    )
    row = row_result.scalar_one()
    assert row.last_lat == pytest.approx(-33.91)
    assert row.last_lng == pytest.approx(151.26)
    assert row.last_position_at is not None


async def test_publish_position_skips_silently_when_no_driver_assigned(client: AsyncClient, session: AsyncSession):
    """No open Shift for this vehicle -- `_persist_driver_position` has
    nothing to enrich. Must not raise, and the existing broadcast response
    must be unaffected."""
    tenant_id, _admin_headers = await _tenant_and_headers(client, session, tenant_name="Proximity Tenant No Driver")

    vehicle = Vehicle(tenant_id=tenant_id, rego="TX-NODRV", vehicle_class="standard", status="active")
    session.add(vehicle)
    await session.commit()
    await session.refresh(vehicle)

    result = await publish_position(
        session, tenant_id=tenant_id, vehicle_id=vehicle.id, lat=-33.91, lng=151.26, status="available"
    )
    assert result["vehicle_id"] == vehicle.id
    assert result["subscriber_count"] == 0


async def test_publish_position_skips_silently_when_driver_has_no_availability_row(
    client: AsyncClient, session: AsyncSession
):
    """Driver is on shift for this vehicle but has never toggled
    `POST /v1/jobs/availability` -- no `DriverAvailability` row exists yet.
    `_persist_driver_position` must not create one (rows are only ever
    created via `set_driver_availability`) and must not raise."""
    tenant_id, _admin_headers = await _tenant_and_headers(
        client, session, tenant_name="Proximity Tenant No Avail Row"
    )

    vehicle = Vehicle(tenant_id=tenant_id, rego="TX-NOAVAIL", vehicle_class="standard", status="active")
    session.add(vehicle)
    await session.commit()
    await session.refresh(vehicle)

    driver = await _make_driver(session, tenant_id=tenant_id, name="Never Toggled")
    await _make_shift(session, tenant_id=tenant_id, driver_id=driver.id, vehicle_id=vehicle.id)

    result = await publish_position(
        session, tenant_id=tenant_id, vehicle_id=vehicle.id, lat=-33.91, lng=151.26, status="available"
    )
    assert result["vehicle_id"] == vehicle.id

    from sqlalchemy import select

    row_result = await session.execute(
        select(DriverAvailability).where(
            DriverAvailability.tenant_id == tenant_id, DriverAvailability.driver_id == driver.id
        )
    )
    assert row_result.scalar_one_or_none() is None


# --- end-to-end: publish_position feeds create_job_and_broadcast's ordering -------------


async def test_end_to_end_published_positions_drive_offer_order(client: AsyncClient, session: AsyncSession):
    """Full stack, over HTTP where possible: two drivers publish real
    positions via `POST /v1/fleet/positions`, then `POST /v1/jobs` must offer
    the nearer one first."""
    tenant_id, admin_headers = await _tenant_and_headers(client, session, tenant_name="Proximity Tenant E2E")

    near_vehicle = Vehicle(tenant_id=tenant_id, rego="TX-NEAR", vehicle_class="standard", status="active")
    far_vehicle = Vehicle(tenant_id=tenant_id, rego="TX-FAR", vehicle_class="standard", status="active")
    session.add_all([near_vehicle, far_vehicle])
    await session.commit()
    await session.refresh(near_vehicle)
    await session.refresh(far_vehicle)

    near_driver, _ = await _available_driver_at(
        client, session, tenant_id=tenant_id, name="E2E Near", vehicle_id=near_vehicle.id
    )
    far_driver, _ = await _available_driver_at(
        client, session, tenant_id=tenant_id, name="E2E Far", vehicle_id=far_vehicle.id
    )

    pub_near = await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": near_vehicle.id, "lat": -33.8690, "lng": 151.2095, "status": "available"},
        headers=admin_headers,
    )
    assert pub_near.status_code == 201, pub_near.text
    pub_far = await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": far_vehicle.id, "lat": -33.95, "lng": 151.30, "status": "available"},
        headers=admin_headers,
    )
    assert pub_far.status_code == 201, pub_far.text

    create_resp = await client.post(
        "/v1/jobs",
        json={
            "origin_lat": _ORIGIN_LAT,
            "origin_lng": _ORIGIN_LNG,
            "origin_address": "1 Test St, Sydney",
            "dest_lat": -33.8568,
            "dest_lng": 151.2153,
            "dest_address": "2 Test Ave, Sydney",
            "fare_estimate_low": "20.00",
            "fare_estimate_high": "28.00",
        },
        headers=admin_headers,
    )
    assert create_resp.status_code == 201, create_resp.text
    job_id = create_resp.json()["id"]

    offers = (await client.get(f"/v1/jobs/{job_id}/offers", headers=admin_headers)).json()
    assert len(offers) == 2
    assert offers[0]["driver_id"] == near_driver.id
    assert offers[1]["driver_id"] == far_driver.id
