"""Tests for the tenant admin-PIN feature: owner-only
`POST /v1/tenants/{id}/admin-pin` (app/api/v1/tenants.py) and the
tenant-scoped device check `POST /v1/fleet/devices/{id}/verify-admin-pin`
(app/api/v1/fleet.py) that replaces the Android app's hardcoded
ADMIN_PIN_PLACEHOLDER factory-reset check.
"""
from __future__ import annotations

import pytest

from app.core import security
from app.models.fleet import Device  # noqa: F401  (ensure table registered)
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


def _tenant_id_of(headers: dict) -> str:
    """Pulls tenant_id back out of the bearer token `auth_headers` minted —
    that fixture returns only headers, not the tenant row/id."""
    token = headers["Authorization"].removeprefix("Bearer ")
    return security.decode_token(token)["tenant_id"]


async def _create_device(client, headers, android_id: str):
    resp = await client.post("/v1/fleet/devices", json={"android_id": android_id}, headers=headers)
    assert resp.status_code == 201
    return resp.json()["id"]


# --- setting the PIN ----------------------------------------------------------


async def test_owner_can_set_admin_pin(client, session):
    headers = await auth_headers(client, session, role="owner", tenant_name="PIN Owner Tenant")
    tenant_id = _tenant_id_of(headers)

    resp = await client.post(f"/v1/tenants/{tenant_id}/admin-pin", json={"pin": "913572"}, headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body == {"tenant_id": tenant_id, "admin_pin_configured": True}


async def test_set_admin_pin_requires_owner_role(client, session):
    admin_headers = await auth_headers(client, session, role="admin", tenant_name="PIN Admin Tenant")
    tenant_id = _tenant_id_of(admin_headers)

    resp = await client.post(
        f"/v1/tenants/{tenant_id}/admin-pin", json={"pin": "1234"}, headers=admin_headers
    )
    assert resp.status_code == 403


async def test_set_admin_pin_rejects_mismatched_tenant_path(client, session):
    owner_a = await auth_headers(client, session, role="owner", tenant_name="PIN Tenant A")
    owner_b = await auth_headers(client, session, role="owner", tenant_name="PIN Tenant B")
    tenant_b_id = _tenant_id_of(owner_b)

    # owner_a's token is scoped to tenant A; trying to set tenant B's PIN
    # through the path must be refused, not silently applied to tenant A.
    resp = await client.post(
        f"/v1/tenants/{tenant_b_id}/admin-pin", json={"pin": "1234"}, headers=owner_a
    )
    assert resp.status_code == 403


@pytest.mark.parametrize("bad_pin", ["12", "abcd", "123456789", ""])
async def test_set_admin_pin_validates_pin_shape(client, session, bad_pin):
    headers = await auth_headers(client, session, role="owner", tenant_name="PIN Shape Tenant")
    tenant_id = _tenant_id_of(headers)

    resp = await client.post(
        f"/v1/tenants/{tenant_id}/admin-pin", json={"pin": bad_pin}, headers=headers
    )
    assert resp.status_code == 422


# --- device-side verification --------------------------------------------------


async def test_device_verifies_correct_and_incorrect_pin(client, session):
    headers = await auth_headers(client, session, role="owner", tenant_name="PIN Verify Tenant")
    tenant_id = _tenant_id_of(headers)

    resp = await client.post(f"/v1/tenants/{tenant_id}/admin-pin", json={"pin": "913572"}, headers=headers)
    assert resp.status_code == 200

    device_id = await _create_device(client, headers, "android-pin-1")

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/verify-admin-pin", json={"pin": "913572"}, headers=headers
    )
    assert resp.status_code == 200
    assert resp.json() == {"valid": True, "configured": True}

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/verify-admin-pin", json={"pin": "000000"}, headers=headers
    )
    assert resp.status_code == 200
    assert resp.json() == {"valid": False, "configured": True}


async def test_device_verify_pin_not_configured_is_unambiguous(client, session):
    """A tenant that never called POST /v1/tenants/{id}/admin-pin must get a
    clearly-flagged not-configured response — never a plain ambiguous
    `valid: true` (would let ANY pin unlock the device) or a plain
    `valid: false` indistinguishable from "PIN set but wrong"."""
    headers = await auth_headers(client, session, role="owner", tenant_name="PIN Unset Tenant")
    device_id = await _create_device(client, headers, "android-pin-unset-1")

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/verify-admin-pin", json={"pin": "913572"}, headers=headers
    )
    assert resp.status_code == 200
    assert resp.json() == {"valid": False, "configured": False}


async def test_device_verify_pin_is_tenant_scoped(client, session):
    """A device belonging to tenant A cannot be probed using tenant B's PIN
    check — the device lookup itself is tenant-scoped, so a caller from
    tenant B gets 404, never a PIN result for tenant A's device."""
    owner_a = await auth_headers(client, session, role="owner", tenant_name="PIN Scope Tenant A")
    owner_b = await auth_headers(client, session, role="owner", tenant_name="PIN Scope Tenant B")

    device_id = await _create_device(client, owner_a, "android-pin-scope-1")

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/verify-admin-pin", json={"pin": "913572"}, headers=owner_b
    )
    assert resp.status_code == 404


