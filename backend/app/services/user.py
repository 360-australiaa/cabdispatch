"""Business logic for the users domain (staff + driver onboarding/CRUD)."""
from __future__ import annotations

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user import User


class UserError(Exception):
    pass


class UserNotFoundError(UserError):
    pass


class DuplicateEmailError(UserError):
    pass


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
