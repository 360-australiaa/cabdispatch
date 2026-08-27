"""Pydantic v2 schemas for the duress camera-snapshot gallery -- see
app.models.duress_snapshot.DuressSnapshot for the table this backs and
app.services.duress for the upload/list/broadcast logic.
"""
from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict


class DuressSnapshotRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    event_id: str
    captured_at: datetime
    created_at: datetime


class DuressSnapshotListResponse(BaseModel):
    items: list[DuressSnapshotRead]
    total: int