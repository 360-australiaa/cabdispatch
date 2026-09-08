"""Tests for workstream B6 — lazy background work.

This backend has no scheduler, task queue, cron, or lifespan worker, and this
workstream deliberately did not add one (see `app.services.lazy_maintenance`
and `app.services.duress.advance_escalation_if_due` for the full rationale).
What it fixed is the set of places where "lazy" had come to mean "never".

Every test here therefore proves the same shape of claim: something that
previously required an explicit human action, or a trip that happened to be
ticking, now happens as a side effect of an ordinary read or heartbeat.
"""
from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest
from httpx import AsyncClient
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.database import AsyncSessionLocal
from app.models.audit_log import AuditLog
from app.models.duress import ESCALATION_STAGES, DuressEvent
from app.models.fatigue_alert import (
    FATIGUE_ALERT_SHIFT_DURATION_EXCEEDED,
    FatigueAlert,
)
from app.models.fleet import Vehicle, VehiclePositionHistory
from app.models.shift import Shift
from app.models.user import ROLE_DRIVER, User
from app.services.audit_log import GENESIS_HASH, record_audit, verify_chain
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


# --- helpers ---------------------------------------------------------------


async def _tenant_of(headers: dict, session: AsyncSession) -> str:
    from app.core.security import decode_token

    payload = decode_token(headers["Authorization"].split()[1])
    return payload["tenant_id"]


def _claims(headers: dict) -> dict:
    from app.core.security import decode_token

    return decode_token(headers["Authorization"].split()[1])


async def _seed_vehicle(session: AsyncSession, *, tenant_id: str, **kwargs) -> Vehicle:
    vehicle = Vehicle(
        tenant_id=tenant_id,
        rego=f"REG{uuid.uuid4().hex[:6].upper()}",
        make="Toyota",
        model="Camry",
        status="active",
        **kwargs,
    )
    session.add(vehicle)
    await session.commit()
    await session.refresh(vehicle)
    return vehicle


# ===========================================================================
# 1 · Duress auto-escalation, driven by reads alone
# ===========================================================================


