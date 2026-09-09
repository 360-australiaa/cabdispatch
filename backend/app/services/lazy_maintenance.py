"""Fatigue + compliance-expiry checks, run lazily off paths that are NOT a
trip tick.

WHY THIS MODULE EXISTS
----------------------
This backend has no scheduler, task queue, cron, or lifespan worker, by
deliberate design (see `app.services.live_ops`'s module docstring and
`app.services.jobs.expire_stale_offers`). Everything periodic happens on the
next read or write that passes through the relevant code path.

Before workstream B6, the fatigue checks (`app.services.fatigue`) and the
compliance-expiry checks (`app.services.compliance_expiry`) had exactly ONE
trigger between them: `PATCH /v1/trips/{id}/tick`. That is a real hole with a
real consequence, from the backend audit (§6):

  * a driver eleven hours into a shift who is not currently inside a fare —
    waiting on a rank, between jobs, on a break they never logged — ticks
    nothing, so the shift-duration and no-break alerts that exist specifically
    to catch that driver never fire;
  * a vehicle that is on shift but idle never has its registration, insurance
    or device-calibration expiry looked at, so an expired rego is silently
    driven around.

The fix, staying inside the lazy pattern rather than adding infrastructure:
hang the same checks off the two other things that are already happening
regularly for an on-shift vehicle.

  1. `POST /v1/fleet/positions` — the position heartbeat every on-shift tablet
     already sends every ~5 seconds. This is the closest thing this system has
     to a per-driver timer, and it exists whether or not a fare is running.
  2. Shift reads (`GET /v1/shifts`, `GET /v1/shifts/{id}`) — so a dispatcher
     looking at who is on shift sees state that is correct as of the moment
     they looked, not as of the last tick.

NEVER RAISES
------------
Every entry point here is wrapped so a failure cannot propagate to its caller.
A fatigue check that errors must not turn a position publish into a 500: the
position heartbeat is how the dashboard knows where the fleet is and how the
device proves it is alive, and it is far more important that it keeps working
than that an advisory alert gets raised on this particular beat. Failures are
logged at ERROR (never swallowed silently). This mirrors the "best-effort
side-write" contract `app.services.live_ops._persist_driver_position` /
`_persist_device_telemetry` already established for enrichments layered on top
of the position publish.

ISOLATED SESSION (production incident fix, request_id
cd2486bb63374e4384e20cdde6dc3558): `run_checks_for_shifts` and
`run_checks_for_vehicle` used to run all of the above on the CALLER's own
request session — the same session `GET /v1/shifts` used to fetch the shifts
it was about to serialize, or `POST /v1/fleet/positions` used for the
publish. A duplicate fatigue-alert row already in the DB (see
`app.services.fatigue`'s module docstring and the migration that added a real
uniqueness guard) made a lookup inside these checks raise
`MultipleResultsFound`; the `except Exception` here caught it as designed,
but the `await session.rollback()` that used to follow EXPIRES every object
already loaded on that session by default (`expire_on_rollback=True`,
independent of `expire_on_commit=False`, which this project's engine sets and
which is a different knob) — including the `Shift` rows `GET /v1/shifts` had
already fetched and was about to hand to FastAPI/Pydantic for serialization.
The very next plain attribute read on any of them (`shift.id`, `.tenant_id`,
literally every column) then tried to lazily refresh from the DB to satisfy
that expiry, which needs an awaited round trip — outside the async context
Pydantic's synchronous field access runs in — raising `MissingGreenlet` and
turning an already-answered, already-caught error into a 500 anyway.

Both functions now open and use their OWN separate session
(`AsyncSessionLocal()`), entirely decoupled from the caller's. A failure
(and the implicit rollback that happens when that session is closed without
being committed) can therefore never expire, detach, or otherwise touch any
object the caller's own session still needs — consistent with this module's
own "the read is answered anyway" contract, which promised exactly that and,
before this fix, did not actually deliver on it. The `session`/request
session each function still accepts is kept only so existing call sites
don't need to change; it is intentionally not read from or written to by
either function below (named with a leading underscore for that reason).

WHAT IS DELIBERATELY *NOT* CHECKED HERE
---------------------------------------
`app.services.fatigue.check_speed` is NOT run off the position heartbeat, even
though `PositionPublishRequest` carries `speed_kmh`. That check has no
debounce and no dedup by design (documented as an open simplification in
`app.services.fatigue`'s module docstring: "every telemetry point over
threshold raises its own alert"), which is tolerable at trip-tick granularity
but would mean roughly twelve new alert rows per minute, per speeding vehicle,
forever, off a 5-second heartbeat — burying the fatigue list under one
incident. The other three checks are all idempotent/deduped per shift or per
unacknowledged alert, so they are safe to run on every beat. Wiring speed in
here needs the "sustained speed" definition that module is already waiting on;
guessing at it and flooding the alerts table is worse than the gap.
"""
from __future__ import annotations

