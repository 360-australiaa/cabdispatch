"""Billing domain: Stripe Billing subscription CRUD, a mock-fallback invoice
list, and Stripe Connect onboarding link creation, all under `/v1/billing`.

Tenant isolation: every endpoint resolves `tenant_id` via
`get_current_tenant_id` and filters every query by it, per the foundation
contract — this is the sole multi-tenancy enforcement mechanism in the
system.

Subscriptions support full CRUD (list/get/create/update/delete — "delete"
cancels rather than hard-deletes, see `cancel_subscription` below, since a
canceled subscription is still a billing-history record worth keeping).
Invoices are read-only from this domain's perspective (Stripe/the billing
provider is the system of record for invoice history) so GET /v1/billing/invoices
is the only invoice endpoint — no create/update/delete, per the "append-only
log" convention for read-only/provider-owned data.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, require_role
from app.models.billing import STATUS_CANCELED, Subscription
from app.schemas.billing import (
    ConnectOnboardResponse,
    InvoiceListResponse,
    InvoiceRead,
    Plan,
    SubscriptionCreate,
    SubscriptionListResponse,
    SubscriptionRead,
    SubscriptionStatus,
    SubscriptionUpdate,
)
from app.services import billing as billing_service

router = APIRouter(prefix="/v1/billing", tags=["billing"])

# This router had no role gate at all until this pass -- any authenticated
# tenant user (including a driver-role account) could create/change/cancel a
# subscription or generate a Stripe Connect onboarding link. The dashboard's
# own `canManage` client-side check (owner/admin/dispatcher) was purely
# decorative without this. Read endpoints (list/get subscriptions, invoices)
# stay open to any authenticated tenant user, same as every other domain.
_require_billing_admin = require_role("owner", "admin", "dispatcher")


async def _get_owned_subscription(session: AsyncSession, tenant_id: str, subscription_id: str) -> Subscription:
    result = await session.execute(
        select(Subscription).where(
            Subscription.id == subscription_id, Subscription.tenant_id == tenant_id
        )
    )
    subscription = result.scalar_one_or_none()
    if subscription is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Subscription not found")
    return subscription


# --- list / get -----------------------------------------------------------------


@router.get("/subscriptions", response_model=SubscriptionListResponse)
async def list_subscriptions(
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    plan: Plan | None = Query(default=None),
    status_filter: SubscriptionStatus | None = Query(default=None, alias="status"),
    vehicle_id: str | None = Query(default=None),
    skip: int = Query(default=0, ge=0),
    limit: int = Query(default=50, ge=1, le=200),
) -> SubscriptionListResponse:
    stmt = select(Subscription).where(Subscription.tenant_id == tenant_id)
    count_stmt = select(func.count()).select_from(Subscription).where(Subscription.tenant_id == tenant_id)

    if plan:
        stmt = stmt.where(Subscription.plan == plan)
        count_stmt = count_stmt.where(Subscription.plan == plan)
    if status_filter:
        stmt = stmt.where(Subscription.status == status_filter)
        count_stmt = count_stmt.where(Subscription.status == status_filter)
    if vehicle_id:
        stmt = stmt.where(Subscription.vehicle_id == vehicle_id)
        count_stmt = count_stmt.where(Subscription.vehicle_id == vehicle_id)

    total = (await session.execute(count_stmt)).scalar_one()
    stmt = stmt.order_by(Subscription.created_at.desc()).offset(skip).limit(limit)
    items = (await session.execute(stmt)).scalars().all()

    return SubscriptionListResponse(items=list(items), total=total, skip=skip, limit=limit)


@router.get("/subscriptions/{subscription_id}", response_model=SubscriptionRead)
async def get_subscription(
    subscription_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> Subscription:
    return await _get_owned_subscription(session, tenant_id, subscription_id)


# --- create / update / cancel ---------------------------------------------------


@router.post("/subscriptions", response_model=SubscriptionRead, status_code=status.HTTP_201_CREATED)
async def create_subscription(
    body: SubscriptionCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_billing_admin),
) -> Subscription:
    result = billing_service.create_subscription(plan=body.plan, vehicle_id=body.vehicle_id)

    subscription = Subscription(
        tenant_id=tenant_id,
        vehicle_id=body.vehicle_id,
        plan=body.plan,
        stripe_subscription_id=result["stripe_subscription_id"],
        status=result["status"],
        price_aud=result["price_aud"],
    )
    session.add(subscription)
    await session.commit()
    await session.refresh(subscription)
    return subscription


@router.patch("/subscriptions/{subscription_id}", response_model=SubscriptionRead)
async def update_subscription(
    subscription_id: str,
    body: SubscriptionUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_billing_admin),
) -> Subscription:
    subscription = await _get_owned_subscription(session, tenant_id, subscription_id)

    if body.plan is not None and body.plan != subscription.plan:
        result = billing_service.change_subscription_plan(
            stripe_subscription_id=subscription.stripe_subscription_id, new_plan=body.plan
        )
        subscription.plan = body.plan
        subscription.price_aud = result["price_aud"]

    if body.status is not None:
        subscription.status = body.status

    await session.commit()
    await session.refresh(subscription)
    return subscription


@router.delete("/subscriptions/{subscription_id}", response_model=SubscriptionRead)
async def cancel_subscription(
    subscription_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_billing_admin),
) -> Subscription:
    """Cancels rather than hard-deletes: DELETE transitions status to
    "canceled" (and best-effort cancels the Stripe-side subscription) so the
    row survives as billing history, matching the payments domain's
    audit-trail convention."""
    subscription = await _get_owned_subscription(session, tenant_id, subscription_id)

    result = billing_service.cancel_subscription(
        stripe_subscription_id=subscription.stripe_subscription_id
    )
    subscription.status = result.get("status", STATUS_CANCELED)

    await session.commit()
    await session.refresh(subscription)
    return subscription


# --- invoices (read-only — Stripe/the billing provider is system of record) ----


@router.get("/invoices", response_model=InvoiceListResponse)
async def list_invoices(
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    subscription_id: str | None = Query(default=None),
) -> InvoiceListResponse:
    stmt = select(Subscription).where(Subscription.tenant_id == tenant_id)
    if subscription_id:
        stmt = stmt.where(Subscription.id == subscription_id)
    subscriptions = (await session.execute(stmt)).scalars().all()

    all_items: list[InvoiceRead] = []
    any_mock = False
    for sub in subscriptions:
        result = billing_service.list_invoices_for_subscription(
            stripe_subscription_id=sub.stripe_subscription_id,
            price_aud=sub.price_aud,
            subscription_id=sub.id,
        )
        any_mock = any_mock or result["mock"]
        for item in result["items"]:
            all_items.append(InvoiceRead(subscription_id=sub.id, **item))

    return InvoiceListResponse(items=all_items, total=len(all_items), mock=any_mock)


# --- Stripe Connect onboarding ---------------------------------------------------


@router.post("/connect/onboard", response_model=ConnectOnboardResponse, status_code=status.HTTP_201_CREATED)
async def connect_onboard(
    tenant_id: str = Depends(get_current_tenant_id),
    _admin=Depends(_require_billing_admin),
) -> ConnectOnboardResponse:
    result = billing_service.create_connect_onboarding_link(
        tenant_id=tenant_id,
        return_url="https://app.cabdispatch.example/settings/billing/connect/return",
        refresh_url="https://app.cabdispatch.example/settings/billing/connect/refresh",
    )
    return ConnectOnboardResponse(
        mock=result["mock"], url=result["url"], stripe_account_id=result.get("stripe_account_id")
    )
