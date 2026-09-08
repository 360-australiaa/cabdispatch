"""Tenant model — one row per taxi network / operator (TSP) on the platform."""
from __future__ import annotations

import uuid

from sqlalchemy import JSON, String
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TimestampMixin

# Lifecycle status for a tenant on the platform, set/read only via the
# platform-owner console (see app.services.platform.update_tenant_status /
# PATCH /v1/platform/tenants/{tenant_id}) — an ordinary tenant owner never
# sets this on themselves. Plain string, same portable-enum convention as
# app.models.billing's PLAN_*/STATUS_* constants, not a DB-level enum.
TENANT_STATUS_ACTIVE = "active"
TENANT_STATUS_TRIAL = "trial"
TENANT_STATUS_SUSPENDED = "suspended"
VALID_TENANT_STATUSES = (TENANT_STATUS_ACTIVE, TENANT_STATUS_TRIAL, TENANT_STATUS_SUSPENDED)


class Tenant(Base, TimestampMixin):
    __tablename__ = "tenants"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    abn: Mapped[str | None] = mapped_column(String(20), nullable=True)
    tsp_number: Mapped[str | None] = mapped_column(String(50), nullable=True)
    bsp_number: Mapped[str | None] = mapped_column(String(50), nullable=True)
    theme_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    plan: Mapped[str] = mapped_column(String(50), nullable=False, default="standard")
    # Every pre-existing tenant row (created before this column existed)
    # defaults to "active" via the server_default in the accompanying
    # migration — no backfill needed, and no tenant silently becomes
    # unusable just because this column was added.
    status: Mapped[str] = mapped_column(String(20), nullable=False, server_default=TENANT_STATUS_ACTIVE)
    stripe_acct_id: Mapped[str | None] = mapped_column(String(255), nullable=True)
    # Hashed (bcrypt via app.core.security.hash_password, same scheme as
    # User.pin_hash) admin PIN gating destructive on-device actions (e.g. the
    # Android app's factory-reset flow — see
    # au...SettingsViewModel.ADMIN_PIN_PLACEHOLDER). Nullable: a tenant that
    # hasn't set one yet simply can't use PIN-gated device actions until an
    # owner sets it via POST /v1/tenants/{id}/admin-pin — see
    # app/api/v1/tenants.py and app/services/tenant.py. Never sent to a
    # device; devices call POST /v1/fleet/devices/{id}/verify-admin-pin
    # instead, which checks the PIN server-side and returns a bool.
    admin_pin_hash: Mapped[str | None] = mapped_column(String(255), nullable=True)
