"""Payments domain: CRUD + the Stripe Tap-to-Pay/payment-link/cash/manual
creation flows, the real-or-mock CabCharge authorization and TTSS subsidy
claim flows, and the Stripe webhook receiver.

Two routers are exported because `/v1/stripe/webhook` lives OUTSIDE the
`/v1/payments` prefix (per the domain contract): `router` (prefix
"/v1/payments") and `webhook_router` (prefix "/v1/stripe"). The integration
step must `app.include_router()` BOTH.

Payment *creation* has no generic `POST /v1/payments` — every payment is
created through one of the method-specific flows below (tap-to-pay intent,
payment link, cash, manual cabcharge/ttss docket, cabcharge authorize, ttss
claim) so the right validation/state-machine for that rail always runs, per
the domain brief. `manual` remains available as a fallback for both
CabCharge and TTSS when the real-or-mock flow below isn't used (e.g. a
driver keying in a paper docket after the fact). List/get/update are
generic. There is deliberately no DELETE: payments are a financial audit
trail and must not be hard-deleted — transition to `refunded`/`canceled` via
PATCH instead.

Tenant isolation: every endpoint below except the webhook resolves
`tenant_id` via `get_current_tenant_id` and filters every query by it, per
the foundation contract. The Stripe webhook is a documented, deliberate
exception — Stripe calls it directly with no Cab Dispatch bearer token, so
there is no JWT to resolve a tenant from. It instead looks the payment up by
its (globally unique) `stripe_pi_id` and takes `tenant_id` from that row,
never from any client-supplied value.
"""
from __future__ import annotations

from datetime import UTC, datetime
from decimal import Decimal

import stripe
from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id
from app.models.payment import (
    METHOD_CABCHARGE,
    METHOD_CASH,
    METHOD_LINK,
    METHOD_TAP_TO_PAY,
    METHOD_TTSS,
    STATUS_PENDING,
    STATUS_SUCCEEDED,
    Payment,
)
from app.schemas.payments import (
    CabChargeAuthorizeRequest,
    CabChargeAuthorizeResponse,
    CashPaymentRequest,
    ManualPaymentRequest,
    PaymentLinkRequest,
    PaymentLinkResponse,
    PaymentListResponse,
    PaymentMethod,
    PaymentRead,
    PaymentStatus,
    PaymentUpdate,
    StripeWebhookResponse,
    TapToPayIntentRequest,
    TapToPayIntentResponse,
    TTSSClaimRequest,
    TTSSClaimResponse,
)
from app.services import payments as payments_service

router = APIRouter(prefix="/v1/payments", tags=["payments"])
webhook_router = APIRouter(prefix="/v1/stripe", tags=["payments"])


async def _get_owned_payment(session: AsyncSession, tenant_id: str, payment_id: str) -> Payment:
    result = await session.execute(
        select(Payment).where(Payment.id == payment_id, Payment.tenant_id == tenant_id)
    )
    payment = result.scalar_one_or_none()
    if payment is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Payment not found")
    return payment


def _raise_if_surcharge_exceeds_cap(amount: Decimal, surcharge: Decimal) -> None:
    try:
        payments_service.validate_surcharge(amount, surcharge)
    except payments_service.SurchargeCapExceeded as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)
        ) from exc


# --- list / get / update -----------------------------------------------------


@router.get("", response_model=PaymentListResponse)
async def list_payments(
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    method: PaymentMethod | None = Query(default=None),
    status_filter: PaymentStatus | None = Query(default=None, alias="status"),
    trip_id: str | None = Query(default=None),
    skip: int = Query(default=0, ge=0),
    limit: int = Query(default=50, ge=1, le=200),
) -> PaymentListResponse:
    stmt = select(Payment).where(Payment.tenant_id == tenant_id)
    count_stmt = select(func.count()).select_from(Payment).where(Payment.tenant_id == tenant_id)

    if method:
        stmt = stmt.where(Payment.method == method)
        count_stmt = count_stmt.where(Payment.method == method)
    if status_filter:
        stmt = stmt.where(Payment.status == status_filter)
        count_stmt = count_stmt.where(Payment.status == status_filter)
    if trip_id:
        stmt = stmt.where(Payment.trip_id == trip_id)
        count_stmt = count_stmt.where(Payment.trip_id == trip_id)

    total = (await session.execute(count_stmt)).scalar_one()
    stmt = stmt.order_by(Payment.created_at.desc()).offset(skip).limit(limit)
    items = (await session.execute(stmt)).scalars().all()

    return PaymentListResponse(items=list(items), total=total, skip=skip, limit=limit)


