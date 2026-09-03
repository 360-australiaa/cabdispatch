"""Pydantic v2 schemas for the trips domain."""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.services.fare_engine import NEGOTIATED_TOTAL_MAX, NEGOTIATED_TOTAL_MIN

TripType = Literal["rank_hail", "booked", "airport_fixed", "multi_hire"]
TripStatus = Literal["open", "closed"]
TimeClass = Literal["day", "night", "holiday"]
# "voucher" (promo code/prepaid voucher redemption), "account" (pre-registered
# linked corporate account, pay-later/invoiced) and "split_fare" (multiple
# sub-payments on one trip) are added on top of the domain brief's original
# cash/card pair — blueprint 5.2.5. See SplitPaymentItem /
# _validate_voucher_and_account / _validate_split_payments_required below for
# the cross-field requirements each new value carries, and
# app.services.payments.redeem_voucher / validate_account_reference /
# app.services.trips.SplitPaymentMismatchError for the server-side checks.
PaymentMethod = Literal["cash", "card", "voucher", "account", "split_fare"]
# Valid methods for one leg of a split-fare payment — deliberately excludes
# "split_fare" itself (no nesting).
SubPaymentMethod = Literal["cash", "card", "voucher", "account"]


class SplitPaymentItem(BaseModel):
    """One leg of a split-fare payment, e.g. {"method": "card", "amount":
    "20.00"}. A trip's split_payments list must sum (to the cent) to its
    total — enforced server-side at close time, since that's the only point
    the trip's final total is known (see app.services.trips.close_trip)."""

    method: SubPaymentMethod
    amount: Decimal = Field(gt=0)


def _validate_voucher_and_account(
    payment_method: str, voucher_code: str | None, account_reference: str | None
) -> None:
    """Shared cross-field validation reused by TripCreate/TripUpdate/
    TripCloseRequest's model_validator below — mirrors the model_validator
    pattern already used by app.schemas.payments.CashPaymentRequest."""
    if payment_method == "voucher" and not (voucher_code and voucher_code.strip()):
        raise ValueError("voucher_code is required (and non-empty) when payment_method is 'voucher'")
    if payment_method == "account" and not (account_reference and account_reference.strip()):
        raise ValueError("account_reference is required (and non-empty) when payment_method is 'account'")


def _validate_split_payments_required(
    payment_method: str, split_payments: list[SplitPaymentItem] | None
) -> None:
    if payment_method == "split_fare" and not split_payments:
        raise ValueError("split_payments is required (and non-empty) when payment_method is 'split_fare'")


def _validate_tip_amount(tip_amount: Decimal | None) -> None:
    """Shared sanity check for the driver-entered tip (Close & Pay "tips"
    pass) — a tip is never negative. No upper cap: unlike `negotiated_total`
    (a substitute for the regulated fare, which the Fares Order caps) a tip
    is a voluntary, uncapped, non-fare payment."""
    if tip_amount is not None and tip_amount < 0:
        raise ValueError("tip_amount must not be negative")


def _validate_negotiated_total(negotiated_total: Decimal | None) -> None:
    """Shared sanity-cap check for the negotiated/"Set Price" fixed fare
    (competitor "Set Price" feature; NSW allows pre-arranged/negotiated
    fares) — reuses app.services.fare_engine's NEGOTIATED_TOTAL_MIN/MAX band,
    the same "reasonable cap" spirit as this codebase's other money sanity
    checks (see e.g. app.services.fare_engine.Tariff.surcharge_pct_cap)."""
    if negotiated_total is None:
        return
    if not (NEGOTIATED_TOTAL_MIN <= negotiated_total <= NEGOTIATED_TOTAL_MAX):
        raise ValueError(
            f"negotiated_total must be between {NEGOTIATED_TOTAL_MIN} and {NEGOTIATED_TOTAL_MAX}"
        )


class TelemetryPoint(BaseModel):
    """A single raw GPS/speed fix, as recorded by the in-vehicle meter."""

    lat: float
    lng: float
    speed_kmh: float = Field(ge=0)
    ts: datetime


# --- Create -------------------------------------------------------------


