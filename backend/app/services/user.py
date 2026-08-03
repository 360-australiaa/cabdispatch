"""Business logic for the users domain (staff + driver onboarding/CRUD)."""
from __future__ import annotations

import secrets
import string

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user import User

# Driver-code alphabet excludes visually-ambiguous characters (0/O, 1/I) —
# same reasoning as app/services/fleet.py's _PAIRING_CODE_ALPHABET, since a
# driver keys this in by hand on a meter/kiosk (see POST /v1/auth/driver-login
# in app/api/v1/auth.py).
_DRIVER_CODE_ALPHABET = "".join(c for c in string.ascii_uppercase + string.digits if c not in "01OI")
DRIVER_CODE_LENGTH = 5
_DRIVER_CODE_GENERATION_ATTEMPTS = 20


class UserError(Exception):
    pass


class UserNotFoundError(UserError):
    pass


class DuplicateEmailError(UserError):
    pass


class DuplicateDriverCodeError(UserError):
    pass


class DriverCodeGenerationError(UserError):
    """Raised if a unique driver_code couldn't be found after several random
    attempts — practically unreachable at this alphabet/length (30^5 ≈ 24M
    combinations) short of a near-exhausted codespace."""


async def assert_email_available(
    session: AsyncSession, *, email: str, exclude_user_id: str | None = None
) -> None:
    """Email is globally unique across the whole platform (User.email has a
    unique constraint spanning all tenants), so this check is deliberately NOT
    tenant-scoped."""
    stmt = select(func.count()).select_from(User).where(User.email == email)
    if exclude_user_id is not None:
        stmt = stmt.where(User.id != exclude_user_id)
    count = (await session.execute(stmt)).scalar_one()
    if count > 0:
        raise DuplicateEmailError(email)


async def get_user_or_404(session: AsyncSession, *, tenant_id: str, user_id: str) -> User:
    result = await session.execute(
        select(User).where(User.id == user_id, User.tenant_id == tenant_id)
    )
    user = result.scalar_one_or_none()
    if user is None:
        raise UserNotFoundError(user_id)
    return user


async def assert_driver_code_available(
    session: AsyncSession, *, driver_code: str, exclude_user_id: str | None = None
) -> None:
    """driver_code is globally unique (like User.email — see
    assert_email_available above), NOT tenant-scoped: POST
    /v1/auth/driver-login has no tenant context to scope a lookup by, so two
    drivers in different tenants sharing a code would make login ambiguous."""
    stmt = select(func.count()).select_from(User).where(User.driver_code == driver_code)
    if exclude_user_id is not None:
        stmt = stmt.where(User.id != exclude_user_id)
    count = (await session.execute(stmt)).scalar_one()
    if count > 0:
        raise DuplicateDriverCodeError(driver_code)


async def generate_unique_driver_code(session: AsyncSession) -> str:
    """Mints a random driver_code and confirms it's globally unused. Used by
    POST /v1/users when creating a role="driver" user without an explicit
    driver_code (see app/api/v1/users.py)."""
    for _ in range(_DRIVER_CODE_GENERATION_ATTEMPTS):
        code = "".join(secrets.choice(_DRIVER_CODE_ALPHABET) for _ in range(DRIVER_CODE_LENGTH))
        count = (
            await session.execute(
                select(func.count()).select_from(User).where(User.driver_code == code)
            )
        ).scalar_one()
        if count == 0:
            return code
    raise DriverCodeGenerationError("Could not generate a unique driver_code")
