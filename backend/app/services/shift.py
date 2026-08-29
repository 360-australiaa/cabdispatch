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
from datetime import UTC, datetime
from decimal import Decimal

from fpdf import FPDF
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.fleet import Vehicle
from app.models.shift import Shift
from app.models.user import (
    ROLE_ADMIN,
    ROLE_DISPATCHER,
    ROLE_DRIVER,
    ROLE_OWNER,
    SUITABILITY_CLEAR,
    User,
)
from app.models.vehicle_assignment import VehicleAssignment
from app.schemas.shift import ShiftReport
from app.services.audit_log import record_audit
from app.services.fare_engine import round_half_up
from app.services import vehicle_readiness as vehicle_readiness_service

logger = logging.getLogger("cab_dispatch.shift")

_DISPATCH_ROLES = (ROLE_OWNER, ROLE_ADMIN, ROLE_DISPATCHER)


# --- start_shift validation errors (mirrors app.services.fleet's FleetError
# style exactly -- a plain base class per domain, one subclass per distinct
# failure, the router translates each subclass to an HTTP status). ------------


class ShiftError(Exception):
    """Base class for shift-domain errors; the router translates each
    subclass to the appropriate HTTP status."""


class ShiftDriverNotFoundError(ShiftError):
    """Gate (a) part 1: driver_id does not resolve to a User row in this
    tenant. Router maps this to 404."""


class ShiftDriverNotEligibleError(ShiftError):
    """Gate (a) part 2: the driver row exists but role != "driver" or
    status != "active". Router maps this to 422."""


class ShiftDriverLicenceExpiredError(ShiftError):
    """Gate (b) part 1: User.driver_license_expiry is a real, in-the-past
    date. A None expiry is "unknown" and fails OPEN (does not raise this),
    matching the codebase-wide convention already documented on
    User.driver_license_expiry. Router maps this to 422."""


class ShiftDriverAuthorityExpiredError(ShiftError):
    """Gate (b) part 2: User.driver_authority_expiry is a real, in-the-past
    date. Same fail-open-on-None convention as licence above. Router maps
    this to 422."""


class ShiftDriverNotSuitableError(ShiftError):
    """Gate (c): User.suitability_status != SUITABILITY_CLEAR. Router maps
    this to 422."""


class ShiftVehicleNotFoundError(ShiftError):
    """Gate (d): vehicle_id (after canonicalization) does not resolve to a
    Vehicle row in this tenant. Router maps this to 404."""


class ShiftVehicleNotOperationalError(ShiftError):
    """Gate (e): app.services.vehicle_readiness.get_vehicle_readiness
    reports the vehicle is not operational. Carries every failing reason
    (not just the first) via `.reasons`, same contract as
    VehicleNotOperationalError in that module. Router maps this to 422."""

    def __init__(self, reasons: list[str]) -> None:
        self.reasons = reasons
        super().__init__("Vehicle is not operational: " + "; ".join(reasons))


class ShiftDriverNotAuthorisedForVehicleError(ShiftError):
    """Gate (f): no active (revoked_at IS NULL) VehicleAssignment row exists
    for this exact (vehicle_id, driver_id) pair -- the driver is not on this
    vehicle's roster. Router maps this to 422."""


class ShiftDriverAlreadyOnShiftError(ShiftError):
    """Gate (g), driver side: this driver already has an open shift
    (end_at IS NULL) on a DIFFERENT vehicle (or this one). Defensive
    pre-check -- the real enforcement is the DB partial unique index
    uq_shifts_one_open_per_driver; this just produces a clean error for the
    common non-racing case. Router maps this to 409."""


class ShiftVehicleAlreadyInUseError(ShiftError):
    """Gate (g), vehicle side: this vehicle already has an open shift
    (end_at IS NULL), for this driver or another one. Defensive pre-check --
    the real enforcement is the DB partial unique index
    uq_shifts_one_open_per_vehicle. Router maps this to 409."""


