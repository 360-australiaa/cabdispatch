# Tests for the Shift Handover domain (`POST /v1/shifts/{id}/handover`,
# WP-32, architecture plan D-2).
#
# Reuses the ReadyFixture / _make_ready_fixture / _make_ready_driver /
# _authorise_driver / _trip_kwargs helpers from tests/test_shifts.py rather
# than re-defining them, per the WP-32 task brief's own instruction to share
# rather than duplicate.
from __future__ import annotations

import uuid

from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.shift import Shift
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

# Plain-text PIN every _make_user-built User is given (see that helper's
# defaults) -- used to exercise the incoming-driver PIN re-authentication.
_DRIVER_PIN = "Test-Passw0rd!"


async def _open_shift_count(session: AsyncSession, *, tenant_id: str, vehicle_id: str) -> int:
    result = await session.execute(
        select(Shift.id).where(
            Shift.tenant_id == tenant_id,
            Shift.vehicle_id == vehicle_id,
            Shift.end_at.is_(None),
        )
    )
    return len(result.scalars().all())


async def _start_shift(client: AsyncClient, fx: ReadyFixture, *, driver=None) -> dict:
    driver = driver or fx.driver
    resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": driver.id, "vehicle_id": fx.vehicle.id},
        headers=_headers_for(driver, fx.tenant.id),
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


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


# --- happy path ------------------------------------------------------------


