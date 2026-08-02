"""Fatigue-monitoring business logic (blueprint 12.3): shift-duration and
speed-exceeded alert detection, called from the trip/shift tick flow.

Kept out of the trips/shifts routers so the "has an alert of this kind
already been raised for this shift" dedup logic lives in exactly one place.
Functions here only `session.add()` — they never commit; the caller (the
trip-tick router) owns the transaction, same convention as
`app.services.trips.apply_tick`/`close_trip`.
"""
from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.models.fatigue_alert import (
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


def shift_duration_limit_hours(tenant_id: str) -> float:  # noqa: ARG001 - see docstring
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
    result = await session.execute(
        select(FatigueAlert.id).where(
            FatigueAlert.tenant_id == tenant_id,
            FatigueAlert.shift_id == shift_id,
            FatigueAlert.kind == FATIGUE_ALERT_SHIFT_DURATION_EXCEEDED,
        )
    )
    return result.scalar_one_or_none() is not None


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
    session.add(alert)
    return alert


# --- speed alert ---------------------------------------------------------------


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