class ShiftConflictError(ShiftError):
    """Gate (g) race backstop: the defensive pre-check
    (ShiftDriverAlreadyOnShiftError / ShiftVehicleAlreadyInUseError) passed,
    but the final INSERT still hit one of the D-1 partial unique indexes
    (uq_shifts_one_open_per_vehicle / uq_shifts_one_open_per_driver) because
    a concurrent request won the race between the pre-check and the commit --
    this is the by-construction guarantee WP-31 exists for. Router maps this
    to 409, same status as the pre-check errors it stands in for."""


class ShiftCallerNotAuthorisedError(ShiftError):
    """Gate (h): the authenticated caller is neither the driver themselves
    nor a dispatcher/admin/owner in this tenant -- a driver cannot open a
    shift for a DIFFERENT driver, and a random authenticated user cannot
    open shifts for other people. This is the actual fix for the "any
    authenticated user can open a shift for anyone" gap documented in the
    architecture plan (Part 1.3). Router maps this to 403."""


class EndShiftOpenTripsError(ShiftError):
    """WP-33: `end_shift` refuses to close a shift while it still has an
    open (status == "open") Trip -- the same rule
    `app.services.shift_handover.perform_handover` already enforces on the
    OUTGOING shift of a handover (see `has_open_trip` below, shared by both
    call sites rather than two independently-written checks). Router maps
    this to 409."""

# Payment methods treated as "cash" for the cash-vs-card reconciliation split.
# Everything else observed on a trip (tap_to_pay, link, cabcharge, ttss, ...) is
# counted into card_total. Mirrors the `payments.method` enum in the product spec.
_CASH_METHOD = "cash"


async def _recompute_trip_aggregates(
    session: AsyncSession, *, tenant_id: str, shift_id: str
) -> tuple[int, Decimal, Decimal, Decimal]:
    """Returns (trips_count, km_total, cash_total, card_total) computed fresh
    from the shift's own closed trips. Authoritative — never trusts client input."""
    try:
        from app.models.trips import Trip
    except ImportError:  # pragma: no cover - defensive only, see module docstring
        logger.warning(
            "app.models.trips.Trip not importable — leaving shift %s aggregates at "
            "zero. Expected only if this domain is exercised before the trips "
            "domain is present in the tree.",
            shift_id,
        )
        return 0, Decimal(0), Decimal(0), Decimal(0)

    base_filter = (Trip.tenant_id == tenant_id, Trip.shift_id == shift_id)

    trips_count = (
        await session.execute(select(func.count(Trip.id)).where(*base_filter))
    ).scalar_one() or 0

    distance_m_total = (
        await session.execute(
            select(func.coalesce(func.sum(Trip.distance_m), 0)).where(*base_filter)
        )
    ).scalar_one() or 0
    km_total = (Decimal(distance_m_total) / Decimal(1000)).quantize(Decimal("0.001"))

    cash_total = (
        await session.execute(
            select(func.coalesce(func.sum(Trip.total), 0)).where(
                *base_filter, Trip.payment_method == _CASH_METHOD
            )
        )
    ).scalar_one() or 0
    card_total = (
        await session.execute(
            select(func.coalesce(func.sum(Trip.total), 0)).where(
                *base_filter, Trip.payment_method != _CASH_METHOD
            )
        )
    ).scalar_one() or 0

    return (
        int(trips_count),
        km_total,
        round_half_up(Decimal(str(cash_total))),
        round_half_up(Decimal(str(card_total))),
    )


