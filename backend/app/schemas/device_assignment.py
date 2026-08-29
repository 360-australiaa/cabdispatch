"""Pydantic v2 schema for the device_assignments domain (plan D-3). Only a
Read schema is needed for this pass -- assignments are only ever created or
closed through service functions a sibling work package writes, never through
a generic CRUD request body."""
from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict


class DeviceAssignmentRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    device_id: str
    vehicle_id: str
    bound_at: datetime
    unbound_at: datetime | None
    bound_by_user_id: str
    pairing_code_id: str | None
    unbound_reason: str | None
    created_at: datetime
    updated_at: datetime