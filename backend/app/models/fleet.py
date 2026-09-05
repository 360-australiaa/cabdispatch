"""Fleet domain models: `Vehicle` (a tenant's physical taxi), `Device` (an Android
tablet/kiosk unit — the taxi-meter app runs on these), and `DevicePairingCode`
(a short-lived, single-use code an admin generates for a vehicle so a device can
QR-pair itself to it via `POST /v1/fleet/devices/register`).

Also `DeviceVersionHistory` and `VehiclePositionHistory` — two append-only
history tables that don't belong to THIS domain (they're written and read by
`app.services.fleet`'s heartbeat handler and `app.services.live_ops`'s
position pipeline respectively, not by anything in `app.services.fleet`
proper for the latter), but live here because their natural home is next to
the `Device`/`Vehicle` rows they each reference — same rationale as
`app.services.live_ops`'s own module docstring, which explains why THAT
domain owns no models file of its own and instead reads/writes these
sibling-domain tables directly.

Multi-tenancy note: every table in this file uses the standard
`TenantScopedMixin` — every row always carries a real tenant_id, there is no
platform-wide/global row pattern here (unlike `app.models.tariffs`).
"""
from __future__ import annotations

import uuid
from datetime import date, datetime

from sqlalchemy import Boolean, Date, DateTime, Float, ForeignKey, Integer, String, UniqueConstraint
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
    make: Mapped[str | None] = mapped_column(String(60), nullable=True)
    model: Mapped[str | None] = mapped_column(String(60), nullable=True)
    vehicle_class: Mapped[str] = mapped_column(String(20), nullable=False, default=VEHICLE_CLASS_STANDARD)
    camera_serial: Mapped[str | None] = mapped_column(String(100), nullable=True)
    tracking_device_id: Mapped[str | None] = mapped_column(String(100), nullable=True)
    meter_device_id: Mapped[str | None] = mapped_column(String(100), nullable=True)
    status: Mapped[str] = mapped_column(String(20), nullable=False, default=VEHICLE_STATUS_ACTIVE)

    # Compliance-expiry tracking (blueprint 7.2.4/10.1) — same nullable,
    # fail-open-on-missing-data convention as User.driver_license_expiry /
    # User.driver_authority_expiry (see that model's doc comment): null means
    # "unknown", not "expired", and never blocks anything on its own. See
    # app.services.compliance_expiry for the expiring-soon/expired detection
    # logic and GET /v1/fleet/compliance-expiry for the dashboard listing.
    registration_expiry: Mapped[date | None] = mapped_column(Date, nullable=True)
    insurance_expiry: Mapped[date | None] = mapped_column(Date, nullable=True)


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
    # MDM-lite remote command flags (blueprint 4.1.3/6.2.1), same "admin sets it,
    # device reads it back on next heartbeat" convention as kiosk_locked /
    # force_update_pending above — no new transport, this just extends the
    # existing flag-polling pattern.
    locate_requested: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    # HONESTY NOTE: this is a real, useful command QUEUE — an admin can set it
    # via POST /devices/{id}/reboot, it's visible on the device row, and a
    # dashboard can show it pending — but nothing in this codebase (and nothing
    # a normal, non-device-owner Android app can do) actually reboots the OS on
    # seeing this flag set. A genuine remote reboot needs the on-device app
    # enrolled as Android Device Owner (DevicePolicyManager.reboot()), which
    # requires zero-touch/QR provisioning at device setup — that provisioning
    # is out of scope for this pass. Do not represent this flag as "the device
    # will reboot"; it queues the request only, for a future device-owner-aware
    # app build to act on.
    reboot_requested: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    battery: Mapped[int | None] = mapped_column(Integer, nullable=True)  # 0-100
    network: Mapped[str | None] = mapped_column(String(20), nullable=True)  # e.g. "wifi", "4g", "offline"

    # Meter re-verification due-date (operations-cycle tracking pass, on top of
    # the compliance-expiry pass above). Same nullable, fail-open-on-missing-
    # data convention as Vehicle.registration_expiry/insurance_expiry: null
    # means "unknown", not "expired", and never blocks anything on its own.
    # This is the meter's statutory periodic-calibration due-date (the
    # taxi-meter equivalent of a cl 14 re-verification requirement), tracked
    # on the Device (the tablet/kiosk unit the meter app runs on) rather than
    # the Vehicle, since calibration is a property of the physical meter
    # instrument, not the car it's currently paired to. See
    # app.services.compliance_expiry for the expiring-soon/expired detection
    # logic (calibration_expiring_soon/calibration_expired kinds) and
    # GET /v1/fleet/compliance-expiry for the dashboard listing.
    calibration_due: Mapped[date | None] = mapped_column(Date, nullable=True)


