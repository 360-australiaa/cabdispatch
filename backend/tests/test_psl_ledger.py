"""Tests for the PSL ledger domain (app/api/v1/psl_ledger.py).

NOTE: this router is not yet registered in app.main — a later integration step
wires it (and the other 11 domains) into the app. Until then these tests will
404. They are written correctly against the endpoints as built; do not try to
make them pass standalone.
"""
from __future__ import annotations

from decimal import Decimal

from app.models import Tenant, User
from tests.conftest import auth_headers


async def _make_tenant(session, name: str = "PSL Test Tenant") -> Tenant:
    tenant = Tenant(name=name)
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    return tenant


async def _make_driver(session, tenant_id: str, *, email: str = "driver@example.com") -> User:
    driver = User(
        tenant_id=tenant_id,
        role="driver",
        name="Test Driver",
        email=email,
        status="active",
    )
    session.add(driver)
    await session.commit()
    await session.refresh(driver)
    return driver


# --- Ledger CRUD --------------------------------------------------------------


async def test_create_and_get_ledger_entry(client, session):
    tenant = await _make_tenant(session)
    driver = await _make_driver(session, tenant.id)
    headers = await auth_headers(client, session, role="admin", tenant_id=tenant.id)

    resp = await client.post(
        "/v1/psl/ledger",
        json={
            "driver_id": driver.id,
            "period": "2026-07",
            "trips_count": 42,
            "amount_owed": "55.44",
            "amount_collected": "10.00",
        },
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["driver_id"] == driver.id
    assert body["tenant_id"] == tenant.id
    assert body["period"] == "2026-07"
    assert Decimal(body["amount_owed"]) == Decimal("55.44")
    entry_id = body["id"]

    get_resp = await client.get(f"/v1/psl/ledger/{entry_id}", headers=headers)
    assert get_resp.status_code == 200
    assert get_resp.json()["id"] == entry_id


async def test_create_duplicate_ledger_entry_conflicts(client, session):
    tenant = await _make_tenant(session)
    driver = await _make_driver(session, tenant.id, email="dup@example.com")
    headers = await auth_headers(client, session, role="admin", tenant_id=tenant.id)

    payload = {"driver_id": driver.id, "period": "2026-07"}
    first = await client.post("/v1/psl/ledger", json=payload, headers=headers)
    assert first.status_code == 201

    second = await client.post("/v1/psl/ledger", json=payload, headers=headers)
    assert second.status_code == 409


async def test_create_ledger_entry_rejects_invalid_period(client, session):
    tenant = await _make_tenant(session)
    driver = await _make_driver(session, tenant.id, email="badperiod@example.com")
    headers = await auth_headers(client, session, role="admin", tenant_id=tenant.id)

    resp = await client.post(
        "/v1/psl/ledger",
        json={"driver_id": driver.id, "period": "July-2026"},
        headers=headers,
    )
    assert resp.status_code == 422


async def test_driver_role_cannot_create_ledger_entry(client, session):
    tenant = await _make_tenant(session)
    driver = await _make_driver(session, tenant.id, email="noperm@example.com")
    driver_headers = await auth_headers(client, session, role="driver", tenant_id=tenant.id)

    resp = await client.post(
        "/v1/psl/ledger",
        json={"driver_id": driver.id, "period": "2026-07"},
        headers=driver_headers,
    )
    assert resp.status_code == 403


async def test_list_ledger_entries_filters_by_driver_and_period(client, session):
    tenant = await _make_tenant(session)
    driver_a = await _make_driver(session, tenant.id, email="a@example.com")
    driver_b = await _make_driver(session, tenant.id, email="b@example.com")
    headers = await auth_headers(client, session, role="admin", tenant_id=tenant.id)

    for driver, period in [(driver_a, "2026-06"), (driver_a, "2026-07"), (driver_b, "2026-07")]:
        resp = await client.post(
            "/v1/psl/ledger", json={"driver_id": driver.id, "period": period}, headers=headers
        )
        assert resp.status_code == 201

    all_entries = await client.get("/v1/psl/ledger", headers=headers)
    assert len(all_entries.json()) == 3

    by_driver = await client.get(f"/v1/psl/ledger?driver_id={driver_a.id}", headers=headers)
    assert len(by_driver.json()) == 2

    by_period = await client.get("/v1/psl/ledger?period=2026-07", headers=headers)
    assert len(by_period.json()) == 2

    paginated = await client.get("/v1/psl/ledger?limit=1&skip=0", headers=headers)
    assert len(paginated.json()) == 1


async def test_update_and_delete_ledger_entry(client, session):
    tenant = await _make_tenant(session)
    driver = await _make_driver(session, tenant.id, email="update@example.com")
    headers = await auth_headers(client, session, role="admin", tenant_id=tenant.id)

    create = await client.post(
        "/v1/psl/ledger", json={"driver_id": driver.id, "period": "2026-07"}, headers=headers
    )
    entry_id = create.json()["id"]

    patch = await client.patch(
        f"/v1/psl/ledger/{entry_id}",
        json={"remitted_at": "2026-08-01T00:00:00Z", "amount_owed": "12.34"},
        headers=headers,
    )
    assert patch.status_code == 200
    assert patch.json()["remitted_at"] is not None
    assert Decimal(patch.json()["amount_owed"]) == Decimal("12.34")

    delete = await client.delete(f"/v1/psl/ledger/{entry_id}", headers=headers)
    assert delete.status_code == 204

    missing = await client.get(f"/v1/psl/ledger/{entry_id}", headers=headers)
    assert missing.status_code == 404


async def test_tenant_isolation_on_ledger(client, session):
    tenant_a = await _make_tenant(session, "Tenant A")
    tenant_b = await _make_tenant(session, "Tenant B")
    driver_a = await _make_driver(session, tenant_a.id, email="ta@example.com")

    headers_a = await auth_headers(client, session, role="admin", tenant_id=tenant_a.id)
    headers_b = await auth_headers(client, session, role="admin", tenant_id=tenant_b.id)

    create = await client.post(
        "/v1/psl/ledger", json={"driver_id": driver_a.id, "period": "2026-07"}, headers=headers_a
    )
    entry_id = create.json()["id"]

    # Tenant B cannot see tenant A's entry, in the list or by id.
    list_b = await client.get("/v1/psl/ledger", headers=headers_b)
    assert list_b.json() == []

    get_b = await client.get(f"/v1/psl/ledger/{entry_id}", headers=headers_b)
    assert get_b.status_code == 404


# --- Top-ups --------------------------------------------------------------


async def test_topup_uses_mock_stripe_fallback_and_credits_ledger(client, session):
    tenant = await _make_tenant(session)
    driver = await _make_driver(session, tenant.id, email="topup@example.com")
    headers = await auth_headers(client, session, role="admin", tenant_id=tenant.id)

    # STRIPE_SECRET_KEY is the "sk_test_placeholder" default in the test env, so
    # this must go through the mock fallback path.
    resp = await client.post(
        "/v1/psl/topup",
        json={"driver_id": driver.id, "period": "2026-07", "amount": "25.00", "payment_method": "card"},
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["status"] == "succeeded"
    assert body["stripe_charge_id"].startswith("mock_ch_")
    assert Decimal(body["amount"]) == Decimal("25.00")

    ledger_resp = await client.get(
        f"/v1/psl/ledger?driver_id={driver.id}&period=2026-07", headers=headers
    )
    entries = ledger_resp.json()
    assert len(entries) == 1
    assert Decimal(entries[0]["amount_collected"]) == Decimal("25.00")

    topups_resp = await client.get(f"/v1/psl/topups?driver_id={driver.id}", headers=headers)
    assert len(topups_resp.json()) == 1


async def test_topup_for_unknown_driver_404s(client, session):
    tenant = await _make_tenant(session)
    headers = await auth_headers(client, session, role="admin", tenant_id=tenant.id)

    resp = await client.post(
        "/v1/psl/topup",
        json={"driver_id": "does-not-exist", "period": "2026-07", "amount": "10.00"},
        headers=headers,
    )
    assert resp.status_code == 404


async def test_topup_for_driver_in_another_tenant_404s(client, session):
    tenant_a = await _make_tenant(session, "Tenant A2")
    tenant_b = await _make_tenant(session, "Tenant B2")
    driver_b = await _make_driver(session, tenant_b.id, email="wrongtenant@example.com")
    headers_a = await auth_headers(client, session, role="admin", tenant_id=tenant_a.id)

    resp = await client.post(
        "/v1/psl/topup",
        json={"driver_id": driver_b.id, "period": "2026-07", "amount": "10.00"},
        headers=headers_a,
    )
    assert resp.status_code == 404


# --- Report -----------------------------------------------------------------


async def test_psl_report_aggregates_tenant_drivers_for_period(client, session):
    tenant = await _make_tenant(session)
    driver_a = await _make_driver(session, tenant.id, email="report-a@example.com")
    driver_b = await _make_driver(session, tenant.id, email="report-b@example.com")
    headers = await auth_headers(client, session, role="admin", tenant_id=tenant.id)

    await client.post(
        "/v1/psl/ledger",
        json={"driver_id": driver_a.id, "period": "2026-07", "trips_count": 10, "amount_owed": "13.20"},
        headers=headers,
    )
    await client.post(
        "/v1/psl/ledger",
        json={"driver_id": driver_b.id, "period": "2026-07", "trips_count": 5, "amount_owed": "6.60"},
        headers=headers,
    )
    await client.post(
        "/v1/psl/topup",
        json={"driver_id": driver_a.id, "period": "2026-07", "amount": "13.20"},
        headers=headers,
    )

    resp = await client.get("/v1/psl/report?period=2026-07", headers=headers)
    assert resp.status_code == 200
    report = resp.json()
    assert report["period"] == "2026-07"
    assert report["driver_count"] == 2
    assert report["total_trips"] == 15
    assert Decimal(report["total_owed"]) == Decimal("19.80")
    assert Decimal(report["total_collected"]) == Decimal("13.20")
    assert Decimal(report["total_outstanding"]) == Decimal("6.60")

    driver_a_line = next(d for d in report["drivers"] if d["driver_id"] == driver_a.id)
    assert driver_a_line["remitted"] is False
    assert Decimal(driver_a_line["amount_outstanding"]) == Decimal("0.00")


async def test_psl_report_rejects_invalid_period_format(client, session):
    tenant = await _make_tenant(session)
    headers = await auth_headers(client, session, role="admin", tenant_id=tenant.id)

    resp = await client.get("/v1/psl/report?period=2026", headers=headers)
    assert resp.status_code == 422
