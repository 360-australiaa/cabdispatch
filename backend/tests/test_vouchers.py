"""Tests for the vouchers domain — CRUD over the Voucher ledger
(app.models.vouchers.Voucher, app/api/v1/vouchers.py). See
tests/test_payments.py for direct unit tests of
app.services.payments.redeem_voucher's business rules (reuse, expiry,
unknown code, tenant isolation) — these are the CRUD-router-level tests.
"""
from __future__ import annotations

from datetime import UTC, datetime, timedelta
from decimal import Decimal

from app.models.tenant import Tenant
from tests.conftest import auth_headers


async def _tenant_and_headers(client, session, *, role="admin", tenant_name="Voucher CRUD Tenant"):
    tenant = Tenant(name=tenant_name, plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    headers = await auth_headers(client, session, role=role, tenant_id=tenant.id)
    return tenant.id, headers


async def test_create_list_get_voucher(client, session):
    _tenant_id, headers = await _tenant_and_headers(client, session)

    create_resp = await client.post(
        "/v1/vouchers", json={"code": "WELCOME10", "value_aud": "10.00"}, headers=headers
    )
    assert create_resp.status_code == 201, create_resp.text
    body = create_resp.json()
    assert body["code"] == "WELCOME10"
    assert Decimal(str(body["value_aud"])) == Decimal("10.00")
    assert body["redeemed_at"] is None
    assert body["redeemed_by_trip_id"] is None

    voucher_id = body["id"]

    list_resp = await client.get("/v1/vouchers", headers=headers)
    assert list_resp.status_code == 200
    assert any(item["id"] == voucher_id for item in list_resp.json()["items"])

    get_resp = await client.get(f"/v1/vouchers/{voucher_id}", headers=headers)
    assert get_resp.status_code == 200
    assert get_resp.json()["code"] == "WELCOME10"


async def test_list_vouchers_filters_by_redeemed(client, session):
    _tenant_id, headers = await _tenant_and_headers(client, session)

    await client.post("/v1/vouchers", json={"code": "UNREDEEMED-1", "value_aud": "5.00"}, headers=headers)

    resp = await client.get("/v1/vouchers", params={"redeemed": "false"}, headers=headers)
    assert resp.status_code == 200
    assert any(item["code"] == "UNREDEEMED-1" for item in resp.json()["items"])

    resp = await client.get("/v1/vouchers", params={"redeemed": "true"}, headers=headers)
    assert resp.status_code == 200
    assert all(item["code"] != "UNREDEEMED-1" for item in resp.json()["items"])


async def test_update_and_delete_voucher(client, session):
    _tenant_id, headers = await _tenant_and_headers(client, session)

    create_resp = await client.post(
        "/v1/vouchers", json={"code": "SUMMER25", "value_aud": "25.00"}, headers=headers
    )
    voucher_id = create_resp.json()["id"]

    new_expiry = (datetime.now(UTC) + timedelta(days=30)).isoformat()
    patch_resp = await client.patch(
        f"/v1/vouchers/{voucher_id}",
        json={"value_aud": "30.00", "expires_at": new_expiry},
        headers=headers,
    )
    assert patch_resp.status_code == 200, patch_resp.text
    assert Decimal(str(patch_resp.json()["value_aud"])) == Decimal("30.00")
    assert patch_resp.json()["expires_at"] is not None

    delete_resp = await client.delete(f"/v1/vouchers/{voucher_id}", headers=headers)
    assert delete_resp.status_code == 204

    get_resp = await client.get(f"/v1/vouchers/{voucher_id}", headers=headers)
    assert get_resp.status_code == 404


async def test_create_voucher_rejects_duplicate_code_for_same_tenant(client, session):
    _tenant_id, headers = await _tenant_and_headers(client, session)

    first = await client.post("/v1/vouchers", json={"code": "DUPE-CODE", "value_aud": "5.00"}, headers=headers)
    assert first.status_code == 201

    second = await client.post("/v1/vouchers", json={"code": "DUPE-CODE", "value_aud": "5.00"}, headers=headers)
    assert second.status_code == 409


async def test_voucher_write_endpoints_are_admin_gated(client, session):
    _tenant_id, driver_headers = await _tenant_and_headers(client, session, role="driver")

    create_resp = await client.post(
        "/v1/vouchers", json={"code": "DRIVER-TRY", "value_aud": "5.00"}, headers=driver_headers
    )
    assert create_resp.status_code == 403


async def test_voucher_is_tenant_isolated(client, session):
    _tenant_a_id, headers_a = await _tenant_and_headers(client, session, tenant_name="Voucher CRUD Tenant A")
    _tenant_b_id, headers_b = await _tenant_and_headers(client, session, tenant_name="Voucher CRUD Tenant B")

    create_resp = await client.post(
        "/v1/vouchers", json={"code": "TENANT-A-ONLY", "value_aud": "5.00"}, headers=headers_a
    )
    voucher_id = create_resp.json()["id"]

    cross_tenant_resp = await client.get(f"/v1/vouchers/{voucher_id}", headers=headers_b)
    assert cross_tenant_resp.status_code == 404

    cross_tenant_list = await client.get("/v1/vouchers", headers=headers_b)
    assert all(item["id"] != voucher_id for item in cross_tenant_list.json()["items"])
