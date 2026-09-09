"""Fatigue-monitoring business logic (blueprint 12.3): shift-duration and
speed-exceeded alert detection, called from the trip/shift tick flow.

Kept out of the trips/shifts routers so the "has an alert of this kind
already been raised for this shift" dedup logic lives in exactly one place.
Functions here only `session.add()`/flush within their own SAVEPOINT — they
never commit the caller's transaction; the caller (the trip-tick router, or
`app.services.lazy_maintenance`) still owns and commits the outer transaction,
same convention as `app.services.trips.apply_tick`/`close_trip`.

RACE-SAFE DEDUP INSERT (production incident fix, request_id
cd2486bb63374e4384e20cdde6dc3558): the shift-duration and no-break-taken
checks below dedupe on "does an alert of this kind already exist for this
shift" via a plain check-then-insert. That is a classic TOCTOU race whenever
two calls can run concurrently for the same shift — which they can: the
position heartbeat (`POST /v1/fleet/positions`, every ~5s per on-shift
tablet) and a shift-list read (`GET /v1/shifts`) both trigger these same
checks (see `app.services.lazy_maintenance`), and nothing serialises them.
Two such calls landing close together can each run
`_shift_duration_alert_exists`, each see zero rows (neither has committed
yet), and each `session.add()` a row — leaving TWO `shift_duration_exceeded`
rows for one shift. That is confirmed to have actually happened in
production: `_shift_duration_alert_exists`'s `scalar_one_or_none()` then
raises `MultipleResultsFound` the next time anything checks this shift again,
which is what actually took `GET /v1/shifts` down.

Fixed at the DB level with a real uniqueness guard — a partial unique index
on `fatigue_alerts (tenant_id, shift_id, kind)`, scoped to exactly these two
bounded-per-shift kinds (see the migration that adds it, and the module-level
DEVIATION note below on why `speed_exceeded` must NOT be covered by it) — and
at the application level by inserting through `_insert_alert_if_new` below,
which adds+flushes inside its own SAVEPOINT so a concurrent duplicate is
caught as an `IntegrityError` on THIS insert alone (not the caller's whole
transaction) and treated as "someone else already raised it," same effective
outcome as a real `get_or_create`. The existence pre-check stays as a
fast-path (skips the query+flush round trip on every one of the many calls
that are NOT the first past the threshold) but is no longer relied on for
correctness by itself.
"""
from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.models.fatigue_alert import (
    FATIGUE_ALERT_NO_BREAK_TAKEN,
    FATIGUE_ALERT_SHIFT_DURATION_EXCEEDED,
    FATIGUE_ALERT_SPEED_EXCEEDED,
    FatigueAlert,
)
from app.models.shift import Shift

# --- speed threshold ----------------------------------------------------------
# SIMPLIFICATION (flagged per task instructions): the blueprint calls for
# alerting ">20km/h over the relevant tariff-region's normal [speed] limit",
# but nothing in this codebase has a per-road/per-region speed-limit source —
# `Tariff` rows (app.models.tariffs) carry fare *rates*, not speed limits, and
# there is no other candidate table. We use a flat, system-wide "normal limit"
# assumption instead and alert on any reading more than 20km/h over it. Swap
# in a real per-road/per-region source here if one becomes available.
ASSUMED_NORMAL_SPEED_LIMIT_KMH = 100.0
SPEED_ALERT_MARGIN_KMH = 20.0
SPEED_ALERT_THRESHOLD_KMH = ASSUMED_NORMAL_SPEED_LIMIT_KMH + SPEED_ALERT_MARGIN_KMH

# Also a documented simplification: the blueprint says "a single SUSTAINED
# speed reading" — this pass does not implement any debounce/sustained-window
# logic (e.g. "over N consecutive seconds") to avoid false-positiving on a
# single noisy GPS fix, or de-dupe repeated readings within one overspeed
# episode. Every telemetry point over threshold raises its own alert. A real
# "sustained" definition needs a design call (how many seconds? consecutive
# points only, or over a rolling window tolerant of gaps?) that's out of scope
# here; flagging it rather than guessing.


def _as_aware(dt: datetime) -> datetime:
    return dt if dt.tzinfo is not None else dt.replace(tzinfo=UTC)


def shift_duration_limit_hours(tenant_id: str) -> float:
    """Returns the shift-duration fatigue-alert threshold, in hours, for a
    tenant.

    DEVIATION (flagged per task instructions): the task calls for this to be
    "configurable via a tenant setting" — this codebase has no persisted
    per-tenant settings table anywhere (`Tenant` only carries
    theme_json/plan/stripe_acct_id; adding a new table/column for this alone
    is out of scope for this pass). The value is instead sourced from
    `Settings.FATIGUE_SHIFT_DURATION_LIMIT_HOURS` — one env-overridable value
    for the whole deployment, defaulting to 12 hours. `tenant_id` is still
    accepted (and threaded through by every caller) so a real per-tenant
    override can be slotted in here later without touching any call site.
    """
    return settings.FATIGUE_SHIFT_DURATION_LIMIT_HOURS


