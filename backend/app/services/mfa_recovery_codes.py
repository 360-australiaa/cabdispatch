"""Generation, storage, and single-use consumption of MFA backup/recovery
codes (plan Part 4 Phase 1 WP-16, docs/ARCHITECTURE_TENANCY_FLEET_COMPLIANCE.md
I-5).

Token storage: same hashing approach as app.services.user_invites -- a plain
hashlib.sha256 hex digest of the raw code (uppercased/stripped for a
forgiving compare), never bcrypt. See that module docstring for the full
reasoning; the short version is that bcrypt buys nothing once brute-forcing
the raw value offline is already the actual threat model, and a fast hash is
sufficient since these codes are consumed through the same rate-limited,
already-authenticated-first-factor POST /v1/auth/mfa/login endpoint (not an
open guessing surface on its own).

Called from exactly two places in app/api/v1/auth.py:
  - generate_recovery_codes: POST /v1/auth/mfa/verify, the moment
    mfa_enabled actually flips True. ANY existing recovery codes for the
    user are replaced (see the function own docstring for why re-verifying
    should not silently accumulate stale sets).
  - consume_recovery_code: POST /v1/auth/mfa/login, tried as a fallback when
    the supplied code fails verify_totp_code -- lets a user who has lost
    their authenticator app still get in, single-use per code.
"""
from __future__ import annotations

import hashlib
import secrets
from datetime import UTC, datetime

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.mfa_recovery_code import MfaRecoveryCode
from app.models.user import User

RECOVERY_CODE_COUNT = 10
RECOVERY_CODE_NBYTES = 5


def _hash_code(raw_code: str) -> str:
    normalized = raw_code.strip().upper().replace("-", "")
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def _generate_raw_code() -> str:
    raw = secrets.token_hex(RECOVERY_CODE_NBYTES).upper()
    return f"{raw[:5]}-{raw[5:]}"


async def generate_recovery_codes(session: AsyncSession, *, user: User) -> list[str]:
    """Replaces ANY existing recovery codes for this user with a fresh set of
    RECOVERY_CODE_COUNT one-time codes and returns the raw (plaintext)
    values -- the ONLY time they exist outside a hash, in the HTTP response
    for this single call. Only the setup->verify flow calls this (see module
    docstring), so re-verifying after a fresh POST /mfa/setup always leaves
    the user with exactly one valid, known set instead of silently
    accumulating old ones a user could no longer see."""
    await session.execute(delete(MfaRecoveryCode).where(MfaRecoveryCode.user_id == user.id))

    raw_codes: list[str] = []
    for _ in range(RECOVERY_CODE_COUNT):
        raw = _generate_raw_code()
        raw_codes.append(raw)
        session.add(MfaRecoveryCode(user_id=user.id, code_hash=_hash_code(raw)))

    await session.commit()
    return raw_codes


async def consume_recovery_code(session: AsyncSession, *, user_id: str, code: str) -> bool:
    """Looks up code by hash scoped to this user_id; if found and unused,
    marks it used_at (single-use) and returns True. Returns False for every
    failure mode -- no such code, code belongs to a different user, already
    used -- deliberately without distinguishing which, same
    do-not-leak-which-check-failed precedent as
    app.services.user_invites.InvalidInviteTokenError."""
    code_hash = _hash_code(code)
    result = await session.execute(
        select(MfaRecoveryCode).where(
            MfaRecoveryCode.user_id == user_id,
            MfaRecoveryCode.code_hash == code_hash,
        )
    )
    recovery_code = result.scalar_one_or_none()
    if recovery_code is None or recovery_code.used_at is not None:
        return False

    recovery_code.used_at = datetime.now(UTC)
    session.add(recovery_code)
    await session.commit()
    return True


__all__ = [
    "RECOVERY_CODE_COUNT",
    "consume_recovery_code",
    "generate_recovery_codes",
]
