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

import asyncio
import hashlib
import json
import logging
from datetime import UTC, datetime, timedelta
from typing import Any, Protocol

from fastapi import Depends
from sqlalchemy import event, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import get_current_tenant_id, get_current_user
from app.models.audit_log import AuditLog
from app.models.user import User

logger = logging.getLogger("cab_dispatch.audit_log")

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


def _chain_lock_key(tenant_id: str) -> int:
    """Deterministic signed-64-bit key for `pg_advisory_xact_lock`, derived
    from `tenant_id`.

    Postgres advisory locks are keyed by a bigint, not a string, so the tenant
    UUID is hashed down to one. blake2b (not Python's `hash()`) because
    `hash()` of a str is salted per process — two uvicorn workers would derive
    DIFFERENT keys for the same tenant and would therefore not exclude each
    other, which is the entire point of the lock. Signed range, because
    Postgres bigint is signed and a value above 2**63-1 is an error.
    """
    digest = hashlib.blake2b(tenant_id.encode("utf-8"), digest_size=8).digest()
    return int.from_bytes(digest, "big", signed=True)


# One asyncio lock per tenant, guarding the read-latest-then-append sequence
# inside this process. See `_acquire_chain_lock` for why the database-level
# lock alone is not enough. Unbounded in principle, bounded in practice by the
# number of tenants a process serves; an `asyncio.Lock` with no waiters is two
# small objects, so this is not worth evicting from.
_tenant_append_locks: dict[str, asyncio.Lock] = {}

# Ceiling on how long one append will wait for another append on the same
# tenant. Reaching it means something is holding a transaction open for half a
# minute, which is a bug elsewhere; we then proceed WITHOUT the lock and log
# loudly, because a duress/fleet write hanging forever is worse than a small
# risk of a chain fork, and a silent deadlock is worse than both.
CHAIN_LOCK_TIMEOUT_SECONDS = 30.0

# Key under which a held lock is parked on the session, so the same session
# appending several audit rows in one transaction re-enters instead of
# deadlocking against itself.
_SESSION_LOCK_KEY = "_audit_chain_lock"


def _chain_lock_key(tenant_id: str) -> int:
    """Deterministic signed-64-bit key for `pg_advisory_xact_lock`, derived
    from `tenant_id`.

    Postgres advisory locks are keyed by a bigint, not a string, so the tenant
    UUID is hashed down to one. blake2b (not Python's `hash()`) because
    `hash()` of a str is salted per process — two uvicorn workers would derive
    DIFFERENT keys for the same tenant and would therefore not exclude each
    other, which is the entire point of the lock. Signed range, because
    Postgres bigint is signed and a value above 2**63-1 is an error.
    """
    digest = hashlib.blake2b(tenant_id.encode("utf-8"), digest_size=8).digest()
    return int.from_bytes(digest, "big", signed=True)


def _release_on_transaction_end(sync_session, transaction) -> None:
    """Releases this session's held chain lock when its transaction ends —
    commit, rollback or close, whichever comes first.

    Transaction end, not "when record_audit returns", is the correct release
    point: `record_audit` deliberately does not commit (see its docstring), so
    the row it appended is not visible to any other connection until the
    CALLER commits. Releasing any earlier would let the next appender read a
    `previous_hash` that is about to be superseded — exactly the fork this
    lock exists to prevent.
    """
    if transaction.parent is not None:  # nested/savepoint, not the real end
        return
    lock = sync_session.info.pop(_SESSION_LOCK_KEY, None)
    if lock is not None and lock.locked():
        lock.release()


