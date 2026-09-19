"""Shifts domain business logic: opening/closing a shift and recomputing its
trip aggregates.

Cross-domain note: `_recompute_trip_aggregates` reads the sibling `trips`
domain's `Trip` model (`app.models.trips`) to sum up a shift's trips at close
time. That table is owned by another agent's slice of this codebase; the import
is wrapped so this domain degrades gracefully (aggregates left at zero, with a
logged warning) rather than hard-crashing if `trips` is ever absent, e.g. if
this module is exercised standalone before the two domains are integrated.

Reporting: `build_report` assembles the JSON summary dict; `render_report_pdf`
and `render_report_csv` below render that same summary (as a `ShiftReport`)
to real bytes/text. PDF rendering mirrors app.services.receipts._render_pdf_bytes's
simple header plus labeled-rows visual style, via the same pure-Python fpdf2
dependency ("fpdf2>=2.8.7" in pyproject.toml, no system dependencies). CSV
rendering mirrors app.api.v1.reports._ptp_rows_to_csv's stdlib io.StringIO
plus csv.writer pattern, no new dependency.
"""
from __future__ import annotations

import csv
import io
import json
import logging
import uuid as uuid_module
from datetime import UTC, datetime
from decimal import Decimal

from fpdf import FPDF
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.fleet import Device, Vehicle
from app.models.shift import Shift
from app.schemas.shift import ShiftReport
from app.services.audit_log import record_audit
from app.services.fare_engine import round_half_up

logger = logging.getLogger("cab_dispatch.shift")

# Payment methods treated as "cash" for the cash-vs-card reconciliation split.
# Everything else observed on a trip (tap_to_pay, link, cabcharge, ttss, ...) is
# counted into card_total. Mirrors the `payments.method` enum in the product spec.
_CASH_METHOD = "cash"

# Attributed leg-by-leg rather than counted wholesale into either bucket —
# see _recompute_trip_aggregates. Mirrors the "split_fare" value in
# app.schemas.trips.PaymentMethod / app.models.trips.Trip.payment_method.
_SPLIT_METHOD = "split_fare"


class ShiftConflictError(Exception):
    """Raised by start_shift() when the target vehicle already has an open
    shift under a DIFFERENT driver and the caller didn't pass
    force_handover=True. Carries the conflicting shift so the API layer can
    build a helpful 409 message (which driver, since when) rather than a bare
    refusal — a dispatcher needs to know who to actually call."""

    def __init__(self, conflicting_shift: Shift):
        self.conflicting_shift = conflicting_shift
        super().__init__(
            f"Vehicle {conflicting_shift.vehicle_id} already has an open shift "
            f"({conflicting_shift.id}) for driver {conflicting_shift.driver_id}"
        )


def _looks_like_vehicle_uuid(value: str) -> bool:
    """True if `value` parses as a UUID, i.e. it is already a Vehicle.id.

    `Vehicle.id` is `str(uuid.uuid4())` (app/models/fleet.py), so "is this a
    UUID" is the whole of "is this already canonical". A NSW rego is at most
    six characters of letters and digits and cannot collide with any form
    uuid.UUID accepts (36-char hyphenated, 32-char bare hex, urn/braces):
    all of those are longer than the 20-char `Vehicle.rego` column.
    """
    try:
        uuid_module.UUID(value)
    except (ValueError, AttributeError, TypeError):
        return False
    return True


def _rego_key(value: str) -> str:
    """The comparison form of a rego: upper-cased, with every non-alphanumeric
    character removed, so "khi-01", "KHI 01" and "KHI01" all compare equal.

    Case alone is not enough, and getting that wrong strands a driver rather
    than merely looking untidy. Regos are stored by
    `VehicleBase._normalize_rego` (app/schemas/fleet.py) as `v.strip().upper()`
    -- punctuation is PRESERVED on write, so a vehicle genuinely can sit in the
    table as "KHI-01" or "ABC 123". Meanwhile the meter's manual rego pad
    (`RegoKeyRows`, LoginVehicleBindScreen.kt) offers A-Z, 0-9 and a hyphen --
    and no space key at all. A driver rebinding a car whose stored rego
    contains a space cannot type the stored spelling, and a driver who omits
    the hyphen on "KHI-01" types a string that never equalled it either.
    Folding punctuation out on BOTH sides is what makes those spellings
    resolve.
    """
    return "".join(ch for ch in value if ch.isalnum()).upper()