async def test_handover_happy_path_closes_and_opens_shifts(
    client: AsyncClient, session: AsyncSession
):
    fx = await _make_ready_fixture(session, tenant_name="Handover Happy Path Co")
    driver_b = await _second_ready_driver(session, fx)

    outgoing = await _start_shift(client, fx)
    assert await _open_shift_count(session, tenant_id=fx.tenant.id, vehicle_id=fx.vehicle.id) == 1

    resp = await client.post(
        f"/v1/shifts/{outgoing['id']}/handover",
        json={
            "incoming_driver_id": driver_b.id,
            "incoming_driver_pin": _DRIVER_PIN,
            "odometer_end": 12345,
            "fuel_level": 80,
            "cleanliness_notes": "Clean, no rubbish.",
            "damage_notes": None,
        },
        headers=fx.driver_headers,  # outgoing driver performs the handover
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["outgoing_shift_id"] == outgoing["id"]
    assert body["odometer_end"] == 12345
    assert body["fuel_level"] == 80
    incoming_shift_id = body["incoming_shift_id"]
    assert incoming_shift_id != outgoing["id"]

    # Exactly one open shift for this vehicle, throughout -- the D-1
    # guarantee this whole work package exists to prove, checked by
    # querying the shifts table directly (not just trusting the response).
    assert await _open_shift_count(session, tenant_id=fx.tenant.id, vehicle_id=fx.vehicle.id) == 1

    outgoing_row = (
        await session.execute(select(Shift).where(Shift.id == outgoing["id"]))
    ).scalar_one()
    assert outgoing_row.end_at is not None

    incoming_row = (
        await session.execute(select(Shift).where(Shift.id == incoming_shift_id))
    ).scalar_one()
    assert incoming_row.end_at is None
    assert incoming_row.driver_id == driver_b.id
    assert incoming_row.vehicle_id == fx.vehicle.id

    handover_row = (
        await session.execute(
            select(ShiftHandover).where(ShiftHandover.outgoing_shift_id == outgoing["id"])
        )
    ).scalar_one()
    assert handover_row.incoming_shift_id == incoming_shift_id
    assert handover_row.handed_over_by_user_id == fx.driver.id


async def test_dispatcher_can_perform_handover(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Handover Dispatcher Co")
    driver_b = await _second_ready_driver(session, fx)
    outgoing = await _start_shift(client, fx)

    resp = await client.post(
        f"/v1/shifts/{outgoing['id']}/handover",
        json={"incoming_driver_id": driver_b.id, "incoming_driver_pin": _DRIVER_PIN},
        headers=fx.admin_headers,
    )
    assert resp.status_code == 201, resp.text


# --- wrong PIN: 401, nothing mutated -----------------------------------------


async def test_handover_wrong_pin_rejected_and_nothing_mutated(
    client: AsyncClient, session: AsyncSession
):
    fx = await _make_ready_fixture(session, tenant_name="Handover Wrong Pin Co")
    driver_b = await _second_ready_driver(session, fx)
    outgoing = await _start_shift(client, fx)

    resp = await client.post(
        f"/v1/shifts/{outgoing['id']}/handover",
        json={"incoming_driver_id": driver_b.id, "incoming_driver_pin": "wrong-pin"},
        headers=fx.driver_headers,
    )
    assert resp.status_code == 401, resp.text

    outgoing_row = (
        await session.execute(select(Shift).where(Shift.id == outgoing["id"]))
    ).scalar_one()
    assert outgoing_row.end_at is None  # untouched

    assert await _open_shift_count(session, tenant_id=fx.tenant.id, vehicle_id=fx.vehicle.id) == 1
    handovers = (
        await session.execute(
            select(ShiftHandover).where(ShiftHandover.outgoing_shift_id == outgoing["id"])
        )
    ).scalars().all()
    assert handovers == []


# --- open trip on outgoing shift blocks the handover: 409 --------------------


async def test_handover_blocked_by_open_trip_on_outgoing_shift(
    client: AsyncClient, session: AsyncSession
):
    fx = await _make_ready_fixture(session, tenant_name="Handover Open Trip Co")
    driver_b = await _second_ready_driver(session, fx)
    outgoing = await _start_shift(client, fx)

    trip = Trip(
        **_trip_kwargs(
            tenant_id=fx.tenant.id,
            shift_id=outgoing["id"],
            driver_id=fx.driver.id,
            vehicle_id=fx.vehicle.id,
            status="open",
            end_at=None,
        )
    )
    session.add(trip)
    await session.commit()

    resp = await client.post(
        f"/v1/shifts/{outgoing['id']}/handover",
        json={"incoming_driver_id": driver_b.id, "incoming_driver_pin": _DRIVER_PIN},
        headers=fx.driver_headers,
    )
    assert resp.status_code == 409, resp.text

    outgoing_row = (
        await session.execute(select(Shift).where(Shift.id == outgoing["id"]))
    ).scalar_one()
    assert outgoing_row.end_at is None
    assert await _open_shift_count(session, tenant_id=fx.tenant.id, vehicle_id=fx.vehicle.id) == 1


# --- caller neither outgoing driver nor dispatch staff: 403 ------------------


async def test_handover_caller_not_authorised(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Handover Caller Guard Co")
    driver_b = await _second_ready_driver(session, fx)
    outgoing = await _start_shift(client, fx)

    resp = await client.post(
        f"/v1/shifts/{outgoing['id']}/handover",
        json={"incoming_driver_id": driver_b.id, "incoming_driver_pin": _DRIVER_PIN},
        headers=_headers_for(driver_b, fx.tenant.id),  # driver_b is not the outgoing driver
    )
    assert resp.status_code == 403, resp.text

    outgoing_row = (
        await session.execute(select(Shift).where(Shift.id == outgoing["id"]))
    ).scalar_one()
    assert outgoing_row.end_at is None


# --- incoming driver fails a gate 1 style eligibility gate: same as start_shift ---


async def test_handover_incoming_driver_not_on_roster_rejected(
    client: AsyncClient, session: AsyncSession
):
    fx = await _make_ready_fixture(session, tenant_name="Handover Not On Roster Co")
    # A real, otherwise-eligible driver, but NOT authorised on fx.vehicle's roster.
    driver_b = await _make_ready_driver(session, fx.tenant.id)
    outgoing = await _start_shift(client, fx)

    resp = await client.post(
        f"/v1/shifts/{outgoing['id']}/handover",
        json={"incoming_driver_id": driver_b.id, "incoming_driver_pin": _DRIVER_PIN},
        headers=fx.driver_headers,
    )
    assert resp.status_code == 422, resp.text

    outgoing_row = (
        await session.execute(select(Shift).where(Shift.id == outgoing["id"]))
    ).scalar_one()
    assert outgoing_row.end_at is None
    assert await _open_shift_count(session, tenant_id=fx.tenant.id, vehicle_id=fx.vehicle.id) == 1


async def test_handover_shift_not_found_404(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Handover Not Found Co")
    driver_b = await _second_ready_driver(session, fx)

    resp = await client.post(
        f"/v1/shifts/{uuid.uuid4()}/handover",
        json={"incoming_driver_id": driver_b.id, "incoming_driver_pin": _DRIVER_PIN},
        headers=fx.driver_headers,
    )
    assert resp.status_code == 404, resp.text


async def test_handover_already_closed_shift_404(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Handover Already Closed Co")
    driver_b = await _second_ready_driver(session, fx)
    outgoing = await _start_shift(client, fx)

    end_resp = await client.post(
        f"/v1/shifts/{outgoing['id']}/end",
        json={"psl_owed": "0", "reconciled": True},
        headers=fx.driver_headers,
    )
    assert end_resp.status_code == 200, end_resp.text

    resp = await client.post(
        f"/v1/shifts/{outgoing['id']}/handover",
        json={"incoming_driver_id": driver_b.id, "incoming_driver_pin": _DRIVER_PIN},
        headers=fx.driver_headers,
    )
    assert resp.status_code == 404, resp.text


# --- plan Part 5 Q3 walkthrough: two drivers, 12 hours each, one vehicle -----


async def test_two_drivers_twelve_hours_each_handover_walkthrough(
    client: AsyncClient, session: AsyncSession
):
    """Driver A opens the vehicle -> hands over to Driver B -> Driver B hands
    back to Driver A. Three shift-open events total, D-1 respected at every
    step (exactly one open shift for this vehicle at all times, checked
    directly against the shifts table after each step, never zero and never
    two)."""
    fx = await _make_ready_fixture(session, tenant_name="Two Drivers Twelve Hours Co")
    driver_b = await _second_ready_driver(session, fx)

    shift_1 = await _start_shift(client, fx, driver=fx.driver)
    assert await _open_shift_count(session, tenant_id=fx.tenant.id, vehicle_id=fx.vehicle.id) == 1

    handover_1 = await client.post(
        f"/v1/shifts/{shift_1['id']}/handover",
        json={"incoming_driver_id": driver_b.id, "incoming_driver_pin": _DRIVER_PIN},
        headers=fx.driver_headers,
    )
    assert handover_1.status_code == 201, handover_1.text
    shift_2_id = handover_1.json()["incoming_shift_id"]
    assert await _open_shift_count(session, tenant_id=fx.tenant.id, vehicle_id=fx.vehicle.id) == 1

    handover_2 = await client.post(
        f"/v1/shifts/{shift_2_id}/handover",
        json={"incoming_driver_id": fx.driver.id, "incoming_driver_pin": _DRIVER_PIN},
        headers=_headers_for(driver_b, fx.tenant.id),
    )
    assert handover_2.status_code == 201, handover_2.text
    shift_3_id = handover_2.json()["incoming_shift_id"]
    assert await _open_shift_count(session, tenant_id=fx.tenant.id, vehicle_id=fx.vehicle.id) == 1

    # Exactly the three shifts exist for this vehicle: the first two closed,
    # the third (currently open) driven by fx.driver again.
    all_shifts = (
        await session.execute(select(Shift).where(Shift.vehicle_id == fx.vehicle.id))
    ).scalars().all()
    assert {s.id for s in all_shifts} == {shift_1["id"], shift_2_id, shift_3_id}
    by_id = {s.id: s for s in all_shifts}
    assert by_id[shift_1["id"]].end_at is not None
    assert by_id[shift_2_id].end_at is not None
    assert by_id[shift_3_id].end_at is None
    assert by_id[shift_3_id].driver_id == fx.driver.id

    handovers = (
        await session.execute(
            select(ShiftHandover).where(ShiftHandover.tenant_id == fx.tenant.id)
        )
    ).scalars().all()
    assert len(handovers) == 2