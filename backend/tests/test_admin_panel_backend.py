"""Backend slice of the admin-panel deep-improvement plan
(docs/plans/2026-09-14-admin-panel-deep-improvement-plan.md), one section per
work item:

  1. fare corrections write a `fare_correction` audit entry (and the chain
     stays valid);
  2. shift totals follow a fare correction and the shift is flipped back to
     unreconciled with a note;
  3. sessions idle-expire after 24 h and are capped at 10 per account;
  4. every trip close/sync accrues the levy onto the PSL ledger exactly once,
     and `POST /v1/psl/ledger/rebuild` backfills/repairs it;
  5. `GET /v1/wallet/balances` and `GET /v1/incentives/{id}/progress`;
  6. `DuressEventRead.stale` / `driver_name`;
  7. `AuditLogRead.actor_name`.

Every assertion is on an HTTP response from a real request through the app,
with the few direct DB writes below standing in for "time passed" (an
`opened_at`/`last_seen_at` in the past) or for "trips closed before the
accrual code existed".
"""
from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import security
from app.core.security import MAX_ACTIVE_SESSIONS_PER_USER
from app.models.duress import DuressEvent
from app.models.psl_ledger import PSLLedgerEntry, PSLTripAccrual
from app.models.trips import TRIP_STATUS_CLOSED, Trip
from app.models.user import User
from app.models.user_session import UserSession
from app.services.audit_log import record_audit
from app.services.psl_ledger import period_for
from tests.conftest import auth_headers
from tests.test_trips import _seed_tariff, _sync_item, _tenant_of, _user_id_of

_PASSWORD = "Test-Passw0rd!"


async def _me(client: AsyncClient, headers: dict) -> dict:
    resp = await client.get("/v1/auth/me", headers=headers)
    assert resp.status_code == 200, resp.text
    return resp.json()


def _one_point_trace() -> list[dict]:
    now = datetime.now(UTC)
    return [{"lat": -33.86, "lng": 151.2093, "speed_kmh": 40, "ts": (now + timedelta(seconds=60)).isoformat()}]


async def _sync_one(client: AsyncClient, headers: dict, tariff_id: str, **overrides) -> dict:
    item = _sync_item(tariff_id=tariff_id, gps_trace=_one_point_trace(), device_total="10.00", **overrides)
    resp = await client.post("/v1/trips/sync", json=[item], headers=headers)
    assert resp.status_code == 200, resp.text
    result = resp.json()["results"][0]
    assert result["status"] == "synced", result
    return result["trip"]


async def _insert_closed_trip(
    session: AsyncSession, *, tenant_id: str, driver_id: str, psl: str = "1.32", end_at: datetime | None = None
) -> Trip:
    """A closed, levied trip written straight to the table -- "closed before
    the accrual code existed", which is exactly what the rebuild is for."""
    end = end_at or datetime.now(UTC)
    trip = Trip(
        tenant_id=tenant_id,
        client_uuid=str(uuid.uuid4()),
        vehicle_id=str(uuid.uuid4()),
        driver_id=driver_id,
        tariff_id=str(uuid.uuid4()),
        type="rank_hail",
        status=TRIP_STATUS_CLOSED,
        start_at=end - timedelta(minutes=10),
        end_at=end,
        start_lat=-33.87,
        start_lng=151.21,
        psl=Decimal(psl),
        total=Decimal("20.00"),
    )
    session.add(trip)
    await session.commit()
    await session.refresh(trip)
    return trip


# --- 1 + 7. fare correction is audited, with the actor's name ----------------


