"""Pydantic v2 schemas for the Audit Log domain.

No `AuditLogUpdate` schema — deliberately: the domain is append-only, there is
no update endpoint (see app/api/v1/audit_log.py).
"""
from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class AuditLogCreate(BaseModel):
    """Body for `POST /v1/audit-log`. Note there is no `actor_user_id` field —
    the endpoint always attributes the entry to the authenticated caller
    (`get_current_user`); a client cannot forge another user as the actor."""

    action: str = Field(..., min_length=1, max_length=100)
    entity_type: str = Field(..., min_length=1, max_length=100)
    entity_id: str = Field(..., min_length=1, max_length=36)
    before_json: dict[str, Any] | None = None
    after_json: dict[str, Any] | None = None


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


class AuditLogListResponse(BaseModel):
    items: list[AuditLogRead]
    total: int
    limit: int
    offset: int