async def _canonical_vehicle_id(session: AsyncSession, *, tenant_id: str, vehicle_id: str) -> str:
    """Resolves vehicle_id to the vehicle's real UUID whether the caller sent
    the UUID or the rego (e.g. "KHI-01"). NOTE (found by the Android-side
    agent, 2026-08-28, verified live against the real device and server): the
    two client call sites disagreed on which shape they sent for the same
    physical vehicle -- POST /v1/shifts/start was sending the rego while
    POST /v1/fleet/positions sends the fleet-vehicle UUID. If shifts.vehicle_id
    is allowed to hold either shape interchangeably, D-1's partial unique
    index (uq_shifts_one_open_per_vehicle on (tenant_id, vehicle_id) WHERE
    end_at IS NULL) cannot actually catch two open shifts on one vehicle if
    they were opened with different-shaped identifiers -- the row values
    would differ even though the vehicle is the same. Canonicalizing here,
    before the Shift row is ever constructed, is what makes that index's
    guarantee real. An unrecognized id (never matches either column) passes
    through unchanged rather than raising, so an unresolved/offline-created
    vehicle_id does not hard-fail shift start -- it just does not get the
    uniqueness guarantee until the id is corrected."""
    row = (
        await session.execute(
            select(Vehicle.id).where(
                Vehicle.tenant_id == tenant_id,
                (Vehicle.id == vehicle_id) | (Vehicle.rego == vehicle_id),
            )
        )
    ).scalar_one_or_none()
    return row or vehicle_id


async def is_driver_authorised_for_vehicle(
    session: AsyncSession, *, tenant_id: str, vehicle_id: str, driver_id: str
) -> bool:
    """Gate (f) helper: is there a currently-active (revoked_at IS NULL)
    VehicleAssignment row for this exact (vehicle_id, driver_id) pair --
    i.e. is this driver on this vehicle's roster right now. Nothing existing
    in app.services.fleet does exactly this single-pair check (that domain's
    authorise_driver/revoke_authorisation manage the roster; this is a plain
    read), so it lives here as a small reusable helper for start_shift."""
    result = await session.execute(
        select(VehicleAssignment.id).where(
            VehicleAssignment.tenant_id == tenant_id,
            VehicleAssignment.vehicle_id == vehicle_id,
            VehicleAssignment.driver_id == driver_id,
            VehicleAssignment.revoked_at.is_(None),
        )
    )
    return result.scalar_one_or_none() is not None


async def has_open_trip(session: AsyncSession, *, tenant_id: str, shift_id: str) -> bool:
    """Is there a currently-open (status == "open") Trip on this shift.

    WP-33: extracted from `app.services.shift_handover`'s previously
    module-local `_has_open_trip` (originally written for the handover's
    outgoing-shift guard) so `end_shift` below and
    `app.services.shift_handover.perform_handover` share ONE implementation
    of "does this shift still have a trip in progress" rather than two
    independently-driftable copies -- same reasoning as
    `validate_driver_for_vehicle`/`is_caller_authorised_for_driver` above.
    `app.services.shift_handover` now imports this instead of defining its
    own.

    Wrapped the same way `_recompute_trip_aggregates` wraps its own import
    of the sibling trips domain, so this module degrades gracefully (treated
    as "no open trip", i.e. does not block the caller) rather than hard-
    crashing if `app.models.trips` is ever absent from the tree."""
    try:
        from app.models.trips import TRIP_STATUS_OPEN, Trip
    except ImportError:  # pragma: no cover - defensive only, see docstring
        logger.warning(
            "app.models.trips.Trip not importable — cannot check for an open "
            "trip on shift %s; treating as no open trip.",
            shift_id,
        )
        return False

    result = await session.execute(
        select(Trip.id).where(
            Trip.tenant_id == tenant_id,
            Trip.shift_id == shift_id,
            Trip.status == TRIP_STATUS_OPEN,
        )
    )
    return result.scalar_one_or_none() is not None


def _is_expired_date(expiry, *, today) -> bool:
    """Same fail-open-on-None convention as app.services.vehicle_readiness's
    module-level `_is_expired` -- None means "unknown", never blocks."""
    return expiry is not None and expiry < today


