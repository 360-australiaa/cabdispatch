"""Tests for the payments domain — full CRUD paths plus the domain's specific
business rules (5% surcharge cap, mock Stripe fallback, cash change, tenant
isolation, webhook status transitions).

NOTE: `app.api.v1.payments.router` / `.webhook_router` are not yet registered
in `app.main` (a later integration step does that). These tests will
currently 404 if run in isolation — that's expected; they're written correctly
against the endpoints as built, per the task instructions.
"""
from __future__ import annotations

import uuid
from decimal import Decimal

from tests.conftest import auth_headers


def _trip_id() -> str:
    return str(uuid.uuid4())


# --- creation flows ------------------------------------------------------------


async def test_tap_to_pay_intent_falls_back_to_mock_without_real_stripe_key(client, session):
    headers = await auth_headers(client, session, role="driver")

    resp = await client.post(
        "/v1/payments/tap-to-pay/intent",
        json={"trip_id": _trip_id(), "amount": "20.00", "surcharge": "1.00"},
        headers=headers,
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["mock"] is True
    assert body["payment"]["method"] == "tap_to_pay"
    assert body["payment"]["status"] == "pending"
    assert Decimal(body["payment"]["amount"]) == Decimal("20.00")
    assert Decimal(body["payment"]["surcharge"]) == Decimal("1.00")
    assert body["payment"]["stripe_pi_id"].startswith("mock_pi_")


async def test_tap_to_pay_intent_rejects_surcharge_over_5_percent_cap(client, session):
    headers = await auth_headers(client, session, role="driver")

    # 5% of $20.00 is $1.00 exactly — $1.01 must be rejected.
    resp = await client.post(
        "/v1/payments/tap-to-pay/intent",
        json={"trip_id": _trip_id(), "amount": "20.00", "surcharge": "1.01"},
        headers=headers,
    )
    assert resp.status_code == 422


async def test_tap_to_pay_intent_allows_surcharge_at_exactly_5_percent_cap(client, session):
    headers = await auth_headers(client, session, role="driver")

    resp = await client.post(
        "/v1/payments/tap-to-pay/intent",
        json={"trip_id": _trip_id(), "amount": "20.00", "surcharge": "1.00"},
        headers=headers,
    )
    assert resp.status_code == 201


async def test_payment_link_falls_back_to_mock(client, session):
    headers = await auth_headers(client, session, role="driver")

    resp = await client.post(
        "/v1/payments/link",
        json={"trip_id": _trip_id(), "amount": "35.50", "surcharge": "1.50"},
        headers=headers,
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["mock"] is True
    assert body["url"] is not None
    assert body["payment"]["method"] == "link"


async def test_payment_link_rejects_surcharge_over_cap(client, session):
    headers = await auth_headers(client, session, role="driver")

    resp = await client.post(
        "/v1/payments/link",
        json={"trip_id": _trip_id(), "amount": "10.00", "surcharge": "5.00"},
        headers=headers,
    )
    assert resp.status_code == 422


async def test_cash_payment_computes_change_given(client, session):
    headers = await auth_headers(client, session, role="driver")

    resp = await client.post(
        "/v1/payments/cash",
        json={"trip_id": _trip_id(), "amount": "18.40", "tendered": "20.00"},
        headers=headers,
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["method"] == "cash"
    assert body["status"] == "succeeded"
    assert Decimal(body["change_given"]) == Decimal("1.60")
    assert Decimal(body["surcharge"]) == Decimal("0.00")


async def test_cash_payment_rejects_tendered_less_than_amount(client, session):
    headers = await auth_headers(client, session, role="driver")

    resp = await client.post(
        "/v1/payments/cash",
        json={"trip_id": _trip_id(), "amount": "18.40", "tendered": "10.00"},
        headers=headers,
    )
    assert resp.status_code == 422


async def test_manual_cabcharge_payment_records_docket(client, session):
    headers = await auth_headers(client, session, role="driver")

    resp = await client.post(
        "/v1/payments/manual",
        json={
            "trip_id": _trip_id(),
            "method": "cabcharge",
            "amount": "42.00",
            "surcharge": "2.10",
            "docket_number": "CBC-00123",
            "notes": "corporate account",
        },
        headers=headers,
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["method"] == "cabcharge"
    assert body["docket_number"] == "CBC-00123"
    assert body["notes"] == "corporate account"


async def test_manual_ttss_payment_requires_docket_number(client, session):
    headers = await auth_headers(client, session, role="driver")

    resp = await client.post(
        "/v1/payments/manual",
        json={"trip_id": _trip_id(), "method": "ttss", "amount": "15.00", "docket_number": ""},
        headers=headers,
    )
    assert resp.status_code == 422


async def test_manual_payment_rejects_surcharge_over_cap(client, session):
    headers = await auth_headers(client, session, role="driver")

    resp = await client.post(
        "/v1/payments/manual",
        json={
            "trip_id": _trip_id(),
            "method": "cabcharge",
            "amount": "10.00",
            "surcharge": "1.00",
            "docket_number": "CBC-1",
        },
        headers=headers,
    )
    assert resp.status_code == 422


# --- CabCharge authorize ---------------------------------------------------------


async def test_cabcharge_authorize_falls_back_to_mock_without_real_credentials(client, session):
    headers = await auth_headers(client, session, role="driver")

    resp = await client.post(
        "/v1/payments/cabcharge/authorize",
        json={
            "trip_id": _trip_id(),
            "card_identifier": "4111-CABCHARGE-0001",
            "amount": "42.00",
            "surcharge": "2.10",
        },
        headers=headers,
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["mock"] is True
    assert body["authorization_id"].startswith("mock_cabcharge_")
    assert body["cabcharge_status"] == "authorized"
    assert body["payment"]["method"] == "cabcharge"
    assert body["payment"]["status"] == "pending"
    assert Decimal(body["payment"]["amount"]) == Decimal("42.00")
    assert Decimal(body["payment"]["surcharge"]) == Decimal("2.10")
    assert body["payment"]["docket_number"].startswith("CBC-")


async def test_cabcharge_authorize_rejects_surcharge_over_5_percent_cap(client, session):
    headers = await auth_headers(client, session, role="driver")

    resp = await client.post(
        "/v1/payments/cabcharge/authorize",
        json={
            "trip_id": _trip_id(),
            "card_identifier": "4111-CABCHARGE-0001",
            "amount": "20.00",
            "surcharge": "1.01",
        },
        headers=headers,
    )
    assert resp.status_code == 422


async def test_cabcharge_authorize_requires_card_identifier(client, session):
    headers = await auth_headers(client, session, role="driver")

    resp = await client.post(
        "/v1/payments/cabcharge/authorize",
        json={"trip_id": _trip_id(), "card_identifier": "", "amount": "20.00"},
        headers=headers,
    )
    assert resp.status_code == 422


# --- TTSS claim --------------------------------------------------------------------


async def test_ttss_claim_falls_back_to_mock_and_computes_50_percent_subsidy(client, session):
    """A $40.00 fare is well under the $60 cap: subsidy is exactly 50%."""
    headers = await auth_headers(client, session, role="driver")

    resp = await client.post(
        "/v1/payments/ttss/claim",
        json={"trip_id": _trip_id(), "concession_identifier": "TTSS-CARD-0042", "amount": "40.00"},
        headers=headers,
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["mock"] is True
    assert body["claim_id"].startswith("mock_ttss_")
    assert body["ttss_status"] == "submitted"
    assert Decimal(body["subsidy_amount"]) == Decimal("20.00")
    assert Decimal(body["passenger_paid_amount"]) == Decimal("20.00")

    payment = body["payment"]
    assert payment["method"] == "ttss"
    assert payment["status"] == "pending"
    assert Decimal(payment["amount"]) == Decimal("40.00")
    assert Decimal(payment["subsidy_amount"]) == Decimal("20.00")
    assert Decimal(payment["passenger_paid_amount"]) == Decimal("20.00")
    assert payment["docket_number"].startswith("TTSS-")


async def test_ttss_claim_caps_subsidy_at_60_dollars_for_a_200_dollar_fare(client, session):
    """Blueprint 11.1: 50% of $200.00 would be $100.00, but the subsidy is
    hard-capped at $60.00 — the passenger pays the remaining $140.00."""
    headers = await auth_headers(client, session, role="driver")

    resp = await client.post(
        "/v1/payments/ttss/claim",
        json={"trip_id": _trip_id(), "concession_identifier": "TTSS-CARD-0099", "amount": "200.00"},
        headers=headers,
    )
    assert resp.status_code == 201
    body = resp.json()
    assert Decimal(body["subsidy_amount"]) == Decimal("60.00")
    assert Decimal(body["subsidy_amount"]) != Decimal("100.00")
    assert Decimal(body["passenger_paid_amount"]) == Decimal("140.00")
    assert Decimal(body["payment"]["subsidy_amount"]) == Decimal("60.00")
    assert Decimal(body["payment"]["passenger_paid_amount"]) == Decimal("140.00")


async def test_ttss_claim_at_exactly_120_dollars_is_the_cap_boundary(client, session):
    """$120.00 fare * 50% = exactly $60.00 — right at the cap, not over it."""
    headers = await auth_headers(client, session, role="driver")

    resp = await client.post(
        "/v1/payments/ttss/claim",
        json={"trip_id": _trip_id(), "concession_identifier": "TTSS-CARD-0100", "amount": "120.00"},
        headers=headers,
    )
    assert resp.status_code == 201
    body = resp.json()
    assert Decimal(body["subsidy_amount"]) == Decimal("60.00")
    assert Decimal(body["passenger_paid_amount"]) == Decimal("60.00")


async def test_ttss_claim_requires_concession_identifier(client, session):
    headers = await auth_headers(client, session, role="driver")

    resp = await client.post(
        "/v1/payments/ttss/claim",
        json={"trip_id": _trip_id(), "concession_identifier": "", "amount": "20.00"},
        headers=headers,
    )
    assert resp.status_code == 422


# --- list / get / update / tenant isolation ------------------------------------


async def test_list_and_get_payment_round_trip(client, session):
    headers = await auth_headers(client, session, role="admin")
    trip_id = _trip_id()

    create_resp = await client.post(
        "/v1/payments/cash",
        json={"trip_id": trip_id, "amount": "10.00", "tendered": "10.00"},
        headers=headers,
    )
    payment_id = create_resp.json()["id"]

    list_resp = await client.get("/v1/payments", headers=headers)
    assert list_resp.status_code == 200
    list_body = list_resp.json()
    assert list_body["total"] >= 1
    assert any(item["id"] == payment_id for item in list_body["items"])

    get_resp = await client.get(f"/v1/payments/{payment_id}", headers=headers)
    assert get_resp.status_code == 200
    assert get_resp.json()["id"] == payment_id


async def test_list_payments_filters_by_method_and_trip_id(client, session):
    headers = await auth_headers(client, session, role="admin")
    trip_id = _trip_id()

    await client.post(
        "/v1/payments/cash", json={"trip_id": trip_id, "amount": "5.00", "tendered": "5.00"}, headers=headers
    )
    await client.post(
        "/v1/payments/manual",
        json={
            "trip_id": trip_id,
            "method": "cabcharge",
            "amount": "6.00",
            "docket_number": "X",
        },
        headers=headers,
    )

    resp = await client.get(f"/v1/payments?trip_id={trip_id}&method=cash", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert all(item["method"] == "cash" and item["trip_id"] == trip_id for item in body["items"])
    assert len(body["items"]) == 1


async def test_update_payment_status(client, session):
    headers = await auth_headers(client, session, role="admin")

    create_resp = await client.post(
        "/v1/payments/manual",
        json={
            "trip_id": _trip_id(),
            "method": "ttss",
            "amount": "12.00",
            "docket_number": "TTSS-1",
        },
        headers=headers,
    )
    payment_id = create_resp.json()["id"]

    patch_resp = await client.patch(
        f"/v1/payments/{payment_id}", json={"status": "succeeded"}, headers=headers
    )
    assert patch_resp.status_code == 200
    assert patch_resp.json()["status"] == "succeeded"


async def test_get_payment_404_for_missing_id(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await client.get(f"/v1/payments/{uuid.uuid4()}", headers=headers)
    assert resp.status_code == 404


async def test_payment_from_other_tenant_is_not_visible(client, session):
    tenant_a_headers = await auth_headers(client, session, role="admin", tenant_name="Tenant A")
    tenant_b_headers = await auth_headers(client, session, role="admin", tenant_name="Tenant B")

    create_resp = await client.post(
        "/v1/payments/cash",
        json={"trip_id": _trip_id(), "amount": "10.00", "tendered": "10.00"},
        headers=tenant_a_headers,
    )
    payment_id = create_resp.json()["id"]

    resp = await client.get(f"/v1/payments/{payment_id}", headers=tenant_b_headers)
    assert resp.status_code == 404

    list_resp = await client.get("/v1/payments", headers=tenant_b_headers)
    assert all(item["id"] != payment_id for item in list_resp.json()["items"])


async def test_payments_have_no_delete_endpoint(client, session):
    headers = await auth_headers(client, session, role="admin")

    create_resp = await client.post(
        "/v1/payments/cash",
        json={"trip_id": _trip_id(), "amount": "10.00", "tendered": "10.00"},
        headers=headers,
    )
    payment_id = create_resp.json()["id"]

    resp = await client.delete(f"/v1/payments/{payment_id}", headers=headers)
    assert resp.status_code == 405


# --- Stripe webhook -------------------------------------------------------------


async def test_stripe_webhook_updates_payment_status_unsigned_dev_mode(client, session):
    """No STRIPE_WEBHOOK_SECRET is configured in the test env (conftest doesn't
    set one), so the webhook must accept an unsigned payload."""
    headers = await auth_headers(client, session, role="driver")

    intent_resp = await client.post(
        "/v1/payments/tap-to-pay/intent",
        json={"trip_id": _trip_id(), "amount": "20.00", "surcharge": "0.00"},
        headers=headers,
    )
    stripe_pi_id = intent_resp.json()["payment"]["stripe_pi_id"]
    payment_id = intent_resp.json()["payment"]["id"]

    webhook_resp = await client.post(
        "/v1/stripe/webhook",
        json={
            "type": "payment_intent.succeeded",
            "data": {"object": {"id": stripe_pi_id}},
        },
    )
    assert webhook_resp.status_code == 200
    body = webhook_resp.json()
    assert body["received"] is True
    assert body["payment_id"] == payment_id
    assert body["status"] == "succeeded"

    get_resp = await client.get(f"/v1/payments/{payment_id}", headers=headers)
    assert get_resp.json()["status"] == "succeeded"
    assert get_resp.json()["captured_at"] is not None


async def test_stripe_webhook_unknown_pi_id_is_a_no_op(client, session):
    resp = await client.post(
        "/v1/stripe/webhook",
        json={
            "type": "payment_intent.succeeded",
            "data": {"object": {"id": "pi_does_not_exist"}},
        },
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["received"] is True
    assert body["payment_id"] is None


async def test_stripe_webhook_ignores_unmapped_event_types(client, session):
    resp = await client.post(
        "/v1/stripe/webhook",
        json={"type": "customer.created", "data": {"object": {"id": "cus_123"}}},
    )
    assert resp.status_code == 200
    assert resp.json()["payment_id"] is None


# --- voucher / account stub validation (blueprint 5.2.5, used by the trips ------
# domain's new "voucher"/"account" Trip.payment_method values — see
# app.services.trips.close_trip / tests/test_trips.py for the endpoint-level
# coverage of those; these are direct unit tests of the stub functions
# themselves)


def test_redeem_voucher_accepts_a_non_empty_code():
    from app.services.payments import redeem_voucher

    result = redeem_voucher(voucher_code="PROMO-2026-XYZ")
    assert result == {"voucher_code": "PROMO-2026-XYZ", "redeemed": True}


def test_redeem_voucher_rejects_empty_code():
    from app.services.payments import InvalidVoucherCodeError, redeem_voucher

    for bad_code in ("", "   "):
        try:
            redeem_voucher(voucher_code=bad_code)
            assert False, "expected InvalidVoucherCodeError"
        except InvalidVoucherCodeError:
            pass


def test_validate_account_reference_accepts_a_non_empty_reference():
    from app.services.payments import validate_account_reference

    validate_account_reference(account_reference="ACME-CORP-0042")  # must not raise


def test_validate_account_reference_rejects_empty_reference():
    from app.services.payments import InvalidAccountReferenceError, validate_account_reference

    for bad_ref in ("", "   "):
        try:
            validate_account_reference(account_reference=bad_ref)
            assert False, "expected InvalidAccountReferenceError"
        except InvalidAccountReferenceError:
            pass
