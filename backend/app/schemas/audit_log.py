"""Pydantic v2 schemas for the Audit Log domain.

No `AuditLogCreate`/`AuditLogUpdate` schema — deliberately: the domain is
append-only and has no HTTP create or update endpoint. Rows are written only
by the server's own `app.services.audit_log.record_audit()`, called from
inside another domain's own mutation (see app/api/v1/audit_log.py's module
docstring for why the API-writable version of this endpoint was removed).
"""
from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict


class AuditLogRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    actor_user_id: str | None
    action: str
    entity_type: str
    entity_id: str
    before_json: dict[str, Any] | None
    after_json: dict[str, Any] | None
    at: datetime
    hash: str
    previous_hash: str


class AuditLogListResponse(BaseModel):
    items: list[AuditLogRead]
    total: int
    limit: int
    offset: int


class AuditLogVerifyResponse(BaseModel):
    """Response for `GET /v1/audit-log/verify` — see
    app.services.audit_log.verify_chain for what each field means."""

    valid: bool
    broken_at_id: str | None
    checked: int