async def test_duress_cascade_advances_on_reads_alone_with_no_escalate_call(
    client: AsyncClient, session: AsyncSession, monkeypatch
):
    """The headline B6 claim: a panic event that nobody escalates still walks
    the whole cascade, and reaches the stage that dials the emergency contact,
    purely because somebody (the dashboard's 5-second poll) READ it.

    `POST /v1/duress/{id}/escalate` is never called anywhere in this test.
    """
    # Compress the between-stage interval so the test does not have to wait
    # three real minutes. The DEADLINE ARITHMETIC is what is under test, not
    # the wall-clock number.
    monkeypatch.setattr(settings, "DURESS_AUTO_ESCALATION_INTERVAL_SECONDS", 0)

    headers = await auth_headers(client, session, role="admin")
    resp = await client.post(
        "/v1/duress/trigger",
        json={"vehicle_id": str(uuid.uuid4()), "driver_id": str(uuid.uuid4()), "trigger": "button"},
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    event = resp.json()
    event_id = event["id"]
    assert event["status"] == "open"
    assert event["escalation_log_json"]["next_stage_index"] == 0

    # Read it immediately: still inside the 10s cancel window, so NOTHING has
    # fallen due yet and the read must not escalate anything.
    resp = await client.get(f"/v1/duress/{event_id}", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["status"] == "open"
    assert resp.json()["escalation_log_json"]["next_stage_index"] == 0

    # Age the event past its cancel deadline, in the DB, exactly as wall-clock
    # time would have.
    target = (
        await session.execute(select(DuressEvent).where(DuressEvent.id == event_id))
    ).scalar_one()
    log = dict(target.escalation_log_json)
    log["cancel_deadline_at"] = (datetime.now(UTC) - timedelta(seconds=1)).isoformat()
    target.escalation_log_json = log
    await session.commit()

    # ONE read of the open-events list — the exact call the dashboard makes
    # every 5 seconds — and no escalate call at all.
    resp = await client.get("/v1/duress?open_only=true", headers=headers)
    assert resp.status_code == 200, resp.text

    resp = await client.get(f"/v1/duress/{event_id}", headers=headers)
    assert resp.status_code == 200
    body = resp.json()

    # With the inter-stage interval at zero, the catch-up walk runs the whole
    # cascade in that single read: open -> escalating -> dispatched.
    assert body["status"] == "dispatched", body
    log = body["escalation_log_json"]
    assert log["next_stage_index"] == len(ESCALATION_STAGES)
    stages = [e["stage"] for e in log["entries"] if e["stage"] in ESCALATION_STAGES]
    assert stages == ESCALATION_STAGES, stages
    # Every stage is marked as automatic, so an auditor can tell this from a
    # dispatcher's own escalation.
    for entry in log["entries"]:
        if entry["stage"] in ESCALATION_STAGES:
            assert "auto-advanced" in (entry["note"] or "")
    # The final stage really did fire the escalation call path.
    assert "escalation_call_result" in log


async def test_duress_auto_escalation_advances_one_stage_at_a_time_as_time_passes(
    client: AsyncClient, session: AsyncSession, monkeypatch
):
    """With a real (non-zero) interval, a read only advances the stages that
    are genuinely overdue — it does not run the whole cascade the instant the
    cancel window closes."""
    monkeypatch.setattr(settings, "DURESS_AUTO_ESCALATION_INTERVAL_SECONDS", 3600)

    headers = await auth_headers(client, session, role="admin")
    resp = await client.post(
        "/v1/duress/trigger",
        json={"vehicle_id": str(uuid.uuid4()), "driver_id": str(uuid.uuid4()), "trigger": "button"},
        headers=headers,
    )
    event_id = resp.json()["id"]

    target = (
        await session.execute(select(DuressEvent).where(DuressEvent.id == event_id))
    ).scalar_one()
    log = dict(target.escalation_log_json)
    log["cancel_deadline_at"] = (datetime.now(UTC) - timedelta(seconds=1)).isoformat()
    target.escalation_log_json = log
    await session.commit()

    resp = await client.get("/v1/duress?open_only=true", headers=headers)
    assert resp.status_code == 200
    item = next(i for i in resp.json()["items"] if i["id"] == event_id)

    # Exactly ONE stage: the cancel window expired, but the next stage is not
    # due for another hour.
    assert item["status"] == "escalating"
    assert item["escalation_log_json"]["next_stage_index"] == 1
    assert "escalation_call_result" not in item["escalation_log_json"]


async def test_duress_reads_never_resume_a_cancelled_event(
    client: AsyncClient, session: AsyncSession, monkeypatch
):
    """A driver who cancels inside the window must stay cancelled forever, no
    matter how many times the event is read afterwards."""
    monkeypatch.setattr(settings, "DURESS_AUTO_ESCALATION_INTERVAL_SECONDS", 0)

    headers = await auth_headers(client, session, role="admin")
    resp = await client.post(
        "/v1/duress/trigger",
        json={"vehicle_id": str(uuid.uuid4()), "driver_id": str(uuid.uuid4()), "trigger": "button"},
        headers=headers,
    )
    event_id = resp.json()["id"]

    resp = await client.post(f"/v1/duress/{event_id}/cancel", json={"note": "false alarm"}, headers=headers)
    assert resp.status_code == 200, resp.text
    assert resp.json()["status"] == "cancelled"

    for _ in range(3):
        resp = await client.get(f"/v1/duress/{event_id}", headers=headers)
        assert resp.status_code == 200
        assert resp.json()["status"] == "cancelled"
        assert resp.json()["escalation_log_json"]["next_stage_index"] == 0


async def test_duress_auto_escalation_can_be_switched_off(
    client: AsyncClient, session: AsyncSession, monkeypatch
):
    """The master switch really does restore the pre-B6 "manual only"
    behaviour."""
    monkeypatch.setattr(settings, "DURESS_AUTO_ESCALATION_ENABLED", False)
    monkeypatch.setattr(settings, "DURESS_AUTO_ESCALATION_INTERVAL_SECONDS", 0)

    headers = await auth_headers(client, session, role="admin")
    resp = await client.post(
        "/v1/duress/trigger",
        json={"vehicle_id": str(uuid.uuid4()), "driver_id": str(uuid.uuid4()), "trigger": "button"},
        headers=headers,
    )
    event_id = resp.json()["id"]

    target = (
        await session.execute(select(DuressEvent).where(DuressEvent.id == event_id))
    ).scalar_one()
    log = dict(target.escalation_log_json)
    log["cancel_deadline_at"] = (datetime.now(UTC) - timedelta(seconds=1)).isoformat()
    target.escalation_log_json = log
    await session.commit()

    resp = await client.get("/v1/duress?open_only=true", headers=headers)
    assert resp.status_code == 200
    resp = await client.get(f"/v1/duress/{event_id}", headers=headers)
    assert resp.json()["status"] == "open"
    assert resp.json()["escalation_log_json"]["next_stage_index"] == 0


# ===========================================================================
# 2 · Fatigue + compliance without a ticking trip
# ===========================================================================


async def test_position_heartbeat_raises_fatigue_alert_for_driver_with_no_active_trip(
    client: AsyncClient, session: AsyncSession
):
    """The audit's headline fatigue gap: "a driver on a 14-hour shift with no
    active trip is never flagged".

    No trip is created anywhere in this test, and `PATCH /v1/trips/{id}/tick`
    is never called. The only thing that happens is the position heartbeat an
    on-shift tablet already sends every 5 seconds.
    """
    headers = await auth_headers(client, session, role="admin")
    tenant_id = _claims(headers)["tenant_id"]
    driver_id = _claims(headers)["sub"]
    vehicle = await _seed_vehicle(session, tenant_id=tenant_id)

    over_limit_start = datetime.now(UTC) - timedelta(
        hours=settings.FATIGUE_SHIFT_DURATION_LIMIT_HOURS + 2
    )
    resp = await client.post(
        "/v1/shifts/start",
        json={
            "driver_id": driver_id,
            "vehicle_id": vehicle.id,
            "start_at": over_limit_start.isoformat(),
        },
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    shift_id = resp.json()["id"]

    # Nothing has flagged this driver yet — pre-B6, nothing ever would.
    count = (
        await session.execute(
            select(func.count(FatigueAlert.id)).where(
                FatigueAlert.tenant_id == tenant_id,
                FatigueAlert.shift_id == shift_id,
                FatigueAlert.kind == FATIGUE_ALERT_SHIFT_DURATION_EXCEEDED,
            )
        )
    ).scalar_one()
    assert count == 0

    resp = await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": vehicle.id, "lat": -33.87, "lng": 151.21, "status": "unknown"},
        headers=headers,
    )
    assert resp.status_code == 201, resp.text

    count = (
        await session.execute(
            select(func.count(FatigueAlert.id)).where(
                FatigueAlert.tenant_id == tenant_id,
                FatigueAlert.shift_id == shift_id,
                FatigueAlert.kind == FATIGUE_ALERT_SHIFT_DURATION_EXCEEDED,
            )
        )
    ).scalar_one()
    assert count == 1, "the position heartbeat should have raised the shift-duration alert"

    # Idempotent across the heartbeat's own cadence: five more beats, still one
    # alert. (A 5-second heartbeat would otherwise produce 720 rows an hour.)
    for _ in range(5):
        resp = await client.post(
            "/v1/fleet/positions",
            json={"vehicle_id": vehicle.id, "lat": -33.87, "lng": 151.21, "status": "unknown"},
            headers=headers,
        )
        assert resp.status_code == 201

    count = (
        await session.execute(
            select(func.count(FatigueAlert.id)).where(
                FatigueAlert.tenant_id == tenant_id,
                FatigueAlert.shift_id == shift_id,
                FatigueAlert.kind == FATIGUE_ALERT_SHIFT_DURATION_EXCEEDED,
            )
        )
    ).scalar_one()
    assert count == 1


async def test_shift_read_raises_fatigue_alert_for_driver_with_no_active_trip(
    client: AsyncClient, session: AsyncSession
):
    """Same claim, via the other new hook: a dispatcher merely listing shifts
    gets state that is correct as of now."""
    headers = await auth_headers(client, session, role="admin")
    tenant_id = _claims(headers)["tenant_id"]
    driver_id = _claims(headers)["sub"]
    vehicle = await _seed_vehicle(session, tenant_id=tenant_id)

    over_limit_start = datetime.now(UTC) - timedelta(
        hours=settings.FATIGUE_SHIFT_DURATION_LIMIT_HOURS + 2
    )
    resp = await client.post(
        "/v1/shifts/start",
        json={
            "driver_id": driver_id,
            "vehicle_id": vehicle.id,
            "start_at": over_limit_start.isoformat(),
        },
        headers=headers,
    )
    shift_id = resp.json()["id"]

    resp = await client.get("/v1/shifts?active_only=true", headers=headers)
    assert resp.status_code == 200, resp.text

    count = (
        await session.execute(
            select(func.count(FatigueAlert.id)).where(
                FatigueAlert.tenant_id == tenant_id,
                FatigueAlert.shift_id == shift_id,
                FatigueAlert.kind == FATIGUE_ALERT_SHIFT_DURATION_EXCEEDED,
            )
        )
    ).scalar_one()
    assert count == 1


async def test_position_heartbeat_raises_compliance_alert_for_idle_vehicle(
    client: AsyncClient, session: AsyncSession
):
    """The compliance half of the same gap: an idle vehicle's expired
    registration is now alerted, with no trip in sight."""
    headers = await auth_headers(client, session, role="admin")
    tenant_id = _claims(headers)["tenant_id"]
    vehicle = await _seed_vehicle(
        session, tenant_id=tenant_id, registration_expiry=(datetime.now(UTC) - timedelta(days=5)).date()
    )

    resp = await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": vehicle.id, "lat": -33.87, "lng": 151.21, "status": "unknown"},
        headers=headers,
    )
    assert resp.status_code == 201, resp.text

    alerts = (
        (
            await session.execute(
                select(FatigueAlert).where(
                    FatigueAlert.tenant_id == tenant_id,
                    FatigueAlert.vehicle_id == vehicle.id,
                )
            )
        )
        .scalars()
        .all()
    )
    kinds = {a.kind for a in alerts}
    assert any("registration" in k for k in kinds), kinds


async def test_position_heartbeat_still_succeeds_when_the_lazy_checks_blow_up(
    client: AsyncClient, session: AsyncSession, monkeypatch
):
    """The hard constraint: a lazy check that errors must never turn a
    position publish into a 500. The heartbeat is how the fleet is tracked."""
    headers = await auth_headers(client, session, role="admin")
    tenant_id = _claims(headers)["tenant_id"]
    vehicle = await _seed_vehicle(session, tenant_id=tenant_id)

    from app.services import lazy_maintenance

    async def _boom(*args, **kwargs):
        raise RuntimeError("deliberate fatigue-check failure")

    monkeypatch.setattr(lazy_maintenance, "_open_shift_for_vehicle", _boom)

    resp = await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": vehicle.id, "lat": -33.87, "lng": 151.21, "status": "unknown"},
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    assert resp.json()["vehicle_id"] == vehicle.id


# ===========================================================================
# 3 · Global position-history retention
# ===========================================================================


async def test_position_publish_prunes_history_for_every_vehicle_not_just_its_own(
    client: AsyncClient, session: AsyncSession
):
    """The privacy fix: a vehicle that has STOPPED reporting used to keep its
    GPS history forever, because the only thing that would have deleted it was
    its own next publish. Any vehicle on the tenant now sweeps the tenant."""
    headers = await auth_headers(client, session, role="admin")
    tenant_id = _claims(headers)["tenant_id"]
    silent = await _seed_vehicle(session, tenant_id=tenant_id)
    reporting = await _seed_vehicle(session, tenant_id=tenant_id)

    retention = settings.POSITION_HISTORY_RETENTION_HOURS
    stale_at = datetime.now(UTC) - timedelta(hours=retention + 1)
    fresh_at = datetime.now(UTC) - timedelta(hours=1)
    for vehicle_id, recorded_at in (
        (silent.id, stale_at),
        (silent.id, fresh_at),
        (reporting.id, stale_at),
    ):
        session.add(
            VehiclePositionHistory(
                tenant_id=tenant_id,
                vehicle_id=vehicle_id,
                lat=-33.87,
                lng=151.21,
                status="unknown",
                recorded_at=recorded_at,
            )
        )
    await session.commit()

    # The silent vehicle never publishes again. The other one does, once.
    resp = await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": reporting.id, "lat": -33.87, "lng": 151.21, "status": "unknown"},
        headers=headers,
    )
    assert resp.status_code == 201, resp.text

    rows = (
        (
            await session.execute(
                select(VehiclePositionHistory).where(
                    VehiclePositionHistory.tenant_id == tenant_id
                )
            )
        )
        .scalars()
        .all()
    )
    cutoff = datetime.now(UTC) - timedelta(hours=retention)
    stale_remaining = [
        r
        for r in rows
        if (r.recorded_at if r.recorded_at.tzinfo else r.recorded_at.replace(tzinfo=UTC)) < cutoff
    ]
    assert stale_remaining == [], "expired rows survived the prune"
    # The silent vehicle's still-fresh row is untouched — this prunes by age,
    # it does not delete a silent vehicle's history wholesale.
    assert any(r.vehicle_id == silent.id for r in rows)


