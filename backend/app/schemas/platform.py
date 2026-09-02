"""Pydantic v2 schemas for the platform-owner admin console
(`app/api/v1/platform.py`) - cross-tenant tenant listing/onboarding and
per-tenant / platform-wide health rollups. Every endpoint this schema module
backs is gated to `role == "owner" AND tenant_id == PLATFORM_TENANT_ID`
specifically (see `app.api.v1.platform.require_platform_owner`), not just any
owner - an ordinary tenant's owner never sees these shapes.
"""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field

from app.models.tenant import VALID_TENANT_STATUSES

# --- Pagination (local to this domain, same shape as app.schemas.fleet.Page,
# until a shared one exists in app.core) -----------------------------------------

T = TypeVar("T")


class Page(BaseModel, Generic[T]):
    items: list[T]
    total: int
    skip: int
    limit: int


# Tenant lifecycle status, mirrors app.models.tenant.VALID_TENANT_STATUSES -
# a plain Literal (not an import of that tuple into a Literal, which Pydantic
# can't build dynamically) so it must be kept in sync with that tuple by hand.
TenantStatus = Literal["active", "trial", "suspended"]
assert set(TenantStatus.__args__) == set(VALID_TENANT_STATUSES)  # keep the two definitions honest


# --- GET /v1/platform/tenants -------------------------------------------------


class PlatformTenantRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    name: str
    plan: str
    status: TenantStatus
    created_at: datetime


# --- POST /v1/platform/tenants (onboarding-flow entry point) -----------------


class PlatformTenantCreate(BaseModel):
    name: str = Field(min_length=1, max_length=255)
    abn: str | None = Field(default=None, max_length=20)
    tsp_number: str | None = Field(default=None, max_length=50)
    bsp_number: str | None = Field(default=None, max_length=50)
    plan: str = Field(default="standard", max_length=50)


# --- GET /v1/platform/tenants/{id}/summary ------------------------------------


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


# --- PATCH /v1/platform/tenants/{id} -------------------------------------------


class TenantStatusUpdate(BaseModel):
    status: TenantStatus


# --- GET /v1/platform/billing/summary ------------------------------------------


class PlatformBillingSummary(BaseModel):
    """See app.services.platform.get_platform_billing_summary's docstring
    for exactly which subscription statuses feed mrr_aud/plan_counts vs.
    status_counts."""

    mrr_aud: Decimal
    plan_counts: dict[str, int]
    status_counts: dict[str, int]


# --- GET /v1/platform/tenants/{id}/billing -------------------------------------


class TenantSubscriptionRead(BaseModel):
    """One subscription row for the platform-owner's per-tenant billing
    support-triage view — deliberately a smaller shape than
    app.schemas.billing.SubscriptionRead (no tenant_id, since the caller
    already knows it from the path; no price_aud/timestamps, not needed for
    this at-a-glance view)."""

    model_config = ConfigDict(from_attributes=True)

    id: str
    vehicle_id: str
    plan: str
    status: str
    stripe_subscription_id: str | None = None


__all__ = [
    "Page",
    "PlatformBillingSummary",
    "PlatformHealth",
    "PlatformTenantCreate",
    "PlatformTenantRead",
    "TenantStatus",
    "TenantStatusUpdate",
    "TenantSubscriptionRead",
    "TenantSummary",
]
