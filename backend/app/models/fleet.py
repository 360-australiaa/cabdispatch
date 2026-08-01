"""Fleet domain models: `Vehicle` (a tenant's physical taxi), `Device` (an Android
tablet/kiosk unit — the taxi-meter app runs on these), and `DevicePairingCode`
(a short-lived, single-use code an admin generates for a vehicle so a device can
QR-pair itself to it via `POST /v1/fleet/devices/register`).

Multi-tenancy note: all three tables use the standard `TenantScopedMixin` — every
row always carries a real tenant_id, there is no platform-wide/global row pattern
here (unlike `app.models.tariffs`).
"""
from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin, TimestampMixin

# --- Vehicle enums (plain string constants, portable across sqlite/postgres —
# same convention as app.models.user's ROLE_* constants) -------------------------

VEHICLE_CLASS_STANDARD = "standard"
VEHICLE_CLASS_PREMIUM = "premium"
VEHICLE_CLASS_MAXI = "maxi"
VEHICLE_CLASS_WAT = "wat"  # Wheelchair Accessible Taxi
VALID_VEHICLE_CLASSES = (
    VEHICLE_CLASS_STANDARD,
    VEHICLE_CLASS_PREMIUM,
    VEHICLE_CLASS_MAXI,
    VEHICLE_CLASS_WAT,
)

VEHICLE_STATUS_ACTIVE = "active"
VEHICLE_STATUS_MAINTENANCE = "maintenance"
VEHICLE_STATUS_SUSPENDED = "suspended"
VEHICLE_STATUS_RETIRED = "retired"
VALID_VEHICLE_STATUSES = (
    VEHICLE_STATUS_ACTIVE,
    VEHICLE_STATUS_MAINTENANCE,
    VEHICLE_STATUS_SUSPENDED,
    VEHICLE_STATUS_RETIRED,
)


class Vehicle(Base, TimestampMixin, TenantScopedMixin):
    __tablename__ = "vehicles"
    __table_args__ = (UniqueConstraint("tenant_id", "rego", name="uq_vehicles_tenant_rego"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    rego: Mapped[str] = mapped_column(String(20), nullable=False, index=True)
    vin: Mapped[str | None] = mapped_column(String(32), nullable=True)
    vehicle_class: Mapped[str] = mapped_column(String(20), nullable=False, default=VEHICLE_CLASS_STANDARD)
    camera_serial: Mapped[str | None] = mapped_column(String(100), nullable=True)
    tracking_device_id: Mapped[str | None] = mapped_column(String(100), nullable=True)
    meter_device_id: Mapped[str | None] = mapped_column(String(100), nullable=True)
    status: Mapped[str] = mapped_column(String(20), nullable=False, default=VEHICLE_STATUS_ACTIVE)


class Device(Base, TimestampMixin, TenantScopedMixin):
    __tablename__ = "devices"
    __table_args__ = (UniqueConstraint("tenant_id", "android_id", name="uq_devices_tenant_android_id"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    android_id: Mapped[str] = mapped_column(String(100), nullable=False, index=True)
    model: Mapped[str | None] = mapped_column(String(100), nullable=True)
    app_version: Mapped[str | None] = mapped_column(String(30), nullable=True)
    vehicle_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("vehicles.id"), nullable=True, index=True
    )
    kiosk_locked: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    # Not in the original field list — added so POST /force-update has a flag to
    # set that the device can actually read back on its next heartbeat. See
    # deviation note in the domain summary.
    force_update_pending: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    battery: Mapped[int | None] = mapped_column(Integer, nullable=True)  # 0-100
    network: Mapped[str | None] = mapped_column(String(20), nullable=True)  # e.g. "wifi", "4g", "offline"


class DevicePairingCode(Base, TimestampMixin, TenantScopedMixin):
    """Short-lived, single-use QR-pairing code minted for one vehicle at a time
    (`POST /v1/fleet/vehicles/{id}/pairing-code`) and consumed by
    `POST /v1/fleet/devices/register`. Append-only from the API's perspective —
    rows are only ever created or marked used by the service layer, never
    directly updated/deleted through the router (no update/delete endpoints)."""

    __tablename__ = "device_pairing_codes"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    vehicle_id: Mapped[str] = mapped_column(String(36), ForeignKey("vehicles.id"), nullable=False, index=True)
    code: Mapped[str] = mapped_column(String(12), nullable=False, index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    used_by_device_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("devices.id"), nullable=True
    )
