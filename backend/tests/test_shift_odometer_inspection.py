# Tests for WP-33 (architecture plan Part 4 Phase 3): odometer_start/
# odometer_end on Shift, end_shift refusing to close while a trip is still
# open, and the end-of-shift inspection checklist (end_inspection_json).
#
# Reuses the ReadyFixture / _make_ready_fixture / _authorise_driver /
# _make_ready_driver / _trip_kwargs helpers from tests/test_shifts.py rather
# than re-defining them, same convention tests/test_shift_handover.py already
# follows.
from __future__ import annotations

from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.shift import Shift
# Importing ShiftHandover here (even though this module never constructs one
# directly) is load-bearing, not decorative: tests/conftest.py's session DB
# is created from whatever tables are registered on Base.metadata at the
# point its create_all() runs, and when this file is the ONLY test module
# collected (e.g. `pytest tests/test_shift_odometer_inspection.py` on its
# own, not the full suite), nothing else would have imported
# app.models.shift_handover first -- without this import, the handover
# tests below fail with "no such table: shift_handovers", not a real bug in
# app code. tests/test_shift_handover.py already relies on the same effect
# via its own ShiftHandover import.
from app.models.shift_handover import ShiftHandover
from app.models.trips import Trip
from tests.test_shifts import (
    ReadyFixture,
    _authorise_driver,
    _headers_for,
    _make_ready_driver,
    _make_ready_fixture,
    _trip_kwargs,
)

_DRIVER_PIN = "Test-Passw0rd!"


async def _second_ready_driver(session: AsyncSession, fx: ReadyFixture):
    driver_b = await _make_ready_driver(session, fx.tenant.id)
    await _authorise_driver(
        session,
        fx.tenant.id,
        vehicle_id=fx.vehicle.id,
        driver_id=driver_b.id,
        actor_user_id=fx.admin.id,
    )
    return driver_b


# --- odometer_start / odometer_end round-trip through the API ---------------


async def test_odometer_start_and_end_round_trip_through_api(
    client: AsyncClient, session: AsyncSession
):
    fx = await _make_ready_fixture(session, tenant_name="Odometer Round Trip Co")

    start_resp = await client.post(
        "/v1/shifts/start",
        json={
            "driver_id": fx.driver.id,
            "vehicle_id": fx.vehicle.id,
            "odometer_start": 100000,
        },
        headers=fx.driver_headers,
    )
    assert start_resp.status_code == 201, start_resp.text
    shift_id = start_resp.json()["id"]
    assert start_resp.json()["odometer_start"] == 100000
    assert start_resp.json()["odometer_end"] is None

    end_resp = await client.post(
        f"/v1/shifts/{shift_id}/end",
        json={"psl_owed": "0.00", "reconciled": True, "odometer_end": 100250},
        headers=fx.driver_headers,
    )
    assert end_resp.status_code == 200, end_resp.text
    body = end_resp.json()
    assert body["odometer_start"] == 100000
    assert body["odometer_end"] == 100250

    row = (await session.execute(select(Shift).where(Shift.id == shift_id))).scalar_one()
    assert row.odometer_start == 100000
    assert row.odometer_end == 100250


async def test_odometer_fields_default_to_none_when_omitted(
    client: AsyncClient, session: AsyncSession
):
    fx = await _make_ready_fixture(session, tenant_name="Odometer Omitted Co")

    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,
    )
    assert start_resp.status_code == 201, start_resp.text
    assert start_resp.json()["odometer_start"] is None

    end_resp = await client.post(
        f"/v1/shifts/{start_resp.json()['id']}/end",
        json={"psl_owed": "0.00", "reconciled": True},
        headers=fx.driver_headers,
    )
    assert end_resp.status_code == 200, end_resp.text
    assert end_resp.json()["odometer_end"] is None


# --- odometer_start wired from the prior handover's odometer_end (WP-33 ------
# wiring up Stage 2's "STAGE 3 NOTE" pointer on ShiftHandover) ---------------


async def test_handover_opened_shift_gets_odometer_start_from_prior_handover(
    client: AsyncClient, session: AsyncSession
):
    fx = await _make_ready_fixture(session, tenant_name="Handover Odometer Co")
    driver_b = await _second_ready_driver(session, fx)

    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id, "odometer_start": 50000},
        headers=fx.driver_headers,
    )
    assert start_resp.status_code == 201, start_resp.text
    outgoing_shift_id = start_resp.json()["id"]

    handover_resp = await client.post(
        f"/v1/shifts/{outgoing_shift_id}/handover",
        json={
            "incoming_driver_id": driver_b.id,
            "incoming_driver_pin": _DRIVER_PIN,
            "odometer_end": 50175,
        },
        headers=fx.driver_headers,
    )
    assert handover_resp.status_code == 201, handover_resp.text
    incoming_shift_id = handover_resp.json()["incoming_shift_id"]

    incoming_row = (
        await session.execute(select(Shift).where(Shift.id == incoming_shift_id))
    ).scalar_one()
    assert incoming_row.odometer_start == 50175

    # Confirm via the API too, not just the row directly.
    get_resp = await client.get(f"/v1/shifts/{incoming_shift_id}", headers=fx.admin_headers)
    assert get_resp.status_code == 200, get_resp.text
    assert get_resp.json()["odometer_start"] == 50175