class TripCreate(BaseModel):
    client_uuid: str = Field(..., description="Offline idempotency key, unique per tenant")
    vehicle_id: str
    driver_id: str
    shift_id: str | None = None
    tariff_id: str
    type: TripType
    start_at: datetime | None = Field(default=None, description="Defaults to now() if omitted")
    start_lat: float
    start_lng: float
    payment_method: PaymentMethod = "cash"
    voucher_code: str | None = None
    account_reference: str | None = None
    # DEVICE ADVISORY ONLY, both fields: accepted for backward compatibility
    # but never trusted for billing — the router deterministically derives
    # the real values server-side from the tariff's own night/peak-window +
    # public-holiday-calendar definitions and the trip's actual start_at (see
    # app.services.fare_engine.resolve_time_class_and_peak), ignoring
    # whatever a device sends here. Same pattern as `maxi` below.
    time_class: TimeClass = "day"
    is_peak: bool = False
    # DEVICE ADVISORY ONLY: this raw flag is accepted for backward
    # compatibility but never trusted for billing — the router looks up the
    # real Vehicle.vehicle_class server-side (see
    # app.services.trips.resolve_is_maxi_vehicle) to decide whether the
    # trip's vehicle is actually a maxi-cab, ignoring whatever a device
    # sends here.
    maxi: bool = False
    passenger_count: int = Field(
        default=1,
        ge=1,
        le=11,
        description="Actual passengers carried — the primary legal trigger (>=5) for the maxi rate.",
    )
    wheelchair_hiring: bool = Field(
        default=False,
        description="Carrying a wheelchair passenger — always overrides the maxi rate off (Order cl 2(d)(ii)).",
    )
    airport_rank_requested_maxi: bool = Field(
        default=False,
        description="A maxi-cab specifically requested at a Sydney Airport rank — triggers the maxi rate independent of passenger_count, except for a wheelchair hiring.",
    )
    tolls: Decimal = Decimal(0)
    extras: Decimal = Decimal(0)
    gps_trace_ref: str | None = None
    negotiated_total: Decimal | None = Field(
        default=None,
        description=(
            "Negotiated/set-price fixed fare (competitor 'Set Price' feature): "
            "the driver enters this before starting the meter (NSW allows this "
            "for pre-arranged/negotiated fares). Settable only at trip creation "
            "— not mid-trip. Replaces the metered flag/distance/time components "
            "at close; PSL and tolls still accrue and add on top of it."
        ),
    )

    @model_validator(mode="after")
    def _validate_payment_fields(self) -> "TripCreate":
        # split_payments isn't a TripCreate field — the trip's total isn't
        # known until close, so split-fare requires closing with
        # payment_method="split_fare" + split_payments rather than opening
        # with it.
        _validate_voucher_and_account(self.payment_method, self.voucher_code, self.account_reference)
        _validate_negotiated_total(self.negotiated_total)
        return self


# --- Update (partial; pre-close mutable fields only) ---------------------


class TripUpdate(BaseModel):
    vehicle_id: str | None = None
    driver_id: str | None = None
    shift_id: str | None = None
    tariff_id: str | None = None
    payment_method: PaymentMethod | None = None
    voucher_code: str | None = None
    account_reference: str | None = None
    split_payments: list[SplitPaymentItem] | None = None
    tolls: Decimal | None = None
    extras: Decimal | None = None
    gps_trace_ref: str | None = None
    receipt_ref: str | None = None
    end_lat: float | None = None
    end_lng: float | None = None

    @model_validator(mode="after")
    def _validate_payment_fields(self) -> "TripUpdate":
        if self.payment_method is not None:
            _validate_voucher_and_account(self.payment_method, self.voucher_code, self.account_reference)
            _validate_split_payments_required(self.payment_method, self.split_payments)
        return self


# --- Tick -----------------------------------------------------------------


class TripTickRequest(BaseModel):
    points: list[TelemetryPoint] = Field(..., min_length=1)


# --- Close ------------------------------------------------------------------


