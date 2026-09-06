"""App-releases domain router: platform-owner APK publishing plus the
device-facing version-check/download endpoints that back the Android meter
app's OTA self-update flow.

Two routers, distinct path prefixes — same convention as the tariffs/payments
domains (see app/main.py's own module docstring):

- `platform_router` (`/v1/platform/app-releases`) — platform-owner only
  (`require_platform_owner`, same gate as every other `/v1/platform/...`
  route — see app/api/v1/platform.py). Publishes a new release.
- `router` (`/v1/app-releases`) — any authenticated tenant/device user.
  `GET /latest` is what `domain/AppUpdateChecker.kt` polls; `GET /{id}/download`
  streams the APK. Neither route is tenant-scoped (releases are
  platform-wide — see app.models.app_release.AppRelease's docstring), but
  both still require a valid bearer token: this is deliberately NOT a public
  unauthenticated download.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status
from fastapi.responses import FileResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.v1.platform import require_platform_owner
from app.core.database import get_session
from app.core.security import get_current_user
from app.schemas.app_releases import AppReleaseRead, AppReleaseUpdate, LatestAppReleaseRead
from app.services import app_releases as app_releases_service

platform_router = APIRouter(prefix="/v1/platform/app-releases", tags=["app-releases"])
router = APIRouter(prefix="/v1/app-releases", tags=["app-releases"])


def _release_error_to_http(exc: app_releases_service.AppReleaseError) -> HTTPException:
    if isinstance(exc, app_releases_service.AppReleaseNotFoundError):
        return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Release not found")
    if isinstance(exc, app_releases_service.DuplicateVersionCodeError):
        return HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"version_code {exc} is already published",
        )
    if isinstance(exc, app_releases_service.InvalidApkUploadError):
        return HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc))
    return HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))


def _download_url_for(release_id: str) -> str:
    return f"/v1/app-releases/{release_id}/download"


@platform_router.post("", response_model=AppReleaseRead, status_code=status.HTTP_201_CREATED)
async def publish_app_release(
    file: UploadFile = File(...),
    version_code: int = Form(...),
    version_name: str = Form(...),
    release_notes: str | None = Form(default=None),
    session: AsyncSession = Depends(get_session),
    _owner=Depends(require_platform_owner),
):
    """Platform-owner-only multipart upload: APK file + version_code +
    version_name + optional release_notes. Stores the file under
    uploads/app-releases/ (see app.services.app_releases.save_release_apk)
    and computes+stores its sha256 server-side — the Android client verifies
    its download against this value before ever building an install Intent.

    Rejects a duplicate version_code with 409 rather than silently
    overwriting a prior release for the same build.
    """
    content = await file.read()
    try:
        await app_releases_service.assert_version_code_available(session, version_code=version_code)
        apk_path, sha256 = await app_releases_service.save_release_apk(
            version_code=version_code,
            original_filename=file.filename or "release.apk",
            content=content,
        )
        release = await app_releases_service.create_release(
            session,
            version_code=version_code,
            version_name=version_name,
            release_notes=release_notes,
            apk_path=apk_path,
            sha256=sha256,
        )
    except app_releases_service.AppReleaseError as exc:
        raise _release_error_to_http(exc) from exc
    return release


@platform_router.patch("/{release_id}", response_model=AppReleaseRead)
async def update_app_release(
    release_id: str,
    payload: AppReleaseUpdate,
    session: AsyncSession = Depends(get_session),
    _owner=Depends(require_platform_owner),
):
    """Platform-owner-only. Toggles `is_active` -- lets a bad build be
    "unpublished" (excluded from `GET /v1/app-releases/latest`) without
    deleting the row or its place in history. A device mid-download of a
    just-unpublished release can still finish it (see
    `download_app_release`'s own docstring)."""
    try:
        release = await app_releases_service.get_release_or_404(session, release_id=release_id)
    except app_releases_service.AppReleaseError as exc:
        raise _release_error_to_http(exc) from exc

    return await app_releases_service.set_active(session, release, is_active=payload.is_active)


@router.get("/latest", response_model=LatestAppReleaseRead)
async def get_latest_app_release(
    session: AsyncSession = Depends(get_session),
    _user=Depends(get_current_user),
):
    """Polled by `domain/AppUpdateChecker.kt`'s `checkForUpdate()` — any
    authenticated tenant/device user, not platform-owner-gated (this is a
    read, not a publish). Returns the highest `version_code` among
    `is_active=true` releases; 404 if none has ever been published (or every
    one has been unpublished)."""
    release = await app_releases_service.get_latest_active_release(session)
    if release is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No app release published yet")

    return LatestAppReleaseRead(
        version_code=release.version_code,
        version_name=release.version_name,
        release_notes=release.release_notes,
        download_url=_download_url_for(release.id),
        sha256=release.sha256,
    )


@router.get("/{release_id}/download")
async def download_app_release(
    release_id: str,
    session: AsyncSession = Depends(get_session),
    _user=Depends(get_current_user),
):
    """Streams the APK file for one release. Authenticated (any valid tenant/
    device bearer token) — deliberately not a public unauthenticated
    download, same convention as `GET /v1/users/{id}/photo`. Does not require
    `is_active` — a device mid-download of a release an admin unpublished a
    moment later should still be able to finish it; new download URLs to it
    simply stop being handed out by GET /latest."""
    try:
        release = await app_releases_service.get_release_or_404(session, release_id=release_id)
        absolute_path = app_releases_service.resolve_apk_path(release.apk_path)
    except app_releases_service.AppReleaseError as exc:
        raise _release_error_to_http(exc) from exc

    if not absolute_path.is_file():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Release row exists but its APK file is missing on disk",
        )

    return FileResponse(
        path=absolute_path,
        media_type="application/vnd.android.package-archive",
        filename=f"cabdispatch-{release.version_name}.apk",
    )


__all__ = ["platform_router", "router"]
