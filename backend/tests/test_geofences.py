"""Tests for the geofences domain (`/v1/geofences`) — admin CRUD over the
circular toll/region zones, tenant-vs-global visibility, and the
haversine-based `app.services.geofence.detect_geofences` helper.

See `tests/test_trips.py::test_tick_through_toll_geofence_auto_adds_toll_once`
for the end-to-end toll-auto-detection-via-tick integration test.
"""
from __future__ import annotations

from decimal import Decimal

import pytest
from sqlalchemy import select

from app.core import security
from app.core.security import PLATFORM_TENANT_ID
from app.models.geofence import GEOFENCE_KIND_REGION, GEOFENCE_KIND_TOLL, Geofence
from app.models.tenant import Tenant
from app.services.geofence import detect_geofences, haversine_m, point_in_geofence
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


def _geofence_payload(**overrides) -> dict:
    payload = {
        "name": "Test Toll Zone",
        "kind": "toll",
        "center_lat": -33.8523,
        "center_lng": 151.2108,
        "radius_m": 300,
        "toll_amount": "4.82",
    }
    payload.update(overrides)
    return payload


# --- platform-owner write helpers -----------------------------------------------
#
# Toll pricing (kind="toll", carries toll_amount) is platform-admin-only since
# this pass (see app/api/v1/geofences.py's module docstring) -- same rule as
# tariffs. kind="region" rows are untouched: still tenant owner/admin-writable.


async def _platform_owner_headers(client, session):
    """Duplicated per-test-file rather than cross-imported, matching the
    existing tests/test_platform.py / tests/test_app_releases.py convention."""
    result = await session.execute(select(Tenant).where(Tenant.id == PLATFORM_TENANT_ID))
    if result.scalar_one_or_none() is None:
        session.add(Tenant(id=PLATFORM_TENANT_ID, name="TCT", plan="platform"))
        await session.commit()
    return await auth_headers(client, session, role="owner", tenant_id=PLATFORM_TENANT_ID)


def _tenant_id_of(headers: dict) -> str:
    token = headers["Authorization"].removeprefix("Bearer ")
    return security.decode_token(token)["tenant_id"]


async def _create_toll_geofence(client, session, tenant_headers, payload):
    tenant_id = _tenant_id_of(tenant_headers)
    owner_headers = await _platform_owner_headers(client, session)
    return await client.post(f"/v1/geofences?tenant_id={tenant_id}", json=payload, headers=owner_headers)


# --- pure haversine / point-in-circle -----------------------------------------


async def test_haversine_m_same_point_is_zero():
    assert haversine_m(-33.86, 151.21, -33.86, 151.21) == pytest.approx(0.0, abs=1e-6)


async def test_point_in_geofence_true_inside_false_outside():
    geofence = Geofence(
        id="g1", tenant_id=None, name="x", kind=GEOFENCE_KIND_TOLL,
        center_lat=-33.8523, center_lng=151.2108, radius_m=200, toll_amount=Decimal("4.00"),
    )
    assert point_in_geofence(-33.8523, 151.2108, geofence) is True  # dead centre
    assert point_in_geofence(-33.90, 151.30, geofence) is False  # ~9km away


# --- CRUD -----------------------------------------------------------------