async def _acquire_chain_lock(session: AsyncSession, *, tenant_id: str) -> None:
    """Serialises hash-chain appends for one tenant, so two concurrent audited
    writes cannot both read the same `previous_hash` and permanently fork the
    chain (backend audit §7: the "Concurrency — audit-chain fork" untested
    gap). A forked chain can never pass `verify_chain` again, on the one
    control this system has for tamper evidence.

    TWO LAYERS, because neither covers the other's case.

    1. Postgres (production): `pg_advisory_xact_lock` blocks any other backend
       — any other worker PROCESS — asking for the same key, until this
       transaction ends. This is the layer that matters in production, and it
       is the only one that can work across processes. Transaction-scoped (the
       `_xact_` variant) specifically so the caller's own commit/rollback
       releases it, since `record_audit` has no commit of its own to hang a
       release off.

       Note this is deliberately NOT `SELECT ... FOR UPDATE` on the latest
       row: a tenant writing its very FIRST audit row has no row to lock, so
       two concurrent first-writes would both lock nothing, both take
       GENESIS_HASH, and fork immediately. The lock has to exist independently
       of whether there is a row to lock.

    2. Every dialect, including SQLite (dev/tests): an in-process
       `asyncio.Lock` per tenant, held until the same transaction end. SQLite
       has no advisory locks at all, and its own write locking does not
       serialise this sequence — measured, not assumed: a no-op write issued
       before the read to force SQLite's RESERVED lock still let 8 concurrent
       appends fork into 4 chains. Since this backend runs single-process by
       policy (`--workers 1`; the in-memory broadcasters in
       `app.services.live_ops` require it), an in-process lock is a real
       guarantee here rather than a test-only convenience — and on Postgres
       it simply sits underneath the advisory lock, costing nothing.

    Re-entrant per session: a request that appends several audit rows in one
    transaction acquires once and releases once, instead of deadlocking on its
    own second call.
    """
    connection = await session.connection()
    if connection.dialect.name == "postgresql":
        await session.execute(
            text("SELECT pg_advisory_xact_lock(:key)"), {"key": _chain_lock_key(tenant_id)}
        )

    sync_session = session.sync_session
    if sync_session.info.get(_SESSION_LOCK_KEY) is not None:
        return  # already held by an earlier record_audit in this transaction

    lock = _tenant_append_locks.setdefault(tenant_id, asyncio.Lock())
    try:
        await asyncio.wait_for(lock.acquire(), timeout=CHAIN_LOCK_TIMEOUT_SECONDS)
    except TimeoutError:
        logger.error(
            "audit chain append lock for tenant %s not obtained within %.0fs; appending "
            "WITHOUT it. Something is holding a transaction open far too long, and this "
            "row could fork the chain.",
            tenant_id,
            CHAIN_LOCK_TIMEOUT_SECONDS,
        )
        return

    sync_session.info[_SESSION_LOCK_KEY] = lock
    if not event.contains(sync_session, "after_transaction_end", _release_on_transaction_end):
        event.listen(sync_session, "after_transaction_end", _release_on_transaction_end)


async def _latest_row(session: AsyncSession, *, tenant_id: str) -> tuple[str, datetime] | None:
    """The `hash` and `at` of the most recently written row in this tenant's
    chain, or None if the tenant has no audit rows yet. Ordered by `at` (the
    chain's chronological axis) with `id` as a tiebreaker for same-instant
    writes."""
    result = await session.execute(
        select(AuditLog.hash, AuditLog.at)
        .where(AuditLog.tenant_id == tenant_id)
        .order_by(AuditLog.at.desc(), AuditLog.id.desc())
        .limit(1)
    )
    row = result.first()
    if row is None:
        return None
    at = row[1]
    return row[0], at if at.tzinfo is not None else at.replace(tzinfo=UTC)


async def _latest_hash(session: AsyncSession, *, tenant_id: str) -> str:
    """The `hash` of the most recently written row in this tenant's chain, or
    `GENESIS_HASH` if the tenant has no audit rows yet."""
    latest = await _latest_row(session, tenant_id=tenant_id)
    return latest[0] if latest is not None else GENESIS_HASH


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

    ORDERING: `at` is forced strictly increasing per tenant (see the comment
    at the nudge below) because the chain's order is `at`, and equal
    timestamps make a row's predecessor ambiguous.

    CONCURRENCY: the latest-row read and this row's insert are serialised
    per tenant by `_acquire_chain_lock` — without it, two simultaneous audited
    writes on one tenant both read the same `previous_hash` and the chain
    forks permanently, which `verify_chain` can then never pass again. See
    that function for the Postgres/SQLite split.
    """
    at_value = at or datetime.now(UTC)
    # Must come BEFORE the read, or the read it is protecting has already
    # happened — see `_acquire_chain_lock`.
    await _acquire_chain_lock(session, tenant_id=tenant_id)

    latest = await _latest_row(session, tenant_id=tenant_id)
    if latest is None:
        previous_hash = GENESIS_HASH
    else:
        previous_hash, latest_at = latest
        if at_value <= latest_at:
            # STRICTLY INCREASING `at`, per tenant. The chain's order IS `at`
            # (see `_latest_row` and `verify_chain`, which both walk by it),
            # so two rows sharing an `at` make "the previous row" ambiguous:
            # the `id DESC` tiebreaker is a random UUID, which means the
            # appender and the verifier can pick DIFFERENT predecessors for
            # the same row and the chain fails verification even though
            # nothing was tampered with.
            #
            # This is not hypothetical. `datetime.now()` on Windows has
            # roughly millisecond granularity (not microsecond), so a burst
            # of audited writes — a fleet wipe closing a dozen shifts, a
            # duress cascade advancing four stages — routinely produces rows
            # with identical timestamps. Measured: 8 appends, fully
            # serialised, still produced only 6 distinct `previous_hash`
            # values before this.
            #
            # Nudging by one microsecond keeps the recorded time honest to
            # the microsecond (it is never moved backwards, and never forward
            # by more than the number of rows appended in the same tick)
            # while making the ordering total. Applies to a caller-supplied
            # `at` as well: a chain whose order is undefined is worse than a
            # timestamp that is a microsecond late.
            at_value = latest_at + timedelta(microseconds=1)
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
