"""Tests for the users domain (staff + driver CRUD) — added post-integration
to close the gap where no domain slice owned "create a driver via the API"."""
from __future__ import annotations

import uuid

import pytest

from app.core import security
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


def _unique_email(prefix: str = "driver") -> str:
    # Email is globally unique across tenants (see app/models/user.py), and
    # this suite shares one DB across every test file in the session, so a
    # fixed literal like "dup@example.com" can collide with an unrelated
    # sibling domain's own test fixture (this happened against
    # test_psl_ledger.py) — always mint a fresh email instead.
    return f"{prefix}-{uuid.uuid4()}@example.com"


async def _create_driver(client, headers, **overrides):
    payload = {
        "name": overrides.pop("name", "Dev Driver"),
        "email": overrides.pop("email", _unique_email()),
        "password": overrides.pop("password", "Driver-Pass1!"),
        "role": overrides.pop("role", "driver"),
        **overrides,
    }
    return await client.post("/v1/users", json=payload, headers=headers)


async def test_create_and_get_driver(client, session):
    headers = await auth_headers(client, session, role="admin")
    email = _unique_email()

    resp = await _create_driver(client, headers, email=email)
    assert resp.status_code == 201
    body = resp.json()
    assert body["role"] == "driver"
    assert body["email"] == email
    assert "password" not in body
    assert "pin_hash" not in body
    user_id = body["id"]

    resp = await client.get(f"/v1/users/{user_id}", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["id"] == user_id


async def test_create_user_requires_admin_role(client, session):
    headers = await auth_headers(client, session, role="driver")

    resp = await _create_driver(client, headers)
    assert resp.status_code == 403


async def test_duplicate_email_rejected(client, session):
    headers = await auth_headers(client, session, role="admin")
    email = _unique_email("dup")

    resp = await _create_driver(client, headers, email=email)
    assert resp.status_code == 201

    resp = await _create_driver(client, headers, email=email, name="Second Driver")
    assert resp.status_code == 409


async def test_list_users_is_tenant_scoped(client, session):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Tenant A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Tenant B")
    email_a, email_b = _unique_email("a-driver"), _unique_email("b-driver")

    await _create_driver(client, headers_a, email=email_a)
    await _create_driver(client, headers_b, email=email_b)

    resp = await client.get("/v1/users", headers=headers_a, params={"role": "driver"})
    assert resp.status_code == 200
    emails = {u["email"] for u in resp.json()["items"]}
    assert email_a in emails
    assert email_b not in emails


async def test_update_user_and_new_password_can_login(client, session):
    headers = await auth_headers(client, session, role="admin")
    email = _unique_email("reset-me")

    resp = await _create_driver(client, headers, email=email, password="Old-Passw0rd!")
    user_id = resp.json()["id"]

    resp = await client.patch(
        f"/v1/users/{user_id}",
        json={"phone": "0400000000", "password": "New-Passw0rd!"},
        headers=headers,
    )
    assert resp.status_code == 200
    assert resp.json()["phone"] == "0400000000"

    resp = await client.post("/v1/auth/login", json={"email": email, "password": "New-Passw0rd!"})
    assert resp.status_code == 200

    resp = await client.post("/v1/auth/login", json={"email": email, "password": "Old-Passw0rd!"})
    assert resp.status_code == 401


async def test_delete_user(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await _create_driver(client, headers, email=_unique_email("to-delete"))
    user_id = resp.json()["id"]

    resp = await client.delete(f"/v1/users/{user_id}", headers=headers)
    assert resp.status_code == 204

    resp = await client.get(f"/v1/users/{user_id}", headers=headers)
    assert resp.status_code == 404


async def test_get_nonexistent_user_is_404(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await client.get("/v1/users/does-not-exist", headers=headers)
    assert resp.status_code == 404


# --- photo -------------------------------------------------------------------


async def test_upload_and_get_user_photo(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await _create_driver(client, headers)
    user_id = resp.json()["id"]

    resp = await client.post(
        f"/v1/users/{user_id}/photo",
        files={"file": ("driver.jpg", b"selfie-bytes-fakejpeg", "image/jpeg")},
        headers=headers,
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["photo_url"] is not None
    assert body["photo_url"].startswith("uploads/")

    resp = await client.get(f"/v1/users/{user_id}/photo", headers=headers)
    assert resp.status_code == 200
    assert resp.content == b"selfie-bytes-fakejpeg"


async def test_upload_user_photo_requires_staff_role(client, session):
    headers = await auth_headers(client, session, role="admin")
    driver_headers = await auth_headers(client, session, role="driver", tenant_id=None)
    resp = await _create_driver(client, headers)
    user_id = resp.json()["id"]

    resp = await client.post(
        f"/v1/users/{user_id}/photo",
        files={"file": ("driver.jpg", b"data", "image/jpeg")},
        headers=driver_headers,
    )
    assert resp.status_code == 403


async def test_driver_can_upload_their_own_photo(client, session):
    """Self-or-staff gate (fixed during integration - a driver could not
    upload their own photo at all under the original staff-only gate, which
    would have 403'd against the Android app's actual Profile-screen upload
    flow on every real device). A driver uploading their OWN photo (not
    another user's) must succeed even though driver is not a staff role."""
    driver_headers = await auth_headers(client, session, role="driver")
    token = driver_headers["Authorization"].removeprefix("Bearer ")
    driver_id = security.decode_token(token)["sub"]

    resp = await client.post(
        f"/v1/users/{driver_id}/photo",
        files={"file": ("selfie.jpg", b"selfie-bytes-fakejpeg", "image/jpeg")},
        headers=driver_headers,
    )
    assert resp.status_code == 200
    assert resp.json()["photo_url"] is not None

    resp = await client.get(f"/v1/users/{driver_id}/photo", headers=driver_headers)
    assert resp.status_code == 200
    assert resp.content == b"selfie-bytes-fakejpeg"


async def test_upload_user_photo_rejects_empty_file(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await _create_driver(client, headers)
    user_id = resp.json()["id"]

    resp = await client.post(
        f"/v1/users/{user_id}/photo",
        files={"file": ("empty.jpg", b"", "image/jpeg")},
        headers=headers,
    )
    assert resp.status_code == 400


async def test_get_user_photo_404_when_none_uploaded(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await _create_driver(client, headers)
    user_id = resp.json()["id"]

    resp = await client.get(f"/v1/users/{user_id}/photo", headers=headers)
    assert resp.status_code == 404


async def test_user_photo_is_tenant_isolated(client, session):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Photo Tenant A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Photo Tenant B")
    resp = await _create_driver(client, headers_a)
    user_id = resp.json()["id"]

    resp = await client.post(
        f"/v1/users/{user_id}/photo",
        files={"file": ("driver.jpg", b"data", "image/jpeg")},
        headers=headers_a,
    )
    assert resp.status_code == 200

    resp = await client.get(f"/v1/users/{user_id}/photo", headers=headers_b)
    assert resp.status_code == 404


async def test_upload_user_photo_404_for_unknown_user(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await client.post(
        "/v1/users/does-not-exist/photo",
        files={"file": ("driver.jpg", b"data", "image/jpeg")},
        headers=headers,
    )
    assert resp.status_code == 404


# --- I-1: role-hierarchy privilege escalation -----------------------------------


async def test_admin_cannot_create_an_owner(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await _create_driver(client, headers, role="owner")
    assert resp.status_code == 403


async def test_admin_cannot_create_another_admin(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await _create_driver(client, headers, role="admin")
    assert resp.status_code == 403


async def test_admin_can_create_a_dispatcher(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await _create_driver(client, headers, role="dispatcher")
    assert resp.status_code == 201


async def test_owner_can_create_another_owner(client, session):
    headers = await auth_headers(client, session, role="owner")

    resp = await _create_driver(client, headers, role="owner")
    assert resp.status_code == 201


async def test_admin_cannot_promote_a_user_to_owner_via_patch(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await _create_driver(client, headers, role="dispatcher")
    user_id = resp.json()["id"]

    resp = await client.patch(f"/v1/users/{user_id}", json={"role": "owner"}, headers=headers)
    assert resp.status_code == 403


async def test_owner_can_promote_a_user_to_owner_via_patch(client, session):
    headers = await auth_headers(client, session, role="owner")
    resp = await _create_driver(client, headers, role="dispatcher")
    user_id = resp.json()["id"]

    resp = await client.patch(f"/v1/users/{user_id}", json={"role": "owner"}, headers=headers)
    assert resp.status_code == 200
    assert resp.json()["role"] == "owner"
