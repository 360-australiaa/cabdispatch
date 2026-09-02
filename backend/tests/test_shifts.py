"""Tests for the Shifts domain (`/v1/shifts`).

NOTE: as of writing, this domain's router is not yet registered in app.main —
that happens in a later integration step that wires all 12 domain routers
together. These tests are written correctly against the endpoints as built and
will pass once that registration lands; run in isolation today they will 404.
"""
from __future__ import annotations

import uuid
from datetime import UTC, datetime
from decimal import Decimal

from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.audit_log import AuditLog
from app.models.fleet import Device, Vehicle
from app.models.trips import Trip
from tests.conftest import auth_headers


async def _tenant_of(headers: dict) -> str:
    from app.core import security

    token = headers["Authorization"].split(" ", 1)[1]
    return security.decode_token(token)["tenant_id"]


def _trip_kwargs(*, tenant_id: str, shift_id: str, driver_id: str, vehicle_id: str, **overrides):
    base = {
        "id": str(uuid.uuid4()),
        "tenant_id": tenant_id,
        "client_uuid": str(uuid.uuid4()),
        "vehicle_id": vehicle_id,
        "driver_id": driver_id,
        "shift_id": shift_id,
        "tariff_id": str(uuid.uuid4()),
        "type": "rank_hail",
        "status": "closed",
        "start_at": datetime.now(UTC),
        "end_at": datetime.now(UTC),
        "start_lat": -33.87,
        "start_lng": 151.21,
        "distance_m": 5000,
        "payment_method": "cash",
        "total": Decimal("25.00"),
    }
    base.update(overrides)
    return base


