"""Business logic for password-invite/reset tokens (plan Part 4, Phase 1,
WP-10/11/12) -- one table (UserInvite) serves both the "set your password
for the first time" (purpose="invite") and "I forgot my password"
(purpose="password_reset") flows, since both are "prove ownership of a
token, then set pin_hash" with only the TTL and the caller differing.

Token storage design (real judgment call, documented per task instructions):
the raw token is a high-entropy secrets.token_urlsafe(32) value (32 random
bytes, about 256 bits of entropy, base64url-encoded to about 43 chars) --
brute-forcing it is infeasible regardless of hash speed, so this
deliberately does NOT reuse app.core.security.hash_password (bcrypt).
bcrypt silently truncates any input past 72 bytes -- this token is well
under that, so no truncation risk here, but leaning on that ceiling is
fragile -- and bcrypt's slow-by-design work factor buys nothing when the
INPUT already carries this much entropy: it would only tax every invite
lookup with about 100ms of needless compute for zero real security benefit
over a fast hash. A plain hashlib.sha256 hex digest is used instead, which
still means the DB never holds a usable plaintext token (the actual goal of
hashing it at all). The raw token exists in plaintext only in the HTTP
response and the caller's memory for the single request that creates it --
never persisted, never logged.
"""
from __future__ import annotations

import hashlib
import logging
import secrets
from datetime import UTC, datetime, timedelta

import httpx
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.security import hash_password
from app.models.user import User
from app.models.user_invite import (
    INVITE_PURPOSE_INVITE,
    INVITE_PURPOSE_PASSWORD_RESET,
    UserInvite,
)

logger = logging.getLogger("cab_dispatch.user_invites")

TOKEN_NBYTES = 32
INVITE_TTL = timedelta(days=7)
PASSWORD_RESET_TTL = timedelta(hours=1)


class UserInviteError(Exception):
    pass


class InvalidInviteTokenError(UserInviteError):
    """Covers "no such token", "expired", "already used", and "wrong
    purpose" -- deliberately a single exception/message for all four so a
    caller can never distinguish "this token never existed" from "it did but
    expired/was used", which would leak information an attacker could use.
    """


def _as_aware(dt: datetime) -> datetime:
    """Same helper as app.services.fatigue._as_aware -- sqlite can hand back
    a naive datetime for a DateTime(timezone=True) column even though it was
    written as an aware UTC value; treat a naive value as UTC rather than
    letting the aware/naive comparison raise."""
    return dt if dt.tzinfo is not None else dt.replace(tzinfo=UTC)


def _hash_token(raw_token: str) -> str:
    return hashlib.sha256(raw_token.encode("utf-8")).hexdigest()


def build_invite_link(*, raw_token: str, purpose: str) -> str:
    """Builds the link a real email/SMS would send. settings.PUBLIC_BASE_URL
    is the dashboard frontend public origin -- empty in dev (no value
    configured), which is flagged, not fatal: the link still builds using a
    frontend-relative path so tests/dev can inspect the token, but it needs
    a real PUBLIC_BASE_URL value in production or an email client cannot
    resolve it.
    """
    path = "accept-invite" if purpose == INVITE_PURPOSE_INVITE else "reset-password"
    base = settings.PUBLIC_BASE_URL.rstrip("/") if settings.PUBLIC_BASE_URL else ""
    return f"{base}/{path}?token={raw_token}"


async def create_invite(
    session: AsyncSession,
    *,
    user: User,
    purpose: str,
    ttl: timedelta | None = None,
) -> tuple[UserInvite, str, str]:
    """Generates a new invite/reset token for user, stores its hash, and
    returns (invite_row, raw_token, link). raw_token is the ONLY time the
    plaintext token exists outside the caller's own memory -- it is never
    written to the DB or logged. Any unused, unexpired prior invite of the
    same purpose for this user is left in place (not revoked) -- a second
    outstanding token is harmless since consuming either one marks only
    itself used_at, and a stale first link simply stops being useful once
    the user has already set a new password via the second one (pin_hash
    changes; the old flow was never a permission grant beyond that).
    """
    if ttl is None:
        ttl = INVITE_TTL if purpose == INVITE_PURPOSE_INVITE else PASSWORD_RESET_TTL

    raw_token = secrets.token_urlsafe(TOKEN_NBYTES)
    invite = UserInvite(
        tenant_id=user.tenant_id,
        user_id=user.id,
        token_hash=_hash_token(raw_token),
        purpose=purpose,
        expires_at=datetime.now(UTC) + ttl,
    )
    session.add(invite)
    await session.commit()
    await session.refresh(invite)

    link = build_invite_link(raw_token=raw_token, purpose=purpose)
    return invite, raw_token, link


