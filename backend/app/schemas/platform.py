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
    # The public handle POST /v1/auth/driver-login requires (X2, 2026-09-08 —
    # see app/models/tenant.py::Tenant.slug). Surfaced here too, not just on
    # GET /v1/tenants/me, so the platform console's own tenant list/create
    # response can show an operator's tablets what to configure without a
    # second round-trip as that tenant.
    slug: str | None
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
    # The onboarding-flow entry point creates the tenant's OWNER user in the
    # same transaction (X2, 2026-09-08 — see app.services.platform.create_tenant),
    # so it needs an email/display name for that user up front. No password
    # here: the owner sets their own via the one-time invite this endpoint
    # returns — see PlatformTenantOnboardRead.
    owner_email: str = Field(min_length=3, max_length=255)
    owner_name: str = Field(min_length=1, max_length=255)


class PlatformTenantOnboardRead(PlatformTenantRead):
    """Response for `POST /v1/platform/tenants` — everything `PlatformTenantRead`
    has, plus the ONE-TIME materials a platform operator needs to hand the new
    owner: their email and a raw invite token. Both are returned exactly once,
    in this response only — the token is never retrievable again (only its
    SHA-256 digest is stored — see app.models.tenant_invite.TenantInvite) and
    is never logged anywhere in this codebase."""

    owner_email: str
    invite_token: str
    invite_expires_at: datetime


# --- POST /v1/platform/invites/accept -----------------------------------------
# Deliberately NOT gated by require_platform_owner (see app/api/v1/platform.py):
# the owner exchanging their invite holds no bearer token yet.


class OwnerInviteAcceptRequest(BaseModel):
    token: str = Field(min_length=1, max_length=200)
    password: str = Field(min_length=6, max_length=128)


class OwnerInviteAcceptResponse(BaseModel):
    """Confirms the owner's own password is now set — deliberately does NOT
    also issue bearer tokens here: that would make this endpoint a second,
    parallel login path with its own credential-handling surface. The owner
    logs in the normal way, immediately afterwards, via POST /v1/auth/login."""

    tenant_id: str
    user_id: str
    email: str


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
    "OwnerInviteAcceptRequest",
    "OwnerInviteAcceptResponse",
    "Page",
    "PlatformBillingSummary",
    "PlatformHealth",
    "PlatformTenantCreate",
    "PlatformTenantOnboardRead",
    "PlatformTenantRead",
    "TenantStatus",
    "TenantStatusUpdate",
    "TenantSubscriptionRead",
    "TenantSummary",
]
