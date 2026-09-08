"""Business logic for the app-releases domain (Android OTA self-update).

Storage convention mirrors `app.services.user`'s photo-upload helpers exactly
(same path-traversal guard, same "store the relative-to-BACKEND_ROOT path"
rule) — see that module's own comment for the reference implementation this
was copied from. The only difference is the on-disk subtree
(`uploads/app-releases/` instead of `uploads/{tenant_id}/users/{user_id}/`),
since releases are platform-wide, not tenant-scoped (see
`app.models.app_release.AppRelease`'s docstring).
"""
from __future__ import annotations

import hashlib
import os
import uuid
from pathlib import Path

from sqlalchemy import desc, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.app_release import AppRelease

# Same BACKEND_ROOT/UPLOADS_ROOT re-derivation convention as
# app.services.user / app.services.compliance — see those modules' own
# comments for why this is duplicated per-domain rather than imported.
BACKEND_ROOT = Path(__file__).resolve().parents[2]
UPLOADS_ROOT = BACKEND_ROOT / "uploads"
RELEASES_ROOT = UPLOADS_ROOT / "app-releases"


class AppReleaseError(Exception):
    pass


class AppReleaseNotFoundError(AppReleaseError):
    pass


class DuplicateVersionCodeError(AppReleaseError):
    pass


class InvalidApkUploadError(AppReleaseError):
    pass


def _safe_component(value: str, *, max_len: int = 80) -> str:
    """Strips anything that could act as a path separator/traversal token —
    identical to app.services.user._safe_component / app.services.compliance's
    own copy of the same helper."""
    cleaned = "".join(c for c in value if c.isalnum() or c in "-_.")
    cleaned = cleaned.strip(".") or "unknown"
    return cleaned[:max_len]


async def assert_version_code_available(session: AsyncSession, *, version_code: int) -> None:
    result = await session.execute(
        select(AppRelease).where(AppRelease.version_code == version_code)
    )
    if result.scalar_one_or_none() is not None:
        raise DuplicateVersionCodeError(version_code)


async def save_release_apk(
    *, version_code: int, original_filename: str, content: bytes
) -> tuple[str, str]:
    """Writes `content` under `uploads/app-releases/`, creating the directory
    if missing, and returns `(relative_path, sha256_hex)` — `relative_path`
    is relative to `BACKEND_ROOT` (never absolute), same portability
    convention as `app.services.user.save_user_photo`. `sha256_hex` is always
    server-computed here, never client-supplied, so the Android client has a
    trustworthy value to verify its download against."""
    if not content:
        raise InvalidApkUploadError("Uploaded APK is empty")

    RELEASES_ROOT.mkdir(parents=True, exist_ok=True)

    safe_name = _safe_component(os.path.basename(original_filename), max_len=200) or "release.apk"
    stored_name = f"{version_code}_{uuid.uuid4().hex}_{safe_name}"
    absolute_path = RELEASES_ROOT / stored_name
    absolute_path.write_bytes(content)

    sha256_hex = hashlib.sha256(content).hexdigest()
    relative_path = absolute_path.relative_to(BACKEND_ROOT).as_posix()
    return relative_path, sha256_hex


def resolve_apk_path(file_path: str) -> Path:
    """Resolves a stored (relative) `AppRelease.apk_path` back to an absolute
    path for streaming. Rejects anything that would escape `BACKEND_ROOT` —
    same guard as app.services.user.resolve_photo_path."""
    absolute_path = (BACKEND_ROOT / file_path).resolve()
    if BACKEND_ROOT not in absolute_path.parents and absolute_path != BACKEND_ROOT:
        raise InvalidApkUploadError("Stored apk_path resolves outside the uploads root")
    return absolute_path


async def create_release(
    session: AsyncSession,
    *,
    version_code: int,
    version_name: str,
    release_notes: str | None,
    apk_path: str,
    sha256: str,
) -> AppRelease:
    await assert_version_code_available(session, version_code=version_code)
    release = AppRelease(
        version_code=version_code,
        version_name=version_name,
        release_notes=release_notes,
        apk_path=apk_path,
        sha256=sha256,
        is_active=True,
    )
    session.add(release)
    await session.commit()
    await session.refresh(release)
    return release


async def get_release_or_404(session: AsyncSession, *, release_id: str) -> AppRelease:
    result = await session.execute(select(AppRelease).where(AppRelease.id == release_id))
    release = result.scalar_one_or_none()
    if release is None:
        raise AppReleaseNotFoundError(release_id)
    return release


async def get_latest_active_release(session: AsyncSession) -> AppRelease | None:
    """The release `GET /v1/app-releases/latest` reports and the heartbeat
    response's `latest_version_code` hint is derived from: the highest
    `version_code` among `is_active=true` rows. `None` if no release has ever
    been published (or every one has been unpublished)."""
    result = await session.execute(
        select(AppRelease)
        .where(AppRelease.is_active.is_(True))
        .order_by(desc(AppRelease.version_code))
        .limit(1)
    )
    return result.scalar_one_or_none()


async def set_active(session: AsyncSession, release: AppRelease, *, is_active: bool) -> AppRelease:
    release.is_active = is_active
    await session.commit()
    await session.refresh(release)
    return release


async def list_releases(
    session: AsyncSession, *, skip: int = 0, limit: int = 20
) -> tuple[list[AppRelease], int]:
    """Every published release, newest `version_code` first — the dashboard's
    Publish Update history table. Platform-wide, so no tenant filter (see
    `AppRelease`'s own docstring); paginated the same `skip`/`limit`/`total`
    shape as `app.services.platform.list_tenants`."""
    total = (await session.execute(select(func.count()).select_from(AppRelease))).scalar_one()
    result = await session.execute(
        select(AppRelease).order_by(desc(AppRelease.version_code)).offset(skip).limit(limit)
    )
    return list(result.scalars().all()), total