async def _canonicalise_vehicle_id(
    session: AsyncSession, *, tenant_id: str, vehicle_id: str
) -> str:
    """Returns the `Vehicle.id` for `vehicle_id`, resolving it by rego when it
    is not already a UUID. Returns `vehicle_id` UNCHANGED when it resolves to
    no vehicle -- see the "never a new failure mode" paragraph below.

    WHY (2026-09-19): the Android meter sends `resolvedVehicleUuid ?: vehicleId`
    -- it falls back to the REGO STRING whenever it could not resolve the UUID
    itself, which is exactly the no-signal basement-bind case that the fallback
    exists for. `LoginVehicleBindViewModel.kt` (~:449) carries a comment
    asserting the backend canonicalises rego->UUID server-side. That comment was
    false: `start_shift` stored whatever arrived, verbatim. Of 184 production
    shifts, 86 hold a non-UUID `vehicle_id`. This function makes the Android
    comment true.

    The consequence was not cosmetic. Every "which vehicle is this" query in
    the system is a plain string equality on `shifts.vehicle_id` -- including
    `_find_open_shift(vehicle_id=...)`, which IS the double-assignment guard.
    A shift opened as 'ABC12D' and a shift opened as the same car's UUID are
    two different strings, so the guard never saw the collision: driver A on
    the rego row and driver B on the UUID row could both hold the same car
    open at once, with no 409 and no handover. Trip attribution and incident
    liability for that car are ambiguous for as long as both are open.
    Canonicalising on the way in is what closes that hole, which is why this
    runs BEFORE the dangling-shift and vehicle-conflict queries below rather
    than just before the INSERT.

    NEVER A NEW FAILURE MODE (the 2026-09-19 review's correction). An earlier
    draft of this raised VehicleNotFoundError on an unresolvable rego and the
    API turned it into a 404. That was strictly worse than the defect it
    replaced, because `POST /v1/shifts/start` had never been able to 404 and
    both deployed Android consumers treat any non-IOException as TERMINAL:
    `OutboxBackedShiftRepository.startShift` deletes the queued outbox row on
    the spot (ShiftRepository.kt, "The server answered and said no"), and
    `OutboxDrainer.drainShiftStarts` burns the row's five attempts and
    dead-letters it -- at which point `rewriteShiftId` never runs and every
    trip closed under the placeholder `local-<uuid>` shift id is ORPHANED.
    Tablets in cabs run month-old code and update on the drivers' schedule, so
    that 404 would destroy real queued shifts on clients that cannot be fixed.
    An unresolvable rego therefore falls back to the pre-2026-09-19 behaviour
    exactly: the value is stored verbatim, the shift opens, and we log a
    warning for the office to chase. A shift on a fuzzily-identified car is a
    degraded shift; a deleted shift is a lost one. This function can only ever
    improve a `vehicle_id` or leave it alone.

    Matching is two-step so the common path stays an index-usable equality:
    first `Vehicle.rego == vehicle_id.strip().upper()` (the same folding
    `GET /v1/fleet/vehicles`'s `rego_exact` filter uses), then, only if that
    misses, a punctuation-insensitive comparison via `_rego_key` over the
    tenant's vehicles. Fleets are tens of cars, so the fallback scan is cheap
    and only runs for a rego that would otherwise have gone unresolved.

    AMBIGUITY: two vehicles can normalise to the same key ("KHI-01" and
    "KHI01"). We do not error -- erroring is the 404 failure mode above wearing
    a different status code. We pick the LOWEST `Vehicle.id` among the
    punctuation-folded matches: deterministic (so the same rego resolves to the
    same car on every retry and every outbox replay, which is exactly what the
    double-assignment guard needs), and independent of insertion order, row
    order and the database's collation. An exact match always beats a folded
    one, so an operator can always disambiguate by storing the rego exactly as
    the driver types it.

    Forward-only by design: 0 of the 86 affected production shifts are OPEN,
    so there is nothing live to repair and no backfill migration is written.
    Historical closed rows keep their rego strings.
    """
    if _looks_like_vehicle_uuid(vehicle_id):
        return vehicle_id

    rego = vehicle_id.strip().upper()
    resolved = (
        await session.execute(
            select(Vehicle.id).where(
                Vehicle.tenant_id == tenant_id, Vehicle.rego == rego
            )
        )
    ).scalar_one_or_none()

    if resolved is None:
        # Punctuation-folded fallback. Ordered by id so the ambiguous case is
        # decided deterministically (see AMBIGUITY above) rather than by
        # whatever order the database happens to return rows in.
        key = _rego_key(vehicle_id)
        if key:
            candidates = (
                await session.execute(
                    select(Vehicle.id, Vehicle.rego)
                    .where(Vehicle.tenant_id == tenant_id)
                    .order_by(Vehicle.id)
                )
            ).all()
            for candidate_id, candidate_rego in candidates:
                if candidate_rego is not None and _rego_key(candidate_rego) == key:
                    resolved = candidate_id
                    break

    if resolved is None:
        # Degrade, never destroy: this is the pre-2026-09-19 behaviour,
        # unchanged, for exactly the clients that cannot be updated.
        logger.warning(
            "start_shift: vehicle_id %r is not a UUID and matches no vehicle rego "
            "in tenant %s; storing it verbatim as before. The shift is opened on a "
            "vehicle we cannot identify -- the double-assignment guard cannot see "
            "this car under any other spelling until the office reconciles it.",
            vehicle_id,
            tenant_id,
        )
        return vehicle_id

    logger.info(
        "start_shift: canonicalised vehicle rego %r -> vehicle id %s (tenant %s). "
        "The meter could not resolve the UUID itself; resolving server-side.",
        vehicle_id,
        resolved,
        tenant_id,
    )
    return resolved


# Public name for the API layer. The underscore-prefixed original stays as the
# in-module spelling used by `start_shift` below; this alias exists so the
# admin CRUD routes in app/api/v1/shifts.py can close the same hole on the
# `POST /v1/shifts` and `PATCH /v1/shifts/{id}` backfill paths without
# importing a private helper.
canonicalise_vehicle_id = _canonicalise_vehicle_id


async def _find_open_shift(
    session: AsyncSession, *, tenant_id: str, driver_id: str | None = None, vehicle_id: str | None = None
) -> Shift | None:
    """The most recently started open (end_at IS NULL) shift matching the
    given filters, or None. This IS the "who is currently driving this
    vehicle" / "is this driver already on shift somewhere" query — the
    system has no separate denormalized "current driver" field anywhere;
    it is always derived live from the shifts table, so it can never drift
    out of sync with reality the way a cached pointer could."""
    filters = [Shift.tenant_id == tenant_id, Shift.end_at.is_(None)]
    if driver_id is not None:
        filters.append(Shift.driver_id == driver_id)
    if vehicle_id is not None:
        filters.append(Shift.vehicle_id == vehicle_id)
    result = await session.execute(
        select(Shift).where(*filters).order_by(Shift.start_at.desc()).limit(1)
    )
    return result.scalar_one_or_none()