class TripCloseRequest(BaseModel):
    end_at: datetime | None = None
    end_lat: float | None = None
    end_lng: float | None = None
    payment_method: PaymentMethod | None = None
    voucher_code: str | None = None
    account_reference: str | None = None
    split_payments: list[SplitPaymentItem] | None = None
    surcharge_pct: Decimal | None = None
    cleaning_fee: Decimal = Decimal(0)
    include_psl: bool = False
    receipt_ref: str | None = None
    tip_amount: Decimal | None = Field(
        default=None,
        description=(
            "Driver tip (Close & Pay 'tips' pass) — a voluntary, non-fare amount, "
            "never folded into fare_total/surcharge/total/gst_component. `null` "
            "means no tip was recorded for this close."
        ),
    )

    @model_validator(mode="after")
    def _validate_payment_fields(self) -> "TripCloseRequest":
        _validate_tip_amount(self.tip_amount)
        # payment_method=None here means "keep the trip's existing
        # payment_method" (see app.api.v1.trips.close_trip_endpoint) — only
        # cross-validate voucher_code/account_reference/split_payments against
        # an *explicitly* requested payment_method; a trip already opened
        # with payment_method="voucher" + voucher_code, then closed without
        # re-specifying either, is valid (app.services.trips.close_trip reads
        # the value already persisted on the trip row in that case).
        if self.payment_method is not None:
            _validate_voucher_and_account(self.payment_method, self.voucher_code, self.account_reference)
            _validate_split_payments_required(self.payment_method, self.split_payments)
        return self


# --- Sync (offline bulk replay) ----------------------------------------------


class TripSyncItem(BaseModel):
    """A complete, self-contained trip payload uploaded after a period offline.

    Carries its own `client_uuid` (idempotency key) and the raw `gps_trace`
    recorded on-device so the server can independently recompute the fare and
    check it against `device_total`.
    """

    client_uuid: str
    vehicle_id: str
    driver_id: str
    shift_id: str | None = None
    tariff_id: str
    type: TripType
    start_at: datetime
    end_at: datetime
    start_lat: float
    start_lng: float
    end_lat: float | None = None
    end_lng: float | None = None
    payment_method: PaymentMethod = "cash"
    # Same three new-payment-method fields as TripCreate/TripCloseRequest (blueprint 5.2.5) —
    # added so a trip closed offline with voucher/account/split_fare doesn't silently lose those
    # details on the ONLY path this platform's driver app actually uses to persist a closed trip
    # (POST /v1/trips/sync — direct TripCreate+TripCloseRequest calls exist in this API but have
    # no real call site on-device, see the Android app's own ApiService.kt doc on TripSyncItemDto).
    # split_payments' sum-vs-total check can't happen in this schema (device_total/the recomputed
    # grand_total aren't known until the router calls recompute_from_trace) — see
    # app.api.v1.trips.sync_trips for where that check actually runs, mirroring close_trip's.
    voucher_code: str | None = None
    account_reference: str | None = None
    split_payments: list[SplitPaymentItem] | None = None
    # DEVICE ADVISORY ONLY — see TripCreate.time_class/is_peak's doc comment
    # above; app.services.trips.recompute_from_trace resolves the
    # authoritative values from the tariff + this item's own start_at.
    time_class: TimeClass = "day"
    is_peak: bool = False
    # DEVICE ADVISORY ONLY — see TripCreate.maxi's doc comment above; the
    # router resolves the authoritative value from Vehicle.vehicle_class.
    maxi: bool = False
    passenger_count: int = Field(default=1, ge=1, le=11)
    wheelchair_hiring: bool = False
    airport_rank_requested_maxi: bool = False
    tolls: Decimal = Decimal(0)
    extras: Decimal = Decimal(0)
    cleaning_fee: Decimal = Decimal(0)
    surcharge_pct: Decimal | None = None
    include_psl: bool = False
    gps_trace: list[TelemetryPoint] = Field(default_factory=list)
    gps_trace_ref: str | None = None
    receipt_ref: str | None = None
    negotiated_total: Decimal | None = Field(
        default=None,
        description=(
            "Same negotiated/set-price fixed fare as TripCreate.negotiated_total "
            "— carried through offline sync so a trip opened+closed on-device "
            "with a driver-entered set price doesn't lose it on replay."
        ),
    )
    device_total: Decimal = Field(..., description="The total the offline device computed on-vehicle")
    tip_amount: Decimal | None = Field(
        default=None,
        description=(
            "Same driver tip as TripCloseRequest.tip_amount — carried through "
            "offline sync so a trip opened+closed on-device with a driver-entered "
            "tip doesn't lose it on replay. Never part of device_total: the "
            "on-device fare engine's own total excludes it, same as the server's."
        ),
    )

    @model_validator(mode="after")
    def _validate_payment_fields(self) -> "TripSyncItem":
        _validate_voucher_and_account(self.payment_method, self.voucher_code, self.account_reference)
        _validate_split_payments_required(self.payment_method, self.split_payments)
        _validate_negotiated_total(self.negotiated_total)
        _validate_tip_amount(self.tip_amount)
        return self


