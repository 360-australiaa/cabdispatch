"""Pydantic v2 schemas for the auth endpoints (app/api/v1/auth.py).

Auth is not one of the 12 domain slices — it's foundation glue added at
integration time so seeded users (see scripts/seed.py) can actually obtain a
bearer token to call the rest of the API / log into the dashboard.
"""
from __future__ import annotations

from pydantic import BaseModel, ConfigDict


class LoginRequest(BaseModel):
    email: str
    password: str


class DriverLoginRequest(BaseModel):
    """Body for POST /v1/auth/driver-login — the driver-facing counterpart to
    LoginRequest above. `pin` is verified against the same `User.pin_hash`
    column `password` is (see app/api/v1/auth.py)."""

    driver_code: str
    pin: str


class UserRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str | None
    role: str
    name: str
    email: str
    status: str
    mfa_enabled: bool
    driver_code: str | None = None


class TokenResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    user: UserRead


class RefreshRequest(BaseModel):
    refresh_token: str


class RefreshResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"


# --- MFA (blueprint 12.2) ----------------------------------------------------


class MfaRequiredResponse(BaseModel):
    """Returned by POST /v1/auth/login instead of TokenResponse when the
    account has mfa_enabled=True. `mfa_token` is short-lived and only good
    for POST /v1/auth/mfa/login."""

    mfa_required: bool = True
    mfa_token: str


class MfaSetupResponse(BaseModel):
    """secret is also embedded in otpauth_uri, but returned separately so the
    frontend can show it as manual-entry text alongside (or instead of) a
    rendered QR code."""

    secret: str
    otpauth_uri: str


class MfaVerifyRequest(BaseModel):
    code: str


class MfaStatusResponse(BaseModel):
    mfa_enabled: bool


class MfaDisableRequest(BaseModel):
    password: str


class MfaLoginRequest(BaseModel):
    mfa_token: str
    code: str