#: Public name for `_find_open_shift`, for callers outside this module. The
#: underscore form is the one the rest of this file uses and is kept as-is;
#: this alias exists so other packages (notably `app.api.v1.live_ops`, which
#: needs it to answer "is this driver really in this vehicle right now" before
#: accepting a position publish) do not have to reach for a private name.
find_open_shift = _find_open_shift


async def _recompute_trip_aggregates(
    session: AsyncSession, *, tenant_id: str, shift_id: str
) -> tuple[int, Decimal, Decimal, Decimal]:
    """Returns (trips_count, km_total, cash_total, card_total) computed fresh
    from the shift's own closed trips. Authoritative — never trusts client input."""
    try:
        from app.models.trips import TRIP_STATUS_CLOSED, Trip
    except ImportError:  # pragma: no cover - defensive only, see module docstring
        logger.warning(
            "app.models.trips.Trip not importable — leaving shift %s aggregates at "
            "zero. Expected only if this domain is exercised before the trips "
            "domain is present in the tree.",
            shift_id,
        )
        return 0, Decimal(0), Decimal(0), Decimal(0)

    # CLOSED trips only (backend audit §4, "Gap: _recompute_trip_aggregates
    # sums ALL trips, not just closed"). An open trip has total = 0 and a
    # distance that is still moving, so counting it deflated cash/card
    # against trips_count and let a shift be reconciled against a fare that
    # had not been struck yet. app.services.trips already filters this way
    # for the driver's own earnings figure; this brings the shift report in
    # line with it.
    base_filter = (
        Trip.tenant_id == tenant_id,
        Trip.shift_id == shift_id,
        Trip.status == TRIP_STATUS_CLOSED,
    )

    trips_count = (
        await session.execute(select(func.count(Trip.id)).where(*base_filter))
    ).scalar_one() or 0

    distance_m_total = (
        await session.execute(
            select(func.coalesce(func.sum(Trip.distance_m), 0)).where(*base_filter)
        )
    ).scalar_one() or 0
    km_total = (Decimal(distance_m_total) / Decimal(1000)).quantize(Decimal("0.001"))

    # A split_fare trip is deliberately EXCLUDED from both sums here and
    # attributed leg-by-leg below (backend audit §4, "Gap: split_fare counted
    # 100% as card"). The old `payment_method != "cash"` catch-all put the
    # whole of a part-cash trip into card_total, so a driver who took $30
    # cash and $30 on a card handed over a drawer that was $30 short against
    # the figure this function told the dashboard to expect — the shift then
    # reconciled as a discrepancy every single time.
    countable = (*base_filter, Trip.payment_method != _SPLIT_METHOD)

    cash_total = (
        await session.execute(
            select(func.coalesce(func.sum(Trip.total), 0)).where(
                *countable, Trip.payment_method == _CASH_METHOD
            )
        )
    ).scalar_one() or 0
    card_total = (
        await session.execute(
            select(func.coalesce(func.sum(Trip.total), 0)).where(
                *countable, Trip.payment_method != _CASH_METHOD
            )
        )
    ).scalar_one() or 0

    cash_total = Decimal(str(cash_total))
    card_total = Decimal(str(card_total))

    # Split fares, attributed by their own components. `split_payments` is a
    # JSON list of {method, amount} legs (amounts stringified — see
    # app.services.trips.close_trip), so this cannot be a SQL SUM; the row
    # count is bounded by one shift's trips, which is at most a few dozen.
    # A split trip whose legs are missing entirely (a pre-split_payments row)
    # falls back to counting as card, the old behaviour, rather than
    # silently vanishing from the takings.
    split_rows = (
        await session.execute(
            select(Trip.total, Trip.split_payments).where(
                *base_filter, Trip.payment_method == _SPLIT_METHOD
            )
        )
    ).all()
    for total, legs in split_rows:
        if not legs:
            card_total += Decimal(str(total or 0))
            continue
        for leg in legs:
            amount = Decimal(str(leg.get("amount", 0)))
            if leg.get("method") == _CASH_METHOD:
                cash_total += amount
            else:
                card_total += amount

    return (
        int(trips_count),
        km_total,
        round_half_up(cash_total),
        round_half_up(card_total),
    )