@router.get("/{payment_id}", response_model=PaymentRead)
async def get_payment(
    payment_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> Payment:
    return await _get_owned_payment(session, tenant_id, payment_id)


@router.patch("/{payment_id}", response_model=PaymentRead)
async def update_payment(
    payment_id: str,
    body: PaymentUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> Payment:
    payment = await _get_owned_payment(session, tenant_id, payment_id)
    for field, value in body.model_dump(exclude_unset=True).items():
        setattr(payment, field, value)
    await session.commit()
    await session.refresh(payment)
    return payment


# --- creation flows -----------------------------------------------------------


@router.post(
    "/tap-to-pay/intent", response_model=TapToPayIntentResponse, status_code=status.HTTP_201_CREATED
)
async def create_tap_to_pay_intent(
    body: TapToPayIntentRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> TapToPayIntentResponse:
    _raise_if_surcharge_exceeds_cap(body.amount, body.surcharge)

    result = payments_service.create_tap_to_pay_intent(amount=body.amount, surcharge=body.surcharge)

    payment = Payment(
        tenant_id=tenant_id,
        trip_id=body.trip_id,
        stripe_pi_id=result["stripe_pi_id"],
        method=METHOD_TAP_TO_PAY,
        amount=body.amount,
        surcharge=body.surcharge,
        status=STATUS_PENDING,
    )
    session.add(payment)
    await session.commit()
    await session.refresh(payment)

    return TapToPayIntentResponse(
        payment=payment,
        mock=result["mock"],
        client_secret=result.get("client_secret"),
        stripe_status=result.get("stripe_status"),
    )


@router.post("/link", response_model=PaymentLinkResponse, status_code=status.HTTP_201_CREATED)
async def create_payment_link(
    body: PaymentLinkRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> PaymentLinkResponse:
    _raise_if_surcharge_exceeds_cap(body.amount, body.surcharge)

    result = payments_service.create_payment_link(
        amount=body.amount, surcharge=body.surcharge, trip_id=body.trip_id
    )

    payment = Payment(
        tenant_id=tenant_id,
        trip_id=body.trip_id,
        stripe_pi_id=result["stripe_pi_id"],
        method=METHOD_LINK,
        amount=body.amount,
        surcharge=body.surcharge,
        status=STATUS_PENDING,
    )
    session.add(payment)
    await session.commit()
    await session.refresh(payment)

    return PaymentLinkResponse(payment=payment, mock=result["mock"], url=result.get("url"))


@router.post("/cash", response_model=PaymentRead, status_code=status.HTTP_201_CREATED)
async def create_cash_payment(
    body: CashPaymentRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> Payment:
    change = payments_service.compute_change(amount=body.amount, tendered=body.tendered)
    payment = Payment(
        tenant_id=tenant_id,
        trip_id=body.trip_id,
        method=METHOD_CASH,
        amount=body.amount,
        surcharge=Decimal("0.00"),
        status=STATUS_SUCCEEDED,
        captured_at=None,
        change_given=change,
    )
    session.add(payment)
    await session.commit()
    await session.refresh(payment)
    return payment


@router.post("/manual", response_model=PaymentRead, status_code=status.HTTP_201_CREATED)
async def create_manual_payment(
    body: ManualPaymentRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> Payment:
    _raise_if_surcharge_exceeds_cap(body.amount, body.surcharge)

    payment = Payment(
        tenant_id=tenant_id,
        trip_id=body.trip_id,
        method=body.method,
        amount=body.amount,
        surcharge=body.surcharge,
        status=STATUS_PENDING,
        docket_number=body.docket_number,
        notes=body.notes,
    )
    session.add(payment)
    await session.commit()
    await session.refresh(payment)
    return payment


@router.post(
    "/cabcharge/authorize",
    response_model=CabChargeAuthorizeResponse,
    status_code=status.HTTP_201_CREATED,
)
async def authorize_cabcharge_payment(
    body: CabChargeAuthorizeRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> CabChargeAuthorizeResponse:
    """Authorization -> Docket creation step of blueprint 5.2.5's CabCharge
    flow (real-or-mock, see app.services.payments.authorize_cabcharge).
    Settlement batching is a later, separate process out of scope here.
    Status starts `pending` (mirroring tap-to-pay/link/manual) since
    settlement hasn't happened yet."""
    _raise_if_surcharge_exceeds_cap(body.amount, body.surcharge)

    result = payments_service.authorize_cabcharge(card_identifier=body.card_identifier, amount=body.amount)

    payment = Payment(
        tenant_id=tenant_id,
        trip_id=body.trip_id,
        method=METHOD_CABCHARGE,
        amount=body.amount,
        surcharge=body.surcharge,
        status=STATUS_PENDING,
        docket_number=result["docket_number"],
        notes=f"CabCharge authorization {result['authorization_id']} (card {body.card_identifier})",
    )
    session.add(payment)
    await session.commit()
    await session.refresh(payment)

    return CabChargeAuthorizeResponse(
        payment=payment,
        mock=result["mock"],
        authorization_id=result["authorization_id"],
        cabcharge_status=result["cabcharge_status"],
    )


@router.post("/ttss/claim", response_model=TTSSClaimResponse, status_code=status.HTTP_201_CREATED)
async def submit_ttss_claim_payment(
    body: TTSSClaimRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> TTSSClaimResponse:
    """Subsidy calculation -> Claim submission step of blueprint 5.2.5's
    TTSS flow. Subsidy is always computed server-side (50% of `amount`,
    capped at $60.00 per blueprint 11.1) — never client-supplied. Status
    starts `pending` since reimbursement hasn't happened yet."""
    subsidy_amount = payments_service.compute_ttss_subsidy(body.amount)
    passenger_paid_amount = body.amount - subsidy_amount

    result = payments_service.submit_ttss_claim(
        concession_identifier=body.concession_identifier,
        fare_amount=body.amount,
        subsidy_amount=subsidy_amount,
    )

    payment = Payment(
        tenant_id=tenant_id,
        trip_id=body.trip_id,
        method=METHOD_TTSS,
        amount=body.amount,
        surcharge=Decimal("0.00"),
        status=STATUS_PENDING,
        docket_number=result["docket_number"],
        notes=f"TTSS claim {result['claim_id']} (concession {body.concession_identifier})",
        subsidy_amount=subsidy_amount,
        passenger_paid_amount=passenger_paid_amount,
    )
    session.add(payment)
    await session.commit()
    await session.refresh(payment)

    return TTSSClaimResponse(
        payment=payment,
        mock=result["mock"],
        claim_id=result["claim_id"],
        ttss_status=result["ttss_status"],
        subsidy_amount=subsidy_amount,
        passenger_paid_amount=passenger_paid_amount,
    )


# --- Stripe webhook (no tenant scoping — see module docstring) --------------


@webhook_router.post("/webhook", response_model=StripeWebhookResponse)
async def stripe_webhook(
    request: Request,
    session: AsyncSession = Depends(get_session),
) -> StripeWebhookResponse:
    payload = await request.body()
    sig_header = request.headers.get("stripe-signature")

    try:
        event = payments_service.verify_and_parse_webhook(payload=payload, sig_header=sig_header)
    except stripe.error.SignatureVerificationError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid signature") from exc
    except (ValueError, TypeError) as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid payload") from exc

    event_type = event.get("type")
    data_object = (event.get("data") or {}).get("object") or {}
    stripe_pi_id = data_object.get("id") if isinstance(data_object, dict) else None
    new_status = payments_service.status_for_event(event_type) if event_type else None

    if not stripe_pi_id or not new_status:
        return StripeWebhookResponse(received=True, payment_id=None, status=None)

    result = await session.execute(select(Payment).where(Payment.stripe_pi_id == stripe_pi_id))
    payment = result.scalar_one_or_none()
    if payment is None:
        return StripeWebhookResponse(received=True, payment_id=None, status=None)

    payment.status = new_status
    if new_status == STATUS_SUCCEEDED:
        payment.captured_at = datetime.now(UTC)
    await session.commit()

    return StripeWebhookResponse(received=True, payment_id=payment.id, status=payment.status)
