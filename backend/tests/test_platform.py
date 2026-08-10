"""Tests for platform admin console."""
from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select

from app.core import security
from app.core.security import PLATFORM_TENANT_ID
from app.models.duress import DURESS_STATUS_OPEN, DURESS_STATUS_RESOLVED, DURESS_TRIGGER_BUTTON, DuressEvent
from app.models.fleet import Vehicle
from app.models.tenant import Tenant
from app.models.trips import TRIP_STATUS_CLOSED, TRIP_TYPE_RANK_HAIL, Trip
from app.models.user import ROLE_DRIVER, User
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio

_DURESS_LOG = {
    "cancel_window_seconds": 10,
    "cancel_deadline_at": "2026-01-01T00:00:00Z",
    "next_stage_index": 0,
    "entries": [],
}


async def _platform_owner_headers(client, session):
    result = await session.execute(select(Tenant).where(Tenant.id == PLATFORM_TENANT_ID))
    platform_tenant = result.scalar_one_or_none()
    if platform_tenant is None:
        platform_tenant = Tenant(id=PLATFORM_TENANT_ID, name="TCT", plan="platform")
        session.add(platform_tenant)
        await session.commit()

    return await auth_headers(client, session, role="owner", tenant_id=PLATFORM_TENANT_ID)


async def _make_tenant(session, *, name: str, plan: str = "standard") -> Tenant:
    tenant = Tenant(name=name, plan=plan)
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    return tenant


@pytest.mark.parametrize(
    "method,path,json_body",
    [
        ("GET", "/v1/platform/tenants", None),
        ("POST", "/v1/platform/tenants", {"name": "Nope Cabs"}),
        ("GET", "/v1/platform/tenants/{tenant_id}/summary", None),
        ("GET", "/v1/platform/health", None),
    ],
)
async def test_normal_tenant_owner_gets_403_on_every_platform_route(client, session, method, path, json_body):
    headers = await auth_headers(client, session, role="owner", tenant_name="Not Platform Tenant")
    token = headers["Authorization"].removeprefix("Bearer ")
    caller_tenant_id = security.decode_token(token)["tenant_id"]

    url = path.format(tenant_id=caller_tenant_id)
    if method == "GET":
        resp = await client.get(url, headers=headers)
    else:
        resp = await client.post(url, json=json_body, headers=headers)

    assert resp.status_code == 403


async def test_admin_role_on_platform_tenant_still_gets_403(client, session):
    result = await session.execute(select(Tenant).where(Tenant.id == PLATFORM_TENANT_ID))
    if result.scalar_one_or_none() is None:
        session.add(Tenant(id=PLATFORM_TENANT_ID, name="TCT", plan="platform"))
        await session.commit()

    headers = await auth_headers(client, session, role="admin", tenant_id=PLATFORM_TENANT_ID)
    resp = await client.get("/v1/platform/tenants", headers=headers)
    assert resp.status_code == 403


