"""Pydantic v2 schemas for the tariffs domain."""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

Region = Literal["urban", "country", "exempt"]
ExtraType = Literal["fixed", "passthrough"]


# --- Tariff -------------------------------------------------------------------


class TariffBase(BaseModel):
    name: str = Field(min_length=1, max_length=255)
    region: Region
    effective_from: datetime
    effective_to: datetime | None = None
    booked: bool = False

    flag_fall: Decimal
    peak_charge: Decimal = Decimal(0)
    dist_rate_1: Decimal
    dist_rate_2: Decimal
    night_rate_1: Decimal
    night_rate_2: Decimal
    holiday_rate_1: Decimal = Decimal(0)
    holiday_rate_2: Decimal = Decimal(0)
    waiting_rate_per_min: Decimal
    dist_km_threshold: Decimal = Decimal(12)
    speed_threshold_kmh: Decimal = Decimal(26)
    maxi_multiplier: Decimal = Decimal("1.5")
    multi_hire_pct: Decimal = Decimal("0.75")
    psl_amount: Decimal = Decimal("1.32")
    surcharge_pct_cap: Decimal = Decimal("5.0")
    cleaning_fee_cap: Decimal = Decimal("124.14")

    @model_validator(mode="after")
    def _effective_to_after_from(self) -> TariffBase:
        if self.effective_to is not None and self.effective_to <= self.effective_from:
            raise ValueError("effective_to must be after effective_from")
        return self


class TariffCreate(TariffBase):
    pass


class TariffUpdate(BaseModel):
    """Partial update — every field optional. `tenant_id` and `region` are not
    changeable after creation (region defines which Fares Order reference a
    tariff is validated against; create a new tariff instead)."""

    name: str | None = Field(default=None, min_length=1, max_length=255)
    effective_from: datetime | None = None
    effective_to: datetime | None = None
    booked: bool | None = None

    flag_fall: Decimal | None = None
    peak_charge: Decimal | None = None
    dist_rate_1: Decimal | None = None
    dist_rate_2: Decimal | None = None
    night_rate_1: Decimal | None = None
    night_rate_2: Decimal | None = None
    holiday_rate_1: Decimal | None = None
    holiday_rate_2: Decimal | None = None
    waiting_rate_per_min: Decimal | None = None
    dist_km_threshold: Decimal | None = None
    speed_threshold_kmh: Decimal | None = None
    maxi_multiplier: Decimal | None = None
    multi_hire_pct: Decimal | None = None
    psl_amount: Decimal | None = None
    surcharge_pct_cap: Decimal | None = None
    cleaning_fee_cap: Decimal | None = None


class TariffRead(TariffBase):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str | None
    created_at: datetime
    updated_at: datetime


class SignedTariffRead(TariffRead):
    """TariffRead + an Ed25519 signature over the tariff's canonical rate
    payload (see app.services.tariff_signing). Used only by
    `GET /v1/tariffs/active` — the endpoint devices poll to refresh their
    cached tariff — so the on-device signature verifier can detect tampering
    of a cached/relayed copy. See app.services.tariff_signing's module
    docstring for the exact canonical-serialization format this signs."""

    signature: str  # base64 Ed25519 signature, standard (non-URL-safe) alphabet


class TariffSigningPublicKeyRead(BaseModel):
    """Response for GET /v1/tariffs/signing-public-key. Public keys aren't
    secret — this endpoint requires no auth so devices can fetch it before
    (or independent of) being otherwise authenticated."""

    public_key: str  # X.509 SubjectPublicKeyInfo DER, base64-encoded
    algorithm: Literal["Ed25519"] = "Ed25519"


# --- Extra ----------------------------------------------------------------------


class ExtraBase(BaseModel):
    name: str = Field(min_length=1, max_length=255)
    amount: Decimal
    type: ExtraType


class ExtraCreate(ExtraBase):
    pass


class ExtraUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=255)
    amount: Decimal | None = None
    type: ExtraType | None = None