async def validate_driver_for_vehicle(
    session: AsyncSession, *, tenant_id: str, driver_id: str, vehicle_id: str
) -> tuple[User, str]:
    """Gates (a)-(f) of the shift-opening validation chain, extracted so
    `start_shift` and `app.services.shift_handover.perform_handover` share
    ONE implementation rather than maintaining two independently-driftable
    copies of "is this driver allowed to get behind the wheel of this
    vehicle right now" (WP-32 task brief, step 3e). Runs, in order:
    driver exists/role/status (a), licence+authority unexpired (b),
    suitability clear (c), vehicle exists (d), vehicle operational (e),
    driver on the vehicle's roster (f). Raises the same ShiftError subclass
    each gate always raised before this extraction -- callers (both
    start_shift and perform_handover) translate those to HTTP the same way.

    Returns `(driver, canonical_vehicle_id)` -- the loaded `User` row (the
    caller may need fields off it, e.g. for an audit snapshot) and
    `vehicle_id` resolved through `_canonical_vehicle_id` (see that
    function's own docstring for why this matters for the D-1 uniqueness
    guarantee).
    """
    today = datetime.now(UTC).date()

    canonical_vehicle_id = await _canonical_vehicle_id(
        session, tenant_id=tenant_id, vehicle_id=vehicle_id
    )

    # --- gate (a): driver exists, role == driver, status == active ---------
    driver_result = await session.execute(
        select(User).where(User.id == driver_id, User.tenant_id == tenant_id)
    )
    driver = driver_result.scalar_one_or_none()
    if driver is None:
        raise ShiftDriverNotFoundError(driver_id)
    if driver.role != ROLE_DRIVER or driver.status != "active":
        raise ShiftDriverNotEligibleError(
            f"User {driver_id} has role='{driver.role}' status='{driver.status}' "
            "-- must be role='driver' and status='active' to open a shift"
        )

    # --- gate (b): licence + authority unexpired (None == unknown, fails open) ---
    if _is_expired_date(driver.driver_license_expiry, today=today):
        raise ShiftDriverLicenceExpiredError(
            f"Driver {driver_id}'s licence expired on {driver.driver_license_expiry}"
        )
    if _is_expired_date(driver.driver_authority_expiry, today=today):
        raise ShiftDriverAuthorityExpiredError(
            f"Driver {driver_id}'s authority expired on {driver.driver_authority_expiry}"
        )

    # --- gate (c): suitability clear ---------------------------------------
    if driver.suitability_status != SUITABILITY_CLEAR:
        raise ShiftDriverNotSuitableError(
            f"Driver {driver_id}'s suitability_status is '{driver.suitability_status}', "
            f"not '{SUITABILITY_CLEAR}'"
        )

    # --- gate (d): vehicle exists -------------------------------------------
    vehicle_result = await session.execute(
        select(Vehicle.id).where(
            Vehicle.id == canonical_vehicle_id, Vehicle.tenant_id == tenant_id
        )
    )
    if vehicle_result.scalar_one_or_none() is None:
        raise ShiftVehicleNotFoundError(canonical_vehicle_id)

    # --- gate (e): vehicle operational --------------------------------------
    readiness = await vehicle_readiness_service.get_vehicle_readiness(
        session, tenant_id=tenant_id, vehicle_id=canonical_vehicle_id
    )
    if not readiness.operational:
        raise ShiftVehicleNotOperationalError(readiness.reasons)

    # --- gate (f): driver is on the vehicle's roster ------------------------
    authorised = await is_driver_authorised_for_vehicle(
        session, tenant_id=tenant_id, vehicle_id=canonical_vehicle_id, driver_id=driver_id
    )
    if not authorised:
        raise ShiftDriverNotAuthorisedForVehicleError(
            f"Driver {driver_id} is not on vehicle {canonical_vehicle_id}'s roster"
        )

    return driver, canonical_vehicle_id