async def test_fare_correction_writes_an_audit_entry_with_actor_name(client: AsyncClient, session: AsyncSession):
    driver_headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, driver_headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _sync_one(client, driver_headers, tariff.id)
    previous_total = trip["total"]

    owner_headers = await auth_headers(client, session, role="owner", tenant_id=tenant_id)
    owner = await _me(client, owner_headers)
    resp = await client.post(
        f"/v1/trips/{trip['id']}/fare-correction",
        json={"total": "59.03", "reason": "Meter charged 59.03"},
        headers=owner_headers,
    )
    assert resp.status_code == 200, resp.text

    log = await client.get(
        f"/v1/audit-log?entity_type=trip&entity_id={trip['id']}&action=fare_correction", headers=owner_headers
    )
    assert log.status_code == 200, log.text
    items = log.json()["items"]
    assert len(items) == 1
    entry = items[0]
    assert entry["actor_user_id"] == owner["id"]
    assert entry["actor_name"] == owner["name"]
    assert Decimal(entry["before_json"]["total"]) == Decimal(previous_total)
    assert Decimal(entry["after_json"]["total"]) == Decimal("59.03")
    assert entry["after_json"]["reason"] == "Meter charged 59.03"

    # The entry joined the tenant's hash chain properly.
    verify = await client.get("/v1/audit-log/verify", headers=owner_headers)
    assert verify.status_code == 200
    assert verify.json()["valid"] is True