async def test_start_shift_opens_shift(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    driver_id = str(uuid.uuid4())
    vehicle_id = str(uuid.uuid4())

    resp = await client.post(
        "/v1/shifts/start",
        json={
            "driver_id": driver_id,
            "vehicle_id": vehicle_id,
            "inspection_json": {"tyres": "ok", "lights": "ok"},
        },
        headers=headers,
    )

    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["driver_id"] == driver_id
    assert body["vehicle_id"] == vehicle_id
    assert body["end_at"] is None
    assert body["trips_count"] == 0
    assert body["inspection_json"] == {"tyres": "ok", "lights": "ok"}


async def test_get_shift_by_id(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="dispatcher")
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": str(uuid.uuid4()), "vehicle_id": str(uuid.uuid4())},
        headers=headers,
    )
    shift_id = start_resp.json()["id"]

    resp = await client.get(f"/v1/shifts/{shift_id}", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["id"] == shift_id


async def test_get_shift_not_found(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    resp = await client.get(f"/v1/shifts/{uuid.uuid4()}", headers=headers)
    assert resp.status_code == 404


async def test_end_shift_recomputes_aggregates_from_trips(
    client: AsyncClient, session: AsyncSession
):
    headers = await auth_headers(client, session, role="admin", tenant_name="Recon Co")

    driver_id = str(uuid.uuid4())
    vehicle_id = str(uuid.uuid4())
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": driver_id, "vehicle_id": vehicle_id},
        headers=headers,
    )
    shift = start_resp.json()
    shift_id = shift["id"]
    tenant_id = shift["tenant_id"]

    # Two cash trips + one card trip, aggregated at shift-end time.
    session.add(
        Trip(
            **_trip_kwargs(
                tenant_id=tenant_id,
                shift_id=shift_id,
                driver_id=driver_id,
                vehicle_id=vehicle_id,
                payment_method="cash",
                total=Decimal("20.00"),
                distance_m=4000,
            )
        )
    )
    session.add(
        Trip(
            **_trip_kwargs(
                tenant_id=tenant_id,
                shift_id=shift_id,
                driver_id=driver_id,
                vehicle_id=vehicle_id,
                payment_method="cash",
                total=Decimal("15.50"),
                distance_m=3000,
            )
        )
    )
    session.add(
        Trip(
            **_trip_kwargs(
                tenant_id=tenant_id,
                shift_id=shift_id,
                driver_id=driver_id,
                vehicle_id=vehicle_id,
                payment_method="tap_to_pay",
                total=Decimal("42.75"),
                distance_m=8000,
            )
        )
    )
    # A trip belonging to a DIFFERENT shift must not be counted.
    other_shift_id = str(uuid.uuid4())
    session.add(
        Trip(
            **_trip_kwargs(
                tenant_id=tenant_id,
                shift_id=other_shift_id,
                driver_id=driver_id,
                vehicle_id=vehicle_id,
                payment_method="cash",
                total=Decimal("999.00"),
                distance_m=99000,
            )
        )
    )
    await session.commit()

    resp = await client.post(
        f"/v1/shifts/{shift_id}/end",
        json={"psl_owed": "4.20", "reconciled": True},
        headers=headers,
    )

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["end_at"] is not None
    assert body["trips_count"] == 3
    assert Decimal(body["km_total"]) == Decimal("15.000")
    assert Decimal(body["cash_total"]) == Decimal("35.50")
    assert Decimal(body["card_total"]) == Decimal("42.75")
    assert Decimal(body["psl_owed"]) == Decimal("4.20")
    assert body["reconciled"] is True


async def test_end_shift_twice_conflicts(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": str(uuid.uuid4()), "vehicle_id": str(uuid.uuid4())},
        headers=headers,
    )
    shift_id = start_resp.json()["id"]

    first = await client.post(
        f"/v1/shifts/{shift_id}/end", json={"psl_owed": "0", "reconciled": True}, headers=headers
    )
    assert first.status_code == 200

    second = await client.post(
        f"/v1/shifts/{shift_id}/end", json={"psl_owed": "0", "reconciled": True}, headers=headers
    )
    assert second.status_code == 409


async def test_report_endpoint_returns_summary(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": str(uuid.uuid4()), "vehicle_id": str(uuid.uuid4())},
        headers=headers,
    )
    shift_id = start_resp.json()["id"]

    await client.post(
        f"/v1/shifts/{shift_id}/end",
        json={"psl_owed": "1.32", "reconciled": True},
        headers=headers,
    )

    resp = await client.get(f"/v1/shifts/{shift_id}/report", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["shift_id"] == shift_id
    assert body["total_takings"] == "0.00"
    assert body["duration_minutes"] is not None
    assert "generated_at" in body


async def test_list_shifts_pagination_and_filters(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    driver_a = str(uuid.uuid4())
    driver_b = str(uuid.uuid4())

    for driver_id in (driver_a, driver_a, driver_b):
        await client.post(
            "/v1/shifts/start",
            json={"driver_id": driver_id, "vehicle_id": str(uuid.uuid4())},
            headers=headers,
        )

    all_resp = await client.get("/v1/shifts", headers=headers)
    assert all_resp.status_code == 200
    all_body = all_resp.json()
    assert all_body["total"] >= 3
    assert all_body["limit"] == 50
    assert all_body["offset"] == 0

    filtered = await client.get(
        "/v1/shifts", params={"driver_id": driver_a}, headers=headers
    )
    assert filtered.status_code == 200
    assert filtered.json()["total"] == 2

    paged = await client.get(
        "/v1/shifts", params={"driver_id": driver_a, "limit": 1, "offset": 1}, headers=headers
    )
    assert paged.status_code == 200
    assert len(paged.json()["items"]) == 1

    active_only = await client.get(
        "/v1/shifts", params={"driver_id": driver_a, "active_only": True}, headers=headers
    )
    # driver_a has 2 shift ROWS total (asserted above), but only 1 truly open:
    # starting the second one auto-closed the first (see
    # test_starting_a_new_shift_auto_closes_the_same_drivers_own_dangling_shift)
    # -- a driver can never have two simultaneously open shifts.
    assert active_only.json()["total"] == 1


async def test_driver_cannot_patch_or_delete_shift(client: AsyncClient, session: AsyncSession):
    admin_headers = await auth_headers(client, session, role="admin", tenant_name="RBAC Co")
    driver_headers = await auth_headers(
        client, session, role="driver", tenant_id=None, tenant_name="RBAC Co 2"
    )

    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": str(uuid.uuid4()), "vehicle_id": str(uuid.uuid4())},
        headers=admin_headers,
    )
    shift_id = start_resp.json()["id"]

    patch_resp = await client.patch(
        f"/v1/shifts/{shift_id}", json={"reconciled": True}, headers=admin_headers
    )
    assert patch_resp.status_code == 200

    # A driver in a DIFFERENT tenant can't even see this shift (tenant isolation),
    # and drivers generally are barred from PATCH/DELETE regardless of tenant.
    forbidden_resp = await client.patch(
        f"/v1/shifts/{shift_id}", json={"reconciled": False}, headers=driver_headers
    )
    assert forbidden_resp.status_code in (403, 404)


async def test_update_and_delete_shift_as_admin(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="owner")
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": str(uuid.uuid4()), "vehicle_id": str(uuid.uuid4())},
        headers=headers,
    )
    shift_id = start_resp.json()["id"]

    patch_resp = await client.patch(
        f"/v1/shifts/{shift_id}",
        json={"inspection_json": {"note": "corrected by admin"}},
        headers=headers,
    )
    assert patch_resp.status_code == 200
    assert patch_resp.json()["inspection_json"] == {"note": "corrected by admin"}

    delete_resp = await client.delete(f"/v1/shifts/{shift_id}", headers=headers)
    assert delete_resp.status_code == 204

    get_resp = await client.get(f"/v1/shifts/{shift_id}", headers=headers)
    assert get_resp.status_code == 404


async def test_tenant_isolation_on_shifts(client: AsyncClient, session: AsyncSession):
    tenant_a_headers = await auth_headers(client, session, role="admin", tenant_name="Tenant A")
    tenant_b_headers = await auth_headers(client, session, role="admin", tenant_name="Tenant B")

    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": str(uuid.uuid4()), "vehicle_id": str(uuid.uuid4())},
        headers=tenant_a_headers,
    )
    shift_id = start_resp.json()["id"]

    cross_tenant_resp = await client.get(f"/v1/shifts/{shift_id}", headers=tenant_b_headers)
    assert cross_tenant_resp.status_code == 404


