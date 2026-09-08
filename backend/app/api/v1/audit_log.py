"""Audit Log domain API — `/v1/audit-log`.

APPEND-ONLY BY DESIGN: this router deliberately exposes ONLY `GET` (list,
verify). There is intentionally NO create, update, or delete endpoint of any
kind reachable over HTTP — a tamper-evidence/audit trail that can be written
or edited by an arbitrary API client isn't one; every row must originate
from the server's own code, and once written is permanent. (Same
never-mutate rule already established in this codebase for the sibling
append-only `psl_topups` table — see app/models/psl_ledger.py.)

An earlier version of this router also exposed `POST /v1/audit-log`, letting
ANY authenticated tenant user write an arbitrary, freeform entry into the
hash chain via a plain API call. That defeated the point of an evidentiary,
tamper-evident log: a write path reachable by a client is a write path a
client (or anything holding a stolen token) can abuse to plant misleading
entries, and it invited every future caller to log via HTTP instead of
`record_audit()`, leaving those entries without the compile-time guarantee
that `actor_user_id`/`tenant_id` came from the authenticated session rather
than a client-controlled body. It has been removed. Verified before removal
that nothing calls it: the dashboard's `pages/audit-log/**` only ever issues
`GET /v1/audit-log` and `GET /v1/audit-log/verify` (grepped
`dashboard/src/pages/audit-log/api.ts`); the Android app has no reference to
`/v1/audit-log` anywhere in `android/` (grepped for the literal path and for
`AuditLogCreate`); and the only caller in this repo was this domain's own
test file, which posted to the endpoint purely as a stand-in for
`record_audit()` — that file has been rewritten to call `record_audit()`
directly, which is what every other domain's service layer already does.

Every query below is filtered by `tenant_id` via `get_current_tenant_id` —
the sole multi-tenancy isolation boundary in this system.

For PROGRAMMATIC logging from other domains' routers/services: import
`record_audit` (or the `get_audit_logger` Depends()-able convenience
wrapper) from `app.services.audit_log` directly and call it as part of your
own mutation's transaction. There is no other way to write a row.
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
from app.schemas.audit_log import AuditLogListResponse, AuditLogVerifyResponse
from app.services.audit_log import verify_chain

router = APIRouter(prefix="/v1/audit-log", tags=["audit-log"])


@router.get("", response_model=AuditLogListResponse)
async def list_audit_log_entries(
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
    limit: int = Query(default=50, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
    entity_type: str | None = Query(default=None),
    entity_id: str | None = Query(default=None),
    subject_id: str | None = Query(
        default=None,
        description=(
            "Alias for `entity_id` -- the id of the record an entry is ABOUT "
            "(e.g. a driver or vehicle), for callers (a driver/vehicle detail "
            "page's Activity tab) that don't know or care about this table's "
            "own `entity_type`/`entity_id` naming. Filters the same "
            "`AuditLog.entity_id` column entity_id does; if both are given "
            "they must agree with each other for a row to match (both are "
            "ANDed in, matching how entity_type + entity_id combine above)."
        ),
    ),
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
    if subject_id is not None:
        filters.append(AuditLog.entity_id == subject_id)
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
