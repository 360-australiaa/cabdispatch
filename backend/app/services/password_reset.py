"""D10 (security settings): password-reset-by-email delivery.

Same SendGrid mock-fallback contract as `app.services.receipts.send_receipt_email`
— check `Settings` for a real `SENDGRID_API_KEY`, make a real `httpx` call if
configured, and return a clearly-flagged `{"mock": True, ...}` response
otherwise, so this is testable (and runs in dev/CI) with zero real
credentials. Copied as its own small function rather than reusing
`send_receipt_email` because that one is receipt-PDF-attachment-shaped
(takes a `Trip`) and this is a plain-text link email with no attachment and
no `Trip` in scope yet at the point it's sent.

NEVER logs the raw reset link/token — only whether the send was real or
mock, exactly like the receipt-email path.
"""
from __future__ import annotations

import logging

import httpx

from app.core.config import settings

logger = logging.getLogger(__name__)


def _sendgrid_configured() -> bool:
    return bool(settings.SENDGRID_API_KEY)


def send_password_reset_email(*, to_email: str, reset_url: str) -> dict:
    """Sends (or mocks) the password-reset email. Returns a dict describing
    what happened — `{"mock": bool, ...}` — for logging/testing purposes
    only; the HTTP response to the CLIENT never reflects this (see
    `POST /v1/auth/password/reset/request`'s account-existence-safe
    contract in app/api/v1/auth.py — that endpoint returns the same 202
    whether or not this function is ever called)."""
    body = (
        "A password reset was requested for your Cab Dispatch account. "
        f"If this was you, use this link within 30 minutes: {reset_url}\n\n"
        "If you didn't request this, you can ignore this email."
    )

    if _sendgrid_configured():
        try:
            with httpx.Client(timeout=10.0) as http_client:
                resp = http_client.post(
                    "https://api.sendgrid.com/v3/mail/send",
                    headers={"Authorization": f"Bearer {settings.SENDGRID_API_KEY}"},
                    json={
                        "personalizations": [{"to": [{"email": to_email}]}],
                        "from": {"email": settings.SENDGRID_FROM_EMAIL},
                        "subject": "Reset your Cab Dispatch password",
                        "content": [{"type": "text/plain", "value": body}],
                    },
                )
                resp.raise_for_status()
            return {"mock": False, "to_email": to_email, "sendgrid_status_code": resp.status_code}
        except httpx.HTTPError as exc:
            logger.warning("SendGrid password-reset send failed (%s) — falling back to mock.", exc)

    logger.info("Mock password-reset email queued for %s.", to_email)
    return {"mock": True, "would_send_to": to_email}


__all__ = ["send_password_reset_email"]