async def _check_device_vehicle_mismatch(
    session: AsyncSession,
    *,
    tenant_id: str,
    actor_user_id: str | None,
    vehicle_id: str,
    device_android_id: str,
    shift_id: str,
) -> str | None:
    """Non-blocking cross-check: does the calling tablet's paired vehicle
    (`fleet.Device.vehicle_id`, set via QR pairing — see app.services.fleet
    module docstring) agree with the vehicle the driver is starting THIS
    shift on? These two records are entirely decoupled (a Device is bound
    only to a Vehicle; a Shift is opened by driver_id/vehicle_id with no
    reference to any Device row at all) and nothing else in the system ever
    compares them, so they can silently drift apart forever — e.g. a tablet
    physically moved to a different car without re-pairing. This is purely
    advisory: it never blocks or alters the shift being started, it only
    writes an audit-log breadcrumb and returns a human-readable warning
    string for the API layer to surface, or None if there's nothing to warn
    about (no device row found, or its vehicle matches).

    `actor_user_id` is the AUTHENTICATED caller (the user hitting the API,
    per `get_current_user`), not `driver_id` from the request body — the
    latter is only a loosely-typed, cross-domain-unconstrained field on
    `Shift` (see app/models/shift.py's own DEVIATION note) that need not
    correspond to a real `users` row, whereas `AuditLog.actor_user_id` has a
    real ForeignKey to `users.id`.

    Queried directly here (not via a `fleet` service/module helper) per the
    task brief, to keep this self-contained in the shift domain while a
    parallel workstream is actively changing `app/models/fleet.py` /
    `app/services/fleet.py` / their API and schema counterparts.
    """
    result = await session.execute(
        select(Device).where(Device.tenant_id == tenant_id, Device.android_id == device_android_id)
    )
    device = result.scalar_one_or_none()
    if device is None or device.vehicle_id is None or device.vehicle_id == vehicle_id:
        return None

    warning = (
        f"This tablet (android_id={device_android_id}) is paired to vehicle "
        f"{device.vehicle_id}, but this shift was started on vehicle {vehicle_id}. "
        "Double-check the tablet is installed in the right car."
    )
    await record_audit(
        session,
        tenant_id=tenant_id,
        actor_user_id=actor_user_id,
        action="shift_device_vehicle_mismatch",
        entity_type="shift",
        entity_id=shift_id,
        after={
            "device_id": device.id,
            "device_android_id": device_android_id,
            "device_vehicle_id": device.vehicle_id,
            "shift_vehicle_id": vehicle_id,
        },
    )
    return warning