async def test_platform_owner_can_list_every_tenant(client, session):
    headers = await _platform_owner_headers(client, session)
    await _make_tenant(session, name="Listable Tenant One")
    await _make_tenant(session, name="Listable Tenant Two")

    resp = await client.get("/v1/platform/tenants", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    names = {row["name"] for row in body["items"]}
    assert "Listable Tenant One" in names
    assert "Listable Tenant Two" in names
    assert body["total"] >= 2


async def test_list_platform_tenants_is_paginated(client, session):
    headers = await _platform_owner_headers(client, session)
    for i in range(3):
        await _make_tenant(session, name=f"Page Tenant {uuid.uuid4()}-{i}")

    resp = await client.get("/v1/platform/tenants?skip=0&limit=1", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["items"]) == 1
    assert body["skip"] == 0
    assert body["limit"] == 1


async def test_platform_owner_can_onboard_a_new_tenant(client, session):
    headers = await _platform_owner_headers(client, session)

    resp = await client.post(
        "/v1/platform/tenants",
        json={"name": "Brand New Cabs", "abn": "12345678901", "plan": "standard"},
        headers=headers,
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["name"] == "Brand New Cabs"
    assert body["plan"] == "standard"

    list_resp = await client.get("/v1/platform/tenants", headers=headers)
    names = {row["name"] for row in list_resp.json()["items"]}
    assert "Brand New Cabs" in names


async def test_onboard_tenant_defaults_plan_to_standard(client, session):
    headers = await _platform_owner_headers(client, session)

    resp = await client.post("/v1/platform/tenants", json={"name": "Default Plan Cabs"}, headers=headers)
    assert resp.status_code == 201
    assert resp.json()["plan"] == "standard"


async def test_onboard_tenant_rejects_blank_name(client, session):
    headers = await _platform_owner_headers(client, session)

    resp = await client.post("/v1/platform/tenants", json={"name": "   "}, headers=headers)
    assert resp.status_code == 422


async def test_onboard_tenant_rejects_empty_string_name(client, session):
    headers = await _platform_owner_headers(client, session)

    resp = await client.post("/v1/platform/tenants", json={"name": ""}, headers=headers)
    assert resp.status_code == 422


async def test_platform_owner_gets_tenant_summary_rollup(client, session):
    headers = await _platform_owner_headers(client, session)
    target = await _make_tenant(session, name="Summary Target Tenant")

    session.add(Vehicle(tenant_id=target.id, rego="SUM001"))
    session.add(Vehicle(tenant_id=target.id, rego="SUM002"))
    session.add(
        User(
            tenant_id=target.id,
            role=ROLE_DRIVER,
            name="Summary Driver",
            email=f"{uuid.uuid4()}@example.com",
            status="active",
        )
    )
    session.add(
        Trip(
            tenant_id=target.id,
            client_uuid=str(uuid.uuid4()),
            type=TRIP_TYPE_RANK_HAIL,
            vehicle_id="veh-1",
            start_lat=-33.8688,
            start_lng=151.2093,
            driver_id="drv-1",
            shift_id="shift-1",
            tariff_id="tariff-1",
            status=TRIP_STATUS_CLOSED,
            start_at=datetime.now(UTC) - timedelta(days=1),
        )
    )
    session.add(
        DuressEvent(
            tenant_id=target.id,
            vehicle_id="veh-1",
            driver_id="drv-1",
            trigger=DURESS_TRIGGER_BUTTON,
            status=DURESS_STATUS_OPEN,
            opened_at=datetime.now(UTC),
            gps_stream_ref="duress/test/gps",
            escalation_log_json=_DURESS_LOG,
        )
    )
    session.add(
        DuressEvent(
            tenant_id=target.id,
            vehicle_id="veh-1",
            driver_id="drv-1",
            trigger=DURESS_TRIGGER_BUTTON,
            status=DURESS_STATUS_RESOLVED,
            opened_at=datetime.now(UTC),
            gps_stream_ref="duress/test/gps",
            escalation_log_json=_DURESS_LOG,
        )
    )
    await session.commit()

    resp = await client.get(f"/v1/platform/tenants/{target.id}/summary", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["tenant_id"] == target.id
    assert body["tenant_name"] == "Summary Target Tenant"
    assert body["vehicle_count"] == 2
    assert body["driver_count"] == 1
    assert body["trip_count_last_30_days"] == 1
    assert body["active_duress_count"] == 1


async def test_tenant_summary_excludes_trips_older_than_30_days(client, session):
    headers = await _platform_owner_headers(client, session)
    target = await _make_tenant(session, name="Old Trips Tenant")

    session.add(
        Trip(
            tenant_id=target.id,
            client_uuid=str(uuid.uuid4()),
            type=TRIP_TYPE_RANK_HAIL,
            vehicle_id="veh-1",
            start_lat=-33.8688,
            start_lng=151.2093,
            driver_id="drv-1",
            shift_id="shift-1",
            tariff_id="tariff-1",
            status=TRIP_STATUS_CLOSED,
            start_at=datetime.now(UTC) - timedelta(days=45),
        )
    )
    await session.commit()

    resp = await client.get(f"/v1/platform/tenants/{target.id}/summary", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["trip_count_last_30_days"] == 0


async def test_tenant_summary_unknown_tenant_404(client, session):
    headers = await _platform_owner_headers(client, session)

    resp = await client.get("/v1/platform/tenants/does-not-exist/summary", headers=headers)
    assert resp.status_code == 404


async def test_platform_health_aggregates_across_tenants(client, session):
    headers = await _platform_owner_headers(client, session)
    tenant_a = await _make_tenant(session, name="Health Tenant A")
    tenant_b = await _make_tenant(session, name="Health Tenant B")

    session.add(Vehicle(tenant_id=tenant_a.id, rego="HLT001"))
    session.add(Vehicle(tenant_id=tenant_b.id, rego="HLT002"))
    session.add(
        Trip(
            tenant_id=tenant_a.id,
            client_uuid=str(uuid.uuid4()),
            type=TRIP_TYPE_RANK_HAIL,
            vehicle_id="veh-1",
            start_lat=-33.8688,
            start_lng=151.2093,
            driver_id="drv-1",
            shift_id="shift-1",
            tariff_id="tariff-1",
            status=TRIP_STATUS_CLOSED,
            start_at=datetime.now(UTC),
        )
    )
    await session.commit()

    resp = await client.get("/v1/platform/health", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["total_tenants"] >= 2
    assert body["total_vehicles"] >= 2
    assert body["total_trips_today"] >= 1