async def test_audit_log_actor_name_is_null_for_system_actors(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(client, headers)
    await record_audit(
        session,
        tenant_id=tenant_id,
        actor_user_id=None,
        action="auto_escalate",
        entity_type="duress_event",
        entity_id=str(uuid.uuid4()),
    )
    await session.commit()

    log = await client.get("/v1/audit-log?action=auto_escalate", headers=headers)
    assert log.status_code == 200
    (entry,) = log.json()["items"]
    assert entry["actor_user_id"] is None
    assert entry["actor_name"] is None


# --- 2. shift totals follow fare corrections ---------------------------------


async def test_fare_correction_updates_shift_totals_and_unreconciles_it(client: AsyncClient, session: AsyncSession):
    driver_headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, driver_headers)
    driver_id = _user_id_of(driver_headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    started = await client.post(
        "/v1/shifts/start", json={"driver_id": driver_id, "vehicle_id": str(uuid.uuid4())}, headers=driver_headers
    )
    assert started.status_code == 201, started.text
    shift_id = started.json()["id"]

    trip = await _sync_one(
        client, driver_headers, tariff.id, driver_id=driver_id, shift_id=shift_id, payment_method="cash"
    )
    stored_total = Decimal(trip["total"])

    ended = await client.post(
        f"/v1/shifts/{shift_id}/end", json={"psl_owed": "1.32", "reconciled": True}, headers=driver_headers
    )
    assert ended.status_code == 200, ended.text
    assert Decimal(ended.json()["cash_total"]) == stored_total
    assert ended.json()["reconciled"] is True
    assert ended.json()["reconciliation_note"] is None

    owner_headers = await auth_headers(client, session, role="owner", tenant_id=tenant_id)
    corrected = await client.post(
        f"/v1/trips/{trip['id']}/fare-correction",
        json={"total": "59.03", "reason": "Meter charged 59.03"},
        headers=owner_headers,
    )
    assert corrected.status_code == 200, corrected.text
    assert stored_total != Decimal("59.03")

    # The shift row (list + detail) shows the corrected cash and is no longer reconciled.
    shift = (await client.get(f"/v1/shifts/{shift_id}", headers=owner_headers)).json()
    assert Decimal(shift["cash_total"]) == Decimal("59.03")
    assert shift["reconciled"] is False
    assert "fare corrected on" in shift["reconciliation_note"]
    assert "re-reconcile" in shift["reconciliation_note"]
    listed = (await client.get(f"/v1/shifts?driver_id={driver_id}", headers=owner_headers)).json()["items"]
    assert Decimal(listed[0]["cash_total"]) == Decimal("59.03")

    # So does the report, in all three renderings of it.
    report = (await client.get(f"/v1/shifts/{shift_id}/report", headers=owner_headers)).json()
    assert Decimal(report["cash_total"]) == Decimal("59.03")
    assert Decimal(report["total_takings"]) == Decimal("59.03")
    assert report["reconciled"] is False
    assert "re-reconcile" in report["reconciliation_note"]
    csv_body = (await client.get(f"/v1/shifts/{shift_id}/report.csv", headers=owner_headers)).text
    assert "59.03" in csv_body


# --- 3. sessions: idle expiry + cap ------------------------------------------


async def _login_user(session: AsyncSession) -> User:
    from app.models.tenant import Tenant

    tenant = Tenant(name=f"Sessions {uuid.uuid4().hex[:6]}", plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    user = User(
        tenant_id=tenant.id,
        role="admin",
        name="Session User",
        email=f"{uuid.uuid4()}@example.com",
        pin_hash=security.hash_password(_PASSWORD),
        status="active",
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)
    return user


async def _login(client: AsyncClient, user: User) -> dict:
    resp = await client.post("/v1/auth/login", json={"email": user.email, "password": _PASSWORD})
    assert resp.status_code == 200, resp.text
    return resp.json()


async def _age_session(session: AsyncSession, access_token: str, *, hours: float) -> None:
    jti = security.decode_token(access_token)["jti"]
    row = (await session.execute(select(UserSession).where(UserSession.current_access_jti == jti))).scalar_one()
    row.last_seen_at = datetime.now(UTC) - timedelta(hours=hours)
    session.add(row)
    await session.commit()


async def test_session_idle_for_a_day_is_expired_on_next_use(client: AsyncClient, session: AsyncSession):
    user = await _login_user(session)
    tokens = await _login(client, user)
    headers = {"Authorization": f"Bearer {tokens['access_token']}"}
    assert (await client.get("/v1/auth/me", headers=headers)).status_code == 200

    await _age_session(session, tokens["access_token"], hours=25)

    expired = await client.get("/v1/auth/me", headers=headers)
    assert expired.status_code == 401, expired.text
    # Its refresh token is dead too, and the session is off the list.
    reused = await client.post("/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert reused.status_code == 401, reused.text
    fresh = await _login(client, user)
    listed = await client.get("/v1/auth/sessions", headers={"Authorization": f"Bearer {fresh['access_token']}"})
    assert [s["is_current"] for s in listed.json()["sessions"]] == [True]


async def test_refresh_alone_cannot_keep_an_idle_session_alive(client: AsyncClient, session: AsyncSession):
    user = await _login_user(session)
    tokens = await _login(client, user)
    await _age_session(session, tokens["access_token"], hours=25)

    refreshed = await client.post("/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert refreshed.status_code == 401, refreshed.text


async def test_active_session_is_touched_not_expired(client: AsyncClient, session: AsyncSession):
    """Under the idle window a request keeps the session alive and moves
    `last_seen_at` forward, so "idle" is measured from real activity."""
    user = await _login_user(session)
    tokens = await _login(client, user)
    headers = {"Authorization": f"Bearer {tokens['access_token']}"}
    await _age_session(session, tokens["access_token"], hours=23)

    assert (await client.get("/v1/auth/me", headers=headers)).status_code == 200
    jti = security.decode_token(tokens["access_token"])["jti"]
    row = (await session.execute(select(UserSession).where(UserSession.current_access_jti == jti))).scalar_one()
    await session.refresh(row)
    last_seen = row.last_seen_at if row.last_seen_at.tzinfo else row.last_seen_at.replace(tzinfo=UTC)
    assert datetime.now(UTC) - last_seen < timedelta(minutes=1)


async def test_sessions_per_user_are_capped_by_revoking_the_oldest(client: AsyncClient, session: AsyncSession):
    user = await _login_user(session)
    logins = [await _login(client, user) for _ in range(MAX_ACTIVE_SESSIONS_PER_USER + 1)]

    newest = {"Authorization": f"Bearer {logins[-1]['access_token']}"}
    listed = await client.get("/v1/auth/sessions", headers=newest)
    assert listed.status_code == 200
    assert len(listed.json()["sessions"]) == MAX_ACTIVE_SESSIONS_PER_USER

    # The very first login -- the least recently seen -- is the one that went.
    oldest = {"Authorization": f"Bearer {logins[0]['access_token']}"}
    assert (await client.get("/v1/auth/me", headers=oldest)).status_code == 401
    second = {"Authorization": f"Bearer {logins[1]['access_token']}"}
    assert (await client.get("/v1/auth/me", headers=second)).status_code == 200


# --- 4. PSL ledger accrual + rebuild -----------------------------------------


async def test_sync_and_close_accrue_psl_onto_the_ledger_once_per_trip(client: AsyncClient, session: AsyncSession):
    driver_headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, driver_headers)
    driver_id = _user_id_of(driver_headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    period = period_for(datetime.now(UTC), "Australia/Sydney")

    # Two levied syncs + one un-levied one (include_psl=False accrues nothing).
    item_a = _sync_item(
        tariff_id=tariff.id, gps_trace=_one_point_trace(), device_total="10.00", driver_id=driver_id, include_psl=True
    )
    item_b = _sync_item(
        tariff_id=tariff.id, gps_trace=_one_point_trace(), device_total="10.00", driver_id=driver_id, include_psl=True
    )
    item_c = _sync_item(
        tariff_id=tariff.id, gps_trace=_one_point_trace(), device_total="10.00", driver_id=driver_id, include_psl=False
    )
    resp = await client.post("/v1/trips/sync", json=[item_a, item_b, item_c], headers=driver_headers)
    assert resp.status_code == 200, resp.text
    assert [r["status"] for r in resp.json()["results"]] == ["synced", "synced", "synced"]
    assert Decimal(resp.json()["results"][0]["trip"]["psl"]) == Decimal("1.32")

    ledger = (await client.get(f"/v1/psl/ledger?driver_id={driver_id}", headers=driver_headers)).json()
    assert ledger["total"] == 1
    (row,) = ledger["items"]
    assert row["period"] == period
    assert row["trips_count"] == 2
    assert Decimal(row["amount_owed"]) == Decimal("2.64")

    # Replaying the same batch (the meter's outbox does) is a no-op on the ledger.
    again = await client.post("/v1/trips/sync", json=[item_a, item_b], headers=driver_headers)
    assert [r["status"] for r in again.json()["results"]] == ["duplicate", "duplicate"]
    (row,) = (await client.get(f"/v1/psl/ledger?driver_id={driver_id}", headers=driver_headers)).json()["items"]
    assert row["trips_count"] == 2
    assert Decimal(row["amount_owed"]) == Decimal("2.64")

    # The online close path accrues too.
    created = await client.post(
        "/v1/trips",
        json={
            "client_uuid": str(uuid.uuid4()),
            "vehicle_id": str(uuid.uuid4()),
            "driver_id": driver_id,
            "tariff_id": tariff.id,
            "type": "rank_hail",
            "start_lat": -33.8688,
            "start_lng": 151.2093,
        },
        headers=driver_headers,
    )
    assert created.status_code == 201, created.text
    closed = await client.post(
        f"/v1/trips/{created.json()['id']}/close", json={"include_psl": True}, headers=driver_headers
    )
    assert closed.status_code == 200, closed.text
    (row,) = (await client.get(f"/v1/psl/ledger?driver_id={driver_id}", headers=driver_headers)).json()["items"]
    assert row["trips_count"] == 3
    assert Decimal(row["amount_owed"]) == Decimal("3.96")

    # And the remittance report for the period now shows the levy.
    report = (await client.get(f"/v1/psl/report?period={period}", headers=driver_headers)).json()
    assert report["total_trips"] == 3
    assert Decimal(report["total_owed"]) == Decimal("3.96")


async def test_sync_for_a_driver_id_that_is_not_a_user_does_not_break_the_close(
    client: AsyncClient, session: AsyncSession
):
    """`trips.driver_id` is unconstrained; `psl_ledger.driver_id` is a real
    FK. A bench/test sync under an unknown driver id must still store the
    trip -- the levy simply has no ledger row to land on."""
    driver_headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, driver_headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _sync_one(client, driver_headers, tariff.id, include_psl=True)  # random driver_id
    assert Decimal(trip["psl"]) == Decimal("1.32")
    accruals = (await session.execute(select(PSLTripAccrual).where(PSLTripAccrual.trip_id == trip["id"]))).all()
    assert accruals == []


async def test_psl_ledger_rebuild_backfills_and_repairs(client: AsyncClient, session: AsyncSession):
    admin_headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(client, admin_headers)
    driver_headers = await auth_headers(client, session, role="driver", tenant_id=tenant_id)
    driver_id = _user_id_of(driver_headers)
    other_headers = await auth_headers(client, session, role="driver", tenant_id=tenant_id)
    other_id = _user_id_of(other_headers)
    now = datetime.now(UTC)
    period = period_for(now, "Australia/Sydney")

    # Three "pre-accrual" closed trips: two this period for one driver, one
    # for another; plus an un-levied one and a trip under an unknown driver.
    await _insert_closed_trip(session, tenant_id=tenant_id, driver_id=driver_id)
    await _insert_closed_trip(session, tenant_id=tenant_id, driver_id=driver_id)
    await _insert_closed_trip(session, tenant_id=tenant_id, driver_id=other_id)
    await _insert_closed_trip(session, tenant_id=tenant_id, driver_id=driver_id, psl="0.00")
    await _insert_closed_trip(session, tenant_id=tenant_id, driver_id=str(uuid.uuid4()))

    assert (await client.post("/v1/psl/ledger/rebuild", headers=driver_headers)).status_code == 403

    first = await client.post("/v1/psl/ledger/rebuild", headers=admin_headers)
    assert first.status_code == 200, first.text
    assert first.json() == {"created": 2, "updated": 0}

    rows = (await client.get(f"/v1/psl/ledger?period={period}", headers=admin_headers)).json()["items"]
    by_driver = {row["driver_id"]: row for row in rows}
    assert by_driver[driver_id]["trips_count"] == 2
    assert Decimal(by_driver[driver_id]["amount_owed"]) == Decimal("2.64")
    assert by_driver[other_id]["trips_count"] == 1
    assert Decimal(by_driver[other_id]["amount_owed"]) == Decimal("1.32")

    # Idempotent.
    second = await client.post("/v1/psl/ledger/rebuild", headers=admin_headers)
    assert second.json() == {"created": 0, "updated": 0}

    # A hand-edited figure is repaired; a collected amount is left alone.
    patched = await client.patch(
        f"/v1/psl/ledger/{by_driver[driver_id]['id']}",
        json={"amount_owed": "99.00", "amount_collected": "1.00"},
        headers=admin_headers,
    )
    assert patched.status_code == 200, patched.text
    third = await client.post("/v1/psl/ledger/rebuild", headers=admin_headers)
    assert third.json() == {"created": 0, "updated": 1}
    repaired = (await client.get(f"/v1/psl/ledger/{by_driver[driver_id]['id']}", headers=admin_headers)).json()
    assert Decimal(repaired["amount_owed"]) == Decimal("2.64")
    assert Decimal(repaired["amount_collected"]) == Decimal("1.00")

    # A trip already accrued live is not double-counted by a rebuild.
    entry = (
        await session.execute(
            select(PSLLedgerEntry).where(PSLLedgerEntry.tenant_id == tenant_id, PSLLedgerEntry.driver_id == driver_id)
        )
    ).scalar_one()
    await session.refresh(entry)
    assert entry.trips_count == 2


async def test_psl_period_follows_the_tenant_timezone():
    """A trip closed at 23:30 Sydney time on 31 Aug is an August trip, even
    though it is already 1 Sep in UTC."""
    moment = datetime(2026, 8, 31, 13, 30, tzinfo=UTC)  # 23:30 AEST
    assert period_for(moment, "Australia/Sydney") == "2026-08"
    assert period_for(moment, "UTC") == "2026-08"
    assert period_for(datetime(2026, 8, 31, 14, 30, tzinfo=UTC), "Australia/Sydney") == "2026-09"
    assert period_for(datetime(2026, 8, 31, 14, 30), "Australia/Sydney") == "2026-09"  # naive == UTC


# --- 5. wallet balances + incentive progress ---------------------------------


async def test_wallet_balances_lists_every_driver_in_one_call(client: AsyncClient, session: AsyncSession):
    admin_headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(client, admin_headers)
    rich_headers = await auth_headers(client, session, role="driver", tenant_id=tenant_id)
    rich_id = _user_id_of(rich_headers)
    poor_headers = await auth_headers(client, session, role="driver", tenant_id=tenant_id)
    poor_id = _user_id_of(poor_headers)
    dispatcher_headers = await auth_headers(client, session, role="dispatcher", tenant_id=tenant_id)
    # Another tenant's driver with money must not appear.
    stranger_headers = await auth_headers(client, session, role="driver")
    stranger_admin = await auth_headers(client, session, role="admin", tenant_id=await _tenant_of(client, stranger_headers))

    for driver, amount, kind in ((rich_id, "50.00", "top_up"), (rich_id, "-12.50", "payout")):
        resp = await client.post(
            "/v1/wallet/transactions", json={"driver_id": driver, "amount_aud": amount, "kind": kind}, headers=admin_headers
        )
        assert resp.status_code == 201, resp.text
    resp = await client.post(
        "/v1/wallet/transactions",
        json={"driver_id": _user_id_of(stranger_headers), "amount_aud": "5.00", "kind": "top_up"},
        headers=stranger_admin,
    )
    assert resp.status_code == 201, resp.text

    assert (await client.get("/v1/wallet/balances", headers=rich_headers)).status_code == 403

    listed = await client.get("/v1/wallet/balances", headers=dispatcher_headers)
    assert listed.status_code == 200, listed.text
    items = {item["driver_id"]: item for item in listed.json()["items"]}
    assert set(items) == {rich_id, poor_id}
    assert Decimal(items[rich_id]["balance"]) == Decimal("37.50")
    assert Decimal(items[poor_id]["balance"]) == Decimal("0.00")
    assert items[rich_id]["driver_name"] == "Test Driver"
    assert "driver_code" in items[rich_id]


async def test_incentive_progress_is_computed_server_side_for_every_driver(client: AsyncClient, session: AsyncSession):
    admin_headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(client, admin_headers)
    a_headers = await auth_headers(client, session, role="driver", tenant_id=tenant_id)
    a_id = _user_id_of(a_headers)
    b_headers = await auth_headers(client, session, role="driver", tenant_id=tenant_id)
    b_id = _user_id_of(b_headers)
    now = datetime.now(UTC)
    starts, ends = now - timedelta(days=1), now + timedelta(days=1)

    created = await client.post(
        "/v1/incentives",
        json={
            "title": "Weekend push",
            "target_trips": 2,
            "reward_aud": "50.00",
            "starts_at": starts.isoformat(),
            "ends_at": ends.isoformat(),
        },
        headers=admin_headers,
    )
    assert created.status_code == 201, created.text
    incentive_id = created.json()["id"]

    # A: 2 in-window closed (earned), 1 before the window, 1 open. B: 1 in-window.
    await _insert_closed_trip(session, tenant_id=tenant_id, driver_id=a_id, end_at=now - timedelta(hours=2))
    await _insert_closed_trip(session, tenant_id=tenant_id, driver_id=a_id, end_at=now - timedelta(hours=1))
    await _insert_closed_trip(session, tenant_id=tenant_id, driver_id=a_id, end_at=starts - timedelta(hours=1))
    open_trip = Trip(
        tenant_id=tenant_id,
        client_uuid=str(uuid.uuid4()),
        vehicle_id=str(uuid.uuid4()),
        driver_id=a_id,
        tariff_id=str(uuid.uuid4()),
        type="rank_hail",
        status="open",
        start_at=now,
        start_lat=-33.87,
        start_lng=151.21,
    )
    session.add(open_trip)
    await session.commit()
    await _insert_closed_trip(session, tenant_id=tenant_id, driver_id=b_id, end_at=now - timedelta(hours=1))

    assert (await client.get(f"/v1/incentives/{incentive_id}/progress", headers=a_headers)).status_code == 403

    progress = await client.get(f"/v1/incentives/{incentive_id}/progress", headers=admin_headers)
    assert progress.status_code == 200, progress.text
    items = progress.json()["items"]
    assert [item["driver_id"] for item in items] == [a_id, b_id]  # most progress first
    assert items[0]["driver_name"] == "Test Driver"
    assert items[0]["completed_trips"] == 2
    assert items[0]["target_trips"] == 2
    assert items[0]["earned"] is True
    assert items[0]["progress_pct"] == 100
    assert Decimal(str(items[0]["reward_aud"])) == Decimal("50.00")
    assert items[1]["completed_trips"] == 1
    assert items[1]["earned"] is False
    assert items[1]["progress_pct"] == 50

    # Unknown / other-tenant incentive.
    other_admin = await auth_headers(client, session, role="admin")
    assert (await client.get(f"/v1/incentives/{incentive_id}/progress", headers=other_admin)).status_code == 404


# --- 6. duress: stale + driver_name ------------------------------------------


async def test_duress_event_carries_driver_name_and_stale_flag(client: AsyncClient, session: AsyncSession):
    driver_headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, driver_headers)
    driver_id = _user_id_of(driver_headers)
    desk_headers = await auth_headers(client, session, role="dispatcher", tenant_id=tenant_id)

    resp = await client.post(
        "/v1/duress/trigger",
        json={"vehicle_id": str(uuid.uuid4()), "driver_id": driver_id, "trigger": "button"},
        headers=driver_headers,
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["driver_name"] == "Test Driver"
    assert body["stale"] is False
    event_id = body["id"]

    # Age it past the stale threshold: still open, so it IS stale now.
    event = (await session.execute(select(DuressEvent).where(DuressEvent.id == event_id))).scalar_one()
    event.opened_at = datetime.now(UTC) - timedelta(hours=13)
    session.add(event)
    await session.commit()

    fetched = (await client.get(f"/v1/duress/{event_id}", headers=desk_headers)).json()
    assert fetched["stale"] is True
    assert fetched["driver_name"] == "Test Driver"
    assert fetched["status"] in ("open", "escalating", "dispatched")
    listed = (await client.get("/v1/duress?open_only=true", headers=desk_headers)).json()["items"]
    assert [e["stale"] for e in listed if e["id"] == event_id] == [True]

    # Closing it clears the flag: a resolved event is never stale.
    closed = await client.post(f"/v1/duress/{event_id}/close", json={"note": "all clear"}, headers=desk_headers)
    assert closed.status_code == 200, closed.text
    assert closed.json()["status"] == "resolved"
    assert closed.json()["stale"] is False

    # An unknown driver id yields a null name, not an error.
    unknown = await client.post(
        "/v1/duress/trigger",
        json={"vehicle_id": str(uuid.uuid4()), "driver_id": str(uuid.uuid4()), "trigger": "button"},
        headers=driver_headers,
    )
    assert unknown.status_code == 201
    assert unknown.json()["driver_name"] is None