class ExtraRead(ExtraBase):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tariff_id: str
    tenant_id: str | None
    created_at: datetime
    updated_at: datetime


# --- Tariff change log (read-only) ----------------------------------------------


class TariffChangeLogRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tariff_id: str
    tenant_id: str | None
    actor_user_id: str
    before_json: dict | None
    after_json: dict
    at: datetime


# --- Pagination -------------------------------------------------------------------


class Page[T](BaseModel):
    """Generic paginated list envelope, local to this domain until a shared
    one exists in app.core."""

    items: list[T]
    total: int
    skip: int
    limit: int


# --- Named presets (see app.services.tariff_presets) ----------------------------

PresetKey = Literal["airport_rank", "special_event", "shared_ride", "wheelchair_accessible"]


class TariffPresetDefaults(BaseModel):
    """The subset of TariffBase fields a preset provides defaults for.
    `name`/`effective_from`/`effective_to` are always supplied by the caller
    at creation time (see TariffFromPresetCreate below), never by the preset
    itself."""

    region: Region
    booked: bool
    flag_fall: Decimal
    peak_charge: Decimal
    dist_rate_1: Decimal
    dist_rate_2: Decimal
    night_rate_1: Decimal
    night_rate_2: Decimal
    holiday_rate_1: Decimal
    holiday_rate_2: Decimal
    waiting_rate_per_min: Decimal
    dist_km_threshold: Decimal
    speed_threshold_kmh: Decimal
    maxi_multiplier: Decimal
    multi_hire_pct: Decimal
    psl_amount: Decimal
    surcharge_pct_cap: Decimal
    cleaning_fee_cap: Decimal


class TariffPresetRead(BaseModel):
    """Response item for GET /v1/tariffs/presets."""

    key: PresetKey
    label: str
    description: str
    defaults: TariffPresetDefaults


class TariffPresetOverrides(BaseModel):
    """Optional per-field overrides layered on top of a preset's defaults in
    POST /v1/tariffs/from-preset — every field optional, same fields as
    TariffPresetDefaults, unset fields left at the preset's own default
    (exclude_unset semantics, same as TariffUpdate)."""

    region: Region | None = None
    booked: bool | None = None
    flag_fall: Decimal | None = None
    peak_charge: Decimal | None = None
    dist_rate_1: Decimal | None = None
    dist_rate_2: Decimal | None = None
    night_rate_1: Decimal | None = None
    night_rate_2: Decimal | None = None
    holiday_rate_1: Decimal | None = None
    holiday_rate_2: Decimal | None = None
    waiting_rate_per_min: Decimal | None = None
    dist_km_threshold: Decimal | None = None
    speed_threshold_kmh: Decimal | None = None
    maxi_multiplier: Decimal | None = None
    multi_hire_pct: Decimal | None = None
    psl_amount: Decimal | None = None
    surcharge_pct_cap: Decimal | None = None
    cleaning_fee_cap: Decimal | None = None


class TariffFromPresetCreate(BaseModel):
    """Request body for POST /v1/tariffs/from-preset: pick a preset, supply
    the fields presets never default (name/effective window), and optionally
    override any of the preset's defaulted fields."""

    preset: PresetKey
    name: str = Field(min_length=1, max_length=255)
    effective_from: datetime
    effective_to: datetime | None = None
    overrides: TariffPresetOverrides | None = None

    @model_validator(mode="after")
    def _effective_to_after_from(self) -> TariffFromPresetCreate:
        if self.effective_to is not None and self.effective_to <= self.effective_from:
            raise ValueError("effective_to must be after effective_from")
        return self


# --- Auto-suggest (see app.services.tariffs.suggest_tariff) ---------------------


class TariffSuggestionRead(BaseModel):
    """Response for GET /v1/tariffs/suggest."""

    tariff_id: str
    tariff_name: str
    time_class: Literal["day", "night"]
    reason: str
