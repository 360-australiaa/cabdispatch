"""Pydantic v2 schemas for the auth endpoints (app/api/v1/auth.py).

Auth is not one of the 12 domain slices — it's foundation glue added at
integration time so seeded users (see scripts/seed.py) can actually obtain a
bearer token to call the rest of the API / log into the dashboard.
"""
from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


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


class LogoutRequest(BaseModel):
    """Body for POST /v1/auth/logout -- entirely optional (see that route's
    docstring): every existing caller (dashboard, Android) already posts with
    no body at all, so this stays an opt-in extra rather than a required
    contract change. refresh_token, if supplied, is revoked in addition to
    the caller own access-token jti (which is always revoked, body or not)."""

    refresh_token: str | None = None


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


class MfaVerifyResponse(BaseModel):
    """Returned by POST /v1/auth/mfa/verify only -- distinct from
    MfaStatusResponse (still used by /mfa/disable, unchanged) because this is
    the ONE moment recovery_codes are generated and returned in plaintext
    (plan Part 4 Phase 1 WP-16, I-5). Never shown again after this response --
    only their hashes persist (see app.services.mfa_recovery_codes)."""

    mfa_enabled: bool
    recovery_codes: list[str]


class MfaDisableRequest(BaseModel):
    password: str


class MfaLoginRequest(BaseModel):
    mfa_token: str
    code: str


# --- Password lifecycle (plan Part 4, Phase 1, WP-10/11/12) -----------------


class PasswordChangeRequiredResponse(BaseModel):
    """Returned by POST /v1/auth/login (or /driver-login) instead of
    TokenResponse when the account has must_change_password=True.
    password_change_token is short-lived and only good for POST
    /v1/auth/change-password."""

    password_change_required: bool = True
    password_change_token: str


class AcceptInviteRequest(BaseModel):
    token: str
    new_password: str = Field(min_length=6, max_length=128)


class ForgotPasswordRequest(BaseModel):
    email: str


class ForgotPasswordResponse(BaseModel):
    """Always the exact same body regardless of whether `email` matches a
    real account -- see POST /v1/auth/forgot-password's docstring for why
    (account-enumeration prevention)."""

    detail: str = "If an account exists for that email, a password reset link has been sent."


class ResetPasswordRequest(BaseModel):
    token: str
    new_password: str = Field(min_length=6, max_length=128)


class ResetPasswordResponse(BaseModel):
    detail: str = "Password has been reset. Please log in with your new password."


class ChangePasswordRequest(BaseModel):
    current_password: str
    new_password: str = Field(min_length=6, max_length=128)
