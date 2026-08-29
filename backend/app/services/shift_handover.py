"""Shift Handover domain service (WP-32, architecture plan D-2).

`perform_handover` is the direct mechanism answering "two drivers, 12 hours
each, same vehicle, in one 24-hour period" (plan Part 5, Q3 walkthrough): it
closes the outgoing driver's shift and opens the incoming driver's shift on
the SAME vehicle in ONE database transaction, so the D-1 partial unique
indexes on `app.models.shift.Shift`
(`uq_shifts_one_open_per_vehicle` / `uq_shifts_one_open_per_driver`) are
never violated even for an instant -- there is no window where the vehicle
has zero open shifts (which a concurrent `POST /v1/shifts/start` for a third
driver could slip into) or two open shifts (which the indexes would reject
outright, but only AFTER a half-completed handover had already written one
of the two rows).

Reuses, rather than re-implements, four pieces of `app.services.shift`:
- `validate_driver_for_vehicle` -- gates (a)-(f) of start_shift's validation
  chain (driver exists/eligible, licence/authority unexpired, suitability
  clear, vehicle exists/operational, driver on the vehicle's roster). The
  incoming driver of a handover must clear the EXACT same bar a fresh
  `POST /v1/shifts/start` would hold them to -- see the WP-32 task brief,
  step 3e ("extract Stage 1 validation into a reusable function ... so
  handover and start_shift share the exact same rules rather than two
  independently-maintained copies that can drift").
- `is_caller_authorised_for_driver` -- gate (h)'s "caller IS the driver, or
  a dispatcher/admin/owner" rule, reused verbatim for the OUTGOING driver
  (the caller must be the outgoing driver themselves, or dispatch staff --
  same rule start_shift applies to the driver being opened FOR).
- `_recompute_trip_aggregates` -- the exact same trips_count/km_total/
  cash_total/card_total recomputation `end_shift` uses to close the
  outgoing shift, so a shift closed via handover reports identically to one
  closed via `POST /v1/shifts/{id}/end`.
- `has_open_trip` (WP-33) -- the exact same open-trip guard `end_shift` now
  applies before closing a shift, reused here for the outgoing shift's step
  (c) check below (originally a module-local `_has_open_trip` in this file,
  extracted into `app.services.shift` once `end_shift` needed the identical
  check, so the two call sites can't drift).

`end_shift`/`start_shift` themselves are NOT called directly -- both commit
internally, and this function's entire point is that closing the outgoing
shift and opening the incoming one must be ONE transaction with ONE commit
(task brief step 3j). This module duplicates their few lines of row
construction/mutation instead, deliberately, to keep the commit boundary
here rather than inside a function two commits deep.
"""
from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import verify_password
from app.models.shift import Shift
from app.models.shift_handover import ShiftHandover
from app.models.user import User
from app.services import shift as shift_service
from app.services.audit_log import record_audit


class HandoverShiftNotFoundError(shift_service.ShiftError):
    """Step (a): `outgoing_shift_id` does not resolve to a Shift row in this
    tenant, or it resolves but is already closed (`end_at` is not None) --
    both cases are indistinguishable to the caller and treated the same,
    since a handover only ever makes sense against a currently-open shift.
    Router maps this to 404."""


class HandoverCallerNotAuthorisedError(shift_service.ShiftError):
    """Step (b): the authenticated caller is neither the OUTGOING driver
    themselves nor a dispatcher/admin/owner in this tenant -- same rule as
    start_shift's gate (h), applied to the driver being handed FROM.
    Router maps this to 403."""


class HandoverOpenTripError(shift_service.ShiftError):
    """Step (c): the outgoing shift still has an open (status == 'open')
    Trip. A handover cannot happen mid-trip -- the outgoing driver must
    finish (or the trip must otherwise be closed) before the vehicle changes
    hands. Router maps this to 409."""


