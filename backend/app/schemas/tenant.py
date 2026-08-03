"""Pydantic v2 schemas for the tenant domain's admin-PIN endpoints."""
from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field

# 4-8 numeric digits. Not hardcoded to exactly 6 even though it replaces the
# Android app's 6-digit ADMIN_PIN_PLACEHOLDER — a tenant owner picks their
# own PIN within a sane length range, same spirit as UserCreate.password's
# min_length/max_length band in app/schemas/user.py.
_PIN_PATTERN = r"^\d{4,8}$"


class AdminPinSetRequest(BaseModel):
    pin: str = Field(min_length=4, max_length=8, pattern=_PIN_PATTERN)


class AdminPinSetResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    tenant_id: str
    admin_pin_configured: bool


__all__ = ["AdminPinSetRequest", "AdminPinSetResponse"]
