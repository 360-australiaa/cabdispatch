"""Audit Log domain service.

`record_audit` is the ONE write path onto the append-only `audit_log` table
and is the primary export other domains are expected to use — import it
directly:

    from app.services.audit_log import record_audit
    ...
    await record_audit(
        session,
        tenant_id=tenant_id,
        actor_user_id=user.id,
        action="update",
        entity_type="trip",
        entity_id=trip.id,
        before=before_snapshot,
        after=after_snapshot,
    )

`get_audit_logger` is a small FastAPI-dependency-shaped convenience wrapper
other domains' ROUTERS can pull in with `Depends(...)` to get a
tenant/actor-bound logging callable for free, instead of re-deriving
tenant_id/actor_user_id at every call site:

    from app.services.audit_log import get_audit_logger, AuditLogger

    @router.patch("/{trip_id}")
    async def update_trip(
        ...,
        audit: AuditLogger = Depends(get_audit_logger),
        session: AsyncSession = Depends(get_session),
    ):
        ...
        await audit(session, action="update", entity_type="trip", entity_id=trip.id,
                    before=before, after=after)
        await session.commit()

Wiring every other domain's router to actually call either of these is out of
scope for this domain agent (per the task brief) — both are demonstrated
against this domain's own endpoints and directly in
tests/test_audit_log.py::test_record_audit_self_test_shows_up_in_list, which
calls `record_audit` directly (bypassing the API) and asserts the resulting
row is visible via `GET /v1/audit-log`.

APPEND-ONLY BY DESIGN: this module intentionally exposes no update/delete
helper of any kind. Tamper evidence requires that once an audit entry exists
it can never be silently altered or removed — see app/api/v1/audit_log.py for
the identical rule enforced at the API layer (create + list endpoints only,
no PATCH/PUT/DELETE).

HASH CHAIN: append-only-by-convention alone doesn't prove nothing was edited
directly at the DB layer, bypassing this module and the API entirely. Every
row `record_audit` writes carries `hash` (a SHA-256 digest over its own
fields) and `previous_hash` (the previous row's `hash`, per tenant, or a fixed
genesis value for a tenant's first row) — see `compute_audit_hash` /
`verify_chain` below and the module docstring on `app.models.audit_log`.
`GET /v1/audit-log/verify` (app/api/v1/audit_log.py) is the read-side of this:
it calls `verify_chain` to walk a tenant's chain and confirm it's intact.
"""
from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime
from typing import Any, Protocol

from fastapi import Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import get_current_tenant_id, get_current_user
from app.models.audit_log import AuditLog
from app.models.user import User

# Fixed previous_hash for the first row in a tenant's chain — there is no
# preceding row to point at, so we point at a value that could never itself be
# a valid SHA-256 digest of anything but happens to be 64 hex-ish characters,
# matching the column width. Same value across every tenant (the chain is
# per-tenant, but the genesis marker itself doesn't need to be).
GENESIS_HASH = "0" * 64


def _canonical_at(at: datetime) -> str:
    """Normalizes `at` to a naive-UTC isoformat string before hashing.

    SQLite (used in dev/test — see the identical caveat already documented in
    app.services.fleet._is_expired) round-trips `DateTime(timezone=True)`
    columns as tzinfo-naive, even though every value this module writes is
    UTC-aware at write time. Without this normalization, the hash computed at
    write time (from the in-memory, tz-aware `at`) and the hash recomputed
    later by `verify_chain` (from the same value re-loaded from the DB, now
    tz-naive) would differ for the SAME instant — a false "tampered"
    positive. Both call sites route through this helper so they agree.
    """
    if at.tzinfo is not None:
        at = at.astimezone(UTC).replace(tzinfo=None)
    return at.isoformat(timespec="microseconds")