async def test_position_history_retention_is_configurable(session: AsyncSession, monkeypatch):
    """Retention is a setting, not a constant baked into the delete."""
    from app.services.live_ops import position_history_retention_hours

    assert position_history_retention_hours("any-tenant") == 72
    monkeypatch.setattr(settings, "POSITION_HISTORY_RETENTION_HOURS", 24)
    assert position_history_retention_hours("any-tenant") == 24


# ===========================================================================
# 4 · Job-offer expiry on the jobs-list read
# ===========================================================================


async def test_jobs_list_read_expires_stale_offers(client: AsyncClient, session: AsyncSession):
    """An offer nobody ever opened used to stay `pending` forever, so the job
    was never re-offered. Reading the jobs list now expires it."""
    from app.models.jobs import OFFER_STATUS_EXPIRED, OFFER_STATUS_PENDING, Job, JobOffer

    headers = await auth_headers(client, session, role="admin")
    tenant_id = _claims(headers)["tenant_id"]

    job = Job(
        tenant_id=tenant_id,
        origin_lat=-33.87,
        origin_lng=151.21,
        origin_address="1 Test St",
        dest_lat=-33.88,
        dest_lng=151.22,
        dest_address="2 Test St",
        fare_estimate_low=Decimal("10.00"),
        fare_estimate_high=Decimal("20.00"),
        requested_at=datetime.now(UTC),
    )
    session.add(job)
    await session.commit()
    await session.refresh(job)

    offer = JobOffer(
        tenant_id=tenant_id,
        job_id=job.id,
        driver_id=str(uuid.uuid4()),
        status=OFFER_STATUS_PENDING,
        offered_at=datetime.now(UTC) - timedelta(minutes=10),
        expires_at=datetime.now(UTC) - timedelta(minutes=9),
    )
    session.add(offer)
    await session.commit()
    offer_id = offer.id

    resp = await client.get("/v1/jobs", headers=headers)
    assert resp.status_code == 200, resp.text

    session.expire_all()
    refreshed = (
        await session.execute(select(JobOffer).where(JobOffer.id == offer_id))
    ).scalar_one()
    assert refreshed.status == OFFER_STATUS_EXPIRED
    assert refreshed.responded_at is not None


