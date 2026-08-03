"""DuressEvent model — the panic-button / covert-alarm incident record.

A duress event is opened the instant a driver (or an automated trigger, e.g. a
meter tamper sensor) signals danger. It then runs through a short state
machine:

    open -> escalating -> dispatched -> resolved
      \\-> cancelled (only inside the cancel window, see below)

`escalation_log_json` is the single source of truth for both the timeline of
what happened AND the bookkeeping needed to run the state machine (the cancel
deadline, and which escalation stage comes next). Shape:

    {
        "cancel_window_seconds": 10,
        "cancel_deadline_at": "<iso8601>",
        "next_stage_index": 0,          # index into ESCALATION_STAGES
        "entries": [
            {"stage": "opened", "at": "<iso8601>", "detail": "..."},
            ...
        ],
    }

See `app.services.duress` for the state-machine transitions and
`app.models.duress.ESCALATION_STAGES` for the fixed escalation cascade.

DEVIATION (flagged per task instructions, matching the precedent already set by
the sibling `shifts`/`trips` domains): `vehicle_id` and `driver_id` are plain
indexed `String(36)` columns with NO ForeignKey constraint. Both *could* validly
FK `users.id`/a future `vehicles.id`, but are left unconstrained so
`Base.metadata.create_all` / Alembic autogenerate stay order-independent of
tables this domain doesn't own (this agent must not touch
`app/models/__init__.py`, so it cannot guarantee sibling models are registered
on `Base.metadata` when this module is imported in isolation). Multi-tenant
isolation is enforced at the application layer via `get_current_tenant_id`, not
via these FKs. A later integration step can add the FKs once all domains are
registered together.
"""
from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import JSON, DateTime, String
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin, TimestampMixin

# --- trigger / status enums (plain strings, sqlite/postgres portable) -------

DURESS_TRIGGER_BUTTON = "button"
DURESS_TRIGGER_GESTURE = "gesture"
DURESS_TRIGGER_VOICE = "voice"
DURESS_TRIGGER_AUTO = "auto"
DURESS_TRIGGERS = {
    DURESS_TRIGGER_BUTTON,
    DURESS_TRIGGER_GESTURE,
    DURESS_TRIGGER_VOICE,
    DURESS_TRIGGER_AUTO,
}

DURESS_STATUS_OPEN = "open"
DURESS_STATUS_ESCALATING = "escalating"
DURESS_STATUS_DISPATCHED = "dispatched"
DURESS_STATUS_RESOLVED = "resolved"
DURESS_STATUS_CANCELLED = "cancelled"
DURESS_STATUSES = {
    DURESS_STATUS_OPEN,
    DURESS_STATUS_ESCALATING,
    DURESS_STATUS_DISPATCHED,
    DURESS_STATUS_RESOLVED,
    DURESS_STATUS_CANCELLED,
}

# Terminal statuses — no further state transitions permitted once here.
DURESS_TERMINAL_STATUSES = {DURESS_STATUS_RESOLVED, DURESS_STATUS_CANCELLED}

# The fixed escalation cascade. Each `POST /v1/duress/{id}/escalate` call
# advances exactly one stage (manually — from a background job or a dispatcher
# clicking a button in this pass, NOT a real server-side timer). The first
# stage flips status open -> escalating; the last stage flips
# escalating -> dispatched. See `app.services.duress.escalate_event`.
ESCALATION_STAGE_CANCEL_WINDOW_EXPIRED = "cancel_window_expired"
ESCALATION_STAGE_NOTIFY_DISPATCH = "notify_dispatch"
ESCALATION_STAGE_SMS_EMERGENCY_CONTACTS = "sms_emergency_contacts"
ESCALATION_STAGE_PRESENT_000_CALL_SCRIPT = "present_000_call_script"
ESCALATION_STAGES: list[str] = [
    ESCALATION_STAGE_CANCEL_WINDOW_EXPIRED,
    ESCALATION_STAGE_NOTIFY_DISPATCH,
    ESCALATION_STAGE_SMS_EMERGENCY_CONTACTS,
    ESCALATION_STAGE_PRESENT_000_CALL_SCRIPT,
]

# Wall-clock length of the window in which the driver may self-cancel a
# just-opened event before dispatch escalation is presumed to have taken over.
CANCEL_WINDOW_SECONDS = 10


class DuressEvent(Base, TenantScopedMixin, TimestampMixin):
    __tablename__ = "duress_events"

    id: Mapped[str] = mapped_column(
        String(36), primary_key=True, default=lambda: str(uuid.uuid4())
    )

    # --- assignment (unconstrained cross-domain refs, see module docstring) ---
    vehicle_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)
    driver_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)

    trigger: Mapped[str] = mapped_column(String(10), nullable=False)
    status: Mapped[str] = mapped_column(
        String(12), nullable=False, default=DURESS_STATUS_OPEN, index=True
    )

    opened_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    closed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    # Reference to the (out-of-band, e.g. object-storage) continuous GPS trace
    # captured for this event. Required — every duress event must be locatable.
    gps_stream_ref: Mapped[str] = mapped_column(String(255), nullable=False)
    # Reference to a captured audio recording, if the device supports it.
    # Either a device-supplied placeholder ref set at trigger time (e.g.
    # `s3://...`, or the default from `DuressTriggerRequest.audio_ref`), or —
    # once `POST /v1/duress/{id}/audio` has been called — the actual
    # BACKEND_ROOT-relative on-disk path of the uploaded recording (see
    # `app.services.duress.save_duress_audio`), which overwrites whichever
    # placeholder was there before.
    audio_ref: Mapped[str | None] = mapped_column(String(255), nullable=True)

    # Cancel-window bookkeeping + the full timestamped escalation timeline —
    # see module docstring for the exact shape. Always replaced wholesale (a
    # brand-new dict, never mutated in place) on every state transition so
    # SQLAlchemy's change-tracking reliably picks it up without a Mutable*
    # wrapper.
    escalation_log_json: Mapped[dict] = mapped_column(JSON, nullable=False)