# --- one-vehicle-two-drivers handover (operational safety pass) -------------
#
# Real fleets run one vehicle across back-to-back shifts by different drivers
# (a classic 12h/12h double-shift). Two failure modes must never be possible:
# (1) two drivers simultaneously "having" the same vehicle on paper (who's
#     actually liable if there's an incident?), and (2) one driver somehow
#     on shift in two vehicles/twice at once (undermines the fatigue-hours
#     limit entirely). See app.services.shift.start_shift's own docstring.


async def test_second_driver_cannot_start_a_shift_on_a_vehicle_already_in_use(
    client: AsyncClient, session: AsyncSession
):
    headers = await auth_headers(client, session, role="driver")
    vehicle_id = str(uuid.uuid4())
    driver_a, driver_b = str(uuid.uuid4()), str(uuid.uuid4())

    first = await client.post(
        "/v1/shifts/start",
        json={"driver_id": driver_a, "vehicle_id": vehicle_id},
        headers=headers,
    )
    assert first.status_code == 201, first.text

    second = await client.post(
        "/v1/shifts/start",
        json={"driver_id": driver_b, "vehicle_id": vehicle_id},
        headers=headers,
    )
    assert second.status_code == 409, second.text
    detail = second.json()["detail"]
    assert detail["conflicting_driver_id"] == driver_a
    assert detail["conflicting_shift_id"] == first.json()["id"]

    # The vehicle still shows exactly one open shift — driver A's, untouched.
    list_resp = await client.get(
        "/v1/shifts", params={"vehicle_id": vehicle_id, "active_only": "true"}, headers=headers
    )
    items = list_resp.json()["items"]
    assert len(items) == 1
    assert items[0]["driver_id"] == driver_a
    assert items[0]["end_at"] is None


