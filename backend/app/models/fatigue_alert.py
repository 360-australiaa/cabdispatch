"""FatigueAlert model — driver fatigue / speed-compliance alerts (blueprint
12.3: "Driver Fatigue Monitoring: Shift duration limits, mandatory break
alerts" + "Speed Monitoring: Alert if vehicle exceeds speed limit by
>20km/h").

Alerts are raised server-side by `app.services.fatigue`, never created
directly through the API — `app/api/v1/fatigue_alerts.py` only lists and
acknowledges them. See that service module for the exact trigger logic and
its documented simplifications (flat speed-limit assumption, no
tenant-settings table for the shift-duration threshold, `no_break_taken`
defined but not yet wired to a trigger).

DEVIATION (flagged per task instructions, matching the precedent already set
by the sibling `shifts`/`trips`/`duress` domains): `driver_id` and `shift_id`
are plain indexed `String(36)` columns with NO ForeignKey constraint, for the
same reason given in `app.models.shift`'s module docstring — multi-tenant
isolation and referential validity are enforced at the application layer via
`get_current_tenant_id`, not via these FKs.
"""
from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import JSON, Boolean, DateTime, String
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin, TimestampMixin

# --- alert kind enum (plain string constants, sqlite/postgres portable —
# same convention as app.models.fleet's VEHICLE_CLASS_* constants) -----------

FATIGUE_ALERT_SHIFT_DURATION_EXCEEDED = "shift_duration_exceeded"
# Defined per the blueprint's "mandatory break alerts" requirement, but NOT
# wired to any trigger in this pass — the Shift model has no persisted
# break-start/break-end fields to check against (see app.models.shift), so
# there is nothing to detect a missed break from yet. Kept here so the kind
# is a real, stable value the moment break-tracking is added, rather than a
# second migration later.
FATIGUE_ALERT_NO_BREAK_TAKEN = "no_break_taken"
FATIGUE_ALERT_SPEED_EXCEEDED = "speed_exceeded"
FATIGUE_ALERT_KINDS = {
    FATIGUE_ALERT_SHIFT_DURATION_EXCEEDED,
    FATIGUE_ALERT_NO_BREAK_TAKEN,
    FATIGUE_ALERT_SPEED_EXCEEDED,
}


class FatigueAlert(Base, TenantScopedMixin, TimestampMixin):
    __tablename__ = "fatigue_alerts"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))

    # --- assignment (unconstrained cross-domain refs, see module docstring) ---
    driver_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)
    shift_id: Mapped[str | None] = mapped_column(String(36), nullable=True, index=True)

    kind: Mapped[str] = mapped_column(String(30), nullable=False, index=True)
    triggered_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

    # Freeform context for the alert — e.g. {"elapsed_hours": 12.4, "limit_hours": 12}
    # for shift_duration_exceeded, or {"speed_kmh": 128.3, "threshold_kmh": 120}
    # for speed_exceeded. Shape varies by kind; not enforced here.
    details_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)

    acknowledged: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