async def test_create_and_get_geofence(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await _create_toll_geofence(client, session, headers, _geofence_payload())
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["kind"] == "toll"
    assert body["toll_amount"] == "4.82"
    geofence_id = body["id"]

    resp = await client.get(f"/v1/geofences/{geofence_id}", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["id"] == geofence_id


async def test_create_geofence_requires_admin_role(client, session):
    headers = await auth_headers(client, session, role="driver")
    resp = await client.post("/v1/geofences", json=_geofence_payload(), headers=headers)
    assert resp.status_code == 403


async def test_tenant_admin_gets_403_creating_toll_geofence(client, session):
    """Toll pricing is platform-admin-only (module docstring): a plain
    tenant owner/admin now gets 403 even though they pass the ordinary
    owner/admin role gate -- only the platform owner may write kind="toll"."""
    headers = await auth_headers(client, session, role="admin")
    resp = await client.post("/v1/geofences", json=_geofence_payload(), headers=headers)
    assert resp.status_code == 403


async def test_tenant_owner_gets_403_creating_toll_geofence(client, session):
    headers = await auth_headers(client, session, role="owner")
    resp = await client.post("/v1/geofences", json=_geofence_payload(), headers=headers)
    assert resp.status_code == 403


async def test_platform_owner_can_create_toll_geofence(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await _create_toll_geofence(client, session, headers, _geofence_payload())
    assert resp.status_code == 201, resp.text
    assert resp.json()["tenant_id"] == _tenant_id_of(headers)


async def test_tenant_admin_still_creates_region_geofence_directly(client, session):
    """Region-kind geofences are NOT pricing (module docstring) -- a plain
    tenant owner/admin keeps write access to those, unaffected by the toll
    platform-owner gate."""
    headers = await auth_headers(client, session, role="admin")
    payload = _geofence_payload(kind="region")
    del payload["toll_amount"]
    resp = await client.post("/v1/geofences", json=payload, headers=headers)
    assert resp.status_code == 201, resp.text


async def test_create_toll_geofence_without_toll_amount_is_422(client, session):
    headers = await auth_headers(client, session, role="admin")
    payload = _geofence_payload()
    del payload["toll_amount"]
    resp = await client.post("/v1/geofences", json=payload, headers=headers)
    assert resp.status_code == 422


async def test_create_region_geofence_without_toll_amount_is_allowed(client, session):
    headers = await auth_headers(client, session, role="admin")
    payload = _geofence_payload(kind="region")
    del payload["toll_amount"]
    resp = await client.post("/v1/geofences", json=payload, headers=headers)
    assert resp.status_code == 201
    assert resp.json()["toll_amount"] is None


async def test_update_geofence(client, session):
    headers = await auth_headers(client, session, role="admin")
    created = await _create_toll_geofence(client, session, headers, _geofence_payload())
    geofence_id = created.json()["id"]

    owner_headers = await _platform_owner_headers(client, session)
    tenant_id = _tenant_id_of(headers)
    resp = await client.patch(
        f"/v1/geofences/{geofence_id}?tenant_id={tenant_id}",
        json={"toll_amount": "5.50"},
        headers=owner_headers,
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["toll_amount"] == "5.50"


async def test_tenant_admin_gets_403_updating_toll_geofence(client, session):
    headers = await auth_headers(client, session, role="admin")
    created = await _create_toll_geofence(client, session, headers, _geofence_payload())
    geofence_id = created.json()["id"]

    resp = await client.patch(
        f"/v1/geofences/{geofence_id}", json={"toll_amount": "5.50"}, headers=headers
    )
    assert resp.status_code == 403


async def test_delete_geofence(client, session):
    headers = await auth_headers(client, session, role="admin")
    created = await _create_toll_geofence(client, session, headers, _geofence_payload())
    geofence_id = created.json()["id"]

    owner_headers = await _platform_owner_headers(client, session)
    tenant_id = _tenant_id_of(headers)
    resp = await client.delete(f"/v1/geofences/{geofence_id}?tenant_id={tenant_id}", headers=owner_headers)
    assert resp.status_code == 204, resp.text

    resp = await client.get(f"/v1/geofences/{geofence_id}", headers=headers)
    assert resp.status_code == 404


async def test_tenant_admin_gets_403_deleting_toll_geofence(client, session):
    headers = await auth_headers(client, session, role="admin")
    created = await _create_toll_geofence(client, session, headers, _geofence_payload())
    geofence_id = created.json()["id"]

    resp = await client.delete(f"/v1/geofences/{geofence_id}", headers=headers)
    assert resp.status_code == 403


async def test_tenant_admin_gets_403_converting_region_geofence_to_toll(client, session):
    """A tenant admin can't dodge the toll gate by starting from an
    admin-writable region row and converting it to toll in the same PATCH."""
    headers = await auth_headers(client, session, role="admin")
    payload = _geofence_payload(kind="region")
    del payload["toll_amount"]
    created = await client.post("/v1/geofences", json=payload, headers=headers)
    assert created.status_code == 201
    geofence_id = created.json()["id"]

    resp = await client.patch(
        f"/v1/geofences/{geofence_id}",
        json={"kind": "toll", "toll_amount": "4.00"},
        headers=headers,
    )
    assert resp.status_code == 403


async def test_tenant_reads_stay_open_after_toll_platform_owner_gate(client, session):
    """Read paths must keep working unchanged for an ordinary tenant user --
    in particular the meter's runtime GPS auto-toll-detection, which reads
    geofences as a driver, not an owner/admin."""
    headers = await auth_headers(client, session, role="admin")
    created = await _create_toll_geofence(client, session, headers, _geofence_payload())
    geofence_id = created.json()["id"]

    driver_headers = await auth_headers(
        client, session, role="driver", tenant_id=_tenant_id_of(headers)
    )
    assert (await client.get("/v1/geofences", headers=driver_headers)).status_code == 200
    assert (
        await client.get(f"/v1/geofences/{geofence_id}", headers=driver_headers)
    ).status_code == 200


# --- tenant isolation vs. global visibility ------------------------------------


async def test_geofence_is_tenant_isolated_for_writes(client, session):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Tenant A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Tenant B")

    created = await _create_toll_geofence(client, session, headers_a, _geofence_payload())
    geofence_id = created.json()["id"]

    # Tenant B cannot see, update, or delete Tenant A's own zone -- the 404
    # from tenant-ownership fires before the platform-owner-for-toll check
    # ever runs, so this holds regardless of headers_b's own role.
    assert (await client.get(f"/v1/geofences/{geofence_id}", headers=headers_b)).status_code == 404
    assert (
        await client.patch(f"/v1/geofences/{geofence_id}", json={"radius_m": 10}, headers=headers_b)
    ).status_code == 404
    assert (await client.delete(f"/v1/geofences/{geofence_id}", headers=headers_b)).status_code == 404


async def test_global_reference_geofence_is_visible_but_not_writable_via_tenant_api(
    client, session
):
    headers = await auth_headers(client, session, role="admin")

    global_geofence = Geofence(
        tenant_id=None,
        name="Global Reference Toll",
        kind=GEOFENCE_KIND_TOLL,
        center_lat=-33.85,
        center_lng=151.21,
        radius_m=100,
        toll_amount=Decimal("3.00"),
    )
    session.add(global_geofence)
    await session.commit()
    await session.refresh(global_geofence)

    # Visible via list/get...
    resp = await client.get(f"/v1/geofences/{global_geofence.id}", headers=headers)
    assert resp.status_code == 200

    listing = await client.get("/v1/geofences", headers=headers)
    assert any(item["id"] == global_geofence.id for item in listing.json()["items"])

    # ...but not editable/deletable through the tenant-scoped write endpoints.
    assert (
        await client.patch(
            f"/v1/geofences/{global_geofence.id}", json={"radius_m": 10}, headers=headers
        )
    ).status_code == 404
    assert (await client.delete(f"/v1/geofences/{global_geofence.id}", headers=headers)).status_code == 404


async def test_list_geofences_filters_by_kind(client, session):
    headers = await auth_headers(client, session, role="admin")
    await _create_toll_geofence(client, session, headers, _geofence_payload(kind="toll"))
    region_payload = _geofence_payload(kind="region", name="Region Zone")
    del region_payload["toll_amount"]
    await client.post("/v1/geofences", json=region_payload, headers=headers)

    resp = await client.get("/v1/geofences?kind=region", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] >= 1
    assert all(item["kind"] == "region" for item in body["items"])


# --- detect_geofences helper ---------------------------------------------------


async def test_detect_geofences_sees_tenant_and_global_rows(client, session):
    headers = await auth_headers(client, session, role="admin")
    from tests.test_trips import _tenant_of  # local import: avoids a module-level cycle

    tenant_id = await _tenant_of(client, headers)

    tenant_owned = Geofence(
        tenant_id=tenant_id, name="Tenant zone", kind=GEOFENCE_KIND_TOLL,
        center_lat=-33.80, center_lng=151.20, radius_m=200, toll_amount=Decimal("2.00"),
    )
    global_row = Geofence(
        tenant_id=None, name="Global zone", kind=GEOFENCE_KIND_TOLL,
        center_lat=-33.90, center_lng=151.30, radius_m=200, toll_amount=Decimal("3.00"),
    )
    session.add_all([tenant_owned, global_row])
    await session.commit()

    hits = await detect_geofences(session, tenant_id=tenant_id, lat=-33.80, lng=151.20)
    assert {g.id for g in hits} == {tenant_owned.id}

    hits2 = await detect_geofences(session, tenant_id=tenant_id, lat=-33.90, lng=151.30)
    assert {g.id for g in hits2} == {global_row.id}


async def test_detect_geofences_kind_filter(client, session):
    headers = await auth_headers(client, session, role="admin")
    from tests.test_trips import _tenant_of

    tenant_id = await _tenant_of(client, headers)

    same_spot = (-33.70, 151.10)
    toll = Geofence(
        tenant_id=tenant_id, name="Toll here", kind=GEOFENCE_KIND_TOLL,
        center_lat=same_spot[0], center_lng=same_spot[1], radius_m=100, toll_amount=Decimal("1.00"),
    )
    region = Geofence(
        tenant_id=tenant_id, name="Region here", kind=GEOFENCE_KIND_REGION,
        center_lat=same_spot[0], center_lng=same_spot[1], radius_m=100, toll_amount=None,
    )
    session.add_all([toll, region])
    await session.commit()

    toll_hits = await detect_geofences(
        session, tenant_id=tenant_id, lat=same_spot[0], lng=same_spot[1], kind=GEOFENCE_KIND_TOLL
    )
    assert {g.id for g in toll_hits} == {toll.id}

    region_hits = await detect_geofences(
        session, tenant_id=tenant_id, lat=same_spot[0], lng=same_spot[1], kind=GEOFENCE_KIND_REGION
    )
    assert {g.id for g in region_hits} == {region.id}
