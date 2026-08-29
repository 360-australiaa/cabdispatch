"""Tests for the per-tenant settings feature (WP-01):
`GET`/`PATCH /v1/tenants/me/settings` (app/api/v1/tenants.py), backed by
app.services.tenant_settings and the app.models.tenant_settings.TenantSettings table.
"""
from __future__ import annotations

import pytest

from app.core import security
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


def _tenant_id_of(headers: dict) -> str:
    token = headers["Authorization"].removeprefix("Bearer ")
    return security.decode_token(token)["tenant_id"]


# --- GET: defaults for a tenant with no row yet (defensive fallback path) ------


async def test_get_settings_returns_documented_defaults_for_new_tenant(client, session):
    headers = await auth_headers(client, session, role="owner", tenant_name="Settings Defaults Tenant")
    tenant_id = _tenant_id_of(headers)

    resp = await client.get("/v1/tenants/me/settings", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["tenant_id"] == tenant_id
    assert body["fatigue_shift_duration_limit_hours"] == 12.0
    assert body["compliance_expiry_warning_days"] == 30
    assert body["duress_escalation_call_phone"] is None
    assert body["duress_call_from_number"] is None


async def test_get_settings_any_authenticated_role_can_read(client, session):
    headers = await auth_headers(client, session, role="driver", tenant_name="Settings Driver Read Tenant")

    resp = await client.get("/v1/tenants/me/settings", headers=headers)
    assert resp.status_code == 200



# --- PATCH: persistence, partial-update semantics ------------------------------


async def test_owner_can_patch_settings_and_it_persists(client, session):
    headers = await auth_headers(client, session, role="owner", tenant_name="Settings Patch Tenant")

    resp = await client.patch(
        "/v1/tenants/me/settings",
        json={"fatigue_shift_duration_limit_hours": 10.5, "compliance_expiry_warning_days": 45},
        headers=headers,
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["fatigue_shift_duration_limit_hours"] == 10.5
    assert body["compliance_expiry_warning_days"] == 45

    # Persists across a fresh GET, not just echoed back.
    resp = await client.get("/v1/tenants/me/settings", headers=headers)
    body = resp.json()
    assert body["fatigue_shift_duration_limit_hours"] == 10.5
    assert body["compliance_expiry_warning_days"] == 45


async def test_patch_only_changes_supplied_fields(client, session):
    headers = await auth_headers(client, session, role="owner", tenant_name="Settings Partial Tenant")

    await client.patch(
        "/v1/tenants/me/settings",
        json={"duress_escalation_call_phone": "+61255501234"},
        headers=headers,
    )
    resp = await client.patch(
        "/v1/tenants/me/settings",
        json={"compliance_expiry_warning_days": 60},
        headers=headers,
    )
    assert resp.status_code == 200
    body = resp.json()
    # untouched field from the first PATCH survives the second, unrelated PATCH
    assert body["duress_escalation_call_phone"] == "+61255501234"
    assert body["compliance_expiry_warning_days"] == 60
    # never touched -- still the default
    assert body["fatigue_shift_duration_limit_hours"] == 12.0


async def test_patch_explicit_null_clears_a_phone_field(client, session):
    headers = await auth_headers(client, session, role="owner", tenant_name="Settings Null Clear Tenant")

    await client.patch(
        "/v1/tenants/me/settings",
        json={"duress_call_from_number": "+61255509999"},
        headers=headers,
    )
    resp = await client.patch(
        "/v1/tenants/me/settings",
        json={"duress_call_from_number": None},
        headers=headers,
    )
    assert resp.status_code == 200
    assert resp.json()["duress_call_from_number"] is None



# --- role gating on PATCH -------------------------------------------------------


async def test_patch_requires_owner_or_admin_role(client, session):
    headers = await auth_headers(client, session, role="driver", tenant_name="Settings Driver Patch Tenant")

    resp = await client.patch(
        "/v1/tenants/me/settings",
        json={"compliance_expiry_warning_days": 10},
        headers=headers,
    )
    assert resp.status_code == 403


async def test_admin_can_patch_settings(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Settings Admin Patch Tenant")

    resp = await client.patch(
        "/v1/tenants/me/settings",
        json={"compliance_expiry_warning_days": 15},
        headers=headers,
    )
    assert resp.status_code == 200
    assert resp.json()["compliance_expiry_warning_days"] == 15


async def test_dispatcher_cannot_patch_settings(client, session):
    headers = await auth_headers(client, session, role="dispatcher", tenant_name="Settings Dispatcher Patch Tenant")

    resp = await client.patch(
        "/v1/tenants/me/settings",
        json={"compliance_expiry_warning_days": 10},
        headers=headers,
    )
    assert resp.status_code == 403



# --- validation ------------------------------------------------------------------


async def test_patch_rejects_out_of_range_fatigue_hours(client, session):
    headers = await auth_headers(client, session, role="owner", tenant_name="Settings Validation Tenant")

    resp = await client.patch(
        "/v1/tenants/me/settings",
        json={"fatigue_shift_duration_limit_hours": 0},
        headers=headers,
    )
    assert resp.status_code == 422

    resp = await client.patch(
        "/v1/tenants/me/settings",
        json={"fatigue_shift_duration_limit_hours": 999},
        headers=headers,
    )
    assert resp.status_code == 422


# --- tenant isolation --------------------------------------------------------------


async def test_settings_are_isolated_per_tenant(client, session):
    tenant_a_headers = await auth_headers(client, session, role="owner", tenant_name="Settings Isolation Tenant A")
    tenant_b_headers = await auth_headers(client, session, role="owner", tenant_name="Settings Isolation Tenant B")

    await client.patch(
        "/v1/tenants/me/settings",
        json={"fatigue_shift_duration_limit_hours": 8.0, "duress_escalation_call_phone": "+61255501111"},
        headers=tenant_a_headers,
    )

    resp = await client.get("/v1/tenants/me/settings", headers=tenant_b_headers)
    body = resp.json()
    assert body["fatigue_shift_duration_limit_hours"] == 12.0
    assert body["duress_escalation_call_phone"] is None