async def test_verify_admin_pin_unknown_device_404(client, session):
    headers = await auth_headers(client, session, role="owner", tenant_name="PIN Missing Device Tenant")

    resp = await client.post(
        "/v1/fleet/devices/does-not-exist/verify-admin-pin", json={"pin": "913572"}, headers=headers
    )
    assert resp.status_code == 404


async def test_setting_pin_again_overwrites_previous_pin(client, session):
    headers = await auth_headers(client, session, role="owner", tenant_name="PIN Overwrite Tenant")
    tenant_id = _tenant_id_of(headers)
    device_id = await _create_device(client, headers, "android-pin-overwrite-1")

    await client.post(f"/v1/tenants/{tenant_id}/admin-pin", json={"pin": "1111"}, headers=headers)
    await client.post(f"/v1/tenants/{tenant_id}/admin-pin", json={"pin": "2222"}, headers=headers)

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/verify-admin-pin", json={"pin": "1111"}, headers=headers
    )
    assert resp.json() == {"valid": False, "configured": True}

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/verify-admin-pin", json={"pin": "2222"}, headers=headers
    )
    assert resp.json() == {"valid": True, "configured": True}


# --- GET/PATCH /v1/tenants/me (white-label branding, blueprint 7.2.10/9.1/13.1) ----------------
# The dashboard's White-label Settings page has called these exact paths since it was first built
# (see dashboard/src/hooks/useWhite-labelSettings.ts's own doc), but the endpoints never landed
# until now — closing a real 404 the page's own React Query error state was surfacing.


async def test_get_my_tenant_returns_identity_and_null_theme_by_default(client, session):
    headers = await auth_headers(client, session, role="owner", tenant_name="White Label Tenant")
    tenant_id = _tenant_id_of(headers)

    resp = await client.get("/v1/tenants/me", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["id"] == tenant_id
    assert body["name"] == "White Label Tenant"
    assert body["theme_json"] is None


async def test_owner_can_update_theme(client, session):
    headers = await auth_headers(client, session, role="owner", tenant_name="Theme Owner Tenant")

    resp = await client.patch(
        "/v1/tenants/me",
        json={"theme_json": {"logo_url": "https://example.com/logo.png", "primary_color": "#D6336C", "accent_color": "#FFB3C6"}},
        headers=headers,
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["theme_json"] == {
        "logo_url": "https://example.com/logo.png",
        "primary_color": "#D6336C",
        "accent_color": "#FFB3C6",
    }

    # Persists across a fresh GET, not just echoed back.
    resp = await client.get("/v1/tenants/me", headers=headers)
    assert resp.json()["theme_json"]["primary_color"] == "#D6336C"


async def test_admin_can_update_theme(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Theme Admin Tenant")

    resp = await client.patch(
        "/v1/tenants/me",
        json={"theme_json": {"primary_color": "#123456", "accent_color": "#abcdef"}},
        headers=headers,
    )
    assert resp.status_code == 200


async def test_driver_cannot_update_theme(client, session):
    headers = await auth_headers(client, session, role="driver", tenant_name="Theme Driver Tenant")

    resp = await client.patch(
        "/v1/tenants/me",
        json={"theme_json": {"primary_color": "#123456", "accent_color": "#abcdef"}},
        headers=headers,
    )
    assert resp.status_code == 403


async def test_invalid_hex_color_is_422(client, session):
    headers = await auth_headers(client, session, role="owner", tenant_name="Theme Invalid Tenant")

    resp = await client.patch(
        "/v1/tenants/me",
        json={"theme_json": {"primary_color": "not-a-color", "accent_color": "#FFB3C6"}},
        headers=headers,
    )
    assert resp.status_code == 422


async def test_reset_theme_to_default_sets_null(client, session):
    headers = await auth_headers(client, session, role="owner", tenant_name="Theme Reset Tenant")

    await client.patch(
        "/v1/tenants/me",
        json={"theme_json": {"primary_color": "#D6336C", "accent_color": "#FFB3C6"}},
        headers=headers,
    )
    resp = await client.patch("/v1/tenants/me", json={"theme_json": None}, headers=headers)
    assert resp.status_code == 200
    assert resp.json()["theme_json"] is None


async def test_tenant_theme_is_isolated_per_tenant(client, session):
    tenant_a_headers = await auth_headers(client, session, role="owner", tenant_name="Theme Tenant A")
    tenant_b_headers = await auth_headers(client, session, role="owner", tenant_name="Theme Tenant B")

    await client.patch(
        "/v1/tenants/me",
        json={"theme_json": {"primary_color": "#111111", "accent_color": "#222222"}},
        headers=tenant_a_headers,
    )

    resp = await client.get("/v1/tenants/me", headers=tenant_b_headers)
    assert resp.json()["theme_json"] is None