# --- shift-duration alert ------------------------------------------------------


async def _shift_duration_alert_exists(session: AsyncSession, *, tenant_id: str, shift_id: str) -> bool:
    # Defensive belt-and-braces fix, independent of the uniqueness guard
    # above: `.limit(1)` + `.scalar()` (never `scalar_one_or_none()`) means
    # this existence check itself can NEVER raise `MultipleResultsFound`,
    # even against rows that already violate the invariant (e.g. duplicates
    # already sitting in production from before the fix below existed, until
    # the cleanup migration runs against them). A dedup *check* has no
    # business being the thing that turns "an alert already exists" into a
    # 500 on every future read of this shift.
    result = await session.execute(
        select(FatigueAlert.id)
        .where(
            FatigueAlert.tenant_id == tenant_id,
            FatigueAlert.shift_id == shift_id,
            FatigueAlert.kind == FATIGUE_ALERT_SHIFT_DURATION_EXCEEDED,
        )
        .limit(1)
    )
    return result.scalar() is not None


async def _insert_alert_if_new(session: AsyncSession, alert: FatigueAlert) -> FatigueAlert | None:
    """Adds `alert` and flushes it inside its own SAVEPOINT, so a concurrent
    duplicate insert for the same dedup key (see module docstring — the
    TOCTOU race the plain existence-check above cannot close by itself) is
    caught HERE as an `IntegrityError` against the partial unique index on
    `fatigue_alerts (tenant_id, shift_id, kind)`, rather than surfacing later
    as a `MultipleResultsFound` the next time anything checks this shift.

    Only this one SAVEPOINT rolls back on conflict — the caller's outer
    transaction, and any other rows already added to it (e.g. alerts for
    other shifts in the same `run_checks_for_shifts` batch), are untouched.
    Returns `None` if a duplicate was rejected (someone else already raised
    this exact alert), `alert` otherwise. Still never commits — same
    add()-only contract as the rest of this module; the caller commits the
    outer transaction.
    """
    try:
        async with session.begin_nested():
            session.add(alert)
            await session.flush()
    except IntegrityError:
        return None
    return alert


async def get_shift_or_none(session: AsyncSession, *, tenant_id: str, shift_id: str) -> Shift | None:
    """Tenant-scoped lookup that returns None instead of raising — callers on
    the tick path treat "trip.shift_id doesn't resolve to a real shift" as a
    silent no-op for fatigue-checking purposes rather than a hard error, since
    `Trip.shift_id` is optional and not FK-enforced (see app.models.trips)."""
    result = await session.execute(select(Shift).where(Shift.id == shift_id, Shift.tenant_id == tenant_id))
    return result.scalar_one_or_none()


async def check_shift_duration(
    session: AsyncSession, *, tenant_id: str, shift: Shift, now: datetime | None = None
) -> FatigueAlert | None:
    """Raises (adds to the session; does NOT commit) a shift_duration_exceeded
    alert if `shift` has been open longer than the configured threshold and
    one doesn't already exist for this shift. Idempotent — safe to call on
    every tick; only the first call past the threshold actually creates a row.
    Returns the new alert, or None if no alert was needed (shift already
    closed, still under the threshold, or already alerted)."""
    if shift.end_at is not None:
        return None

    now_aware = _as_aware(now) if now is not None else datetime.now(UTC)
    start_aware = _as_aware(shift.start_at)
    elapsed_hours = (now_aware - start_aware).total_seconds() / 3600.0

    limit_hours = shift_duration_limit_hours(tenant_id)
    if elapsed_hours <= limit_hours:
        return None

    if await _shift_duration_alert_exists(session, tenant_id=tenant_id, shift_id=shift.id):
        return None

    alert = FatigueAlert(
        tenant_id=tenant_id,
        driver_id=shift.driver_id,
        shift_id=shift.id,
        kind=FATIGUE_ALERT_SHIFT_DURATION_EXCEEDED,
        triggered_at=now_aware,
        details_json={
            "elapsed_hours": round(elapsed_hours, 2),
            "limit_hours": limit_hours,
        },
        acknowledged=False,
    )
    return await _insert_alert_if_new(session, alert)


# --- no-break-taken alert -------------------------------------------------------
# Threshold: HALF of the shift-duration limit (settings.FATIGUE_SHIFT_DURATION_LIMIT_HOURS),
# i.e. 6 hours at the default 12-hour limit. Not a value the blueprint pins down
# explicitly ("mandatory break alerts") -- half of the shift-duration limit is
# the most defensible reading (a driver who is already halfway to the point
# we consider fatigue-worthy, with no break taken at all yet, is a reasonable
# early-warning signal), so that is what is implemented. Swap in a distinct
# tenant-configurable value here if a real policy number becomes available --
# same DEVIATION as shift_duration_limit_hours above (no persisted per-tenant
# settings table exists yet).
NO_BREAK_ALERT_FRACTION_OF_SHIFT_LIMIT = 0.5


