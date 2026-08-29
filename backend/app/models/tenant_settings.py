"""TenantSettings model -- one row per tenant, carrying values that used to be
deployment-wide env vars in app.core.config but are logically per-operator (D-7 in
docs/ARCHITECTURE_TENANCY_FLEET_COMPLIANCE.md, Part 3).

Not a TenantScopedMixin user -- TenantScopedMixin is for child rows that belong to a
tenant (many rows per tenant_id). This table IS the tenant-scoped settings row itself:
exactly one per tenant, enforced by a unique constraint on tenant_id. There is no
existing 1:1-with-tenant precedent elsewhere in this codebase (grepped app/models for
unique=True FK columns -- none found), so this follows a plain
Base + TimestampMixin + explicit unique FK shape instead.

Defaults mirror app.core.config.Settings current values exactly, so a freshly
backfilled row (or a defensively auto-created one, see app.services.tenant_settings)
behaves identically to the env-var-only behaviour it replaces:
  - FATIGUE_SHIFT_DURATION_LIMIT_HOURS -> fatigue_shift_duration_limit_hours (12.0)
  - COMPLIANCE_EXPIRY_WARNING_DAYS -> compliance_expiry_warning_days (30)
  - DURESS_ESCALATION_CALL_PHONE -> duress_escalation_call_phone (nullable, no default --
    empty string in config means not configured, which maps to NULL here)
  - DURESS_CALL_FROM_NUMBER -> duress_call_from_number (nullable, same convention)

The two phone-number columns stay nullable with no server_default: env-var "" (not
configured) is represented here as NULL, not empty string, so callers can use a plain
is-None check instead of also handling an empty string.
"""
from __future__ import annotations

import uuid

from sqlalchemy import ForeignKey, Integer, Numeric, String
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TimestampMixin

DEFAULT_FATIGUE_SHIFT_DURATION_LIMIT_HOURS = 12.0
DEFAULT_COMPLIANCE_EXPIRY_WARNING_DAYS = 30


class TenantSettings(Base, TimestampMixin):
    __tablename__ = "tenant_settings"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))

    tenant_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("tenants.id"), nullable=False, unique=True, index=True
    )

    fatigue_shift_duration_limit_hours: Mapped[float] = mapped_column(
        Numeric(4, 1),
        nullable=False,
        server_default=str(DEFAULT_FATIGUE_SHIFT_DURATION_LIMIT_HOURS),
    )
    compliance_expiry_warning_days: Mapped[int] = mapped_column(
        Integer, nullable=False, server_default=str(DEFAULT_COMPLIANCE_EXPIRY_WARNING_DAYS)
    )
    duress_escalation_call_phone: Mapped[str | None] = mapped_column(String(32), nullable=True)
    duress_call_from_number: Mapped[str | None] = mapped_column(String(32), nullable=True)


__all__ = [
    "DEFAULT_COMPLIANCE_EXPIRY_WARNING_DAYS",
    "DEFAULT_FATIGUE_SHIFT_DURATION_LIMIT_HOURS",
    "TenantSettings",
]