import logging

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import AsyncSessionLocal
from app.models.fleet import Vehicle
from app.models.shift import Shift
from app.models.user import User
from app.services import compliance_expiry as compliance_expiry_service
from app.services import fatigue as fatigue_service

logger = logging.getLogger("cab_dispatch.lazy_maintenance")


async def _open_shift_for_vehicle(
    session: AsyncSession, *, tenant_id: str, vehicle_id: str
) -> Shift | None:
    """The currently-open shift on this vehicle, or None.

    Ordered newest-first rather than asserting there is at most one: nothing
    in `app.models.shift.Shift` enforces "one open shift per vehicle", and if
    a stale open shift somehow coexists with a live one, the live one is the
    one whose fatigue clock a dispatcher cares about.
    """
    result = await session.execute(
        select(Shift)
        .where(
            Shift.tenant_id == tenant_id,
            Shift.vehicle_id == vehicle_id,
            Shift.end_at.is_(None),
        )
        .order_by(Shift.start_at.desc())
        .limit(1)
    )
    return result.scalars().first()


async def _run_shift_checks(session: AsyncSession, *, tenant_id: str, shift: Shift) -> None:
    """Fatigue checks that are a function of how long a shift has been open.
    Both are idempotent per shift (they no-op once an alert of that kind
    exists), so this is safe on every heartbeat."""
    await fatigue_service.check_shift_duration(session, tenant_id=tenant_id, shift=shift)
    await fatigue_service.check_no_break_taken(session, tenant_id=tenant_id, shift=shift)


async def _run_driver_checks(session: AsyncSession, *, tenant_id: str, driver_id: str) -> None:
    result = await session.execute(
        select(User).where(User.id == driver_id, User.tenant_id == tenant_id)
    )
    driver = result.scalar_one_or_none()
    if driver is not None:
        await compliance_expiry_service.run_driver_compliance_checks(
            session, tenant_id=tenant_id, driver=driver
        )


async def _run_vehicle_checks(session: AsyncSession, *, tenant_id: str, vehicle_id: str) -> None:
    result = await session.execute(
        select(Vehicle).where(Vehicle.id == vehicle_id, Vehicle.tenant_id == tenant_id)
    )
    vehicle = result.scalar_one_or_none()
    if vehicle is not None:
        await compliance_expiry_service.run_vehicle_compliance_checks(
            session, tenant_id=tenant_id, vehicle=vehicle
        )