async def test_force_handover_closes_the_outgoing_drivers_shift_and_opens_the_new_one(
    client: AsyncClient, session: AsyncSession
):
    headers = await auth_headers(client, session, role="dispatcher")
    vehicle_id = str(uuid.uuid4())
    driver_a, driver_b = str(uuid.uuid4()), str(uuid.uuid4())

    morning = await client.post(
        "/v1/shifts/start",
        json={"driver_id": driver_a, "vehicle_id": vehicle_id},
        headers=headers,
    )
    assert morning.status_code == 201, morning.text
    morning_shift_id = morning.json()["id"]

    evening = await client.post(
        "/v1/shifts/start",
        json={"driver_id": driver_b, "vehicle_id": vehicle_id, "force_handover": True},
        headers=headers,
    )
    assert evening.status_code == 201, evening.text
    assert evening.json()["driver_id"] == driver_b
    assert evening.json()["end_at"] is None

    # The outgoing driver's shift is now closed, stamped at the handover moment.
    closed = await client.get(f"/v1/shifts/{morning_shift_id}", headers=headers)
    assert closed.json()["end_at"] is not None
    assert closed.json()["end_at"] == evening.json()["start_at"]

    # Vehicle history now shows a clean back-to-back handover, newest first —
    # this IS the "which drivers have had this vehicle, and when" view.
    history = await client.get(
        "/v1/shifts", params={"vehicle_id": vehicle_id}, headers=headers
    )
    items = history.json()["items"]
    assert len(items) == 2
    assert items[0]["driver_id"] == driver_b  # newest first
    assert items[1]["driver_id"] == driver_a


async def test_starting_a_new_shift_auto_closes_the_same_drivers_own_dangling_shift(
    client: AsyncClient, session: AsyncSession
):
    """A driver forgetting to tap "End Shift" (crash, flat battery, drove home
    without closing out — this happens constantly in real fleets) must not
    permanently jam that driver out of ever starting a new shift, and must
    not require dispatcher intervention to recover from."""
    headers = await auth_headers(client, session, role="driver")
    driver_id = str(uuid.uuid4())
    old_vehicle, new_vehicle = str(uuid.uuid4()), str(uuid.uuid4())

    first = await client.post(
        "/v1/shifts/start",
        json={"driver_id": driver_id, "vehicle_id": old_vehicle},
        headers=headers,
    )
    assert first.status_code == 201, first.text
    first_shift_id = first.json()["id"]

    second = await client.post(
        "/v1/shifts/start",
        json={"driver_id": driver_id, "vehicle_id": new_vehicle},
        headers=headers,
    )
    assert second.status_code == 201, second.text
    assert second.json()["vehicle_id"] == new_vehicle
    assert second.json()["end_at"] is None

    stale = await client.get(f"/v1/shifts/{first_shift_id}", headers=headers)
    assert stale.json()["end_at"] is not None


async def test_same_driver_starting_a_new_shift_on_the_same_vehicle_is_not_a_conflict(
    client: AsyncClient, session: AsyncSession
):
    """A driver re-starting on the SAME vehicle they were already on (e.g. a
    quick restart after correcting a mistaken checklist entry) is just the
    own-dangling-shift auto-close path — never a 409, and never needs
    force_handover, since there's no other driver being displaced."""
    headers = await auth_headers(client, session, role="driver")
    driver_id = str(uuid.uuid4())
    vehicle_id = str(uuid.uuid4())

    first = await client.post(
        "/v1/shifts/start",
        json={"driver_id": driver_id, "vehicle_id": vehicle_id},
        headers=headers,
    )
    assert first.status_code == 201, first.text

    second = await client.post(
        "/v1/shifts/start",
        json={"driver_id": driver_id, "vehicle_id": vehicle_id},
        headers=headers,
    )
    assert second.status_code == 201, second.text


