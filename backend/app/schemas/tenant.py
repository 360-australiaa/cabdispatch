"""Pydantic v2 schemas for the tenant domain's admin-PIN endpoints."""
from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field

# 4-8 numeric digits. Not hardcoded to exactly 6 even though it replaces the
# Android app's 6-digit ADMIN_PIN_PLACEHOLDER — a tenant owner picks their
# own PIN within a sane length range, same spirit as UserCreate.password's
# min_length/max_length band in app/schemas/user.py.
_PIN_PATTERN = r"^\d{4,8}$"


class AdminPinSetRequest(BaseModel):
    pin: str = Field(min_length=4, max_length=8, pattern=_PIN_PATTERN)


class AdminPinSetResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    tenant_id: str
    admin_pin_configured: bool


class TenantTheme(BaseModel):
    """White-label branding (blueprint 7.2.10/9.1/13.1) — mirrors `Tenant.theme_json`'s shape
    exactly (a plain JSON column, no dedicated table). All fields optional/nullable: a tenant that
    has never customized branding simply has `theme_json IS NULL`, and the dashboard falls back to
    the platform default brand in that case (see the dashboard's own `BRAND_DEFAULT` constant)."""

    logo_url: str | None = Field(default=None, max_length=1000)
    primary_color: str | None = Field(default=None, pattern=r"^#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?$")
    accent_color: str | None = Field(default=None, pattern=r"^#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?$")


class TenantRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    name: str
    abn: str | None
    tsp_number: str | None
    bsp_number: str | None
    theme_json: TenantTheme | None
    plan: str
    status: str


class TenantThemeUpdate(BaseModel):
    """Body for `PATCH /v1/tenants/me`. `theme_json=None` explicitly resets branding to the
    platform default (the dashboard's "Reset to default" action) — distinct from omitting the
    field, which Pydantic can't distinguish from `None` here since this is the only field on the
    body today, so this endpoint always overwrites `theme_json` wholesale rather than merging
    partial updates (matches `set_admin_pin`'s own "set/update are the same operation" precedent)."""

    theme_json: TenantTheme | None = None


__all__ = [
    "AdminPinSetRequest",
    "AdminPinSetResponse",
    "TenantRead",
    "TenantTheme",
    "TenantThemeUpdate",
]