def compute_audit_hash(
    *,
    tenant_id: str,
    actor_user_id: str | None,
    action: str,
    entity_type: str,
    entity_id: str,
    before: dict[str, Any] | None,
    after: dict[str, Any] | None,
    at: datetime,
    previous_hash: str,
) -> str:
    """SHA-256 hex digest of a canonical, deterministically-ordered
    serialization of one audit row's tamper-evident fields, chained to the
    immediately preceding row (`previous_hash`) for this tenant.

    The field order below (tenant_id, actor_user_id, action, entity_type,
    entity_id, before, after, at, previous_hash) is fixed and serialized as a
    JSON *list* (not a dict) specifically so order is structural, not just a
    dict-insertion-order convention. `before`/`after` are themselves
    re-serialized with sort_keys=True so the hash doesn't depend on the
    incidental key order of the dict a caller happened to pass in. Uses only
    stdlib `hashlib` + `json` — no new dependency.
    """
    canonical = json.dumps(
        [
            tenant_id,
            actor_user_id,
            action,
            entity_type,
            entity_id,
            json.dumps(before, sort_keys=True, separators=(",", ":")) if before is not None else None,
            json.dumps(after, sort_keys=True, separators=(",", ":")) if after is not None else None,
            _canonical_at(at),
            previous_hash,
        ],
        separators=(",", ":"),
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


async def _latest_hash(session: AsyncSession, *, tenant_id: str) -> str:
    """The `hash` of the most recently written row in this tenant's chain, or
    `GENESIS_HASH` if the tenant has no audit rows yet. Ordered by `at` (the
    chain's chronological axis) with `id` as a tiebreaker for same-instant
    writes."""
    result = await session.execute(
        select(AuditLog.hash)
        .where(AuditLog.tenant_id == tenant_id)
        .order_by(AuditLog.at.desc(), AuditLog.id.desc())
        .limit(1)
    )
    latest = result.scalar_one_or_none()
    return latest if latest is not None else GENESIS_HASH


async def record_audit(
    session: AsyncSession,
    *,
    tenant_id: str,
    actor_user_id: str | None,
    action: str,
    entity_type: str,
    entity_id: str,
    before: dict[str, Any] | None = None,
    after: dict[str, Any] | None = None,
    at: datetime | None = None,
) -> AuditLog:
    """Appends one audit-log row and returns it.

    Deliberately does NOT call `session.commit()`. This is meant to be
    callable from inside another domain's own mutation — right after that
    domain updates/creates/deletes its row, before ITS OWN
    `await session.commit()` — so the audit entry lands in the exact same
    database transaction as the change it documents. If that transaction
    rolls back, the audit entry rolls back with it, which is the correct
    behaviour for a tamper-evidence log: there should never be an audit row
    describing a change that didn't actually happen. Callers that need the
    entry durable/visible immediately on its own (this domain's own `POST
    /v1/audit-log` endpoint, and the self-test) call `session.commit()`
    themselves right after.

    Uses `session.flush()` (not commit) so the returned object's generated
    `id` and `at` are populated without needing a full refresh.

    Also computes and sets the hash-chain columns (`hash` / `previous_hash`)
    — see `compute_audit_hash` / module docstring. `previous_hash` is looked
    up per-tenant (the chain does not span tenants) via `_latest_hash`, which
    queries through THIS session — since record_audit flushes at the end,
    successive calls within the same session/transaction see each other's
    rows and chain correctly even before an outer commit.
    """
    at_value = at or datetime.now(UTC)
    previous_hash = await _latest_hash(session, tenant_id=tenant_id)
    row_hash = compute_audit_hash(
        tenant_id=tenant_id,
        actor_user_id=actor_user_id,
        action=action,
        entity_type=entity_type,
        entity_id=entity_id,
        before=before,
        after=after,
        at=at_value,
        previous_hash=previous_hash,
    )
    entry = AuditLog(
        tenant_id=tenant_id,
        actor_user_id=actor_user_id,
        action=action,
        entity_type=entity_type,
        entity_id=entity_id,
        before_json=before,
        after_json=after,
        at=at_value,
        hash=row_hash,
        previous_hash=previous_hash,
    )
    session.add(entry)
    await session.flush()
    return entry


async def verify_chain(session: AsyncSession, *, tenant_id: str) -> tuple[bool, str | None, int]:
    """Walks this tenant's ENTIRE hash chain, oldest row first, recomputing
    each row's hash from its own stored fields + the preceding row's stored
    `hash`, and checking two things per row:

    1. its stored `previous_hash` matches the actual previous row's `hash`
       (or `GENESIS_HASH` for the first row), and
    2. recomputing `compute_audit_hash` over its own fields reproduces its
       own stored `hash`.

    Either check failing means that row (or an earlier one it transitively
    depends on) was altered after being written. Returns
    `(valid, broken_at_id, checked)`:
    - `valid`: True iff every row in the chain checks out.
    - `broken_at_id`: the `id` of the first row that fails either check, or
      None if `valid` is True.
    - `checked`: how many rows were examined before stopping (or the total,
      if the chain is fully valid).

    This is the function `GET /v1/audit-log/verify` calls.
    """
    result = await session.execute(
        select(AuditLog)
        .where(AuditLog.tenant_id == tenant_id)
        .order_by(AuditLog.at.asc(), AuditLog.id.asc())
    )
    rows = result.scalars().all()

    expected_previous_hash = GENESIS_HASH
    checked = 0
    for row in rows:
        checked += 1

        if row.previous_hash != expected_previous_hash:
            return False, row.id, checked

        recomputed = compute_audit_hash(
            tenant_id=row.tenant_id,
            actor_user_id=row.actor_user_id,
            action=row.action,
            entity_type=row.entity_type,
            entity_id=row.entity_id,
            before=row.before_json,
            after=row.after_json,
            at=row.at,
            previous_hash=row.previous_hash,
        )
        if recomputed != row.hash:
            return False, row.id, checked

        expected_previous_hash = row.hash

    return True, None, checked


class AuditLogger(Protocol):
    """Shape of the callable `get_audit_logger` hands back."""

    async def __call__(
        self,
        session: AsyncSession,
        *,
        action: str,
        entity_type: str,
        entity_id: str,
        before: dict[str, Any] | None = None,
        after: dict[str, Any] | None = None,
    ) -> AuditLog: ...


def get_audit_logger(
    tenant_id: str = Depends(get_current_tenant_id),
    user: User = Depends(get_current_user),
) -> AuditLogger:
    """FastAPI dependency factory: resolves the current request's tenant_id +
    authenticated user ONCE, and hands back a small async callable that other
    domains' route handlers can call inline to log a mutation without
    re-deriving those two values themselves. Thin wrapper over `record_audit`
    — see module docstring for a full usage example."""

    async def _log(
        session: AsyncSession,
        *,
        action: str,
        entity_type: str,
        entity_id: str,
        before: dict[str, Any] | None = None,
        after: dict[str, Any] | None = None,
    ) -> AuditLog:
        return await record_audit(
            session,
            tenant_id=tenant_id,
            actor_user_id=user.id,
            action=action,
            entity_type=entity_type,
            entity_id=entity_id,
            before=before,
            after=after,
        )

    return _log
