"""Business logic for the users domain (staff + driver onboarding/CRUD)."""
from __future__ import annotations

import os
import secrets
import string
import uuid
from pathlib import Path

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user import User

# Backend root: app/services/user.py -> parents[0]=services, [1]=app,
# [2]=backend project root. Uploads live at "<backend root>/uploads/...".
# Mirrors app.services.compliance's BACKEND_ROOT/UPLOADS_ROOT exactly
# (re-derived here rather than imported, matching this codebase's existing
# per-domain-duplication convention for small storage/alphabet constants —
# see e.g. app.services.fleet._PAIRING_CODE_ALPHABET vs this module's own
# _DRIVER_CODE_ALPHABET below).
BACKEND_ROOT = Path(__file__).resolve().parents[2]
UPLOADS_ROOT = BACKEND_ROOT / "uploads"

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


class InvalidPhotoUploadError(UserError):
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


# --- photo storage (mirrors app.services.compliance's local-disk convention,
# see that module for the reference implementation this was copied from) ----


def _safe_component(value: str, *, max_len: int = 80) -> str:
    """Strips anything that could act as a path separator/traversal token
    from a value about to become part of an on-disk path — identical to
    app.services.compliance._safe_component."""
    cleaned = "".join(c for c in value if c.isalnum() or c in "-_.")
    cleaned = cleaned.strip(".") or "unknown"
    return cleaned[:max_len]


def user_photo_dir(*, tenant_id: str | None, user_id: str) -> Path:
    # tenant_id is nullable on User (platform-tenant staff — see
    # app.models.user's module docstring); "platform" is a stable fallback
    # component for that case, never used for any real tenant_id value since
    # _safe_component would already have accepted the real one.
    return UPLOADS_ROOT / _safe_component(tenant_id or "platform") / "users" / _safe_component(user_id)


async def save_user_photo(
    *,
    tenant_id: str | None,
    user_id: str,
    original_filename: str,
    content: bytes,
) -> str:
    """Writes `content` under `uploads/{tenant_id}/users/{user_id}/`, creating
    the directory tree if missing, and returns the *relative* (to
    `BACKEND_ROOT`) path to persist on `User.photo_url` — never the absolute
    path, so this stays portable across machines/deployments, same as
    app.services.compliance.save_upload.
    """
    if not content:
        raise InvalidPhotoUploadError("Uploaded file is empty")

    target_dir = user_photo_dir(tenant_id=tenant_id, user_id=user_id)
    target_dir.mkdir(parents=True, exist_ok=True)

    safe_name = _safe_component(os.path.basename(original_filename), max_len=200) or "upload"
    stored_name = f"{uuid.uuid4().hex}_{safe_name}"
    absolute_path = target_dir / stored_name
    absolute_path.write_bytes(content)

    return absolute_path.relative_to(BACKEND_ROOT).as_posix()


def resolve_photo_path(file_path: str) -> Path:
    """Resolves a stored (relative) `User.photo_url` back to an absolute path
    for streaming. Rejects anything that would escape `BACKEND_ROOT` — same
    guard as app.services.compliance.resolve_absolute_path."""
    absolute_path = (BACKEND_ROOT / file_path).resolve()
    if BACKEND_ROOT not in absolute_path.parents and absolute_path != BACKEND_ROOT:
        raise InvalidPhotoUploadError("Stored photo_url resolves outside the uploads root")
    return absolute_path


def delete_photo_best_effort(file_path: str) -> None:
    """Best-effort on-disk delete — a missing file must not block anything.
    Not currently wired to any endpoint (no photo-delete endpoint exists in
    this pass), kept for symmetry with app.services.compliance and for a
    future DELETE /v1/users/{id}/photo to use."""
    try:
        absolute_path = resolve_photo_path(file_path)
        absolute_path.unlink(missing_ok=True)
    except (InvalidPhotoUploadError, OSError):
        pass

