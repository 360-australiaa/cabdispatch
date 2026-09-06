"""Pydantic v2 schemas for the app-releases domain (Android OTA self-update).

See `app/models/app_release.py` for the platform-wide (not tenant-scoped)
rationale, and `app/api/v1/app_releases.py` for the endpoints these back.
"""
from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class AppReleaseCreate(BaseModel):
    """Form fields accompanying the multipart APK upload on
    `POST /v1/platform/app-releases`. The APK file itself arrives as a
    separate `UploadFile` (see the router), not part of this model."""

    version_code: int = Field(gt=0)
    version_name: str = Field(min_length=1, max_length=30)
    release_notes: str | None = Field(default=None, max_length=10_000)


class AppReleaseRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    version_code: int
    version_name: str
    release_notes: str | None
    is_active: bool
    sha256: str
    created_at: datetime
    updated_at: datetime


class AppReleaseUpdate(BaseModel):
    """Partial update — currently just the unpublish/republish toggle."""

    is_active: bool


class LatestAppReleaseRead(BaseModel):
    """Response for `GET /v1/app-releases/latest` — deliberately narrower
    than `AppReleaseRead`: this is what the Android client polls, so it
    carries only what `domain/AppUpdateChecker.kt` needs to decide "is this
    newer than BuildConfig.VERSION_CODE" and then download+verify it."""

    version_code: int
    version_name: str
    release_notes: str | None
    download_url: str
    sha256: str