async def start_shift(
    session: AsyncSession,
    *,
    tenant_id: str,
    driver_id: str,
    vehicle_id: str,
    start_at: datetime | None,
    inspection_json: dict | None,
    client_uuid: str | None = None,
    force_handover: bool = False,
    device_android_id: str | None = None,
    device_check_actor_user_id: str | None = None,
) -> Shift:
    """Opens a new shift, guarding against the two ways "who is currently
    driving this vehicle" can otherwise go ambiguous on a real fleet where
    one vehicle runs back-to-back 12-hour shifts across two+ drivers:

    1. The SAME driver already has a dangling open shift (they forgot to tap
       "End Shift" last time, or the app crashed). This is common and
       harmless to auto-recover from — a person cannot literally be driving
       two shifts at once, so a fresh start unambiguously means their old
       session is over. Auto-closed at this shift's start_at, aggregates
       recomputed normally via end_shift(), no data lost.

    2. The vehicle already has an open shift under a DIFFERENT driver — e.g.
       driver A's 12-hour shift is still showing open (they forgot to end
       it, or the handover call didn't happen yet) when driver B tries to
       start theirs on the same car. This is NOT auto-resolved: it raises
       ShiftConflictError so the caller sees exactly who the vehicle is
       currently assigned to, unless the caller explicitly passes
       force_handover=True (the real "shift changeover" action — a
       dispatcher confirming the handover, or the outgoing driver having
       just ended their own shift moments before). This is the guard that
       stops two drivers from ever simultaneously "having" the same vehicle
       on paper, which would otherwise make trip attribution and incident
       liability ambiguous.

    If `device_android_id` is given, also runs a non-blocking cross-check
    against `fleet.Device.vehicle_id` (see `_check_device_vehicle_mismatch`)
    — the tablet's paired vehicle and the shift's vehicle_id are entirely
    independent facts recorded in different tables, so nothing else in the
    system ever notices if they disagree. A mismatch never blocks or alters
    this shift; it only writes an audit-log row and sets the returned
    Shift's transient `device_mismatch_warning` attribute (also exposed on
    `ShiftRead`) for the API layer to surface to the driver.

    `vehicle_id` may arrive as a rego string rather than a `Vehicle.id`: the
    Android meter sends `resolvedVehicleUuid ?: vehicleId` and falls back to
    the rego when it could not resolve the UUID offline. It is canonicalised
    to the real `Vehicle.id` up front (see `_canonicalise_vehicle_id`, which
    folds case AND punctuation) -- without this, the guard in (2) compared two
    different spellings of the same car and silently failed to fire. A rego
    that still resolves to nothing is stored VERBATIM, exactly as it was
    before canonicalisation existed, and logged as a warning: this call has
    never been able to 404 and deployed meters treat any refusal as terminal,
    deleting or dead-lettering the driver's queued shift. Degrading is safe,
    refusing is not.

    If `client_uuid` is given this call is IDEMPOTENT on it (see
    `app.models.shift.Shift.client_uuid`), by exactly the same two-part
    pattern `app.api.v1.trips` uses for a trip's client_uuid: an up-front
    read returns any shift already opened under that uuid, and the unique
    `(tenant_id, client_uuid)` constraint catches the concurrent case that
    read cannot, so a replay never opens a second shift. Critically, the
    pre-check runs BEFORE any of the dangling-shift / handover side effects
    below — replaying a start must not re-close a shift the first attempt
    already closed, nor perform a second handover.
    """
    if client_uuid is not None:
        replay = await session.execute(
            select(Shift).where(Shift.tenant_id == tenant_id, Shift.client_uuid == client_uuid)
        )
        existing = replay.scalar_one_or_none()
        if existing is not None:
            logger.info(
                "start_shift: client_uuid %s already opened shift %s — returning it "
                "unchanged rather than starting a second shift.",
                client_uuid,
                existing.id,
            )
            return existing

    # Canonicalise BEFORE anything reads or writes shifts by vehicle: both the
    # dangling-shift auto-close and the vehicle-conflict guard below compare
    # `shifts.vehicle_id` as a plain string, so a rego resolved afterwards
    # would be compared in the wrong alphabet and the guard would keep
    # missing double-bookings. Also placed after the client_uuid replay
    # check, so replaying an old start whose vehicle has since been retired
    # still returns the shift it already opened rather than 404-ing.
    vehicle_id = await _canonicalise_vehicle_id(
        session, tenant_id=tenant_id, vehicle_id=vehicle_id
    )

    effective_start_at = start_at or datetime.now(UTC)

    own_dangling_shift = await _find_open_shift(session, tenant_id=tenant_id, driver_id=driver_id)
    if own_dangling_shift is not None:
        logger.info(
            "start_shift: driver %s had a dangling open shift %s (vehicle %s) — "
            "auto-closing it at this shift's start_at before opening the new one.",
            driver_id,
            own_dangling_shift.id,
            own_dangling_shift.vehicle_id,
        )
        await end_shift(
            session,
            own_dangling_shift,
            end_at=effective_start_at,
            psl_owed=Decimal(0),
            reconciled=False,
        )

    vehicle_conflict = await _find_open_shift(session, tenant_id=tenant_id, vehicle_id=vehicle_id)
    if vehicle_conflict is not None and vehicle_conflict.driver_id != driver_id:
        if not force_handover:
            raise ShiftConflictError(vehicle_conflict)
        logger.info(
            "start_shift: vehicle %s handed over from driver %s (shift %s) to driver %s "
            "via force_handover.",
            vehicle_id,
            vehicle_conflict.driver_id,
            vehicle_conflict.id,
            driver_id,
        )
        await end_shift(
            session,
            vehicle_conflict,
            end_at=effective_start_at,
            psl_owed=Decimal(0),
            reconciled=False,
        )

    shift = Shift(
        tenant_id=tenant_id,
        driver_id=driver_id,
        vehicle_id=vehicle_id,
        start_at=effective_start_at,
        inspection_json=inspection_json,
        client_uuid=client_uuid,
    )
    session.add(shift)
    # Flush (not commit) so shift.id is populated before the device mismatch
    # check needs it as the audit entry's entity_id, while keeping the audit
    # row (if any) in the SAME transaction as the shift creation — see
    # app.services.audit_log.record_audit's own docstring on why it flushes
    # rather than commits.
    try:
        await session.flush()
    except IntegrityError:
        # Lost a race on the unique (tenant_id, client_uuid) constraint: a
        # concurrent replay of this same offline start won. Roll back and
        # return the shift that won, so both callers see one shift — the
        # 409-safe half of the idempotency contract above.
        await session.rollback()
        replay = await session.execute(
            select(Shift).where(Shift.tenant_id == tenant_id, Shift.client_uuid == client_uuid)
        )
        return replay.scalar_one()

    device_mismatch_warning: str | None = None
    if device_android_id is not None:
        device_mismatch_warning = await _check_device_vehicle_mismatch(
            session,
            tenant_id=tenant_id,
            actor_user_id=device_check_actor_user_id,
            vehicle_id=vehicle_id,
            device_android_id=device_android_id,
            shift_id=shift.id,
        )

    await session.commit()
    await session.refresh(shift)
    # Transient (non-mapped) attribute — never persisted, just a way to hand
    # the API layer this advisory warning without changing start_shift's
    # return type. ShiftRead declares a matching optional field so pydantic
    # picks it up via getattr when present, and defaults to None (same as
    # every other caller of start_shift/ShiftRead, which never sets this)
    # when absent — see app.schemas.shift.ShiftRead.device_mismatch_warning.
    shift.device_mismatch_warning = device_mismatch_warning
    return shift


async def end_shift(
    session: AsyncSession,
    shift: Shift,
    *,
    end_at: datetime | None,
    psl_owed: Decimal,
    reconciled: bool,
) -> Shift:
    """Closes a shift: stamps `end_at`, recomputes the four trip-derived
    aggregates from that shift's own trips, and records the reconciliation
    figures supplied by the caller."""
    trips_count, km_total, cash_total, card_total = await _recompute_trip_aggregates(
        session, tenant_id=shift.tenant_id, shift_id=shift.id
    )

    shift.end_at = end_at or datetime.now(UTC)
    shift.trips_count = trips_count
    shift.km_total = km_total
    shift.cash_total = cash_total
    shift.card_total = card_total
    shift.psl_owed = psl_owed
    shift.reconciled = reconciled

    await session.commit()
    await session.refresh(shift)

    # The cab is no longer working: say so on the Live Map. Every close path in
    # the system funnels through here (driver cash-up, auto-close, heartbeat
    # timeout), which is why the correction lives at this choke point rather
    # than in any one caller -- see `live_ops.mark_vehicle_offline` for the
    # ghost-vehicle finding this fixes. Imported locally: `app.services.live_ops`
    # is a heavier module that reads this package's own models, and a top-level
    # import here would make that a mutual one.
    from app.services.live_ops import mark_vehicle_offline

    await mark_vehicle_offline(shift.tenant_id, shift.vehicle_id)
    return shift


