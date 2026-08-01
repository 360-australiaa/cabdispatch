"""Tests for the billing domain — full CRUD paths for subscriptions plus the
domain's specific business rules (plan -> price derivation, mock Stripe
fallback, mock invoice list, Connect onboarding, tenant isolation).

NOTE: `app.api.v1.billing.router` is not yet registered in `app.main` (a
later integration step does that). These tests will currently 404 if run in
isolation — that's expected; they're written correctly against the endpoints
as built, per the task instructions.
"""
from __future__ import annotations

import uuid
from decimal import Decimal

from tests.conftest import auth_headers


def _vehicle_id() -> str:
    return str(uuid.uuid4())


# --- create ----------------------------------------------------------------------


async def test_create_subscription_falls_back_to_mock_without_real_stripe_key(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await client.post(
        "/v1/billing/subscriptions",
        json={"vehicle_id": _vehicle_id(), "plan": "pro"},
        headers=headers,
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["plan"] == "pro"
    assert Decimal(body["price_aud"]) == Decimal("49.00")
    assert body["status"] == "active"
    assert body["stripe_subscription_id"].startswith("mock_sub_")


async def test_create_subscription_price_for_basic_plan(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await client.post(
        "/v1/billing/subscriptions",
        json={"vehicle_id": _vehicle_id(), "plan": "basic"},
        headers=headers,
    )
    assert resp.status_code == 201
    assert Decimal(resp.json()["price_aud"]) == Decimal("29.00")


async def test_create_subscription_price_for_enterprise_plan(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await client.post(
        "/v1/billing/subscriptions",
        json={"vehicle_id": _vehicle_id(), "plan": "enterprise"},
        headers=headers,
    )
    assert resp.status_code == 201
    assert Decimal(resp.json()["price_aud"]) == Decimal("79.00")


async def test_create_subscription_rejects_client_supplied_price(client, session):
    """price_aud is not part of SubscriptionCreate — a client-supplied value
    must be silently ignored (extra fields dropped by Pydantic), never
    trusted."""
    headers = await auth_headers(client, session, role="admin")

    resp = await client.post(
        "/v1/billing/subscriptions",
        json={"vehicle_id": _vehicle_id(), "plan": "basic", "price_aud": "1.00"},
        headers=headers,
    )
    assert resp.status_code == 201
    assert Decimal(resp.json()["price_aud"]) == Decimal("29.00")


async def test_create_subscription_rejects_invalid_plan(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await client.post(
        "/v1/billing/subscriptions",
        json={"vehicle_id": _vehicle_id(), "plan": "ultra"},
        headers=headers,
    )
    assert resp.status_code == 422


# --- list / get / tenant isolation ------------------------------------------------


async def test_list_and_get_subscription_round_trip(client, session):
    headers = await auth_headers(client, session, role="admin")

    create_resp = await client.post(
        "/v1/billing/subscriptions",
        json={"vehicle_id": _vehicle_id(), "plan": "pro"},
        headers=headers,
    )
    subscription_id = create_resp.json()["id"]

    list_resp = await client.get("/v1/billing/subscriptions", headers=headers)
    assert list_resp.status_code == 200
    list_body = list_resp.json()
    assert list_body["total"] >= 1
    assert any(item["id"] == subscription_id for item in list_body["items"])

    get_resp = await client.get(f"/v1/billing/subscriptions/{subscription_id}", headers=headers)
    assert get_resp.status_code == 200
    assert get_resp.json()["id"] == subscription_id


async def test_list_subscriptions_filters_by_plan_and_vehicle_id(client, session):
    headers = await auth_headers(client, session, role="admin")
    vehicle_id = _vehicle_id()

    await client.post(
        "/v1/billing/subscriptions", json={"vehicle_id": vehicle_id, "plan": "basic"}, headers=headers
    )
    await client.post(
        "/v1/billing/subscriptions",
        json={"vehicle_id": _vehicle_id(), "plan": "enterprise"},
        headers=headers,
    )

    resp = await client.get(
        f"/v1/billing/subscriptions?vehicle_id={vehicle_id}&plan=basic", headers=headers
    )
    assert resp.status_code == 200
    body = resp.json()
    assert all(item["plan"] == "basic" and item["vehicle_id"] == vehicle_id for item in body["items"])
    assert len(body["items"]) == 1


async def test_get_subscription_404_for_missing_id(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await client.get(f"/v1/billing/subscriptions/{uuid.uuid4()}", headers=headers)
    assert resp.status_code == 404


async def test_subscription_from_other_tenant_is_not_visible(client, session):
    tenant_a_headers = await auth_headers(client, session, role="admin", tenant_name="Tenant A")
    tenant_b_headers = await auth_headers(client, session, role="admin", tenant_name="Tenant B")

    create_resp = await client.post(
        "/v1/billing/subscriptions",
        json={"vehicle_id": _vehicle_id(), "plan": "basic"},
        headers=tenant_a_headers,
    )
    subscription_id = create_resp.json()["id"]

    resp = await client.get(f"/v1/billing/subscriptions/{subscription_id}", headers=tenant_b_headers)
    assert resp.status_code == 404

    list_resp = await client.get("/v1/billing/subscriptions", headers=tenant_b_headers)
    assert all(item["id"] != subscription_id for item in list_resp.json()["items"])


# --- update (plan change) ---------------------------------------------------------


async def test_update_subscription_changes_plan_and_price(client, session):
    headers = await auth_headers(client, session, role="admin")

    create_resp = await client.post(
        "/v1/billing/subscriptions",
        json={"vehicle_id": _vehicle_id(), "plan": "basic"},
        headers=headers,
    )
    subscription_id = create_resp.json()["id"]
    assert Decimal(create_resp.json()["price_aud"]) == Decimal("29.00")

    patch_resp = await client.patch(
        f"/v1/billing/subscriptions/{subscription_id}", json={"plan": "enterprise"}, headers=headers
    )
    assert patch_resp.status_code == 200
    body = patch_resp.json()
    assert body["plan"] == "enterprise"
    assert Decimal(body["price_aud"]) == Decimal("79.00")


async def test_update_subscription_status_directly(client, session):
    headers = await auth_headers(client, session, role="admin")

    create_resp = await client.post(
        "/v1/billing/subscriptions",
        json={"vehicle_id": _vehicle_id(), "plan": "basic"},
        headers=headers,
    )
    subscription_id = create_resp.json()["id"]

    patch_resp = await client.patch(
        f"/v1/billing/subscriptions/{subscription_id}", json={"status": "past_due"}, headers=headers
    )
    assert patch_resp.status_code == 200
    assert patch_resp.json()["status"] == "past_due"


async def test_update_subscription_404_for_other_tenant(client, session):
    tenant_a_headers = await auth_headers(client, session, role="admin", tenant_name="Tenant A")
    tenant_b_headers = await auth_headers(client, session, role="admin", tenant_name="Tenant B")

    create_resp = await client.post(
        "/v1/billing/subscriptions",
        json={"vehicle_id": _vehicle_id(), "plan": "basic"},
        headers=tenant_a_headers,
    )
    subscription_id = create_resp.json()["id"]

    resp = await client.patch(
        f"/v1/billing/subscriptions/{subscription_id}", json={"plan": "pro"}, headers=tenant_b_headers
    )
    assert resp.status_code == 404


# --- delete (cancel) ---------------------------------------------------------------


async def test_delete_subscription_cancels_rather_than_removes(client, session):
    headers = await auth_headers(client, session, role="admin")

    create_resp = await client.post(
        "/v1/billing/subscriptions",
        json={"vehicle_id": _vehicle_id(), "plan": "basic"},
        headers=headers,
    )
    subscription_id = create_resp.json()["id"]

    delete_resp = await client.delete(f"/v1/billing/subscriptions/{subscription_id}", headers=headers)
    assert delete_resp.status_code == 200
    assert delete_resp.json()["status"] == "canceled"

    # still fetchable — it's a cancel, not a hard delete
    get_resp = await client.get(f"/v1/billing/subscriptions/{subscription_id}", headers=headers)
    assert get_resp.status_code == 200
    assert get_resp.json()["status"] == "canceled"


async def test_delete_subscription_404_for_other_tenant(client, session):
    tenant_a_headers = await auth_headers(client, session, role="admin", tenant_name="Tenant A")
    tenant_b_headers = await auth_headers(client, session, role="admin", tenant_name="Tenant B")

    create_resp = await client.post(
        "/v1/billing/subscriptions",
        json={"vehicle_id": _vehicle_id(), "plan": "basic"},
        headers=tenant_a_headers,
    )
    subscription_id = create_resp.json()["id"]

    resp = await client.delete(f"/v1/billing/subscriptions/{subscription_id}", headers=tenant_b_headers)
    assert resp.status_code == 404


# --- invoices (mock list) -----------------------------------------------------------


async def test_list_invoices_returns_mock_invoice_without_real_stripe_key(client, session):
    headers = await auth_headers(client, session, role="admin")

    create_resp = await client.post(
        "/v1/billing/subscriptions",
        json={"vehicle_id": _vehicle_id(), "plan": "pro"},
        headers=headers,
    )
    subscription_id = create_resp.json()["id"]

    resp = await client.get("/v1/billing/invoices", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["mock"] is True
    assert any(item["subscription_id"] == subscription_id for item in body["items"])
    matching = next(item for item in body["items"] if item["subscription_id"] == subscription_id)
    assert Decimal(matching["amount_aud"]) == Decimal("49.00")
    assert matching["mock"] is True


async def test_list_invoices_filters_by_subscription_id(client, session):
    headers = await auth_headers(client, session, role="admin")

    sub_a = (
        await client.post(
            "/v1/billing/subscriptions", json={"vehicle_id": _vehicle_id(), "plan": "basic"}, headers=headers
        )
    ).json()["id"]
    await client.post(
        "/v1/billing/subscriptions", json={"vehicle_id": _vehicle_id(), "plan": "pro"}, headers=headers
    )

    resp = await client.get(f"/v1/billing/invoices?subscription_id={sub_a}", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert all(item["subscription_id"] == sub_a for item in body["items"])


async def test_invoices_from_other_tenant_subscriptions_are_not_visible(client, session):
    tenant_a_headers = await auth_headers(client, session, role="admin", tenant_name="Tenant A")
    tenant_b_headers = await auth_headers(client, session, role="admin", tenant_name="Tenant B")

    sub_a = (
        await client.post(
            "/v1/billing/subscriptions",
            json={"vehicle_id": _vehicle_id(), "plan": "basic"},
            headers=tenant_a_headers,
        )
    ).json()["id"]

    resp = await client.get("/v1/billing/invoices", headers=tenant_b_headers)
    assert resp.status_code == 200
    assert all(item["subscription_id"] != sub_a for item in resp.json()["items"])


# --- Stripe Connect onboarding -------------------------------------------------------


async def test_connect_onboard_falls_back_to_mock_link(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await client.post("/v1/billing/connect/onboard", headers=headers)
    assert resp.status_code == 201
    body = resp.json()
    assert body["mock"] is True
    assert body["url"].startswith("https://connect.mock.stripe.com/onboard/")
    assert body["stripe_account_id"].startswith("mock_acct_")
