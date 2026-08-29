"""Pydantic v2 schemas for the per-tenant settings domain (WP-01, D-7 in
docs/ARCHITECTURE_TENANCY_FLEET_COMPLIANCE.md).

Field-level docs mirror app.models.tenant_settings.TenantSettings' own column comments --
these are the per-operator overrides of what used to be deployment-wide env vars in
app.core.config.
"""
from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field

from app.models.tenant_settings import (
    DEFAULT_COMPLIANCE_EXPIRY_WARNING_DAYS,
    DEFAULT_FATIGUE_SHIFT_DURATION_LIMIT_HOURS,
)


class TenantSettingsRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    tenant_id: str
    fatigue_shift_duration_limit_hours: float
    compliance_expiry_warning_days: int
    duress_escalation_call_phone: str | None
    duress_call_from_number: str | None


class TenantSettingsUpdate(BaseModel):
    """PATCH body -- every field optional, only supplied fields are changed
    (partial update, unlike TenantThemeUpdate's deliberate full-overwrite
    precedent -- there is no single blob here, each field is an independent
    per-operator override so partial-update is the correct default)."""

    fatigue_shift_duration_limit_hours: float | None = Field(
        default=None, gt=0, le=24, description="Shift-duration fatigue-alert threshold, in hours."
    )
    compliance_expiry_warning_days: int | None = Field(
        default=None, ge=1, le=365, description="Days-before-expiry threshold for compliance alerts."
    )
    duress_escalation_call_phone: str | None = Field(
        default=None, max_length=32, description="E.164-ish phone number dialed on final duress escalation. Send null explicitly to clear it; omit the field to leave it unchanged."
    )
    duress_call_from_number: str | None = Field(
        default=None, max_length=32, description="Twilio caller ID used when calling a duress device. Send null explicitly to clear it; omit the field to leave it unchanged."
    )


__all__ = [
    "DEFAULT_COMPLIANCE_EXPIRY_WARNING_DAYS",
    "DEFAULT_FATIGUE_SHIFT_DURATION_LIMIT_HOURS",
    "TenantSettingsRead",
    "TenantSettingsUpdate",
]
