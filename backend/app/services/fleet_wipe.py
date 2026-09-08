"""TEMPORARY testing-only bulk-wipe support (2026-09, direct product
instruction -- see `dashboard/src/pages/fleet/index.tsx`'s
`WipeAllFleetDataButton` / `dashboard/src/pages/fleet/api.ts`'s
`useWipeAllFleetData` for the full context and the plan to remove this once
onboarding/pairing testing is done). This module exists solely to back the
one destructive action described below; it is not meant to become a permanent
part of the product.

There are two distinct wipe actions on the dashboard, and only the SECOND one
needs any backend code at all:

1. The DEFAULT wipe: the dashboard itself loops the ordinary per-row DELETE
   endpoints (`DELETE /v1/fleet/vehicles/{id}`, `/v1/fleet/devices/{id}`,
   `/v1/users/{id}`) -- see `useWipeAllFleetData`. No new backend surface is
   needed for that path, and it is CORRECT AS-IS: it already respects every
   existing safety check --
     * `app.services.user.assert_user_deletable`'s evidence-blocking (a
       driver with real PSL/wallet/rating/compliance/tariff-change-log/
       audit-log history correctly survives, reported back as a per-row
       failure), and
     * the fleet domain's own vehicle-delete side effects (an open shift on
       that vehicle is closed rather than left dangling, devices are
       unbound rather than deleted -- see
       `app.services.fleet.prepare_vehicle_for_deletion`).
   A driver who has genuine evidence on file SHOULD survive the default
   wipe -- that is the correct, intended behaviour this module must never
   route around silently.

2. The FORCE path (this module, `POST /v1/fleet/wipe-test-data/force`): a
   deliberate, EXPLICIT, separately-confirmed action for a product owner who
   needs a test tenant genuinely emptied -- evidence and all -- because the
   default wipe (correctly) leaves behind exactly the drivers who have real
   PSL/wallet/rating/compliance/tariff-change-log rows attached. Per the task
   brief: this must be a real server-side flag/endpoint, never implemented by
   having the client delete dependent rows one by one behind the operator's
   back (that would silently defeat `assert_user_deletable` from outside,
   with none of its "here's exactly what's blocking, in one place" honesty).

   `force_wipe_tenant_fleet_data` below PURGES, per tenant, the exact
   evidence categories `assert_user_deletable` polices (PSL ledger entries +
   top-ups, wallet transactions, trip ratings, compliance documents, tariff
   change-log entries) for every driver about to be deleted, then deletes
   devices, vehicles (via the same `prepare_vehicle_for_deletion` the normal
   delete endpoint uses -- open shifts still get closed, not silently
   dropped), and drivers -- one row at a time, continuing past a single
   row's failure, same "don't let one bad row block the rest" philosophy as
   the dashboard's own `deleteAllSequentially`.

   DELIBERATELY NEVER TOUCHED, even under force: `AuditLog` rows. This is a
   considered decision, not an oversight -- see `_AUDIT_LOG_NEVER_DELETED`
   below for the full reasoning. A driver who has ever been recorded as an
   audit-log actor (in this codebase today, that only happens via
   `app.services.shift._check_device_vehicle_mismatch`'s advisory
   device/vehicle-pairing check) therefore still cannot be deleted even
   under force -- this is reported as an honest per-row failure, exactly
   like any other undeletable row, never silently skipped or miscounted as
   a success.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.audit_log import AuditLog
from app.models.compliance import ComplianceDocument
from app.models.driver_engagement import TripRating, WalletTransaction
from app.models.fleet import Device, Vehicle
from app.models.psl_ledger import PSLLedgerEntry, PSLTopUp
from app.models.tariffs import TariffChangeLog
from app.models.user import ROLE_DRIVER, User
from app.services import compliance as compliance_service
from app.services.fleet import prepare_vehicle_for_deletion
from app.services.shift import close_open_shifts_for_driver_deletion

# _AUDIT_LOG_NEVER_DELETED (decision, not a TODO): `app.models.audit_log`'s own
# module docstring is explicit that this table has "deliberately no
# update/delete path anywhere" because it is cryptographically hash-chained
# (`hash`/`previous_hash`, see `app.services.audit_log.compute_audit_hash` /
# `verify_chain`) -- each row's hash is computed over its own fields PLUS the
# previous row's hash, so deleting even ONE row from the middle (or end) of a
# tenant's chain leaves every row after it referencing a `previous_hash` that
# no longer matches any surviving row, which `GET /v1/audit-log/verify` would
# then correctly (and permanently) report as tampered. There is no partial
# deletion that preserves verifiability short of destroying the ENTIRE
# tenant's chain outright -- and even then, the wipe itself is exactly the
# kind of event this codebase's audit trail is designed to record ("silently
# erasing the log of a destructive action while performing it" is precisely
# the failure mode tamper-evidence exists to catch). So: force wipe leaves
# `AuditLog` untouched, full stop, no exceptions -- a test-tenant reset is not
# worth compromising the one invariant (verifiable and immutable) the rest of
# the audit-log domain builds on. A driver who has ever accrued an audit-log
# row as actor is reported back as an honest failure, same as any other real
# blocker -- never miscounted as deleted.


@dataclass
class WipeFailure:
    kind: str  # "vehicle" | "device" | "driver"
    id: str
    reason: str


@dataclass
class ForceWipeResult:
    vehicles_deleted: int = 0
    devices_deleted: int = 0
    drivers_deleted: int = 0
    # Category label -> row count permanently destroyed. Always present (even
    # at 0) for every category this function is capable of purging, so the
    # dashboard can render a complete "what was destroyed" list rather than
    # only the categories that happened to have rows this run.
    evidence_rows_destroyed: dict[str, int] = field(
        default_factory=lambda: {
            "psl_ledger_entries": 0,
            "psl_topups": 0,
            "wallet_transactions": 0,
            "trip_ratings": 0,
            "compliance_documents": 0,
            "tariff_change_log_entries": 0,
        }
    )
    failures: list[WipeFailure] = field(default_factory=list)


async def _purge_driver_evidence(session: AsyncSession, *, driver_id: str) -> dict[str, int]:
    """Deletes every evidence row `assert_user_deletable` would otherwise
    block `driver_id`'s deletion on -- EXCEPT `AuditLog`, see this module's
    docstring -- so the caller's subsequent delete of the `User` row can
    actually succeed. Returns a category-label -> row-count dict (only
    categories that had at least one row are included) for the caller to
    fold into `ForceWipeResult.evidence_rows_destroyed`."""
    destroyed: dict[str, int] = {}

    async def _delete_matching(model, column, label: str) -> None:
        result = await session.execute(select(model).where(column == driver_id))
        rows = result.scalars().all()
        for row in rows:
            await session.delete(row)
        if rows:
            destroyed[label] = len(rows)

    await _delete_matching(PSLLedgerEntry, PSLLedgerEntry.driver_id, "psl_ledger_entries")
    await _delete_matching(PSLTopUp, PSLTopUp.driver_id, "psl_topups")
    await _delete_matching(WalletTransaction, WalletTransaction.driver_id, "wallet_transactions")
    await _delete_matching(TripRating, TripRating.driver_id, "trip_ratings")
    await _delete_matching(TariffChangeLog, TariffChangeLog.actor_user_id, "tariff_change_log_entries")

    # Compliance documents get the same on-disk cleanup as the real DELETE
    # /v1/compliance/documents/{id} endpoint (app.services.compliance
    # .delete_file_best_effort) -- a force wipe shouldn't leave orphaned
    # files on disk any more than a normal delete would.
    result = await session.execute(
        select(ComplianceDocument).where(ComplianceDocument.uploaded_by == driver_id)
    )
    docs = result.scalars().all()
    for doc in docs:
        file_path = doc.file_path
        await session.delete(doc)
        compliance_service.delete_file_best_effort(file_path)
    if docs:
        destroyed["compliance_documents"] = len(docs)

    return destroyed


async def _audit_log_actor_count(session: AsyncSession, *, driver_id: str) -> int:
    return (
        await session.execute(
            select(func.count()).select_from(AuditLog).where(AuditLog.actor_user_id == driver_id)
        )
    ).scalar_one()


async def force_wipe_tenant_fleet_data(
    session: AsyncSession, *, tenant_id: str, actor_user_id: str
) -> ForceWipeResult:
    """Deletes every vehicle, device, and driver on `tenant_id`, purging the
    per-driver evidence categories that would otherwise block driver
    deletion -- see this module's docstring for exactly what is and is not
    destroyed. `actor_user_id` is the owner performing the wipe (attributed
    on the shift-close audit entries `prepare_vehicle_for_deletion` writes
    for any open shift on a vehicle being force-wiped -- see
    `app.services.shift.close_open_shifts_for_vehicle_deletion`).

    One row at a time, continuing past an individual row's failure -- same
    philosophy as the dashboard's own `deleteAllSequentially` (a single
    stubborn row must not stop the rest of the tenant from being cleared).
    Does not commit; the caller commits once at the end so a failure calling
    this function partway through still leaves whatever succeeded durable up
    to that point (matching the per-row `session.delete` + outer commit
    pattern the rest of this domain already uses).
    """
    result = ForceWipeResult()

    devices = (
        (await session.execute(select(Device).where(Device.tenant_id == tenant_id))).scalars().all()
    )
    for device in devices:
        await session.delete(device)
        result.devices_deleted += 1

    vehicles = (
        (await session.execute(select(Vehicle).where(Vehicle.tenant_id == tenant_id))).scalars().all()
    )
    for vehicle in vehicles:
        # Closes any open shift on this vehicle (unreconciled, audit-logged)
        # and unbinds any device still pointing at it -- identical to what
        # DELETE /v1/fleet/vehicles/{id} does, so force wipe can never drift
        # from that endpoint's own behaviour. Devices were already deleted
        # above in this same pass, so the unlink step is a no-op there; it
        # still matters for any device this tenant might somehow have that
        # wasn't caught above (there shouldn't be one -- kept for safety and
        # to reuse one single code path rather than a wipe-only variant).
        await prepare_vehicle_for_deletion(
            session, tenant_id=tenant_id, vehicle_id=vehicle.id, actor_user_id=actor_user_id
        )
        await session.delete(vehicle)
        result.vehicles_deleted += 1

    drivers = (
        (
            await session.execute(
                select(User).where(User.tenant_id == tenant_id, User.role == ROLE_DRIVER)
            )
        )
        .scalars()
        .all()
    )
    for driver in drivers:
        # Closes any shift still open for this driver before the driver row
        # goes — the driver-side counterpart of the vehicle pass above, which
        # a force wipe previously skipped entirely, leaving open shifts
        # pointing at a driver_id nothing could resolve. Runs BEFORE the
        # evidence purge and the deletability check so the shift is closed and
        # audited even in the case below where the driver survives the wipe:
        # a driver who cannot be deleted still must not be left holding a
        # shift that a wipe has torn the rest of the fleet out from under.
        await close_open_shifts_for_driver_deletion(
            session, tenant_id=tenant_id, driver_id=driver.id, actor_user_id=actor_user_id
        )

        destroyed = await _purge_driver_evidence(session, driver_id=driver.id)
        for label, count in destroyed.items():
            result.evidence_rows_destroyed[label] += count

        audit_count = await _audit_log_actor_count(session, driver_id=driver.id)
        if audit_count:
            result.failures.append(
                WipeFailure(
                    kind="driver",
                    id=driver.id,
                    reason=(
                        f"{audit_count} audit log entr{'y' if audit_count == 1 else 'ies'} "
                        "recorded by this user as actor -- the tamper-evident audit trail is "
                        "never deleted, even by force wipe. Every other blocking record was "
                        "destroyed; only this one is left behind on purpose."
                    ),
                )
            )
            continue

        await session.delete(driver)
        result.drivers_deleted += 1

    return result