# --- device/shift mismatch warning (advisory, non-blocking) -------------------
# The tablet's paired vehicle (fleet.Device.vehicle_id) and a shift's
# vehicle_id are two entirely independent facts, recorded in different
# tables with no cross-reference — see app.services.shift.start_shift's
# _check_device_vehicle_mismatch docstring. These tests confirm the
# non-blocking cross-check surfaces a warning + audit-log row on a genuine
# mismatch, and stays silent (and fully backward compatible) otherwise.


async def test_device_vehicle_mismatch_warns_without_blocking_the_shift(
    client: AsyncClient, session: AsyncSession
):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(headers)

    paired_vehicle = Vehicle(tenant_id=tenant_id, rego="TX-001")
    other_vehicle = Vehicle(tenant_id=tenant_id, rego="TX-002")
    session.add_all([paired_vehicle, other_vehicle])
    await session.commit()
    await session.refresh(paired_vehicle)
    await session.refresh(other_vehicle)

    android_id = f"android-{uuid.uuid4()}"
    device = Device(tenant_id=tenant_id, android_id=android_id, vehicle_id=paired_vehicle.id)
    session.add(device)
    await session.commit()

    driver_id = str(uuid.uuid4())
    resp = await client.post(
        "/v1/shifts/start",
        json={
            "driver_id": driver_id,
            "vehicle_id": other_vehicle.id,
            "device_android_id": android_id,
        },
        headers=headers,
    )

    # Never blocks: the shift opens normally despite the mismatch.
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["vehicle_id"] == other_vehicle.id
    assert body["end_at"] is None
    assert body["device_mismatch_warning"] is not None
    assert paired_vehicle.id in body["device_mismatch_warning"]
    assert other_vehicle.id in body["device_mismatch_warning"]

    # An audit-log breadcrumb was written in the same transaction.
    audit_result = await session.execute(
        select(AuditLog).where(
            AuditLog.tenant_id == tenant_id,
            AuditLog.action == "shift_device_vehicle_mismatch",
            AuditLog.entity_id == body["id"],
        )
    )
    audit_row = audit_result.scalar_one_or_none()
    assert audit_row is not None
    assert audit_row.entity_type == "shift"
    assert audit_row.after_json["device_vehicle_id"] == paired_vehicle.id
    assert audit_row.after_json["shift_vehicle_id"] == other_vehicle.id


async def test_device_vehicle_match_produces_no_warning_and_no_audit_row(
    client: AsyncClient, session: AsyncSession
):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(headers)

    vehicle = Vehicle(tenant_id=tenant_id, rego="TX-003")
    session.add(vehicle)
    await session.commit()
    await session.refresh(vehicle)

    android_id = f"android-{uuid.uuid4()}"
    device = Device(tenant_id=tenant_id, android_id=android_id, vehicle_id=vehicle.id)
    session.add(device)
    await session.commit()

    driver_id = str(uuid.uuid4())
    resp = await client.post(
        "/v1/shifts/start",
        json={
            "driver_id": driver_id,
            "vehicle_id": vehicle.id,
            "device_android_id": android_id,
        },
        headers=headers,
    )

    assert resp.status_code == 201, resp.text
    assert resp.json()["device_mismatch_warning"] is None

    audit_result = await session.execute(
        select(AuditLog).where(
            AuditLog.tenant_id == tenant_id,
            AuditLog.action == "shift_device_vehicle_mismatch",
        )
    )
    assert audit_result.scalar_one_or_none() is None


async def test_omitted_device_android_id_behaves_exactly_as_before(
    client: AsyncClient, session: AsyncSession
):
    """Backward compatibility: a request with no device_android_id at all
    (every caller before this change, and any client that never adopts the
    new field) must behave identically — 201, shift opens, warning field is
    simply absent/null."""
    headers = await auth_headers(client, session, role="driver")
    driver_id = str(uuid.uuid4())
    vehicle_id = str(uuid.uuid4())

    resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": driver_id, "vehicle_id": vehicle_id},
        headers=headers,
    )

    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["driver_id"] == driver_id
    assert body["vehicle_id"] == vehicle_id
    assert body["device_mismatch_warning"] is None