async def refresh_trip_aggregates(session: AsyncSession, shift: Shift) -> Shift:
    """Re-derives the four trip-derived aggregates from the shift's closed
    trips AS THEY ARE NOW and stores them on the row. `end_shift` computes
    them once at cash-up and nothing recomputed them afterwards, so a fare
    corrected after the shift closed (`POST /v1/trips/{id}/fare-correction`)
    left the shift showing the pre-correction cash. Does NOT commit — the
    caller's transaction owns that."""
    trips_count, km_total, cash_total, card_total = await _recompute_trip_aggregates(
        session, tenant_id=shift.tenant_id, shift_id=shift.id
    )
    shift.trips_count = trips_count
    shift.km_total = km_total
    shift.cash_total = cash_total
    shift.card_total = card_total
    return shift


def mark_needs_reconciliation(shift: Shift, *, reason: str) -> Shift:
    """Flips a shift back to unreconciled and records why on
    `reconciliation_note` (appended, newest last, so a twice-corrected shift
    keeps both reasons). The driver's counted cash was checked against
    figures that have since changed; the check has to happen again."""
    shift.reconciled = False
    shift.reconciliation_note = (
        f"{shift.reconciliation_note} | {reason}" if shift.reconciliation_note else reason
    )
    return shift


async def start_break(session: AsyncSession, shift: Shift) -> Shift:
    """Starts a break on `shift`: stamps `break_started_at` = now. The caller
    (the router) owns the 409 conflict checks -- break already in progress,
    or the shift already ended -- before calling this, mirroring how
    `start`/`end` in app/api/v1/shifts.py own the end_at-already-set check
    around `end_shift` above."""
    shift.break_started_at = datetime.now(UTC)
    await session.commit()
    await session.refresh(shift)
    return shift


async def end_break(session: AsyncSession, shift: Shift) -> Shift:
    """Ends the in-progress break on `shift`: clears `break_started_at` back
    to None and flips `break_taken` to True. Deliberately just one break slot
    per shift, not a break-history log -- see app.models.shift.Shift's
    DEVIATION note. The caller owns the 409 check (no break in progress)
    before calling this."""
    shift.break_started_at = None
    shift.break_taken = True
    await session.commit()
    await session.refresh(shift)
    return shift


async def close_open_shifts_for_vehicle_deletion(
    session: AsyncSession, *, tenant_id: str, vehicle_id: str, actor_user_id: str | None
) -> list[Shift]:
    """Closes every currently-open shift on a vehicle that is about to be
    deleted, instead of leaving it open forever, pointing at a `vehicle_id`
    that no longer exists in the fleet register (the real production bug this
    fixes: `DELETE /v1/fleet/vehicles/{id}` used to leave dangling open
    shifts, which then rendered as raw UUIDs on the dashboard's drivers list
    where a rego should be — see `DriversPanel.tsx` / `format.ts`).

    DECISION (two defensible options existed — CLOSE vs REFUSE the vehicle
    delete; this is the one chosen, called from `DELETE
    /v1/fleet/vehicles/{id}` in `app/api/v1/fleet.py`): an open shift is live
    OPERATIONAL STATE — "who is currently driving this vehicle right now" —
    not historical EVIDENCE the way a PSL ledger entry or an uploaded
    compliance document is (contrast `app.services.user
    .assert_user_deletable`, which correctly REFUSES those: "each of these
    rows is evidence THAT SOMETHING HAPPENED", independent of whether the
    vehicle/driver is later deleted). A driver cannot literally keep driving
    a vehicle that has just been removed from the fleet register, so an open
    shift surviving the vehicle's deletion is already the LESS truthful
    record of the two — it goes on silently claiming a live session against
    a vehicle_id nothing else in the system can resolve. Closing it produces
    the more truthful record for a fare-regulated operator: "this shift
    ended when its vehicle was deleted", not "this shift is still open"
    (false) or a delete that's refused forever until a dispatcher notices and
    manually ends the shift first (needlessly blocks a legitimate fleet
    change for what is, after all, still just live state, not evidence).

    This also mirrors a precedent already established in this exact module:
    `start_shift` auto-closes a driver's OWN dangling open shift rather than
    refusing to let them start a new one, using the identical
    `end_shift(..., psl_owed=Decimal(0), reconciled=False)` call below.

    `psl_owed=Decimal(0)` / `reconciled=False`: the four trip-derived
    aggregates (trips_count/km_total/cash_total/card_total) are recomputed
    HONESTLY from the shift's own real trips inside `end_shift()` — nothing
    here fabricates those. But `psl_owed`/`reconciled` are ordinarily figures
    the DRIVER supplies at end-of-shift (their physical cash count vs the
    system total); this is an involuntary, admin-triggered closure with no
    driver present to supply them, so `psl_owed` stays at zero and
    `reconciled` is explicitly False — honestly flagging "nobody reconciled
    this shift" for a dispatcher/owner to follow up on, rather than
    pretending a reconciliation happened that didn't.

    Every closure is also recorded to the tamper-evident audit log
    (`action="shift_force_closed_vehicle_deleted"`) so there is a permanent,
    hash-chained record of WHY this shift ended when it did, distinct from an
    ordinary driver-initiated `POST /v1/shifts/{id}/end` — "recording why",
    per the task brief. Returns the list of shifts that were closed (empty if
    none were open) purely for the caller's own logging/response purposes.
    """
    result = await session.execute(
        select(Shift).where(
            Shift.tenant_id == tenant_id, Shift.vehicle_id == vehicle_id, Shift.end_at.is_(None)
        )
    )
    open_shifts = result.scalars().all()
    for shift in open_shifts:
        logger.info(
            "delete_vehicle: vehicle %s has open shift %s (driver %s) — force-closing it as "
            "unreconciled before the vehicle row is removed.",
            vehicle_id,
            shift.id,
            shift.driver_id,
        )
        await end_shift(session, shift, end_at=None, psl_owed=Decimal(0), reconciled=False)
        await record_audit(
            session,
            tenant_id=tenant_id,
            actor_user_id=actor_user_id,
            action="shift_force_closed_vehicle_deleted",
            entity_type="shift",
            entity_id=shift.id,
            before={"end_at": None, "reconciled": False},
            after={
                "end_at": shift.end_at.isoformat() if shift.end_at else None,
                "reconciled": False,
                "reason": "vehicle_deleted",
                "vehicle_id": vehicle_id,
            },
        )
    return list(open_shifts)