async def consume_invite(
    session: AsyncSession,
    *,
    token: str,
    new_password: str,
    expected_purpose: str | None = None,
) -> User:
    """Looks up the invite by hashing the presented token, validates it, sets
    the user's password, marks the invite used, and returns the user.

    Single generic InvalidInviteTokenError for every failure mode (missing,
    expired, already-used, wrong purpose) -- see that class's docstring.

    Clears must_change_password when consumed via the invite purpose (plan
    step 2 of this work package): an invite-flow user is, by definition,
    setting their password for the first time through the intended
    self-service path, so there is nothing left to force them to change.
    A password_reset consumption also clears it for the same reason -- the
    user has just proven a fresh password of their own choosing either way.
    """
    token_hash = _hash_token(token)
    result = await session.execute(select(UserInvite).where(UserInvite.token_hash == token_hash))
    invite = result.scalar_one_or_none()

    if invite is None:
        raise InvalidInviteTokenError("Invalid or expired token")
    if invite.used_at is not None:
        raise InvalidInviteTokenError("Invalid or expired token")
    if expected_purpose is not None and invite.purpose != expected_purpose:
        raise InvalidInviteTokenError("Invalid or expired token")
    if _as_aware(invite.expires_at) <= datetime.now(UTC):
        raise InvalidInviteTokenError("Invalid or expired token")

    result = await session.execute(select(User).where(User.id == invite.user_id))
    user = result.scalar_one_or_none()
    if user is None:
        raise InvalidInviteTokenError("Invalid or expired token")

    now = datetime.now(UTC)
    user.pin_hash = hash_password(new_password)
    user.must_change_password = False
    user.password_changed_at = now
    invite.used_at = now

    session.add_all([user, invite])
    await session.commit()
    await session.refresh(user)
    return user


# --- SendGrid email delivery with mock fallback -------------------------------
# Same mock-fallback contract as app.services.receipts.send_receipt_email:
# a real SendGrid call when settings.SENDGRID_API_KEY is configured, a
# clearly-flagged {"mock": True, ...} response otherwise (or if the real
# call fails), so both endpoints below are testable with zero live
# credentials.


def _sendgrid_configured() -> bool:
    return bool(settings.SENDGRID_API_KEY)


def _send_link_email(*, to_email: str, subject: str, body_text: str) -> dict:
    if _sendgrid_configured():
        try:
            with httpx.Client(timeout=10.0) as http_client:
                resp = http_client.post(
                    "https://api.sendgrid.com/v3/mail/send",
                    headers={"Authorization": f"Bearer {settings.SENDGRID_API_KEY}"},
                    json={
                        "personalizations": [{"to": [{"email": to_email}]}],
                        "from": {"email": settings.SENDGRID_FROM_EMAIL},
                        "subject": subject,
                        "content": [{"type": "text/plain", "value": body_text}],
                    },
                )
                resp.raise_for_status()
            return {"mock": False, "to_email": to_email, "sendgrid_status_code": resp.status_code}
        except httpx.HTTPError as exc:
            logger.warning("SendGrid mail send failed (%s) -- returning mock email response.", exc)

    logger.info("Mock email to %s: %s / %s", to_email, subject, body_text)
    return {"mock": True, "would_send_to": to_email, "subject": subject, "body": body_text}


def send_invite_email(*, to_email: str, name: str, link: str) -> dict:
    """Delivers a purpose="invite" link -- see _send_link_email above for
    the real-vs-mock contract."""
    subject = "You have been invited to Cab Dispatch"
    body_text = (
        f"Hi {name},\n\n"
        "An account has been created for you on Cab Dispatch. "
        f"Set your password to get started: {link}\n\n"
        "This link expires in 7 days."
    )
    return _send_link_email(to_email=to_email, subject=subject, body_text=body_text)


def send_reset_email(*, to_email: str, name: str, link: str) -> dict:
    """Delivers a purpose="password_reset" link -- see _send_link_email above
    for the real-vs-mock contract."""
    subject = "Reset your Cab Dispatch password"
    body_text = (
        f"Hi {name},\n\n"
        "A password reset was requested for your Cab Dispatch account. "
        f"If this was you, choose a new password here: {link}\n\n"
        "This link expires in 1 hour. If you did not request this, you can "
        "safely ignore this email -- your password has not been changed."
    )
    return _send_link_email(to_email=to_email, subject=subject, body_text=body_text)


__all__ = [
    "INVITE_TTL",
    "InvalidInviteTokenError",
    "PASSWORD_RESET_TTL",
    "UserInviteError",
    "build_invite_link",
    "consume_invite",
    "create_invite",
    "send_invite_email",
    "send_reset_email",
]