def is_caller_authorised_for_driver(
    *, caller_user_id: str, driver_id: str, caller_role: str | None
) -> bool:
    """Gate (h) rule, extracted so both `start_shift` and
    `app.services.shift_handover.perform_handover` share one definition of
    "may this caller act on behalf of this driver": the caller IS the
    driver, or the caller is a dispatcher/admin/owner. `caller_role` is
    `None` when the caller_user_id could not be resolved to a User row at
    all (treated as not authorised, same as any other unrecognised role)."""
    return caller_user_id == driver_id or caller_role in _DISPATCH_ROLES


async def start_shift(
    session: AsyncSession,
    *,
    tenant_id: str,
    driver_id: str,
    vehicle_id: str,
    start_at: datetime | None,
    inspection_json: dict | None,
    caller_user_id: str,
    odometer_start: int | None = None,
) -> Shift:
    """Opens a new shift -- full validation (plan D-1/WP-30/WP-31), replacing
    the previous 5-line unvalidated version. Every gate below raises a
    specific ShiftError subclass (see the top of this module) that the
    router translates to an HTTP status; gates run in the exact order listed
    in the work package brief (a-h). The DB partial unique indexes on
    app.models.shift.Shift (uq_shifts_one_open_per_vehicle /
    uq_shifts_one_open_per_driver) are the actual by-construction guarantee
    against double-booking -- gate (g) below is a defensive pre-check that
    produces a clean 409 in the common non-racing case; a genuine race still
    hits the DB constraint and surfaces as an IntegrityError the router must
    handle separately (see app.api.v1.shifts)."""
    # --- gates (a)-(f): driver+vehicle eligibility, see validate_driver_for_vehicle ---
    _driver, canonical_vehicle_id = await validate_driver_for_vehicle(
        session, tenant_id=tenant_id, driver_id=driver_id, vehicle_id=vehicle_id
    )

    # --- gate (g): neither driver nor vehicle already has an open shift -----
    # (defensive pre-check -- see docstring; the DB partial unique indexes
    # are the real enforcement against a race).
    driver_open_result = await session.execute(
        select(Shift.id).where(
            Shift.tenant_id == tenant_id,
            Shift.driver_id == driver_id,
            Shift.end_at.is_(None),
        )
    )
    if driver_open_result.scalar_one_or_none() is not None:
        raise ShiftDriverAlreadyOnShiftError(
            f"Driver {driver_id} already has an open shift"
        )

    vehicle_open_result = await session.execute(
        select(Shift.id).where(
            Shift.tenant_id == tenant_id,
            Shift.vehicle_id == canonical_vehicle_id,
            Shift.end_at.is_(None),
        )
    )
    if vehicle_open_result.scalar_one_or_none() is not None:
        raise ShiftVehicleAlreadyInUseError(
            f"Vehicle {canonical_vehicle_id} already has an open shift"
        )

    # --- gate (h): caller is the driver themselves, or a dispatcher/admin/owner ---
    if caller_user_id != driver_id:
        caller_result = await session.execute(
            select(User.role).where(User.id == caller_user_id, User.tenant_id == tenant_id)
        )
        caller_role = caller_result.scalar_one_or_none()
        if caller_role not in _DISPATCH_ROLES:
            raise ShiftCallerNotAuthorisedError(
                f"Caller {caller_user_id} may not open a shift for a different "
                f"driver ({driver_id}) -- must be the driver themselves or a "
                "dispatcher/admin/owner"
            )

    shift = Shift(
        tenant_id=tenant_id,
        driver_id=driver_id,
        vehicle_id=canonical_vehicle_id,
        start_at=start_at or datetime.now(UTC),
        inspection_json=inspection_json,
        odometer_start=odometer_start,
    )
    session.add(shift)
    try:
        # The INSERT is actually issued here (flush), not at commit() --
        # sqlite/postgres both enforce the partial unique index immediately
        # on INSERT, so the race window this whole try/except guards against
        # can surface as early as this flush, not just the final commit.
        await session.flush()  # also populates shift.id before the audit row below
        await record_audit(
            session,
            tenant_id=tenant_id,
            actor_user_id=caller_user_id,
            action="shift_started",
            entity_type="shift",
            entity_id=shift.id,
            before=None,
            after={
                "driver_id": driver_id,
                "vehicle_id": canonical_vehicle_id,
                "caller_user_id": caller_user_id,
                "start_at": shift.start_at.isoformat(),
            },
        )
        await session.commit()
    except IntegrityError as exc:
        await session.rollback()
        raise ShiftConflictError(
            "A concurrent request already opened a shift for this driver or "
            "vehicle -- the D-1 partial unique index rejected this one."
        ) from exc
    await session.refresh(shift)
    return shift