# ===========================================================================
# 5 · Open shifts do not survive driver deletion
# ===========================================================================


async def test_force_wipe_closes_open_shifts_for_deleted_drivers(
    client: AsyncClient, session: AsyncSession
):
    """`fleet_wipe` deleted drivers without touching their shifts, leaving
    open shifts pointing at a driver_id nothing could resolve — the same
    dangling-open-shift bug already fixed on the vehicle side."""
    from app.services.fleet_wipe import force_wipe_tenant_fleet_data

    headers = await auth_headers(client, session, role="owner")
    tenant_id = _claims(headers)["tenant_id"]
    actor_id = _claims(headers)["sub"]

    driver = User(
        tenant_id=tenant_id,
        email=f"driver-{uuid.uuid4().hex[:8]}@example.com",
        role=ROLE_DRIVER,
        name="Wipe Me",
    )
    session.add(driver)
    await session.commit()
    await session.refresh(driver)

    shift = Shift(
        tenant_id=tenant_id,
        driver_id=driver.id,
        vehicle_id=str(uuid.uuid4()),  # no matching Vehicle row: the vehicle
        start_at=datetime.now(UTC) - timedelta(hours=2),  # pass cannot catch it
        end_at=None,
    )
    session.add(shift)
    await session.commit()
    shift_id = shift.id

    await force_wipe_tenant_fleet_data(session, tenant_id=tenant_id, actor_user_id=actor_id)
    await session.commit()

    session.expire_all()
    closed = (await session.execute(select(Shift).where(Shift.id == shift_id))).scalar_one()
    assert closed.end_at is not None, "the wiped driver's shift is still open"
    assert closed.reconciled is False, "an involuntary closure must not claim a reconciliation"

    # And the closure is on the tamper-evident record, with its reason.
    entries = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.tenant_id == tenant_id,
                    AuditLog.action == "shift_force_closed_driver_deleted",
                    AuditLog.entity_id == shift_id,
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(entries) == 1
    assert entries[0].after_json["reason"] == "driver_deleted"


