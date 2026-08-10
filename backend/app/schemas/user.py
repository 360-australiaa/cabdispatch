"""Pydantic v2 schemas for the users domain (staff + driver onboarding/CRUD).

Added post-integration: none of the 12 parallel domain slices owned "create a
driver/dispatcher/admin via the API" (only scripts/seed.py could create users),
which left a real CRUD gap. This follows the same local-Page[T] / Base-Create-
Update-Read convention as every other domain (see app/schemas/fleet.py).
"""
from __future__ import annotations

from datetime import date, datetime
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
    # Compliance-expiry tracking (blueprint 7.2.3/10.1). Only meaningful for
    # role="driver" accounts but, like driver_licence_no above, accepted (and
    # a no-op) for any role. Null means "unknown, not expired" — see
    # app.models.user.User's doc comment for the fail-open convention and
    # app.services.compliance_expiry for the alerting/login-block logic.
    driver_license_expiry: date | None = Field(default=None)
    driver_authority_expiry: date | None = Field(default=None)


class UserCreate(UserBase):
    # Plain str (not EmailStr) — matches app/schemas/auth.py's LoginRequest.email,
    # which avoids requiring the optional `email-validator` package.
    email: str = Field(min_length=3, max_length=255)
    role: UserRole = "driver"
    # Plaintext in the request only — hashed into User.pin_hash before storage,
    # same column/verification path app/api/v1/auth.py already uses for login.
    password: str = Field(min_length=6, max_length=128)
    # Optional: only meaningful for role="driver". If omitted, the create
    # endpoint auto-generates one (see app/services/user.py::generate_unique_driver_code).
    # Supplying it for a non-driver role is accepted but has no login effect —
    # POST /v1/auth/driver-login is unreachable without role="driver" (see
    # app/api/v1/auth.py).
    driver_code: str | None = Field(default=None, min_length=4, max_length=6)


class UserUpdate(BaseModel):
    """Partial update — every field optional."""

    name: str | None = Field(default=None, min_length=1, max_length=255)
    phone: str | None = None
    driver_licence_no: str | None = None
    wat_endorsed: bool | None = None
    status: UserStatus | None = None
    role: UserRole | None = None
    password: str | None = Field(default=None, min_length=6, max_length=128)
    driver_license_expiry: date | None = None
    driver_authority_expiry: date | None = None


class UserRead(UserBase):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str | None
    role: UserRole
    email: str
    # Read-only after creation — see UserCreate.driver_code. None for every
    # non-driver user (and for drivers created before this field existed).
    driver_code: str | None = None
    # Read-only — set via POST /v1/users/{id}/photo, never directly through
    # UserCreate/UserUpdate. Relative (to BACKEND_ROOT) on-disk path, same
    # convention as app.schemas.compliance.ComplianceDocumentRead.file_path.
    # None until a photo is uploaded.
    photo_url: str | None = None
    created_at: datetime
    updated_at: datetime