class HandoverInvalidCredentialsError(shift_service.ShiftError):
    """Step (d): the incoming driver could not be re-authenticated --
    `incoming_driver_id` didn't resolve to a User in this tenant, the user
    has no PIN set, or `incoming_driver_pin` didn't match. Deliberately does
    NOT distinguish "wrong id" from "wrong PIN" in its message (same
    anti-enumeration spirit as POST /v1/auth/driver-login's
    _INVALID_DRIVER_CREDENTIALS). Router maps this to 401."""


class HandoverConflictError(shift_service.ShiftError):
    """Race backstop, mirroring start_shift's ShiftConflictError: the
    defensive checks above all passed, but the final flush still hit one of
    the D-1 partial unique indexes because a concurrent request won a race
    between this function's checks and its commit. Router maps this to 409,
    same status as HandoverOpenTripError since both describe "the vehicle/
    driver state changed under you, try again."""


async def _caller_role(session: AsyncSession, *, tenant_id: str, caller_user_id: str) -> str | None:
    result = await session.execute(
        select(User.role).where(User.id == caller_user_id, User.tenant_id == tenant_id)
    )
    return result.scalar_one_or_none()


async def perform_handover(
    session: AsyncSession,
    *,
    tenant_id: str,
    outgoing_shift_id: str,
    incoming_driver_id: str,
    incoming_driver_pin: str,
    caller_user_id: str,
    odometer_end: int | None,
    fuel_level: int | None,
    cleanliness_notes: str | None,
    damage_notes: str | None,
    handed_over_at: datetime | None = None,
) -> ShiftHandover:
    """Closes `outgoing_shift_id` and opens a new shift for
    `incoming_driver_id` on the SAME vehicle, in ONE transaction (task
    brief step 3j) -- see module docstring for why this can't be built out
    of start_shift/end_shift directly. Steps (a)-(j) below match the task
    brief's lettering exactly.
    """
    now = handed_over_at or datetime.now(UTC)

    # --- (a): load the outgoing shift, 404 if missing/wrong tenant/already ended ---
    outgoing_result = await session.execute(
        select(Shift).where(Shift.id == outgoing_shift_id, Shift.tenant_id == tenant_id)
    )
    outgoing_shift = outgoing_result.scalar_one_or_none()
    if outgoing_shift is None or outgoing_shift.end_at is not None:
        raise HandoverShiftNotFoundError(
            f"Shift {outgoing_shift_id} not found, not in this tenant, or already closed"
        )

    # --- (b): caller is the outgoing driver, or a dispatcher/admin/owner ---
    if caller_user_id != outgoing_shift.driver_id:
        caller_role = await _caller_role(session, tenant_id=tenant_id, caller_user_id=caller_user_id)
        if not shift_service.is_caller_authorised_for_driver(
            caller_user_id=caller_user_id,
            driver_id=outgoing_shift.driver_id,
            caller_role=caller_role,
        ):
            raise HandoverCallerNotAuthorisedError(
                f"Caller {caller_user_id} may not hand over shift {outgoing_shift_id} "
                "-- must be the outgoing driver themselves or a dispatcher/admin/owner"
            )

    # --- (c): no open trip on the outgoing shift -----------------------------
    # WP-33: shared with app.services.shift.end_shift's own open-trip guard
    # via app.services.shift.has_open_trip -- see that function's docstring.
    if await shift_service.has_open_trip(session, tenant_id=tenant_id, shift_id=outgoing_shift_id):
        raise HandoverOpenTripError(
            f"Shift {outgoing_shift_id} still has an open trip -- it must be "
            "closed before the vehicle can be handed over"
        )

    # --- (d): re-authenticate the incoming driver -----------------------------
    incoming_user_result = await session.execute(
        select(User).where(User.id == incoming_driver_id, User.tenant_id == tenant_id)
    )
    incoming_user = incoming_user_result.scalar_one_or_none()
    if (
        incoming_user is None
        or not incoming_user.pin_hash
        or not verify_password(incoming_driver_pin, incoming_user.pin_hash)
    ):
        raise HandoverInvalidCredentialsError("Invalid incoming driver or PIN")

    # --- (e): incoming driver runs the SAME gates (a)-(f) start_shift uses ---
    _incoming_driver, canonical_vehicle_id = await shift_service.validate_driver_for_vehicle(
        session,
        tenant_id=tenant_id,
        driver_id=incoming_driver_id,
        vehicle_id=outgoing_shift.vehicle_id,
    )

    # --- (f): close the outgoing shift ----------------------------------------
    trips_count, km_total, cash_total, card_total = await shift_service._recompute_trip_aggregates(
        session, tenant_id=tenant_id, shift_id=outgoing_shift_id
    )
    outgoing_shift.end_at = now
    outgoing_shift.trips_count = trips_count
    outgoing_shift.km_total = km_total
    outgoing_shift.cash_total = cash_total
    outgoing_shift.card_total = card_total
    # psl_owed/reconciled are NOT set here -- a handover is not a
    # reconciliation event (no cash-count figures are collected as part of
    # it); they keep whatever value the shift already had (its Shift-model
    # default of Decimal(0)/False if never touched). A real end-of-day
    # reconciliation still goes through POST /v1/shifts/{id}/end as normal.

    # --- (g): open the incoming shift -----------------------------------------
    # WP-33: Shift.odometer_start now exists (see app.models.shift's
    # DEVIATION note) -- the incoming shift starts from the exact odometer
    # reading the outgoing one ended on, wiring up the "STAGE 3 NOTE"
    # pointer Stage 2 left on app.models.shift_handover.ShiftHandover.
    incoming_shift = Shift(
        tenant_id=tenant_id,
        driver_id=incoming_driver_id,
        vehicle_id=canonical_vehicle_id,
        start_at=now,
        inspection_json=None,
        odometer_start=odometer_end,
    )
    session.add(incoming_shift)

    handover = ShiftHandover(
        tenant_id=tenant_id,
        outgoing_shift_id=outgoing_shift_id,
        # incoming_shift_id is set right below, once the flush has
        # populated incoming_shift.id -- see the try block.
        incoming_shift_id="",
        handed_over_at=now,
        handed_over_by_user_id=caller_user_id,
        odometer_end=odometer_end,
        fuel_level=fuel_level,
        cleanliness_notes=cleanliness_notes,
        damage_notes=damage_notes,
    )

    try:
        # ORDERING IS LOAD-BEARING: the outgoing shift's UPDATE (end_at set
        # non-null) MUST be flushed to the DB before the incoming shift's
        # INSERT is attempted. The D-1 partial unique index checks are
        # immediate (not deferred) -- if the INSERT went first, it would
        # collide with the still-open outgoing row on this same vehicle and
        # fail with a false-positive IntegrityError even for a perfectly
        # legitimate handover. Flushing outgoing_shift alone first (session
        # not yet committed, so this is still one transaction -- a rollback
        # below undoes this UPDATE too) is what makes "the vehicle briefly
        # has zero open shifts, then immediately has exactly one again"
        # true at the statement level, which is the actual D-2 guarantee.
        await session.flush([outgoing_shift])
        await session.flush([incoming_shift])
        handover.incoming_shift_id = incoming_shift.id
        session.add(handover)
        await session.flush([handover])  # populates handover.id for the audit row

        # --- (i): audit-log the handover ---------------------------------
        await record_audit(
            session,
            tenant_id=tenant_id,
            actor_user_id=caller_user_id,
            action="shift_handover",
            entity_type="shift_handover",
            entity_id=handover.id,
            before=None,
            after={
                "outgoing_shift_id": outgoing_shift_id,
                "incoming_shift_id": incoming_shift.id,
                "incoming_driver_id": incoming_driver_id,
                "caller_user_id": caller_user_id,
            },
        )
        # --- (j): commit once, covering everything above ------------------
        await session.commit()
    except IntegrityError as exc:
        await session.rollback()
        raise HandoverConflictError(
            "A concurrent request already opened or closed a shift for this "
            "driver or vehicle -- the D-1 partial unique index rejected this "
            "handover. Retry."
        ) from exc

    await session.refresh(handover)
    return handover