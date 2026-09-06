"""`AppRelease`: a published build of the Android meter app, uploaded once by a
platform owner and then polled by every tenant's devices to learn "is a newer
build available".

Platform-wide, NOT tenant-scoped: this is one Android app codebase serving
every tenant on the platform (there is no per-tenant APK build), so unlike
the standard `TenantScopedMixin` domain tables, a row here has no tenant_id
at all — same "genuinely global, not per-tenant" reasoning as
`app.models.tariffs.Tariff`'s nullable-tenant_id global Fares Order reference
row, except here there is no tenant-scoped variant ever created: every row in
this table is a platform release, full stop. Only a platform owner
(`require_platform_owner`, see `app/api/v1/platform.py`) may create one — see
`app/api/v1/app_releases.py`.

Most-recent-wins "current record" style (like `app.models.tariffs.Tariff`),
not an append-only history table (like `app.models.fleet.DeviceVersionHistory`):
rows are never deleted, but `is_active` lets a platform owner "unpublish" a
bad build (excluding it from `GET /v1/app-releases/latest`) without losing
the row or its place in history.
"""
from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TimestampMixin


class AppRelease(Base, TimestampMixin):
    __tablename__ = "app_releases"
    __table_args__ = (UniqueConstraint("version_code", name="uq_app_releases_version_code"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))

    # Android's monotonically-increasing build identifier (BuildConfig.VERSION_CODE
    # on the client) — this, not version_name, is what "latest" is decided by
    # (GET /v1/app-releases/latest picks the highest version_code among
    # is_active rows). Unique: two releases can never claim the same build.
    version_code: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    # Human-readable display string (Android's BuildConfig.VERSION_NAME, e.g.
    # "0.2.0") — informational only, never compared for ordering.
    version_name: Mapped[str] = mapped_column(String(30), nullable=False)

    # Server-local relative storage path, same on-disk-relative-path convention
    # as `User.photo_url` (see app.services.user.save_user_photo/UPLOADS_ROOT)
    # — relative to BACKEND_ROOT, never absolute, so it stays portable across
    # deployments. Lives under uploads/app-releases/, see
    # app.services.app_releases.save_release_apk.
    apk_path: Mapped[str] = mapped_column(String(500), nullable=False)
    release_notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    # Lets a platform owner "unpublish" a bad build (excluded from
    # GET /v1/app-releases/latest) without deleting the row/history.
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    # SHA-256 of the stored APK bytes, computed server-side at upload time
    # (never client-supplied) — the Android client verifies its download
    # against this before ever building an install Intent; see
    # domain/AppUpdateChecker.kt's downloadAndInstall.
    sha256: Mapped[str] = mapped_column(String(64), nullable=False)
