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
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.trips import Trip
from tests.conftest import auth_headers


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
    assert active_only.json()["total"] == 2  # none ended yet


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