# ===========================================================================
# 6 · The audit hash chain cannot fork under concurrency
# ===========================================================================


async def test_concurrent_audit_appends_do_not_fork_the_chain(
    client: AsyncClient, session: AsyncSession
):
    """Backend audit §7's untested concurrency gap: `_latest_hash` + insert was
    not serialised, so two concurrent audited writes on one tenant could both
    read the same `previous_hash` and permanently break `verify_chain` — on the
    one control the system has for tamper evidence.

    Each append runs on its OWN session (its own DB connection), concurrently
    via `asyncio.gather`, which is exactly the shape two simultaneous HTTP
    requests take.
    """
    headers = await auth_headers(client, session, role="admin")
    tenant_id = _claims(headers)["tenant_id"]
    actor_id = _claims(headers)["sub"]

    appends = 12

    async def _append(index: int) -> None:
        async with AsyncSessionLocal() as own_session:
            await record_audit(
                own_session,
                tenant_id=tenant_id,
                actor_user_id=actor_id,
                action="update",
                entity_type="concurrency_probe",
                entity_id=f"probe-{index}",
            )
            await own_session.commit()

    await asyncio.gather(*(_append(i) for i in range(appends)))

    rows = (
        (
            await session.execute(
                select(AuditLog)
                .where(AuditLog.tenant_id == tenant_id)
                .order_by(AuditLog.at.asc(), AuditLog.id.asc())
            )
        )
        .scalars()
        .all()
    )
    assert len(rows) == appends

    # A fork shows up as two rows claiming the same predecessor. This is the
    # assertion that fails without the per-tenant append lock.
    previous_hashes = [r.previous_hash for r in rows]
    assert len(set(previous_hashes)) == len(previous_hashes), "two rows share a previous_hash"
    assert previous_hashes.count(GENESIS_HASH) == 1, "more than one row claims to be the genesis"

    ok, bad_id, checked = await verify_chain(session, tenant_id=tenant_id)
    assert ok, f"chain broken at row {bad_id} after {checked} rows"
    assert checked == appends