async def end_shift(
    session: AsyncSession,
    shift: Shift,
    *,
    end_at: datetime | None,
    psl_owed: Decimal,
    reconciled: bool,
    actor_user_id: str | None = None,
    odometer_end: int | None = None,
    end_inspection_json: dict | None = None,
) -> Shift:
    """Closes a shift: stamps `end_at`, recomputes the four trip-derived
    aggregates from that shift's own trips, and records the reconciliation
    figures supplied by the caller. Audit-logs the close (action=
    "shift_ended", WP-34) when `actor_user_id` is supplied -- optional /
    defaulting to None so any other internal caller of this function
    (currently none, but kept consistent with record_audit's own
    actor_user_id: str | None contract) doesn't need a real user.

    WP-33: refuses to close (raises `EndShiftOpenTripsError`, mapped to 409
    by the router) while the shift still has an open Trip -- same
    `has_open_trip` check `app.services.shift_handover.perform_handover`
    applies to the outgoing shift of a handover, so both call sites use one
    shared definition of "this shift still has a trip in progress" rather
    than two that could drift."""
    if await has_open_trip(session, tenant_id=shift.tenant_id, shift_id=shift.id):
        raise EndShiftOpenTripsError(
            f"Shift {shift.id} still has an open trip -- it must be closed "
            "before the shift can end"
        )

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
    shift.odometer_end = odometer_end
    shift.end_inspection_json = end_inspection_json

    await record_audit(
        session,
        tenant_id=shift.tenant_id,
        actor_user_id=actor_user_id,
        action="shift_ended",
        entity_type="shift",
        entity_id=shift.id,
        before=None,
        after={
            "driver_id": shift.driver_id,
            "vehicle_id": shift.vehicle_id,
            "end_at": shift.end_at.isoformat(),
            "trips_count": trips_count,
            "psl_owed": str(psl_owed),
            "reconciled": reconciled,
        },
    )

    await session.commit()
    await session.refresh(shift)
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


def build_report(shift: Shift) -> dict:
    """Builds the JSON summary payload for `GET /v1/shifts/{id}/report`.

    Real PDF/CSV export lives below: `render_report_pdf`/`render_report_csv`
    render this same dict (wrapped as a `ShiftReport`) without changing this
    function's contract.
    """
    duration_minutes: float | None = None
    if shift.end_at is not None:
        duration_minutes = round((shift.end_at - shift.start_at).total_seconds() / 60, 2)

    return {
        "shift_id": shift.id,
        "tenant_id": shift.tenant_id,
        "driver_id": shift.driver_id,
        "vehicle_id": shift.vehicle_id,
        "start_at": shift.start_at,
        "end_at": shift.end_at,
        "duration_minutes": duration_minutes,
        "trips_count": shift.trips_count,
        "km_total": shift.km_total,
        "cash_total": shift.cash_total,
        "card_total": shift.card_total,
        "total_takings": round_half_up(shift.cash_total + shift.card_total),
        "psl_owed": shift.psl_owed,
        "reconciled": shift.reconciled,
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
