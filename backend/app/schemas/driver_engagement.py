"""Pydantic schemas for the driver-engagement domain (wallet, ratings,
announcements, incentives) -- shared by the operator CRUD routers
(app.api.v1.wallet / ratings / announcements / incentives) and the
driver-facing `/v1/me/*` router (app.api.v1.me).

Money is `Decimal` end to end (serialised as a decimal string on the wire,
same as every other money field in this API -- e.g. VoucherRead.value_aud);
never float. Windowed datetimes are normalised to UTC on the way in (see
app.services.driver_engagement.to_utc) so the window queries behave the same
on sqlite (string compare) and postgres (timestamptz).
"""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.models.driver_engagement import RATING_MAX_STARS, RATING_MIN_STARS
from app.services.driver_engagement import to_utc

T = TypeVar("T")


class Page(BaseModel, Generic[T]):
    items: list[T]
    total: int
    skip: int
    limit: int


WalletKind = Literal["trip_earning", "top_up", "adjustment", "payout"]
OperatorWalletKind = Literal["top_up", "adjustment", "payout"]
AnnouncementKind = Literal["info", "maintenance", "surge", "feature"]


# --- wallet -------------------------------------------------------------------


class WalletTransactionCreate(BaseModel):
    """Operator-posted ledger line (POST /v1/wallet/transactions).
    `amount_aud` is the SIGNED delta applied to the driver's balance:
    top_up must be positive, payout must be negative (money leaving the
    wallet), adjustment may be either but never zero."""

    driver_id: str = Field(min_length=1, max_length=36)
    amount_aud: Decimal = Field(max_digits=10, decimal_places=2)
    kind: OperatorWalletKind
    reference: str | None = Field(default=None, max_length=100)
    note: str | None = Field(default=None, max_length=2000)

    @model_validator(mode="after")
    def _validate_sign(self) -> "WalletTransactionCreate":
        if self.amount_aud == 0:
            raise ValueError("amount_aud must be non-zero")
        if self.kind == "top_up" and self.amount_aud < 0:
            raise ValueError("top_up amount_aud must be positive")
        if self.kind == "payout" and self.amount_aud > 0:
            raise ValueError("payout amount_aud must be negative (money leaving the wallet)")
        return self


class WalletTransactionRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    driver_id: str
    amount_aud: Decimal
    kind: str
    reference: str | None
    note: str | None
    created_by_user_id: str | None
    created_at: datetime


class WalletRead(BaseModel):
    """`GET /v1/me/wallet` and `GET /v1/wallet/drivers/{driver_id}`.
    `balance_aud` is derived (SUM of the ledger) on every read."""

    driver_id: str
    balance_aud: Decimal
    recent: list[WalletTransactionRead]


# --- ratings ------------------------------------------------------------------


class TripRatingCreate(BaseModel):
    """`POST /v1/trips/{trip_id}/rating` -- Close & Pay's rating step."""

    stars: int = Field(ge=RATING_MIN_STARS, le=RATING_MAX_STARS)
    comment: str | None = Field(default=None, max_length=1000)


class TripRatingRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    trip_id: str
    driver_id: str
    stars: int
    comment: str | None
    created_at: datetime


class RatingRead(BaseModel):
    """`GET /v1/me/rating`. `average_stars` is null until the first rating."""

    driver_id: str
    average_stars: Decimal | None
    rating_count: int
    recent: list[TripRatingRead]


# --- announcements ------------------------------------------------------------


def _utc(value: datetime | None) -> datetime | None:
    return to_utc(value) if value is not None else None


class AnnouncementCreate(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    body: str = Field(min_length=1, max_length=5000)
    kind: AnnouncementKind = "info"
    starts_at: datetime
    ends_at: datetime | None = None
    active: bool = True

    @field_validator("starts_at", "ends_at")
    @classmethod
    def _normalise_utc(cls, value: datetime | None) -> datetime | None:
        return _utc(value)

    @model_validator(mode="after")
    def _validate_window(self) -> "AnnouncementCreate":
        if self.ends_at is not None and self.ends_at <= self.starts_at:
            raise ValueError("ends_at must be after starts_at")
        return self


class AnnouncementUpdate(BaseModel):
    title: str | None = Field(default=None, min_length=1, max_length=200)
    body: str | None = Field(default=None, min_length=1, max_length=5000)
    kind: AnnouncementKind | None = None
    starts_at: datetime | None = None
    ends_at: datetime | None = None
    active: bool | None = None

    @field_validator("starts_at", "ends_at")
    @classmethod
    def _normalise_utc(cls, value: datetime | None) -> datetime | None:
        return _utc(value)


class AnnouncementRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    title: str
    body: str
    kind: str
    starts_at: datetime
    ends_at: datetime | None
    active: bool
    created_at: datetime
    updated_at: datetime


class AnnouncementListRead(BaseModel):
    """`GET /v1/me/announcements` -- only the currently-live ones."""

    items: list[AnnouncementRead]


# --- incentives ---------------------------------------------------------------


class IncentiveCreate(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    description: str | None = Field(default=None, max_length=5000)
    target_trips: int = Field(gt=0)
    reward_aud: Decimal = Field(gt=0, max_digits=10, decimal_places=2)
    starts_at: datetime
    ends_at: datetime
    active: bool = True

    @field_validator("starts_at", "ends_at")
    @classmethod
    def _normalise_utc(cls, value: datetime) -> datetime:
        return to_utc(value)

    @model_validator(mode="after")
    def _validate_window(self) -> "IncentiveCreate":
        if self.ends_at <= self.starts_at:
            raise ValueError("ends_at must be after starts_at")
        return self


class IncentiveUpdate(BaseModel):
    title: str | None = Field(default=None, min_length=1, max_length=200)
    description: str | None = Field(default=None, max_length=5000)
    target_trips: int | None = Field(default=None, gt=0)
    reward_aud: Decimal | None = Field(default=None, gt=0, max_digits=10, decimal_places=2)
    starts_at: datetime | None = None
    ends_at: datetime | None = None
    active: bool | None = None

    @field_validator("starts_at", "ends_at")
    @classmethod
    def _normalise_utc(cls, value: datetime | None) -> datetime | None:
        return _utc(value)


class IncentiveRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    title: str
    description: str | None
    target_trips: int
    reward_aud: Decimal
    starts_at: datetime
    ends_at: datetime
    active: bool
    created_at: datetime
    updated_at: datetime


class IncentiveProgressRead(IncentiveRead):
    """One live incentive + the calling driver's derived progress."""

    completed_trips: int
    remaining_trips: int
    progress_pct: int
    achieved: bool


class IncentiveProgressListRead(BaseModel):
    """`GET /v1/me/incentives`."""

    items: list[IncentiveProgressRead]
