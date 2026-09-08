"""Tests for the app-releases domain (Android OTA self-update publishing)."""
from __future__ import annotations

import hashlib

import pytest
from sqlalchemy import select

from app.core.security import PLATFORM_TENANT_ID
from app.models.tenant import Tenant
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


async def _platform_owner_headers(client, session):
    """Same pattern as tests/test_platform.py's own helper of the same
    name -- duplicated here rather than cross-imported, matching this
    codebase's existing per-test-file-owns-its-helpers convention."""
    result = await session.execute(select(Tenant).where(Tenant.id == PLATFORM_TENANT_ID))
    platform_tenant = result.scalar_one_or_none()
    if platform_tenant is None:
        platform_tenant = Tenant(id=PLATFORM_TENANT_ID, name="TCT", plan="platform")
        session.add(platform_tenant)
        await session.commit()

    return await auth_headers(client, session, role="owner", tenant_id=PLATFORM_TENANT_ID)


async def _publish_release(client, headers, *, version_code, version_name="1.0.0", content=b"fake-apk-bytes"):
    return await client.post(
        "/v1/platform/app-releases",
        data={"version_code": str(version_code), "version_name": version_name},
        files={"file": ("app-release.apk", content, "application/vnd.android.package-archive")},
        headers=headers,
    )


async def test_publish_app_release_requires_platform_owner(client, session):
    """A normal tenant owner (role == owner but NOT the platform tenant) is
    refused, same 403 gate as every other /v1/platform/... route."""
    headers = await auth_headers(client, session, role="owner")
    resp = await _publish_release(client, headers, version_code=101)
    assert resp.status_code == 403


async def test_publish_app_release_requires_owner_role(client, session):
    """A platform-tenant admin (not role == owner) is also refused."""
    result = await session.execute(select(Tenant).where(Tenant.id == PLATFORM_TENANT_ID))
    if result.scalar_one_or_none() is None:
        session.add(Tenant(id=PLATFORM_TENANT_ID, name="TCT", plan="platform"))
        await session.commit()
    headers = await auth_headers(client, session, role="admin", tenant_id=PLATFORM_TENANT_ID)

    resp = await _publish_release(client, headers, version_code=102)
    assert resp.status_code == 403


async def test_platform_owner_can_publish_a_release_with_correct_sha256(client, session):
    headers = await _platform_owner_headers(client, session)
    content = b"a-real-apk-would-go-here"

    resp = await _publish_release(
        client, headers, version_code=200, version_name="2.0.0", content=content
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["version_code"] == 200
    assert body["version_name"] == "2.0.0"
    assert body["is_active"] is True
    assert body["sha256"] == hashlib.sha256(content).hexdigest()


async def test_publishing_a_duplicate_version_code_is_rejected(client, session):
    headers = await _platform_owner_headers(client, session)
    resp = await _publish_release(client, headers, version_code=210)
    assert resp.status_code == 201

    resp = await _publish_release(client, headers, version_code=210)
    assert resp.status_code == 409


async def test_latest_returns_highest_active_version_code(client, session):
    headers = await _platform_owner_headers(client, session)
    await _publish_release(client, headers, version_code=300, version_name="3.0.0")
    await _publish_release(client, headers, version_code=310, version_name="3.1.0")
    await _publish_release(client, headers, version_code=305, version_name="3.0.5")

    device_headers = await auth_headers(client, session, role="driver")
    resp = await client.get("/v1/app-releases/latest", headers=device_headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["version_code"] == 310
    assert body["version_name"] == "3.1.0"
    assert body["download_url"].endswith("/download")
    assert len(body["sha256"]) == 64


async def test_unpublished_release_is_excluded_from_latest(client, session):
    headers = await _platform_owner_headers(client, session)
    await _publish_release(client, headers, version_code=400, version_name="4.0.0")
    resp = await _publish_release(client, headers, version_code=410, version_name="4.1.0")
    newer_release_id = resp.json()["id"]

    # Unpublish the newer (higher version_code) build -- the older, still-active
    # 400 should now win as "latest".
    resp = await client.patch(
        f"/v1/platform/app-releases/{newer_release_id}",
        json={"is_active": False},
        headers=headers,
    )
    assert resp.status_code == 200
    assert resp.json()["is_active"] is False

    resp = await client.get(
        "/v1/app-releases/latest", headers=await auth_headers(client, session, role="driver")
    )
    assert resp.status_code == 200
    assert resp.json()["version_code"] == 400


async def test_download_requires_authentication(client, session):
    headers = await _platform_owner_headers(client, session)
    resp = await _publish_release(client, headers, version_code=500, version_name="5.0.0")
    release_id = resp.json()["id"]

    resp = await client.get(f"/v1/app-releases/{release_id}/download")
    assert resp.status_code in (401, 403)


async def test_download_streams_the_uploaded_apk_bytes(client, session):
    headers = await _platform_owner_headers(client, session)
    content = b"binary-apk-payload-xyz"
    resp = await _publish_release(client, headers, version_code=510, version_name="5.1.0", content=content)
    release_id = resp.json()["id"]

    device_headers = await auth_headers(client, session, role="driver")
    resp = await client.get(f"/v1/app-releases/{release_id}/download", headers=device_headers)
    assert resp.status_code == 200
    assert resp.content == content


async def test_list_app_releases_requires_platform_owner(client, session):
    headers = await auth_headers(client, session, role="owner")
    resp = await client.get("/v1/platform/app-releases", headers=headers)
    assert resp.status_code == 403


async def test_list_app_releases_newest_version_code_first(client, session):
    headers = await _platform_owner_headers(client, session)
    await _publish_release(client, headers, version_code=600, version_name="6.0.0")
    await _publish_release(client, headers, version_code=601, version_name="6.0.1")

    resp = await client.get("/v1/platform/app-releases", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    version_codes = [item["version_code"] for item in body["items"]]
    # Newest first, and both of this test's own releases are present -- other
    # tests in this module publish their own version_codes into the same
    # shared session db, so this only asserts relative order/membership, not
    # an exact total.
    assert version_codes.index(601) < version_codes.index(600)
    assert body["total"] >= 2


async def test_list_app_releases_reflects_unpublish(client, session):
    headers = await _platform_owner_headers(client, session)
    resp = await _publish_release(client, headers, version_code=610, version_name="6.1.0")
    release_id = resp.json()["id"]

    await client.patch(
        f"/v1/platform/app-releases/{release_id}", json={"is_active": False}, headers=headers
    )

    resp = await client.get("/v1/platform/app-releases", headers=headers)
    body = resp.json()
    listed = next(item for item in body["items"] if item["id"] == release_id)
    assert listed["is_active"] is False


