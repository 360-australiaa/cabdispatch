"""Pydantic v2 schemas for the messages domain (dispatch<->driver threaded
messaging)."""
from __future__ import annotations

from datetime import datetime
from typing import Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field

SenderType = Literal["dispatch", "driver"]


# --- Pagination (local to this domain, same shape as app.schemas.fleet.Page,
# until a shared one exists in app.core) -----------------------------------------

T = TypeVar("T")


class Page(BaseModel, Generic[T]):
    items: list[T]
    total: int
    skip: int
    limit: int


# --- Message ------------------------------------------------------------------


class MessageCreate(BaseModel):
    """Body for `POST /v1/messages`. `driver_id` identifies whose thread the
    message belongs to — required for dispatch-side senders (owner/admin/
    dispatcher); ignored (and replaced with the caller's own id) for a
    `driver`-role sender. See `app.api.v1.messages`'s module docstring."""

    driver_id: str | None = None
    body: str = Field(min_length=1, max_length=4000)


class MessageRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    thread_id: str
    driver_id: str
    sender_type: SenderType
    sender_user_id: str | None
    body: str
    sent_at: datetime
    read_at: datetime | None
