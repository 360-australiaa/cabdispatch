"""Pydantic v2 schemas for the PSL ledger domain."""
from __future__ import annotations

import re
from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict, Field, field_validator

_PERIOD_RE = re.compile(r"^\d{4}-(0[1-9]|1[0-2])$")


def _validate_period(value: str) -> str:
    if not _PERIOD_RE.match(value):
        raise ValueError('period must be in "YYYY-MM" format, e.g. "2026-07"')
    return value


# --- PSL ledger entries ------------------------------------------------------


class PSLLedgerBase(BaseModel):
    driver_id: str
    period: str
    trips_count: int = Field(default=0, ge=0)
    amount_owed: Decimal = Field(default=Decimal("0.00"), ge=0)
    amount_collected: Decimal = Field(default=Decimal("0.00"), ge=0)
    remitted_at: datetime | None = None

    _validate_period = field_validator("period")(_validate_period)


class PSLLedgerCreate(PSLLedgerBase):
    pass


class PSLLedgerUpdate(BaseModel):
    """Partial update — e.g. correcting an accrual or marking a period remitted."""

    trips_count: int | None = Field(default=None, ge=0)
    amount_owed: Decimal | None = Field(default=None, ge=0)
    amount_collected: Decimal | None = Field(default=None, ge=0)
    remitted_at: datetime | None = None


class PSLLedgerRead(PSLLedgerBase):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    created_at: datetime
    updated_at: datetime


# --- PSL top-ups (append-only) -----------------------------------------------


class PSLTopUpCreate(BaseModel):
    driver_id: str
    period: str
    amount: Decimal = Field(gt=0)
    payment_method: str = "card"

    _validate_period = field_validator("period")(_validate_period)


class PSLTopUpRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    driver_id: str
    period: str
    amount: Decimal
    payment_method: str
    stripe_charge_id: str
    status: str
    created_at: datetime


# --- PSL remittance report ----------------------------------------------------


class PSLReportDriverLine(BaseModel):
    driver_id: str
    driver_name: str | None = None
    trips_count: int
    amount_owed: Decimal
    amount_collected: Decimal
    amount_outstanding: Decimal
    remitted: bool


class PSLReport(BaseModel):
    tenant_id: str
    period: str
    driver_count: int
    total_trips: int
    total_owed: Decimal
    total_collected: Decimal
    total_outstanding: Decimal
    drivers: list[PSLReportDriverLine]
