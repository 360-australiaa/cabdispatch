"""FatigueAlert model — driver fatigue / speed-compliance alerts (blueprint
12.3: "Driver Fatigue Monitoring: Shift duration limits, mandatory break
alerts" + "Speed Monitoring: Alert if vehicle exceeds speed limit by
>20km/h").

Alerts are raised server-side by `app.services.fatigue`, never created
directly through the API — `app/api/v1/fatigue_alerts.py` only lists and
acknowledges them. See that service module for the exact trigger logic and
its documented simplifications (flat speed-limit assumption, no
tenant-settings table for the shift-duration or no-break-taken thresholds).

DEVIATION (flagged per task instructions, matching the precedent already set
by the sibling `shifts`/`trips`/`duress` domains): `driver_id`, `vehicle_id`
and `shift_id` are plain indexed `String(36)` columns with NO ForeignKey
constraint, for the same reason given in `app.models.shift`'s module
docstring — multi-tenant isolation and referential validity are enforced at
the application layer via `get_current_tenant_id`, not via these FKs.

DEVIATION (compliance-expiry pass, blueprint 7.2.3/7.2.4/10.1, added on top of
the original fatigue/speed alert pass): `driver_id` was originally NOT NULL —
every alert used to be tied to a driver. The new
registration_expiring_soon/registration_expired/insurance_expiring_soon/
insurance_expired kinds (see app.services.compliance_expiry) are raised
against a *vehicle*, which has no driver at all in general (a car isn't always
mid-shift when its rego lapses), so `driver_id` is now nullable and a sibling
`vehicle_id` column was added. Every pre-existing kind (shift_duration_exceeded,
no_break_taken, speed_exceeded) is unaffected — those call sites always pass a
real driver_id, exactly as before.
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
# Wired to a real trigger by the break-tracking pass (see app.services.shift
# start_break/end_break and app.services.fatigue.check_no_break_taken) --
# the Shift model now carries break_started_at/break_taken (see
# app.models.shift.Shift's break-tracking DEVIATION note) to check against.
FATIGUE_ALERT_NO_BREAK_TAKEN = "no_break_taken"
FATIGUE_ALERT_SPEED_EXCEEDED = "speed_exceeded"

# --- compliance-expiry kinds (blueprint 7.2.3/7.2.4/10.1), raised by
# app.services.compliance_expiry — see that module for the exact
# expiring-soon/expired thresholds and dedup rules. Driver-facing kinds carry
# `driver_id` (like the pre-existing kinds above); vehicle-facing kinds carry
# `vehicle_id` instead (see the class-level DEVIATION note on why `driver_id`
# had to become nullable for these). -----------------------------------------
FATIGUE_ALERT_LICENSE_EXPIRING_SOON = "license_expiring_soon"
FATIGUE_ALERT_LICENSE_EXPIRED = "license_expired"
FATIGUE_ALERT_AUTHORITY_EXPIRING_SOON = "authority_expiring_soon"
FATIGUE_ALERT_AUTHORITY_EXPIRED = "authority_expired"
FATIGUE_ALERT_REGISTRATION_EXPIRING_SOON = "registration_expiring_soon"
FATIGUE_ALERT_REGISTRATION_EXPIRED = "registration_expired"
FATIGUE_ALERT_INSURANCE_EXPIRING_SOON = "insurance_expiring_soon"
FATIGUE_ALERT_INSURANCE_EXPIRED = "insurance_expired"

# Meter re-verification / calibration due-date kinds (operations-cycle
# tracking pass, on top of the compliance-expiry pass above), raised by
# app.services.compliance_expiry against the VEHICLE the calibration-due
# Device is currently paired to (a Device has no driver, and FatigueAlert has
# no device_id column — see app.models.fleet.Device.calibration_due's doc
# comment for why calibration lives on Device, not Vehicle). Same naming
# pattern as the registration/insurance kinds above.
FATIGUE_ALERT_CALIBRATION_EXPIRING_SOON = "calibration_expiring_soon"
FATIGUE_ALERT_CALIBRATION_EXPIRED = "calibration_expired"

FATIGUE_ALERT_KINDS = {
    FATIGUE_ALERT_SHIFT_DURATION_EXCEEDED,
    FATIGUE_ALERT_NO_BREAK_TAKEN,
    FATIGUE_ALERT_SPEED_EXCEEDED,
    FATIGUE_ALERT_LICENSE_EXPIRING_SOON,
    FATIGUE_ALERT_LICENSE_EXPIRED,
    FATIGUE_ALERT_AUTHORITY_EXPIRING_SOON,
    FATIGUE_ALERT_AUTHORITY_EXPIRED,
    FATIGUE_ALERT_REGISTRATION_EXPIRING_SOON,
    FATIGUE_ALERT_REGISTRATION_EXPIRED,
    FATIGUE_ALERT_INSURANCE_EXPIRING_SOON,
    FATIGUE_ALERT_INSURANCE_EXPIRED,
    FATIGUE_ALERT_CALIBRATION_EXPIRING_SOON,
    FATIGUE_ALERT_CALIBRATION_EXPIRED,
}

# Kinds raised against a vehicle (no driver_id) rather than a driver.
FATIGUE_ALERT_VEHICLE_KINDS = {
    FATIGUE_ALERT_REGISTRATION_EXPIRING_SOON,
    FATIGUE_ALERT_REGISTRATION_EXPIRED,
    FATIGUE_ALERT_INSURANCE_EXPIRING_SOON,
    FATIGUE_ALERT_INSURANCE_EXPIRED,
    FATIGUE_ALERT_CALIBRATION_EXPIRING_SOON,
    FATIGUE_ALERT_CALIBRATION_EXPIRED,
}


class FatigueAlert(Base, TenantScopedMixin, TimestampMixin):
    __tablename__ = "fatigue_alerts"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))

    # --- assignment (unconstrained cross-domain refs, see module docstring) ---
    # driver_id is nullable — see the class-level DEVIATION note — but every
    # kind in FATIGUE_ALERT_KINDS except FATIGUE_ALERT_VEHICLE_KINDS still
    # always sets it; only vehicle-facing compliance-expiry alerts leave it
    # null and set vehicle_id instead.
    driver_id: Mapped[str | None] = mapped_column(String(36), nullable=True, index=True)
    vehicle_id: Mapped[str | None] = mapped_column(String(36), nullable=True, index=True)
    shift_id: Mapped[str | None] = mapped_column(String(36), nullable=True, index=True)

    kind: Mapped[str] = mapped_column(String(30), nullable=False, index=True)
    triggered_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

    # Freeform context for the alert — e.g. {"elapsed_hours": 12.4, "limit_hours": 12}
    # for shift_duration_exceeded, {"speed_kmh": 128.3, "threshold_kmh": 120}
    # for speed_exceeded, or {"expiry_date": "2026-09-01", "days_remaining": 12}
    # for the compliance-expiry kinds. Shape varies by kind; not enforced here.
    details_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)

    acknowledged: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
