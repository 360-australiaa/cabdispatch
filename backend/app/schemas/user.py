"""Pydantic v2 schemas for the users domain (staff + driver onboarding/CRUD).

Added post-integration: none of the 12 parallel domain slices owned "create a
driver/dispatcher/admin via the API" (only scripts/seed.py could create users),
which left a real CRUD gap. This follows the same local-Page[T] / Base-Create-
Update-Read convention as every other domain (see app/schemas/fleet.py).
"""
from __future__ import annotations

from datetime import datetime
from typing import Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field

UserRole = Literal["owner", "admin", "dispatcher", "driver"]
UserStatus = Literal["active", "inactive", "suspended"]

T = TypeVar("T")


class Page(BaseModel, Generic[T]):
    items: list[T]
    total: int
    skip: int
    limit: int


class UserBase(BaseModel):
    name: str = Field(min_length=1, max_length=255)
    phone: str | None = Field(default=None, max_length=30)
    driver_licence_no: str | None = Field(default=None, max_length=50)
    wat_endorsed: bool = False
    status: UserStatus = "active"


class UserCreate(UserBase):
    # Plain str (not EmailStr) — matches app/schemas/auth.py's LoginRequest.email,
    # which avoids requiring the optional `email-validator` package.
    email: str = Field(min_length=3, max_length=255)
    role: UserRole = "driver"
    # Plaintext in the request only — hashed into User.pin_hash before storage,
    # same column/verification path app/api/v1/auth.py already uses for login.
    password: str = Field(min_length=6, max_length=128)


class UserUpdate(BaseModel):
    """Partial update — every field optional."""

    name: str | None = Field(default=None, min_length=1, max_length=255)
    phone: str | None = None
    driver_licence_no: str | None = None
    wat_endorsed: bool | None = None
    status: UserStatus | None = None
    role: UserRole | None = None
    password: str | None = Field(default=None, min_length=6, max_length=128)


class UserRead(UserBase):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str | None
    role: UserRole
    email: str
    created_at: datetime
    updated_at: datetime