async def test_audit_chain_lock_uses_a_postgres_advisory_lock(session: AsyncSession):
    """The SQLite path this suite runs on and the Postgres path production runs
    on are different statements, so assert the Postgres one is actually issued
    rather than only exercising SQLite.

    Also pins the lock key's determinism: a per-process-salted `hash()` would
    give two uvicorn workers different keys for the same tenant and they would
    not exclude each other, which is the whole point.
    """
    from app.services import audit_log as audit_log_service

    key = audit_log_service._chain_lock_key("tenant-abc")
    assert key == audit_log_service._chain_lock_key("tenant-abc")
    assert -(2**63) <= key < 2**63
    assert audit_log_service._chain_lock_key("tenant-xyz") != key

    issued: list[str] = []

    class _FakeDialect:
        name = "postgresql"

    class _FakeConnection:
        dialect = _FakeDialect()

    class _FakeSyncSession:
        def __init__(self) -> None:
            # Pre-marked as already holding the lock so the in-process half
            # short-circuits and this test isolates the Postgres statement.
            self.info: dict = {"_audit_chain_lock": object()}

    class _FakeSession:
        sync_session = _FakeSyncSession()

        async def connection(self):
            return _FakeConnection()

        async def execute(self, stmt, params=None):
            issued.append(str(stmt))

    await audit_log_service._acquire_chain_lock(_FakeSession(), tenant_id="tenant-abc")
    assert issued == ["SELECT pg_advisory_xact_lock(:key)"]
