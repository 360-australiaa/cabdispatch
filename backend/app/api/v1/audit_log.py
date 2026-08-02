"""Audit Log domain API — `/v1/audit-log`.

APPEND-ONLY BY DESIGN: this router deliberately exposes ONLY `POST` (create)
and `GET` (list). There is intentionally NO update or delete endpoint of any
kind — a tamper-evidence/audit trail that can be silently edited or removed
after the fact isn't one; every row, once written, is permanent. (Same rule
already established in this codebase for the sibling append-only
`psl_topups` table — see app/models/psl_ledger.py.)

Every query below is filtered by `tenant_id` via `get_current_tenant_id` —
the sole multi-tenancy isolation boundary in this system.

For PROGRAMMATIC logging from other domains' routers/services, prefer
importing `record_audit` (or the `get_audit_logger` Depends()-able
convenience wrapper) from `app.services.audit_log` directly, rather than
issuing an HTTP call to this router's own POST endpoint — see that module's
docstring for the intended usage pattern. This router's `POST` endpoint
exists for: (a) CRUD completeness / manual admin logging via the API, and
(b) as the target endpoint the self-test in tests/test_audit_log.py checks
a directly-recorded entry shows up in.
"""
from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, Query
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, get_current_user, require_role
from app.models.audit_log import AuditLog
from app.models.user import User
from app.schemas.audit_log import (
    AuditLogCreate,
    AuditLogListResponse,
    AuditLogRead,
    AuditLogVerifyResponse,
)
from app.services.audit_log import record_audit, verify_chain

router = APIRouter(prefix="/v1/audit-log", tags=["audit-log"])


@router.post("", response_model=AuditLogRead, status_code=201)
async def create_audit_log_entry(
    body: AuditLogCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> AuditLog:
    """Any authenticated tenant user may write an audit entry. The entry
    always records the CALLER (`get_current_user`) as `actor_user_id` — this
    is not a client-supplied field on `AuditLogCreate`, precisely so a client
    cannot forge an entry attributed to a different user."""
    entry = await record_audit(
        session,
        tenant_id=tenant_id,
        actor_user_id=user.id,
        action=body.action,
        entity_type=body.entity_type,
        entity_id=body.entity_id,
        before=body.before_json,
        after=body.after_json,
    )
    await session.commit()
    await session.refresh(entry)
    return entry


@router.get("", response_model=AuditLogListResponse)
async def list_audit_log_entries(
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
    limit: int = Query(default=50, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
    entity_type: str | None = Query(default=None),
    entity_id: str | None = Query(default=None),
    actor_user_id: str | None = Query(default=None),
    action: str | None = Query(default=None),
    at_from: datetime | None = Query(default=None, description="Inclusive lower bound on `at`."),
    at_to: datetime | None = Query(default=None, description="Inclusive upper bound on `at`."),
) -> dict:
    filters = [AuditLog.tenant_id == tenant_id]
    if entity_type is not None:
        filters.append(AuditLog.entity_type == entity_type)
    if entity_id is not None:
        filters.append(AuditLog.entity_id == entity_id)
    if actor_user_id is not None:
        filters.append(AuditLog.actor_user_id == actor_user_id)
    if action is not None:
        filters.append(AuditLog.action == action)
    if at_from is not None:
        filters.append(AuditLog.at >= at_from)
    if at_to is not None:
        filters.append(AuditLog.at <= at_to)

    total = (await session.execute(select(func.count(AuditLog.id)).where(*filters))).scalar_one()

    result = await session.execute(
        select(AuditLog).where(*filters).order_by(AuditLog.at.desc()).limit(limit).offset(offset)
    )
    items = result.scalars().all()

    return {"items": items, "total": total, "limit": limit, "offset": offset}


@router.get("/verify", response_model=AuditLogVerifyResponse)
async def verify_audit_log_chain(
    tenant_id: str = Depends(get_current_tenant_id),
    _admin: User = Depends(require_role("owner", "admin")),
    session: AsyncSession = Depends(get_session),
) -> dict:
    """Admin/owner-only. Walks the CALLING TENANT's full hash chain, oldest
    row first, recomputing every row's hash from its own stored fields plus
    the preceding row's stored hash, to confirm the `previous_hash` linkage
    is intact end to end — see app.services.audit_log.verify_chain and the
    hash-chain section of app/models/audit_log.py's module docstring for the
    tamper-evidence design this checks. A row edited directly at the DB
    layer (bypassing this router/service entirely, so the append-only
    convention alone wouldn't catch it) breaks the chain at that row's id.
    """
    valid, broken_at_id, checked = await verify_chain(session, tenant_id=tenant_id)
    return {"valid": valid, "broken_at_id": broken_at_id, "checked": checked}


# NOTE: no PATCH/PUT/DELETE endpoints anywhere in this file — deliberate, see
# module docstring. This is the entire surface of the Audit Log API.
