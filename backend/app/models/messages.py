"""Messages domain model — dispatch<->driver threaded messaging.

A "thread" is just every `Message` row sharing a `thread_id` for one driver's
ongoing conversation with dispatch. Per the domain brief this deliberately
does NOT build a generic multi-thread system — `thread_id` always equals the
driver's own id (one thread per driver; see `app.services.messages.send_message`,
which is the sole writer of this column). It's kept as its own column rather
than just aliasing `driver_id` in queries so a real future multi-thread need
wouldn't require a schema migration, without building that generality now.

DEVIATION (flagged, same precedent as `app.models.duress`): `driver_id` and
`sender_user_id` are plain indexed `String(36)` columns with NO ForeignKey
constraint to `users.id`. This keeps the module safely importable/creatable
in isolation ahead of the integration step that registers every domain's
models together on `Base.metadata` (see `app/api/v1/messages.py`'s module
docstring — this router is not yet wired into `app.main` either). Tenant
isolation is enforced at the application layer via `get_current_tenant_id`,
not via these FKs.

Also deviates from the `TimestampMixin` convention used elsewhere in this
codebase (no generic `updated_at`) — same precedent as
`app.models.audit_log.AuditLog` / `app.models.tariffs.TariffChangeLog`: this
table has its own purpose-built timestamp, `sent_at`. `read_at` is the one
field ever mutated after insert (see `app.services.messages.mark_read`).
"""
from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import DateTime, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin

SENDER_TYPE_DISPATCH = "dispatch"
SENDER_TYPE_DRIVER = "driver"
SENDER_TYPES = {SENDER_TYPE_DISPATCH, SENDER_TYPE_DRIVER}


class Message(Base, TenantScopedMixin):
    __tablename__ = "messages"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))

    # One thread per driver by convention — see module docstring.
    thread_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)
    driver_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)

    sender_type: Mapped[str] = mapped_column(String(10), nullable=False)
    # Nullable: reserved for a future system/automated dispatch message with
    # no human sender, mirrors app.models.audit_log.AuditLog.actor_user_id.
    # Every message sent through app.api.v1.messages today always sets this
    # to the caller's own user id.
    sender_user_id: Mapped[str | None] = mapped_column(String(36), nullable=True)

    body: Mapped[str] = mapped_column(Text, nullable=False)

    # server_default is a DB-level safety net; app.services.messages.send_message
    # always sets this explicitly in Python so the value is available
    # immediately on the returned object without needing a round-trip refresh
    # (same pattern as app.models.audit_log.AuditLog.at).
    sent_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False, index=True
    )
    read_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
