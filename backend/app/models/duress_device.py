"""DuressDevice model -- the physical CT-DPD-01 panic-button hardware (SIM7600G-H
4G/GNSS/VoLTE + ESP32-S3 BLE, own battery backup), factory-provisioned and
bound to one vehicle. See docs/DURESS_DEVICE_INTEGRATION.md for the full
system contract this table backs (BLE GATT profile, device<->server cellular
API, and the correlation model that lets a device-side alarm and a tablet-side
DuressEvent merge into one incident).

DEVIATION (same precedent as app.models.duress.DuressEvent's own module
docstring): vehicle_id is a plain indexed String(36) with no ForeignKey to
vehicles.id, for the same "this domain must not assume fleet's models are
registered yet when imported in isolation" reason already documented there.

Security model: each device holds a 32-byte shared secret (K_dev in the
integration contract) that both the device firmware and this row know. The
server never stores it in plaintext -- secret_encrypted is a Fernet
ciphertext (see app.core.crypto) the server decrypts on demand to verify a
device's HMAC-signed POST /v1/devices/auth request. This is the one place
in the codebase a secret is stored reversibly rather than one-way hashed
(see that module's docstring for why: HMAC verification, unlike password
checking, requires the server to reproduce the same computation the device
performed, which needs the plaintext key back).
"""
from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin, TimestampMixin


class DuressDevice(Base, TenantScopedMixin, TimestampMixin):
    __tablename__ = "duress_devices"
    # A device_code that resolves to more than one row makes
    # authenticate_device's scalar_one_or_none() raise MultipleResultsFound
    # (a real bug this constraint closes off at the schema level) --
    # same per-tenant uniqueness precedent as fleet.Device's
    # uq_devices_tenant_android_id.
    __table_args__ = (
        UniqueConstraint("tenant_id", "device_code", name="uq_duress_devices_tenant_device_code"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))

    # Factory-provisioned identifier printed/etched on the unit and burned
    # into firmware -- distinct from the row's own id (uuid) so the device
    # can be re-provisioned (secret rotation) without changing its physical
    # label. Unique per tenant, not globally, matching Device.android_id's
    # precedent in app.models.fleet.
    device_code: Mapped[str] = mapped_column(String(64), nullable=False, index=True)

    vehicle_id: Mapped[str | None] = mapped_column(String(36), nullable=True, index=True)

    # Fernet-encrypted shared secret (K_dev) -- see module docstring. Never
    # exposed on any read schema; only app.services.duress_device decrypts it.
    secret_encrypted: Mapped[str] = mapped_column(String(255), nullable=False)

    # E.164 MSISDN of the device's own SIM -- the number Twilio dials for the
    # operator's "call the cab" action (POST /v1/duress/{event_id}/call).
    phone_number: Mapped[str | None] = mapped_column(String(32), nullable=True)

    # --- health / heartbeat snapshot (POST /v1/devices/{id}/heartbeat) ---
    battery_pct: Mapped[int | None] = mapped_column(Integer, nullable=True)  # 0-100
    on_battery: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    gnss_fix: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    signal_csq: Mapped[int | None] = mapped_column(Integer, nullable=True)  # 0-31, GSM CSQ scale
    firmware_version: Mapped[str | None] = mapped_column(String(30), nullable=True)
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    # Set false to reject the device at POST /v1/devices/auth (e.g. reported
    # stolen/decommissioned) without deleting its history.
    active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)