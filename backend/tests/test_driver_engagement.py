"""Tests for the driver-engagement domain -- wallet ledger, trip ratings,
announcements, incentives (app/models/driver_engagement.py,
app/services/driver_engagement.py, app/api/v1/{me,wallet,ratings,
announcements,incentives}.py).

Covers the hard rules from the task brief: balance = SUM of the ledger; a
driver cannot read another driver's wallet; rating average + 1-5 validation;
announcement window filtering; incentive progress counts only CLOSED trips
inside the window; tenant isolation for all four; owner/admin write gates.
"""
from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

from app.core import security
from app.models.tenant import Tenant
from app.models.trips import TRIP_STATUS_CLOSED, TRIP_STATUS_OPEN, Trip
from app.models.user import User


async def _tenant(session, name="Engagement Tenant") -> str:
    tenant = Tenant(name=name, plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    return tenant.id


async def _user(session, *, tenant_id: str, role: str) -> tuple[str, dict]:
    """Creates a user of `role` in `tenant_id`; returns (user_id, headers)."""
    user = User(
        tenant_id=tenant_id,
        role=role,
        name=f"Test {role}",
        email=f"{uuid.uuid4()}@example.com",
        pin_hash=security.hash_password("Test-Passw0rd!"),
        status="active",
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)
    token = security.create_access_token(user_id=user.id, tenant_id=tenant_id, role=role)
    return user.id, {"Authorization": f"Bearer {token}"}


async def _trip(
    session,
    *,
    tenant_id: str,
    driver_id: str,
    status: str = TRIP_STATUS_CLOSED,
    end_at: datetime | None = None,
) -> str:
    """Inserts a trip row directly (bypassing the fare engine) -- only the
    columns the engagement queries read matter here."""
    start = (end_at or datetime.now(UTC)) - timedelta(minutes=15)
    trip = Trip(
        tenant_id=tenant_id,
        client_uuid=str(uuid.uuid4()),
        vehicle_id=str(uuid.uuid4()),
        driver_id=driver_id,
        tariff_id=str(uuid.uuid4()),
        type="rank_hail",
        status=status,
        start_at=start,
        end_at=end_at if status == TRIP_STATUS_CLOSED else None,
        start_lat=-33.87,
        start_lng=151.21,
    )
    session.add(trip)
    await session.commit()
    await session.refresh(trip)
    return trip.id


def _iso(dt: datetime) -> str:
    return dt.isoformat()


# --- wallet -------------------------------------------------------------------


async def test_wallet_balance_is_sum_of_ledger(client, session):
    tenant_id = await _tenant(session)
    _admin_id, admin = await _user(session, tenant_id=tenant_id, role="admin")
    driver_id, driver = await _user(session, tenant_id=tenant_id, role="driver")

    # Empty ledger => 0.00, never null.
    empty = await client.get("/v1/me/wallet", headers=driver)
    assert empty.status_code == 200, empty.text
    assert Decimal(str(empty.json()["balance_aud"])) == Decimal("0.00")
    assert empty.json()["driver_id"] == driver_id
    assert empty.json()["recent"] == []

    for amount, kind in (("100.00", "top_up"), ("-30.25", "payout"), ("5.10", "adjustment")):
        resp = await client.post(
            "/v1/wallet/transactions",
            json={"driver_id": driver_id, "amount_aud": amount, "kind": kind, "note": kind},
            headers=admin,
        )
        assert resp.status_code == 201, resp.text
        assert resp.json()["created_by_user_id"] == _admin_id

    me = await client.get("/v1/me/wallet", headers=driver)
    assert me.status_code == 200
    assert Decimal(str(me.json()["balance_aud"])) == Decimal("74.85")
    assert len(me.json()["recent"]) == 3
    assert {row["kind"] for row in me.json()["recent"]} == {"top_up", "payout", "adjustment"}

    # Operator view derives the same number from the same ledger.
    op_view = await client.get(f"/v1/wallet/drivers/{driver_id}", headers=admin)
    assert op_view.status_code == 200, op_view.text
    assert Decimal(str(op_view.json()["balance_aud"])) == Decimal("74.85")

    listing = await client.get("/v1/wallet/transactions", params={"driver_id": driver_id}, headers=admin)
    assert listing.status_code == 200
    assert listing.json()["total"] == 3


async def test_wallet_transaction_sign_and_kind_validation(client, session):
    tenant_id = await _tenant(session)
    _admin_id, admin = await _user(session, tenant_id=tenant_id, role="admin")
    driver_id, _driver = await _user(session, tenant_id=tenant_id, role="driver")

    bad = [
        {"driver_id": driver_id, "amount_aud": "-5.00", "kind": "top_up"},
        {"driver_id": driver_id, "amount_aud": "5.00", "kind": "payout"},
        {"driver_id": driver_id, "amount_aud": "0", "kind": "adjustment"},
        {"driver_id": driver_id, "amount_aud": "5.00", "kind": "trip_earning"},  # system-only kind
        {"driver_id": driver_id, "amount_aud": "1.5", "kind": "bogus"},
    ]
    for payload in bad:
        resp = await client.post("/v1/wallet/transactions", json=payload, headers=admin)
        assert resp.status_code == 422, (payload, resp.text)


async def test_driver_cannot_read_another_drivers_wallet(client, session):
    tenant_id = await _tenant(session)
    _admin_id, admin = await _user(session, tenant_id=tenant_id, role="admin")
    rich_id, rich = await _user(session, tenant_id=tenant_id, role="driver")
    _poor_id, poor = await _user(session, tenant_id=tenant_id, role="driver")

    resp = await client.post(
        "/v1/wallet/transactions",
        json={"driver_id": rich_id, "amount_aud": "250.00", "kind": "top_up"},
        headers=admin,
    )
    assert resp.status_code == 201

    # /v1/me/wallet is bound to the token's user -- a driver_id query param is
    # simply not an input, so the other driver still sees only their own 0.00.
    mine = await client.get("/v1/me/wallet", params={"driver_id": rich_id}, headers=poor)
    assert mine.status_code == 200
    assert mine.json()["driver_id"] != rich_id
    assert Decimal(str(mine.json()["balance_aud"])) == Decimal("0.00")
    assert mine.json()["recent"] == []

    # And the operator surfaces are owner/admin only.
    assert (await client.get(f"/v1/wallet/drivers/{rich_id}", headers=poor)).status_code == 403
    assert (await client.get("/v1/wallet/transactions", headers=poor)).status_code == 403
    denied = await client.post(
        "/v1/wallet/transactions",
        json={"driver_id": rich_id, "amount_aud": "1.00", "kind": "top_up"},
        headers=poor,
    )
    assert denied.status_code == 403

    own = await client.get("/v1/me/wallet", headers=rich)
    assert Decimal(str(own.json()["balance_aud"])) == Decimal("250.00")


async def test_wallet_tenant_isolation(client, session):
    tenant_a = await _tenant(session, "Wallet Tenant A")
    tenant_b = await _tenant(session, "Wallet Tenant B")
    _a_admin_id, a_admin = await _user(session, tenant_id=tenant_a, role="admin")
    _b_admin_id, b_admin = await _user(session, tenant_id=tenant_b, role="admin")
    a_driver_id, _a_driver = await _user(session, tenant_id=tenant_a, role="driver")

    ok = await client.post(
        "/v1/wallet/transactions",
        json={"driver_id": a_driver_id, "amount_aud": "40.00", "kind": "top_up"},
        headers=a_admin,
    )
    assert ok.status_code == 201

    # Tenant B's admin cannot post to, or read, tenant A's driver.
    cross = await client.post(
        "/v1/wallet/transactions",
        json={"driver_id": a_driver_id, "amount_aud": "40.00", "kind": "top_up"},
        headers=b_admin,
    )
    assert cross.status_code == 404
    assert (await client.get(f"/v1/wallet/drivers/{a_driver_id}", headers=b_admin)).status_code == 404
    listing = await client.get("/v1/wallet/transactions", headers=b_admin)
    assert listing.status_code == 200
    assert listing.json()["total"] == 0


# --- ratings ------------------------------------------------------------------


async def test_rating_average_and_validation(client, session):
    tenant_id = await _tenant(session)
    _admin_id, admin = await _user(session, tenant_id=tenant_id, role="admin")
    driver_id, driver = await _user(session, tenant_id=tenant_id, role="driver")

    none_yet = await client.get("/v1/me/rating", headers=driver)
    assert none_yet.status_code == 200
    assert none_yet.json() == {
        "driver_id": driver_id,
        "average_stars": None,
        "rating_count": 0,
        "recent": [],
    }

    open_trip = await _trip(session, tenant_id=tenant_id, driver_id=driver_id, status=TRIP_STATUS_OPEN)
    not_closed = await client.post(f"/v1/trips/{open_trip}/rating", json={"stars": 5}, headers=driver)
    assert not_closed.status_code == 409, not_closed.text

    trips = [
        await _trip(session, tenant_id=tenant_id, driver_id=driver_id, end_at=datetime.now(UTC))
        for _ in range(3)
    ]
    for trip_id, stars in zip(trips, (5, 4, 4), strict=True):
        resp = await client.post(
            f"/v1/trips/{trip_id}/rating", json={"stars": stars, "comment": "great"}, headers=driver
        )
        assert resp.status_code == 201, resp.text
        assert resp.json()["driver_id"] == driver_id
        assert resp.json()["trip_id"] == trip_id

    # 1-5 validation
    extra = await _trip(session, tenant_id=tenant_id, driver_id=driver_id, end_at=datetime.now(UTC))
    for bad in (0, 6, -1):
        resp = await client.post(f"/v1/trips/{extra}/rating", json={"stars": bad}, headers=driver)
        assert resp.status_code == 422, (bad, resp.text)

    # one rating per trip
    dup = await client.post(f"/v1/trips/{trips[0]}/rating", json={"stars": 1}, headers=admin)
    assert dup.status_code == 409

    me = await client.get("/v1/me/rating", headers=driver)
    assert me.status_code == 200
    assert Decimal(str(me.json()["average_stars"])) == Decimal("4.33")
    assert me.json()["rating_count"] == 3
    assert len(me.json()["recent"]) == 3

    listing = await client.get("/v1/ratings", params={"driver_id": driver_id}, headers=admin)
    assert listing.status_code == 200
    assert listing.json()["total"] == 3
    assert (await client.get("/v1/ratings", headers=driver)).status_code == 403


async def test_rating_scoped_to_own_driver_and_tenant(client, session):
    tenant_a = await _tenant(session, "Rating Tenant A")
    tenant_b = await _tenant(session, "Rating Tenant B")
    driver_a_id, driver_a = await _user(session, tenant_id=tenant_a, role="driver")
    other_a_id, other_a = await _user(session, tenant_id=tenant_a, role="driver")
    _b_admin_id, b_admin = await _user(session, tenant_id=tenant_b, role="admin")

    trip = await _trip(session, tenant_id=tenant_a, driver_id=driver_a_id, end_at=datetime.now(UTC))

    # Another driver in the same tenant may not rate someone else's trip.
    assert (await client.post(f"/v1/trips/{trip}/rating", json={"stars": 1}, headers=other_a)).status_code == 403
    # Another tenant cannot even see the trip.
    assert (await client.post(f"/v1/trips/{trip}/rating", json={"stars": 1}, headers=b_admin)).status_code == 404

    ok = await client.post(f"/v1/trips/{trip}/rating", json={"stars": 5}, headers=driver_a)
    assert ok.status_code == 201

    # The rating belongs to driver A only.
    assert (await client.get("/v1/me/rating", headers=other_a)).json()["rating_count"] == 0
    assert (await client.get("/v1/me/rating", headers=driver_a)).json()["rating_count"] == 1
    assert other_a_id != driver_a_id
    # ...and tenant B's admin list never sees it.
    assert (await client.get("/v1/ratings", headers=b_admin)).json()["total"] == 0


# --- announcements ------------------------------------------------------------


async def test_announcement_window_filtering(client, session):
    tenant_id = await _tenant(session)
    _admin_id, admin = await _user(session, tenant_id=tenant_id, role="admin")
    _driver_id, driver = await _user(session, tenant_id=tenant_id, role="driver")

    now = datetime.now(UTC)
    cases = {
        "live-open-ended": {"starts_at": _iso(now - timedelta(hours=1)), "ends_at": None, "active": True},
        "live-windowed": {
            "starts_at": _iso(now - timedelta(hours=1)),
            "ends_at": _iso(now + timedelta(hours=1)),
            "active": True,
        },
        "future": {"starts_at": _iso(now + timedelta(hours=1)), "ends_at": None, "active": True},
        "expired": {
            "starts_at": _iso(now - timedelta(hours=3)),
            "ends_at": _iso(now - timedelta(hours=1)),
            "active": True,
        },
        "inactive": {"starts_at": _iso(now - timedelta(hours=1)), "ends_at": None, "active": False},
    }
    ids: dict[str, str] = {}
    for title, window in cases.items():
        resp = await client.post(
            "/v1/announcements",
            json={"title": title, "body": f"body of {title}", "kind": "info", **window},
            headers=admin,
        )
        assert resp.status_code == 201, (title, resp.text)
        ids[title] = resp.json()["id"]

    # Invalid window / kind rejected
    bad_window = await client.post(
        "/v1/announcements",
        json={
            "title": "bad",
            "body": "x",
            "starts_at": _iso(now),
            "ends_at": _iso(now - timedelta(minutes=1)),
        },
        headers=admin,
    )
    assert bad_window.status_code == 422
    bad_kind = await client.post(
        "/v1/announcements",
        json={"title": "bad", "body": "x", "kind": "party", "starts_at": _iso(now)},
        headers=admin,
    )
    assert bad_kind.status_code == 422

    live = await client.get("/v1/me/announcements", headers=driver)
    assert live.status_code == 200, live.text
    assert {item["title"] for item in live.json()["items"]} == {"live-open-ended", "live-windowed"}

    # Operator list shows everything
    full = await client.get("/v1/announcements", headers=admin)
    assert full.status_code == 200
    assert full.json()["total"] == 5

    # Deactivating a live one removes it from the driver list; drivers can't write.
    assert (
        await client.patch(f"/v1/announcements/{ids['live-windowed']}", json={"active": False}, headers=admin)
    ).status_code == 200
    live = await client.get("/v1/me/announcements", headers=driver)
    assert {item["title"] for item in live.json()["items"]} == {"live-open-ended"}

    assert (
        await client.post(
            "/v1/announcements",
            json={"title": "nope", "body": "x", "starts_at": _iso(now)},
            headers=driver,
        )
    ).status_code == 403
    assert (
        await client.delete(f"/v1/announcements/{ids['future']}", headers=driver)
    ).status_code == 403
    assert (await client.delete(f"/v1/announcements/{ids['future']}", headers=admin)).status_code == 204
    assert (await client.get(f"/v1/announcements/{ids['future']}", headers=admin)).status_code == 404


async def test_announcement_tenant_isolation(client, session):
    tenant_a = await _tenant(session, "Ann Tenant A")
    tenant_b = await _tenant(session, "Ann Tenant B")
    _a_admin_id, a_admin = await _user(session, tenant_id=tenant_a, role="admin")
    _b_admin_id, b_admin = await _user(session, tenant_id=tenant_b, role="admin")
    _b_driver_id, b_driver = await _user(session, tenant_id=tenant_b, role="driver")

    now = datetime.now(UTC)
    created = await client.post(
        "/v1/announcements",
        json={"title": "A only", "body": "x", "starts_at": _iso(now - timedelta(minutes=5))},
        headers=a_admin,
    )
    assert created.status_code == 201
    ann_id = created.json()["id"]

    assert (await client.get("/v1/me/announcements", headers=b_driver)).json()["items"] == []
    assert (await client.get("/v1/announcements", headers=b_admin)).json()["total"] == 0
    assert (await client.get(f"/v1/announcements/{ann_id}", headers=b_admin)).status_code == 404
    assert (
        await client.patch(f"/v1/announcements/{ann_id}", json={"active": False}, headers=b_admin)
    ).status_code == 404
    assert (await client.delete(f"/v1/announcements/{ann_id}", headers=b_admin)).status_code == 404


# --- incentives ---------------------------------------------------------------


async def test_incentive_progress_counts_only_completed_trips_in_window(client, session):
    tenant_id = await _tenant(session)
    _admin_id, admin = await _user(session, tenant_id=tenant_id, role="admin")
    driver_id, driver = await _user(session, tenant_id=tenant_id, role="driver")
    other_id, _other = await _user(session, tenant_id=tenant_id, role="driver")

    now = datetime.now(UTC)
    starts = now - timedelta(days=1)
    ends = now + timedelta(days=1)

    created = await client.post(
        "/v1/incentives",
        json={
            "title": "Weekend push",
            "description": "Complete 5 trips this weekend",
            "target_trips": 5,
            "reward_aud": "50.00",
            "starts_at": _iso(starts),
            "ends_at": _iso(ends),
        },
        headers=admin,
    )
    assert created.status_code == 201, created.text
    assert Decimal(str(created.json()["reward_aud"])) == Decimal("50.00")

    # Not-yet-started and inactive incentives never appear to the driver.
    future = await client.post(
        "/v1/incentives",
        json={
            "title": "Future",
            "target_trips": 1,
            "reward_aud": "1.00",
            "starts_at": _iso(now + timedelta(days=2)),
            "ends_at": _iso(now + timedelta(days=3)),
        },
        headers=admin,
    )
    assert future.status_code == 201
    inactive = await client.post(
        "/v1/incentives",
        json={
            "title": "Inactive",
            "target_trips": 1,
            "reward_aud": "1.00",
            "starts_at": _iso(starts),
            "ends_at": _iso(ends),
            "active": False,
        },
        headers=admin,
    )
    assert inactive.status_code == 201

    # 2 closed in-window (count), 1 closed before window, 1 closed after
    # window, 1 still open in-window, 1 closed in-window by ANOTHER driver.
    await _trip(session, tenant_id=tenant_id, driver_id=driver_id, end_at=now - timedelta(hours=2))
    await _trip(session, tenant_id=tenant_id, driver_id=driver_id, end_at=now - timedelta(hours=1))
    await _trip(session, tenant_id=tenant_id, driver_id=driver_id, end_at=starts - timedelta(hours=1))
    await _trip(session, tenant_id=tenant_id, driver_id=driver_id, end_at=ends + timedelta(hours=1))
    await _trip(session, tenant_id=tenant_id, driver_id=driver_id, status=TRIP_STATUS_OPEN)
    await _trip(session, tenant_id=tenant_id, driver_id=other_id, end_at=now - timedelta(hours=1))

    me = await client.get("/v1/me/incentives", headers=driver)
    assert me.status_code == 200, me.text
    items = me.json()["items"]
    assert [item["title"] for item in items] == ["Weekend push"]
    item = items[0]
    assert item["completed_trips"] == 2
    assert item["target_trips"] == 5
    assert item["remaining_trips"] == 3
    assert item["progress_pct"] == 40
    assert item["achieved"] is False
    assert Decimal(str(item["reward_aud"])) == Decimal("50.00")

    # Three more in-window closes => achieved, capped at 100%.
    for _ in range(3):
        await _trip(session, tenant_id=tenant_id, driver_id=driver_id, end_at=now - timedelta(minutes=30))
    await _trip(session, tenant_id=tenant_id, driver_id=driver_id, end_at=now - timedelta(minutes=20))
    item = (await client.get("/v1/me/incentives", headers=driver)).json()["items"][0]
    assert item["completed_trips"] == 6
    assert item["remaining_trips"] == 0
    assert item["progress_pct"] == 100
    assert item["achieved"] is True

    # Validation + role gate
    bad = await client.post(
        "/v1/incentives",
        json={
            "title": "bad",
            "target_trips": 0,
            "reward_aud": "1.00",
            "starts_at": _iso(starts),
            "ends_at": _iso(ends),
        },
        headers=admin,
    )
    assert bad.status_code == 422
    bad_window = await client.post(
        "/v1/incentives",
        json={
            "title": "bad",
            "target_trips": 1,
            "reward_aud": "1.00",
            "starts_at": _iso(ends),
            "ends_at": _iso(starts),
        },
        headers=admin,
    )
    assert bad_window.status_code == 422
    assert (
        await client.post(
            "/v1/incentives",
            json={
                "title": "nope",
                "target_trips": 1,
                "reward_aud": "1.00",
                "starts_at": _iso(starts),
                "ends_at": _iso(ends),
            },
            headers=driver,
        )
    ).status_code == 403

    # Update + delete
    inc_id = created.json()["id"]
    patched = await client.patch(f"/v1/incentives/{inc_id}", json={"target_trips": 10}, headers=admin)
    assert patched.status_code == 200, patched.text
    assert patched.json()["target_trips"] == 10
    bad_patch = await client.patch(
        f"/v1/incentives/{inc_id}", json={"ends_at": _iso(starts - timedelta(hours=1))}, headers=admin
    )
    assert bad_patch.status_code == 422
    assert (await client.delete(f"/v1/incentives/{inc_id}", headers=driver)).status_code == 403
    assert (await client.delete(f"/v1/incentives/{inc_id}", headers=admin)).status_code == 204
    assert (await client.get("/v1/me/incentives", headers=driver)).json()["items"] == []


async def test_incentive_tenant_isolation(client, session):
    tenant_a = await _tenant(session, "Inc Tenant A")
    tenant_b = await _tenant(session, "Inc Tenant B")
    _a_admin_id, a_admin = await _user(session, tenant_id=tenant_a, role="admin")
    _b_admin_id, b_admin = await _user(session, tenant_id=tenant_b, role="admin")
    _b_driver_id, b_driver = await _user(session, tenant_id=tenant_b, role="driver")

    now = datetime.now(UTC)
    created = await client.post(
        "/v1/incentives",
        json={
            "title": "A only",
            "target_trips": 3,
            "reward_aud": "20.00",
            "starts_at": _iso(now - timedelta(days=1)),
            "ends_at": _iso(now + timedelta(days=1)),
        },
        headers=a_admin,
    )
    assert created.status_code == 201
    inc_id = created.json()["id"]

    assert (await client.get("/v1/me/incentives", headers=b_driver)).json()["items"] == []
    assert (await client.get("/v1/incentives", headers=b_admin)).json()["total"] == 0
    assert (await client.get(f"/v1/incentives/{inc_id}", headers=b_admin)).status_code == 404
    assert (
        await client.patch(f"/v1/incentives/{inc_id}", json={"active": False}, headers=b_admin)
    ).status_code == 404
    assert (await client.delete(f"/v1/incentives/{inc_id}", headers=b_admin)).status_code == 404


async def test_me_endpoints_require_auth(client):
    for path in ("/v1/me/wallet", "/v1/me/rating", "/v1/me/announcements", "/v1/me/incentives"):
        resp = await client.get(path)
        assert resp.status_code in (401, 403), path