async def _no_break_taken_alert_exists(session: AsyncSession, *, tenant_id: str, shift_id: str) -> bool:
    # Same defensive fix as `_shift_duration_alert_exists` above, for the
    # identical fragile pattern on the sibling check — see that function's
    # comment.
    result = await session.execute(
        select(FatigueAlert.id)
        .where(
            FatigueAlert.tenant_id == tenant_id,
            FatigueAlert.shift_id == shift_id,
            FatigueAlert.kind == FATIGUE_ALERT_NO_BREAK_TAKEN,
        )
        .limit(1)
    )
    return result.scalar() is not None


async def check_no_break_taken(
    session: AsyncSession, *, tenant_id: str, shift: Shift, now: datetime | None = None
) -> FatigueAlert | None:
    """Raises (adds to the session; does NOT commit) a no_break_taken alert if
    `shift` has been open longer than NO_BREAK_ALERT_FRACTION_OF_SHIFT_LIMIT of
    the configured shift-duration threshold, `shift.break_taken` is still
    False, and one does not already exist for this shift. Mirrors
    check_shift_duration exact shape: same add()-only-no-commit contract,
    same "does an alert of this kind already exist for this shift" dedup
    (bounded-per-shift, unlike compliance_expiry.py unacknowledged-only
    dedup for renewable kinds like licences) -- idempotent, safe to call on
    every tick. Returns the new alert, or None if no alert was needed (shift
    already closed, still under the threshold, a break was already taken, or
    already alerted)."""
    if shift.end_at is not None:
        return None

    if shift.break_taken:
        return None

    now_aware = _as_aware(now) if now is not None else datetime.now(UTC)
    start_aware = _as_aware(shift.start_at)
    elapsed_hours = (now_aware - start_aware).total_seconds() / 3600.0

    threshold_hours = shift_duration_limit_hours(tenant_id) * NO_BREAK_ALERT_FRACTION_OF_SHIFT_LIMIT
    if elapsed_hours <= threshold_hours:
        return None

    if await _no_break_taken_alert_exists(session, tenant_id=tenant_id, shift_id=shift.id):
        return None

    alert = FatigueAlert(
        tenant_id=tenant_id,
        driver_id=shift.driver_id,
        shift_id=shift.id,
        kind=FATIGUE_ALERT_NO_BREAK_TAKEN,
        triggered_at=now_aware,
        details_json={
            "elapsed_hours": round(elapsed_hours, 2),
            "threshold_hours": round(threshold_hours, 2),
        },
        acknowledged=False,
    )
    return await _insert_alert_if_new(session, alert)


# --- speed alert ---------------------------------------------------------------
# DEVIATION (interaction with the dedup uniqueness guard above): `check_speed`
# is deliberately NOT deduped — see the "sustained speed" simplification note
# earlier in this module — so, unlike shift_duration_exceeded/no_break_taken,
# MANY `speed_exceeded` rows are expected for one shift_id. The partial unique
# index this pass adds on `fatigue_alerts (tenant_id, shift_id, kind)` is
# therefore explicitly scoped to `kind IN ('shift_duration_exceeded',
# 'no_break_taken')` only — it must never be widened to cover
# `speed_exceeded`, or every second qualifying telemetry point on an already-
# speeding shift would start raising `IntegrityError`s here.


async def check_speed(
    session: AsyncSession,
    *,
    tenant_id: str,
    driver_id: str,
    shift_id: str | None,
    speed_kmh: float,
    ts: datetime | None = None,
) -> FatigueAlert | None:
    """Raises (adds to the session; does NOT commit) a speed_exceeded alert if
    `speed_kmh` is more than `SPEED_ALERT_MARGIN_KMH` over
    `ASSUMED_NORMAL_SPEED_LIMIT_KMH` (see module docstring for the flat-limit
    simplification). Not deduped like the shift-duration check — each
    qualifying telemetry point raises its own alert (see the "sustained"
    simplification note above). Returns None if under threshold."""
    if speed_kmh <= SPEED_ALERT_THRESHOLD_KMH:
        return None

    triggered_at = _as_aware(ts) if ts is not None else datetime.now(UTC)
    alert = FatigueAlert(
        tenant_id=tenant_id,
        driver_id=driver_id,
        shift_id=shift_id,
        kind=FATIGUE_ALERT_SPEED_EXCEEDED,
        triggered_at=triggered_at,
        details_json={
            "speed_kmh": speed_kmh,
            "assumed_normal_limit_kmh": ASSUMED_NORMAL_SPEED_LIMIT_KMH,
            "threshold_kmh": SPEED_ALERT_THRESHOLD_KMH,
        },
        acknowledged=False,
    )
    session.add(alert)
    return alert