class DeviceVersionHistory(Base, TenantScopedMixin):
    """Append-only app_version history for a Device -- one row appended
    whenever POST /v1/fleet/devices/{id}/heartbeat receives an app_version
    different from the one currently stored on the Device row (see
    app.services.fleet.record_heartbeat). Feeds the per-vehicle compliance
    evidence pack (GET /v1/fleet/vehicles/{id}/evidence-pack, see
    app.services.evidence_pack) so an auditor can see exactly when a
    device's app was updated over time, not just its current version.

    No TimestampMixin -- append-only with its own purpose-built `recorded_at`
    timestamp, same convention as the sibling `app.models.tariffs.TariffChangeLog`
    append-only audit table (no updated_at makes sense for a row that is
    never updated).
    """

    __tablename__ = "device_version_history"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    device_id: Mapped[str] = mapped_column(String(36), ForeignKey("devices.id"), nullable=False, index=True)
    app_version: Mapped[str] = mapped_column(String(30), nullable=False)
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class VehiclePositionHistory(Base, TenantScopedMixin):
    """Append-only durable position log for a Vehicle -- one row appended by
    `app.services.live_ops._persist_position_history` on every
    `publish_position` call (`POST /v1/fleet/positions`, or a WS-side
    publish), on top of the existing broadcast/cache.

    This is the FIRST durable table the live-ops domain's position pipeline
    has ever written to. Before this pass, `app.services.live_ops`'s own
    module docstring stated flatly that position publishing was
    "in-process pub/sub only... nothing published here is persisted to the
    database" -- see `_FleetBroadcaster`'s in-memory `_latest` cache, which
    remains exactly as it was: the fast "latest known position" path for
    `GET /v1/vehicles` and `WS /v1/fleet/live`, unaffected by this table's
    existence. This table is purely additive -- a durable side-channel for
    "what was this vehicle's position N hours ago", the "scrub back through
    the last few hours" replay feature the in-memory cache alone can never
    answer (a process restart or a second uvicorn worker loses it entirely).

    Retention is a lazily-enforced default, NOT a decided data-retention
    policy -- see `app.services.live_ops.POSITION_HISTORY_RETENTION_HOURS`'s
    own doc comment for the full framing (mirrors this codebase's existing
    convention of flagging such numbers explicitly rather than presenting
    them as a business decision -- see docs/DURESS_DEVICE_INTEGRATION.md
    sec 8 on the duress-recordings retention question).

    No TimestampMixin -- append-only with its own purpose-built
    `recorded_at` timestamp, same convention as the sibling
    `DeviceVersionHistory` table above (never updated, so no `updated_at`
    makes sense) -- modeled directly on that table's shape per this pass's
    own brief. `TenantScopedMixin` (not a bare tenant_id column) since, like
    every other row in this domain's sibling tables, there is no
    global/tenant-null concept for a vehicle's position history.
    """

    __tablename__ = "vehicle_position_history"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    vehicle_id: Mapped[str] = mapped_column(String(36), ForeignKey("vehicles.id"), nullable=False, index=True)
    lat: Mapped[float] = mapped_column(Float, nullable=False)
    lng: Mapped[float] = mapped_column(Float, nullable=False)
    # Honest-null, same convention as PositionRead.speed_kmh/heading (see
    # app/schemas/live_ops.py) -- not every publish carries these two.
    speed_kmh: Mapped[float | None] = mapped_column(Float, nullable=True)
    heading: Mapped[float | None] = mapped_column(Float, nullable=True)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)


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
