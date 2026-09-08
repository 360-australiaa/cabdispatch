"""Pydantic v2 schemas for the auth endpoints (app/api/v1/auth.py).

Auth is not one of the 12 domain slices — it's foundation glue added at
integration time so seeded users (see scripts/seed.py) can actually obtain a
bearer token to call the rest of the API / log into the dashboard.
"""
from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict


class LoginRequest(BaseModel):
    email: str
    password: str


class DriverLoginRequest(BaseModel):
    """Body for POST /v1/auth/driver-login — the driver-facing counterpart to
    LoginRequest above. `pin` is verified against the same `User.pin_hash`
    column `password` is (see app/api/v1/auth.py).

    ⚠ BREAKING CHANGE: `tenant_slug` is REQUIRED as of this revision.

    It was previously absent, and the driver-code lookup ran unfiltered across
    every tenant on the platform (backend audit §5 ADDENDUM) — one 6-digit-PIN
    credential space shared by every operator, on an endpoint with no rate
    limiting. `tenant_slug` is `Tenant.slug` (see app/models/tenant.py): a
    public, stable, URL-safe handle, unique platform-wide. It is not a secret
    and is not a second authentication factor; it is the discriminator that
    turns one global PIN space into one PIN space per tenant.

    Required, not optional-with-fallback: an optional field would leave the
    global lookup reachable by simply omitting it, which is the whole hole.
    The Android client change is tracked as a separate workstream.
    """

    tenant_slug: str
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
    # Exactly one of `code` (a live 6-digit TOTP) or `recovery_code` (one of
    # the ten single-use codes from POST /mfa/recovery-codes/generate) must
    # be supplied — see app/api/v1/auth.py::mfa_login.
    code: str | None = None
    recovery_code: str | None = None


# --- D10: password change / reset -------------------------------------------


class PasswordChangeRequest(BaseModel):
    current_password: str
    new_password: str


class PasswordResetRequest(BaseModel):
    email: str


class PasswordResetConfirmRequest(BaseModel):
    reset_token: str
    new_password: str


# --- D10: MFA recovery codes -------------------------------------------------


class RecoveryCodesResponse(BaseModel):
    """The ten plaintext codes — returned exactly ONCE, by
    POST /mfa/recovery-codes/generate, and never retrievable again."""

    codes: list[str]


class RecoveryCodeStatus(BaseModel):
    remaining: int


# --- D10: sessions ("sign out everywhere") -----------------------------------


class SessionRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    user_agent: str | None
    ip_address: str | None
    created_at: datetime
    last_seen_at: datetime
    is_current: bool = False


class SessionListResponse(BaseModel):
    sessions: list[SessionRead]
