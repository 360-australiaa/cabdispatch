"""Tests for the corporate-accounts domain — CRUD over the CorporateAccount
ledger (app.models.vouchers.CorporateAccount, app/api/v1/corporate_accounts.py).
See tests/test_payments.py for direct unit tests of
app.services.payments.validate_account_reference's business rules (inactive,
unknown reference, tenant isolation) — these are the CRUD-router-level tests.
"""
from __future__ import annotations

from app.models.tenant import Tenant
from tests.conftest import auth_headers


async def _tenant_and_headers(client, session, *, role="admin", tenant_name="Corp Account CRUD Tenant"):
    tenant = Tenant(name=tenant_name, plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    headers = await auth_headers(client, session, role=role, tenant_id=tenant.id)
    return tenant.id, headers


async def test_create_list_get_corporate_account(client, session):
    _tenant_id, headers = await _tenant_and_headers(client, session)

    create_resp = await client.post(
        "/v1/corporate-accounts",
        json={"reference": "ACME-0001", "company_name": "Acme Pty Ltd"},
        headers=headers,
    )
    assert create_resp.status_code == 201, create_resp.text
    body = create_resp.json()
    assert body["reference"] == "ACME-0001"
    assert body["active"] is True

    account_id = body["id"]

    list_resp = await client.get("/v1/corporate-accounts", headers=headers)
    assert list_resp.status_code == 200
    assert any(item["id"] == account_id for item in list_resp.json()["items"])

    get_resp = await client.get(f"/v1/corporate-accounts/{account_id}", headers=headers)
    assert get_resp.status_code == 200
    assert get_resp.json()["company_name"] == "Acme Pty Ltd"


async def test_list_corporate_accounts_filters_by_active(client, session):
    _tenant_id, headers = await _tenant_and_headers(client, session)

    create_resp = await client.post(
        "/v1/corporate-accounts",
        json={"reference": "INACTIVE-1", "company_name": "Dormant Co", "active": False},
        headers=headers,
    )
    assert create_resp.status_code == 201

    resp = await client.get("/v1/corporate-accounts", params={"active": "true"}, headers=headers)
    assert resp.status_code == 200
    assert all(item["reference"] != "INACTIVE-1" for item in resp.json()["items"])

    resp = await client.get("/v1/corporate-accounts", params={"active": "false"}, headers=headers)
    assert resp.status_code == 200
    assert any(item["reference"] == "INACTIVE-1" for item in resp.json()["items"])


async def test_update_and_delete_corporate_account(client, session):
    _tenant_id, headers = await _tenant_and_headers(client, session)

    create_resp = await client.post(
        "/v1/corporate-accounts",
        json={"reference": "GLOBEX-0002", "company_name": "Globex"},
        headers=headers,
    )
    account_id = create_resp.json()["id"]

    patch_resp = await client.patch(
        f"/v1/corporate-accounts/{account_id}",
        json={"active": False, "company_name": "Globex Corporation"},
        headers=headers,
    )
    assert patch_resp.status_code == 200, patch_resp.text
    assert patch_resp.json()["active"] is False
    assert patch_resp.json()["company_name"] == "Globex Corporation"

    delete_resp = await client.delete(f"/v1/corporate-accounts/{account_id}", headers=headers)
    assert delete_resp.status_code == 204

    get_resp = await client.get(f"/v1/corporate-accounts/{account_id}", headers=headers)
    assert get_resp.status_code == 404


async def test_create_corporate_account_rejects_duplicate_reference_for_same_tenant(client, session):
    _tenant_id, headers = await _tenant_and_headers(client, session)

    first = await client.post(
        "/v1/corporate-accounts", json={"reference": "DUPE-REF", "company_name": "A"}, headers=headers
    )
    assert first.status_code == 201

    second = await client.post(
        "/v1/corporate-accounts", json={"reference": "DUPE-REF", "company_name": "B"}, headers=headers
    )
    assert second.status_code == 409


async def test_corporate_account_write_endpoints_are_admin_gated(client, session):
    _tenant_id, driver_headers = await _tenant_and_headers(client, session, role="driver")

    create_resp = await client.post(
        "/v1/corporate-accounts",
        json={"reference": "DRIVER-TRY", "company_name": "X"},
        headers=driver_headers,
    )
    assert create_resp.status_code == 403


async def test_corporate_account_is_tenant_isolated(client, session):
    _tenant_a_id, headers_a = await _tenant_and_headers(client, session, tenant_name="Corp CRUD Tenant A")
    _tenant_b_id, headers_b = await _tenant_and_headers(client, session, tenant_name="Corp CRUD Tenant B")

    create_resp = await client.post(
        "/v1/corporate-accounts",
        json={"reference": "TENANT-A-ONLY", "company_name": "A Co"},
        headers=headers_a,
    )
    account_id = create_resp.json()["id"]

    cross_tenant_resp = await client.get(f"/v1/corporate-accounts/{account_id}", headers=headers_b)
    assert cross_tenant_resp.status_code == 404

    cross_tenant_list = await client.get("/v1/corporate-accounts", headers=headers_b)
    assert all(item["id"] != account_id for item in cross_tenant_list.json()["items"])
