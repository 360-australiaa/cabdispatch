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
"""
from __future__ import annotations

from datetime import UTC, datetime
from typing import Any, Protocol

from fastapi import Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import get_current_tenant_id, get_current_user
from app.models.audit_log import AuditLog
from app.models.user import User


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
    """
    entry = AuditLog(
        tenant_id=tenant_id,
        actor_user_id=actor_user_id,
        action=action,
        entity_type=entity_type,
        entity_id=entity_id,
        before_json=before,
        after_json=after,
        at=at or datetime.now(UTC),
    )
    session.add(entry)
    await session.flush()
    return entry


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