async def close_open_shifts_for_driver_deletion(
    session: AsyncSession, *, tenant_id: str, driver_id: str, actor_user_id: str | None
) -> list[Shift]:
    """Closes every currently-open shift belonging to a driver who is about to
    be deleted. The exact counterpart of
    `close_open_shifts_for_vehicle_deletion` above, one axis over — read
    that function's DECISION note first; every word of its reasoning applies
    here unchanged, and this function exists rather than a `vehicle_id or
    driver_id` parameter on that one purely so the audit action, the log line
    and the recorded reason can each name what actually happened.

    The gap this closes (backend audit §4/§6): `app.services.fleet_wipe
    .force_wipe_fleet` already routed every VEHICLE it deleted through
    `close_open_shifts_for_vehicle_deletion`, but deleted DRIVERS without
    touching their shifts at all. A wipe therefore left behind open shifts
    pointing at a driver_id that no longer resolves — the same dangling-open-
    shift bug that was already fixed for vehicles, still live on the other
    side of the same operation, and worse here because a wipe deletes every
    driver at once.

    In practice most of a wiped tenant's open shifts are caught by the vehicle
    pass first (a shift has both a vehicle and a driver, and vehicles are
    deleted before drivers). This catches the remainder: a shift whose
    vehicle_id no longer matches any live vehicle row, or that was force-wiped
    in a tenant whose vehicles failed to delete.

    Same honest-closure contract as the vehicle case: `psl_owed=Decimal(0)`,
    `reconciled=False`, trip-derived aggregates recomputed for real inside
    `end_shift`, and a hash-chained audit entry
    (`action="shift_force_closed_driver_deleted"`) recording WHY the shift
    ended when it did. Does not commit — the caller owns the transaction,
    matching `force_wipe_fleet`'s per-row-delete/outer-commit pattern.
    """
    result = await session.execute(
        select(Shift).where(
            Shift.tenant_id == tenant_id, Shift.driver_id == driver_id, Shift.end_at.is_(None)
        )
    )
    open_shifts = result.scalars().all()
    for shift in open_shifts:
        logger.info(
            "delete_driver: driver %s has open shift %s (vehicle %s) — force-closing it as "
            "unreconciled before the driver row is removed.",
            driver_id,
            shift.id,
            shift.vehicle_id,
        )
        await end_shift(session, shift, end_at=None, psl_owed=Decimal(0), reconciled=False)
        await record_audit(
            session,
            tenant_id=tenant_id,
            actor_user_id=actor_user_id,
            action="shift_force_closed_driver_deleted",
            entity_type="shift",
            entity_id=shift.id,
            before={"end_at": None, "reconciled": False},
            after={
                "end_at": shift.end_at.isoformat() if shift.end_at else None,
                "reconciled": False,
                "reason": "driver_deleted",
                "driver_id": driver_id,
            },
        )
    return list(open_shifts)


async def build_report(session: AsyncSession, shift: Shift) -> dict:
    """Builds the JSON summary payload for `GET /v1/shifts/{id}/report`.

    Real PDF/CSV export lives below: `render_report_pdf`/`render_report_csv`
    render this same dict (wrapped as a `ShiftReport`) without changing this
    function's contract.

    The trip-derived figures are read LIVE from the shift's closed trips when
    it has any (admin-panel plan 1.2: the stored aggregates are a snapshot
    taken at cash-up, and a fare correction after that point must show on
    the report). A shift with no closed trips on record keeps its stored
    figures — an admin-backfilled shift (`POST /v1/shifts` with explicit
    totals) has no trips to derive anything from, and zeroing it would be
    wrong.
    """
    duration_minutes: float | None = None
    if shift.end_at is not None:
        duration_minutes = round((shift.end_at - shift.start_at).total_seconds() / 60, 2)

    trips_count, km_total, cash_total, card_total = await _recompute_trip_aggregates(
        session, tenant_id=shift.tenant_id, shift_id=shift.id
    )
    if trips_count == 0:
        trips_count, km_total, cash_total, card_total = (
            shift.trips_count,
            shift.km_total,
            shift.cash_total,
            shift.card_total,
        )

    return {
        "shift_id": shift.id,
        "tenant_id": shift.tenant_id,
        "driver_id": shift.driver_id,
        "vehicle_id": shift.vehicle_id,
        "start_at": shift.start_at,
        "end_at": shift.end_at,
        "duration_minutes": duration_minutes,
        "trips_count": trips_count,
        "km_total": km_total,
        "cash_total": cash_total,
        "card_total": card_total,
        "total_takings": round_half_up(cash_total + card_total),
        "psl_owed": shift.psl_owed,
        "reconciled": shift.reconciled,
        "reconciliation_note": shift.reconciliation_note,
        "inspection_json": shift.inspection_json,
        "generated_at": datetime.now(UTC),
    }


