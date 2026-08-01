"""Pydantic v2 schemas for the trips domain."""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

TripType = Literal["rank_hail", "booked", "airport_fixed", "multi_hire"]
TripStatus = Literal["open", "closed"]
TimeClass = Literal["day", "night", "holiday"]
PaymentMethod = Literal["cash", "card"]


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
    time_class: TimeClass = "day"
    is_peak: bool = False
    maxi: bool = False
    tolls: Decimal = Decimal(0)
    extras: Decimal = Decimal(0)
    gps_trace_ref: str | None = None


# --- Update (partial; pre-close mutable fields only) ---------------------


class TripUpdate(BaseModel):
    vehicle_id: str | None = None
    driver_id: str | None = None
    shift_id: str | None = None
    tariff_id: str | None = None
    payment_method: PaymentMethod | None = None
    tolls: Decimal | None = None
    extras: Decimal | None = None
    gps_trace_ref: str | None = None
    receipt_ref: str | None = None
    end_lat: float | None = None
    end_lng: float | None = None


# --- Tick -----------------------------------------------------------------


class TripTickRequest(BaseModel):
    points: list[TelemetryPoint] = Field(..., min_length=1)


# --- Close ------------------------------------------------------------------


class TripCloseRequest(BaseModel):
    end_at: datetime | None = None
    end_lat: float | None = None
    end_lng: float | None = None
    payment_method: PaymentMethod | None = None
    surcharge_pct: Decimal | None = None
    cleaning_fee: Decimal = Decimal(0)
    include_psl: bool = False
    receipt_ref: str | None = None


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
    time_class: TimeClass = "day"
    is_peak: bool = False
    maxi: bool = False
    tolls: Decimal = Decimal(0)
    extras: Decimal = Decimal(0)
    cleaning_fee: Decimal = Decimal(0)
    surcharge_pct: Decimal | None = None
    include_psl: bool = False
    gps_trace: list[TelemetryPoint] = Field(default_factory=list)
    gps_trace_ref: str | None = None
    receipt_ref: str | None = None
    device_total: Decimal = Field(..., description="The total the offline device computed on-vehicle")


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
