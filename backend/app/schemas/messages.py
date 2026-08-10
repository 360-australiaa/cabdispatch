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


# --- canned message templates --------------------------------------------------
# See `app.services.messages.MESSAGE_TEMPLATES` for the fixed template list this
# mirrors — modeled on a real taxi-meter driver quick-request menu ("No Job" /
# "Recall" / "Job Query" / "Other") plus dispatch-initiated "Vehicle Initiated
# Message" style status templates. No new table: a small fixed set, same
# rationale as `app.models.fatigue_alert`'s plain-string `kind` constants.


class MessageTemplateRead(BaseModel):
    """One entry of `GET /v1/messages/templates` — lets the client render the
    quick-tap menu without hardcoding codes/labels itself."""

    code: str
    label: str
    sender_type: SenderType


class TemplateMessageCreate(BaseModel):
    """Body for `POST /v1/messages/templates/{code}`. Same `driver_id` rule as
    `MessageCreate` — required for a dispatch-side sender, ignored (replaced
    with the caller's own id) for a `driver`-role sender. `note` is an
    optional free-text suffix, primarily meant for the driver-side "Other"
    template, but accepted (and appended, when given) on any template."""

    driver_id: str | None = None
    note: str | None = Field(default=None, max_length=500)