# --- PDF / CSV export ---------------------------------------------------------


def _fmt(amount: Decimal) -> str:
    return f"${amount:.2f}"


def _report_row(pdf: FPDF, label: str, value) -> None:
    pdf.set_font("Helvetica", "B", 10)
    pdf.cell(60, 6, label)
    pdf.set_font("Helvetica", "", 10)
    pdf.cell(0, 6, str(value), new_x="LMARGIN", new_y="NEXT")


def render_report_pdf(report: ShiftReport) -> bytes:
    """Pure rendering step (no I/O): one-page PDF laying out every
    ShiftReport field, mirroring app.services.receipts._render_pdf_bytes's
    simple header plus labeled-rows visual style."""
    pdf = FPDF(format="A4")
    pdf.set_auto_page_break(auto=True, margin=15)
    pdf.add_page()

    pdf.set_font("Helvetica", "B", 18)
    pdf.cell(0, 10, "SHIFT REPORT", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", "", 8)
    pdf.set_text_color(120, 120, 120)
    pdf.cell(0, 5, f"Shift ID: {report.shift_id}", new_x="LMARGIN", new_y="NEXT")
    pdf.cell(0, 5, f"Tenant ID: {report.tenant_id}", new_x="LMARGIN", new_y="NEXT")
    pdf.set_text_color(0, 0, 0)
    pdf.ln(3)
    pdf.set_font("Helvetica", "B", 11)
    pdf.cell(0, 7, "Driver & Vehicle", new_x="LMARGIN", new_y="NEXT")
    _report_row(pdf, "Driver ID", report.driver_id)
    _report_row(pdf, "Vehicle ID", report.vehicle_id)
    pdf.ln(3)

    pdf.set_font("Helvetica", "B", 11)
    pdf.cell(0, 7, "Timing", new_x="LMARGIN", new_y="NEXT")
    _report_row(pdf, "Start", report.start_at.strftime("%Y-%m-%d %H:%M UTC"))
    _report_row(
        pdf, "End", report.end_at.strftime("%Y-%m-%d %H:%M UTC") if report.end_at else "-"
    )
    _report_row(
        pdf,
        "Duration (minutes)",
        report.duration_minutes if report.duration_minutes is not None else "-",
    )
    pdf.ln(3)

    pdf.set_font("Helvetica", "B", 11)
    pdf.cell(0, 7, "Trip Summary", new_x="LMARGIN", new_y="NEXT")
    _report_row(pdf, "Trips", report.trips_count)
    _report_row(pdf, "Distance (km)", f"{report.km_total:.3f}")
    pdf.ln(3)

    pdf.set_font("Helvetica", "B", 11)
    pdf.cell(0, 7, "Takings", new_x="LMARGIN", new_y="NEXT")
    _report_row(pdf, "Cash total", _fmt(report.cash_total))
    _report_row(pdf, "Card total", _fmt(report.card_total))
    pdf.set_font("Helvetica", "B", 12)
    pdf.cell(60, 8, "Total takings")
    pdf.cell(0, 8, _fmt(report.total_takings), new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", "", 10)
    _report_row(pdf, "PSL owed", _fmt(report.psl_owed))
    _report_row(pdf, "Reconciled", "Yes" if report.reconciled else "No")
    pdf.ln(3)
    pdf.set_font("Helvetica", "B", 11)
    pdf.cell(0, 7, "Pre-Shift Inspection", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", "", 10)
    if report.inspection_json:
        for key, value in report.inspection_json.items():
            _report_row(pdf, str(key), value)
    else:
        pdf.cell(0, 6, "No inspection checklist recorded.", new_x="LMARGIN", new_y="NEXT")
    pdf.ln(3)

    pdf.set_font("Helvetica", "", 8)
    pdf.set_text_color(120, 120, 120)
    pdf.cell(
        0,
        5,
        f"Generated: {report.generated_at.strftime('%Y-%m-%d %H:%M UTC')}",
        new_x="LMARGIN",
        new_y="NEXT",
    )

    return bytes(pdf.output())


_REPORT_CSV_FIELDS = (
    "shift_id",
    "tenant_id",
    "driver_id",
    "vehicle_id",
    "start_at",
    "end_at",
    "duration_minutes",
    "trips_count",
    "km_total",
    "cash_total",
    "card_total",
    "total_takings",
    "psl_owed",
    "reconciled",
    "inspection_json",
    "generated_at",
)


def render_report_csv(report: ShiftReport) -> str:
    """Renders `report` as a one-row CSV (header row plus one value row) via
    the stdlib `csv` module, mirroring app.api.v1.reports._ptp_rows_to_csv's
    io.StringIO plus csv.writer pattern, no new dependency. Same field set as
    render_report_pdf above, in ShiftReport declaration order."""
    buffer = io.StringIO()
    writer = csv.writer(buffer)
    writer.writerow(_REPORT_CSV_FIELDS)
    writer.writerow(
        [
            report.shift_id,
            report.tenant_id,
            report.driver_id,
            report.vehicle_id,
            report.start_at.isoformat(),
            report.end_at.isoformat() if report.end_at else "",
            report.duration_minutes if report.duration_minutes is not None else "",
            report.trips_count,
            report.km_total,
            report.cash_total,
            report.card_total,
            report.total_takings,
            report.psl_owed,
            report.reconciled,
            json.dumps(report.inspection_json) if report.inspection_json else "",
            report.generated_at.isoformat(),
        ]
    )
    return buffer.getvalue()