# --- Read ---------------------------------------------------------------


class TripRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    client_uuid: str
    vehicle_id: str
    driver_id: str
    shift_id: str | None
    tariff_id: str
    type: str
    status: str
    time_class: str
    is_peak: bool
    maxi: bool
    passenger_count: int
    wheelchair_hiring: bool
    airport_rank_requested_maxi: bool
    start_at: datetime
    end_at: datetime | None
    start_lat: float
    start_lng: float
    end_lat: float | None
    end_lng: float | None
    distance_m: int
    moving_s: int
    waiting_s: int
    flag_fall: Decimal
    dist_amount: Decimal
    wait_amount: Decimal
    peak_amount: Decimal
    tolls: Decimal
    psl: Decimal
    extras: Decimal
    subtotal: Decimal
    surcharge: Decimal
    total: Decimal
    gst_component: Decimal
    payment_method: str
    gps_trace_ref: str | None
    max_fare_check_passed: bool
    variance_pct: Decimal | None
    receipt_ref: str | None
    auto_tolls_applied: list[str] | None = Field(default_factory=list)
    flagged_for_review: bool
    review_notes: str | None
    voucher_code: str | None
    account_reference: str | None
    split_payments: list[dict] | None = Field(default_factory=list)
    negotiated_total: Decimal | None
    tip_amount: Decimal | None
    created_at: datetime
    updated_at: datetime


class TripListResponse(BaseModel):
    items: list[TripRead]
    total: int
    skip: int
    limit: int


class TripSyncResultItem(BaseModel):
    client_uuid: str
    duplicate: bool
    trip: TripRead


class TripSyncResponse(BaseModel):
    results: list[TripSyncResultItem]


# --- Dispute flagging (blueprint 5.2.5 "Dispute" button / 6.1.3 schema) ------


class TripFlagRequest(BaseModel):
    """Body for PATCH /v1/trips/{id}/flag. `flagged=True` (the default) flags
    a closed trip for operator review and requires a non-empty `reason`;
    `flagged=False` clears an existing flag (staff-only — see
    app.api.v1.trips.flag_trip) and ignores `reason`."""

    flagged: bool = True
    reason: str | None = Field(default=None, max_length=2000)


# --- Receipt delivery (blueprint 5.2.6/8.5) ----------------------------------
# Trip carries no customer-contact column (no email/phone field on the model —
# it's an offline-first meter journey record, not a booking), so the
# recipient is supplied per-request rather than read off the trip row.


class ReceiptEmailRequest(BaseModel):
    to_email: str = Field(..., min_length=3, max_length=255)


class ReceiptEmailResponse(BaseModel):
    """Same mock-fallback shape/spirit as app.schemas.payments.TapToPayIntentResponse:
    `mock` always present; `would_send_to` is populated only in the mock path,
    `to_email` + `sendgrid_status_code` only on a real send."""

    mock: bool
    would_send_to: str | None = None
    to_email: str | None = None
    sendgrid_status_code: int | None = None
    receipt_ref: str | None = None
    pdf_relative_path: str
    pdf_generated_now: bool


class ReceiptSmsRequest(BaseModel):
    to_phone: str = Field(..., min_length=3, max_length=32)


class ReceiptSmsResponse(BaseModel):
    mock: bool
    would_send_to: str | None = None
    to_phone: str | None = None
    twilio_sid: str | None = None
    message: str | None = None
    receipt_ref: str | None = None
    pdf_relative_path: str
    pdf_generated_now: bool