async def test_handover_with_no_odometer_end_leaves_incoming_odometer_start_none(
    client: AsyncClient, session: AsyncSession
):
    fx = await _make_ready_fixture(session, tenant_name="Handover No Odometer Co")
    driver_b = await _second_ready_driver(session, fx)

    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,
    )
    outgoing_shift_id = start_resp.json()["id"]

    handover_resp = await client.post(
        f"/v1/shifts/{outgoing_shift_id}/handover",
        json={"incoming_driver_id": driver_b.id, "incoming_driver_pin": _DRIVER_PIN},
        headers=fx.driver_headers,
    )
    assert handover_resp.status_code == 201, handover_resp.text
    incoming_row = (
        await session.execute(
            select(Shift).where(Shift.id == handover_resp.json()["incoming_shift_id"])
        )
    ).scalar_one()
    assert incoming_row.odometer_start is None


# --- end_shift refuses to close while a trip is still open (409) ------------


async def test_end_shift_rejected_with_open_trip_and_shift_stays_open(
    client: AsyncClient, session: AsyncSession
):
    fx = await _make_ready_fixture(session, tenant_name="End Shift Open Trip Co")

    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,
    )
    shift_id = start_resp.json()["id"]
    tenant_id = start_resp.json()["tenant_id"]

    trip = Trip(
        **_trip_kwargs(
            tenant_id=tenant_id,
            shift_id=shift_id,
            driver_id=fx.driver.id,
            vehicle_id=fx.vehicle.id,
            status="open",
            end_at=None,
        )
    )
    session.add(trip)
    await session.commit()

    resp = await client.post(
        f"/v1/shifts/{shift_id}/end",
        json={"psl_owed": "0.00", "reconciled": True},
        headers=fx.driver_headers,
    )
    assert resp.status_code == 409, resp.text

    row = (await session.execute(select(Shift).where(Shift.id == shift_id))).scalar_one()
    assert row.end_at is None  # the shift was NOT closed

    # Close the trip, then confirm end_shift now succeeds -- proves the 409
    # was genuinely caused by the open trip, not something else.
    trip.status = "closed"
    await session.commit()

    resp2 = await client.post(
        f"/v1/shifts/{shift_id}/end",
        json={"psl_owed": "0.00", "reconciled": True},
        headers=fx.driver_headers,
    )
    assert resp2.status_code == 200, resp2.text


# --- regression: the SAME open-trip guard blocks a handover too, proving ----
# end_shift's guard and the pre-existing handover guard did not diverge -----
# (both now call app.services.shift.has_open_trip) ---------------------------


async def test_handover_also_blocked_by_same_open_trip_guard(
    client: AsyncClient, session: AsyncSession
):
    fx = await _make_ready_fixture(session, tenant_name="Shared Guard Regression Co")
    driver_b = await _second_ready_driver(session, fx)

    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,
    )
    shift_id = start_resp.json()["id"]
    tenant_id = start_resp.json()["tenant_id"]

    trip = Trip(
        **_trip_kwargs(
            tenant_id=tenant_id,
            shift_id=shift_id,
            driver_id=fx.driver.id,
            vehicle_id=fx.vehicle.id,
            status="open",
            end_at=None,
        )
    )
    session.add(trip)
    await session.commit()

    end_resp = await client.post(
        f"/v1/shifts/{shift_id}/end",
        json={"psl_owed": "0.00", "reconciled": True},
        headers=fx.driver_headers,
    )
    assert end_resp.status_code == 409, end_resp.text

    handover_resp = await client.post(
        f"/v1/shifts/{shift_id}/handover",
        json={"incoming_driver_id": driver_b.id, "incoming_driver_pin": _DRIVER_PIN},
        headers=fx.driver_headers,
    )
    assert handover_resp.status_code == 409, handover_resp.text

    row = (await session.execute(select(Shift).where(Shift.id == shift_id))).scalar_one()
    assert row.end_at is None  # untouched by either rejected attempt


# --- end_inspection_json round-trips through the API -------------------------


async def test_end_inspection_json_round_trips_through_api(
    client: AsyncClient, session: AsyncSession
):
    fx = await _make_ready_fixture(session, tenant_name="End Inspection Co")

    start_resp = await client.post(
        "/v1/shifts/start",
        json={
            "driver_id": fx.driver.id,
            "vehicle_id": fx.vehicle.id,
            "inspection_json": {"tyres": "ok"},
        },
        headers=fx.driver_headers,
    )
    shift_id = start_resp.json()["id"]
    assert start_resp.json()["end_inspection_json"] is None

    checklist = {"tyres": "ok", "lights": "ok", "fuel_cap": "secure", "damage": "none noted"}
    end_resp = await client.post(
        f"/v1/shifts/{shift_id}/end",
        json={"psl_owed": "0.00", "reconciled": True, "end_inspection_json": checklist},
        headers=fx.driver_headers,
    )
    assert end_resp.status_code == 200, end_resp.text
    assert end_resp.json()["end_inspection_json"] == checklist
    # The pre-shift checklist is untouched by the end-of-shift one.
    assert end_resp.json()["inspection_json"] == {"tyres": "ok"}

    row = (await session.execute(select(Shift).where(Shift.id == shift_id))).scalar_one()
    assert row.end_inspection_json == checklist