async def run_checks_for_vehicle(
    _request_session: AsyncSession, *, tenant_id: str, vehicle_id: str
) -> None:
    """The `POST /v1/fleet/positions` entry point. Resolves the vehicle's open
    shift (if any) and runs the shift-duration, no-break, driver-compliance and
    vehicle-compliance checks against it.

    A vehicle with no open shift still gets its VEHICLE compliance checked —
    an expired registration is a fact about the vehicle, not about whether
    somebody happens to be driving it right now — but no fatigue check and no
    driver check, since without a shift there is no driver to attribute either
    to.

    Runs entirely on its own, isolated session (see the ISOLATED SESSION
    section of this module's docstring) and commits that session's own
    transaction. Never raises: see the module docstring. `_request_session`
    is accepted only for call-site compatibility with `app/api/v1/live_ops.py`
    — it is never read from or written to here, deliberately.
    """
    try:
        async with AsyncSessionLocal() as check_session:
            shift = await _open_shift_for_vehicle(
                check_session, tenant_id=tenant_id, vehicle_id=vehicle_id
            )
            if shift is not None:
                await _run_shift_checks(check_session, tenant_id=tenant_id, shift=shift)
                await _run_driver_checks(
                    check_session, tenant_id=tenant_id, driver_id=shift.driver_id
                )
            await _run_vehicle_checks(check_session, tenant_id=tenant_id, vehicle_id=vehicle_id)
            await check_session.commit()
    except Exception:  # broad on purpose — see the NEVER RAISES section in this module's docstring
        logger.exception(
            "lazy fatigue/compliance checks failed for vehicle %s (tenant %s); the "
            "position publish itself is unaffected, but no alert was raised on this beat",
            vehicle_id,
            tenant_id,
        )
        # No rollback of `_request_session` here: the checks above ran
        # entirely on `check_session`, a separate connection this function
        # opened and owns — `_request_session` was never touched, so there is
        # nothing on it to roll back, and nothing on it gets expired by this
        # except block. `check_session`'s own uncommitted work is discarded
        # when the `async with` block above exits on this exception.


async def run_checks_for_shifts(_request_session: AsyncSession, *shifts: Shift) -> None:
    """The shift-read entry point (`GET /v1/shifts`, `GET /v1/shifts/{id}`).

    Only OPEN shifts are checked — a closed shift's duration is settled
    history, and raising a fatigue alert against it now would be a new alert
    about something that finished, which is noise, not safety. Both checks
    no-op on `end_at is not None` anyway; this skips the query.

    Never raises: a dispatcher must always be able to read the shift list,
    even if raising the alert that read would have triggered failed.

    Runs entirely on its own, isolated session (see the ISOLATED SESSION
    section of this module's docstring — this is the exact function whose old
    same-session behaviour took `GET /v1/shifts` down in production) and
    commits that session's own transaction. `shifts` were fetched by the
    caller on ITS OWN session; only their already-loaded plain scalar
    attributes (`.id`, `.tenant_id`, `.driver_id`, `.vehicle_id`, `.start_at`,
    `.end_at`, `.break_taken`) are ever read here — `Shift` has no
    relationships to lazily load, so reading them triggers no I/O and is safe
    regardless of which session is doing the checking. `_request_session` is
    accepted only for call-site compatibility with `app/api/v1/shifts.py` —
    it is never read from or written to here, deliberately.
    """
    open_shifts = [s for s in shifts if s.end_at is None]
    if not open_shifts:
        return
    try:
        async with AsyncSessionLocal() as check_session:
            for shift in open_shifts:
                await _run_shift_checks(check_session, tenant_id=shift.tenant_id, shift=shift)
                await _run_driver_checks(
                    check_session, tenant_id=shift.tenant_id, driver_id=shift.driver_id
                )
                await _run_vehicle_checks(
                    check_session, tenant_id=shift.tenant_id, vehicle_id=shift.vehicle_id
                )
            await check_session.commit()
    except Exception:  # broad on purpose — see the NEVER RAISES section in this module's docstring
        logger.exception(
            "lazy fatigue/compliance checks failed on a shift read (%d open shift(s)); "
            "the read is answered anyway, but no alert was raised",
            len(open_shifts),
        )
        # No rollback of `_request_session` here — same reasoning as
        # `run_checks_for_vehicle` above: the checks ran entirely on
        # `check_session`, which this function opened and owns, so there is
        # nothing on the caller's own session to roll back or expire.
