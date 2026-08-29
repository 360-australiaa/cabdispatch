"""Pydantic v2 schemas for the platform-owner admin console
(`app/api/v1/platform.py`) - cross-tenant tenant listing/onboarding and
per-tenant / platform-wide health rollups. Every endpoint this schema module
backs is gated to `role == "owner" AND tenant_id == PLATFORM_TENANT_ID`
specifically (see `app.api.v1.platform.require_platform_owner`), not just any
owner - an ordinary tenant's owner never sees these shapes.
"""
from __future__ import annotations

from datetime import datetime
from typing import Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field

# --- Pagination (local to this domain, same shape as app.schemas.fleet.Page,
# until a shared one exists in app.core) -----------------------------------------

T = TypeVar("T")


class Page(BaseModel, Generic[T]):
    items: list[T]
    total: int
    skip: int
    limit: int


# --- GET /v1/platform/tenants -------------------------------------------------


class PlatformTenantRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    name: str
    plan: str
    status: str
    created_at: datetime


# --- POST /v1/platform/tenants (onboarding-flow entry point) -----------------


class PlatformTenantCreate(BaseModel):
    name: str = Field(min_length=1, max_length=255)
    abn: str | None = Field(default=None, max_length=20)
    tsp_number: str | None = Field(default=None, max_length=50)
    bsp_number: str | None = Field(default=None, max_length=50)
    plan: str = Field(default="standard", max_length=50)
    # First owner user for the new tenant (plan Part 5, Q1 answer -- WP-17):
    # onboarding a tenant with no owner is not a usable tenant, so both are
    # required here rather than a separate follow-up call.
    owner_name: str = Field(min_length=1, max_length=255)
    owner_email: str = Field(min_length=3, max_length=255)


class PlatformTenantOnboarded(BaseModel):
    """Response for POST /v1/platform/tenants. Embeds the invite link
    (not the raw token separately -- the link already contains it, see
    app.services.user_invites.build_invite_link) so a caller never sees a
    real access/refresh token for the brand-new owner: the owner account
    is created with pin_hash=None and cannot log in until they follow this
    link and accept the invite."""

    id: str
    name: str
    plan: str
    status: str
    created_at: datetime
    owner_user_id: str
    owner_email: str
    invite_link: str


class TenantSummary(BaseModel):
    tenant_id: str
    tenant_name: str
    vehicle_count: int
    driver_count: int
    trip_count_last_30_days: int
    active_duress_count: int


# --- GET /v1/platform/health ---------------------------------------------------


class PlatformHealth(BaseModel):
    total_tenants: int
    total_vehicles: int
    total_trips_today: int


# --- PATCH /v1/platform/tenants/{id} (lifecycle, WP-18) -----------------------

TenantStatus = Literal["active", "suspended"]


class PlatformTenantStatusUpdate(BaseModel):
    """Partial update -- both fields optional, only what is sent changes.
    A suspended tenant own users cannot log in -- see
    app.api.v1.auth._login_result."""

    status: TenantStatus | None = None
    plan: str | None = Field(default=None, max_length=50)


__all__ = [
    "Page",
    "PlatformHealth",
    "PlatformTenantCreate",
    "PlatformTenantOnboarded",
    "PlatformTenantRead",
    "PlatformTenantStatusUpdate",
    "TenantStatus",
    "TenantSummary",
]
