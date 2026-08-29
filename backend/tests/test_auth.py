"""HTTP-level tests for app/api/v1/auth.py's password-lifecycle endpoints
(plan Part 4, Phase 1, WP-10/11/12): accept-invite, forgot-password,
reset-password, change-password, and the must_change_password login gate.

Existing auth surface (login/driver-login/mfa/refresh/logout) already has
coverage in tests/test_auth_mfa.py and tests/test_health_and_auth_smoke.py --
this file only adds what's new.
"""
from __future__ import annotations

import uuid

import pytest
from httpx import AsyncClient

from app.core import security
from app.models.user import User
from app.models.user_invite import INVITE_PURPOSE_INVITE, INVITE_PURPOSE_PASSWORD_RESET
from app.services.user_invites import create_invite

pytestmark = pytest.mark.asyncio


async def _make_tenant(session):
    from app.models import Tenant

    tenant = Tenant(name=f"Auth Test Tenant {uuid.uuid4()}", plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    return tenant


async def _make_user(
    session,
    *,
    tenant_id: str,
    password: str | None = None,
    must_change_password: bool = False,
    role: str = "dispatcher",
) -> User:
    user = User(
        tenant_id=tenant_id,
        role=role,
        name="Auth Test User",
        email=f"{uuid.uuid4()}@example.com",
        pin_hash=security.hash_password(password) if password else None,
        status="active",
        must_change_password=must_change_password,
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)
    return user


# --- accept-invite ------------------------------------------------------


async def test_accept_invite_then_login_then_old_token_rejected(client: AsyncClient, session):
    tenant = await _make_tenant(session)
    user = await _make_user(session, tenant_id=tenant.id, password=None)
    _invite, raw_token, _link = await create_invite(session, user=user, purpose=INVITE_PURPOSE_INVITE)

    resp = await client.post(
        "/v1/auth/accept-invite",
        json={"token": raw_token, "new_password": "BrandNewPassw0rd!"},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert "access_token" in body
    assert body["user"]["email"] == user.email

    login_resp = await client.post(
        "/v1/auth/login",
        json={"email": user.email, "password": "BrandNewPassw0rd!"},
    )
    assert login_resp.status_code == 200
    assert "access_token" in login_resp.json()

    reuse_resp = await client.post(
        "/v1/auth/accept-invite",
        json={"token": raw_token, "new_password": "AnotherPassw0rd!"},
    )
    assert reuse_resp.status_code == 400


# --- forgot-password (account-enumeration prevention) --------------------


async def test_forgot_password_identical_response_for_real_and_fake_email(
    client: AsyncClient, session
):
    tenant = await _make_tenant(session)
    real_user = await _make_user(session, tenant_id=tenant.id, password="ExistingPassw0rd!")
    fake_email = f"{uuid.uuid4()}@does-not-exist.example"

    real_resp = await client.post("/v1/auth/forgot-password", json={"email": real_user.email})
    fake_resp = await client.post("/v1/auth/forgot-password", json={"email": fake_email})

    assert real_resp.status_code == fake_resp.status_code == 202
    assert real_resp.json() == fake_resp.json()
    assert set(real_resp.headers.keys()) == set(fake_resp.headers.keys())


# --- reset-password -------------------------------------------------------


async def test_reset_password_happy_path(client: AsyncClient, session):
    tenant = await _make_tenant(session)
    user = await _make_user(session, tenant_id=tenant.id, password="OldPassw0rd!")
    _invite, raw_token, _link = await create_invite(
        session, user=user, purpose=INVITE_PURPOSE_PASSWORD_RESET
    )

    resp = await client.post(
        "/v1/auth/reset-password",
        json={"token": raw_token, "new_password": "ResetPassw0rd!"},
    )
    assert resp.status_code == 200, resp.text

    old_login = await client.post(
        "/v1/auth/login", json={"email": user.email, "password": "OldPassw0rd!"}
    )
    assert old_login.status_code == 401

    new_login = await client.post(
        "/v1/auth/login", json={"email": user.email, "password": "ResetPassw0rd!"}
    )
    assert new_login.status_code == 200


async def test_reset_password_expired_token_rejected(client: AsyncClient, session):
    from datetime import timedelta

    tenant = await _make_tenant(session)
    user = await _make_user(session, tenant_id=tenant.id, password="OldPassw0rd!")
    _invite, raw_token, _link = await create_invite(
        session,
        user=user,
        purpose=INVITE_PURPOSE_PASSWORD_RESET,
        ttl=timedelta(seconds=-1),
    )

    resp = await client.post(
        "/v1/auth/reset-password",
        json={"token": raw_token, "new_password": "ResetPassw0rd!"},
    )
    assert resp.status_code == 400


async def test_reset_password_token_cannot_be_reused(client: AsyncClient, session):
    tenant = await _make_tenant(session)
    user = await _make_user(session, tenant_id=tenant.id, password="OldPassw0rd!")
    _invite, raw_token, _link = await create_invite(
        session, user=user, purpose=INVITE_PURPOSE_PASSWORD_RESET
    )

    first = await client.post(
        "/v1/auth/reset-password",
        json={"token": raw_token, "new_password": "ResetPassw0rd!"},
    )
    assert first.status_code == 200

    second = await client.post(
        "/v1/auth/reset-password",
        json={"token": raw_token, "new_password": "AnotherPassw0rd!"},
    )
    assert second.status_code == 400


# --- change-password (self-service) ---------------------------------------


async def test_change_password_wrong_current_password_rejected(client: AsyncClient, session):
    tenant = await _make_tenant(session)
    user = await _make_user(session, tenant_id=tenant.id, password="CorrectPassw0rd!")
    token = security.create_access_token(user_id=user.id, tenant_id=tenant.id, role=user.role)

    resp = await client.post(
        "/v1/auth/change-password",
        json={"current_password": "WrongPassw0rd!", "new_password": "NewPassw0rd!"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 401


async def test_change_password_success_clears_must_change_password(client: AsyncClient, session):
    tenant = await _make_tenant(session)
    user = await _make_user(
        session, tenant_id=tenant.id, password="CorrectPassw0rd!", must_change_password=True
    )
    token = security.create_access_token(user_id=user.id, tenant_id=tenant.id, role=user.role)

    resp = await client.post(
        "/v1/auth/change-password",
        json={"current_password": "CorrectPassw0rd!", "new_password": "NewPassw0rd!"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200, resp.text
    assert "access_token" in resp.json()

    login_resp = await client.post(
        "/v1/auth/login", json={"email": user.email, "password": "NewPassw0rd!"}
    )
    assert login_resp.status_code == 200
    assert "access_token" in login_resp.json()


# --- must_change_password login gate ---------------------------------------


async def test_must_change_password_blocks_normal_login(client: AsyncClient, session):
    tenant = await _make_tenant(session)
    user = await _make_user(
        session, tenant_id=tenant.id, password="TempPassw0rd!", must_change_password=True
    )

    resp = await client.post(
        "/v1/auth/login", json={"email": user.email, "password": "TempPassw0rd!"}
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body.get("password_change_required") is True
    assert "password_change_token" in body
    assert "access_token" not in body


async def test_password_change_token_rejected_by_regular_endpoints(client: AsyncClient, session):
    tenant = await _make_tenant(session)
    user = await _make_user(
        session, tenant_id=tenant.id, password="TempPassw0rd!", must_change_password=True
    )

    login_resp = await client.post(
        "/v1/auth/login", json={"email": user.email, "password": "TempPassw0rd!"}
    )
    password_change_token = login_resp.json()["password_change_token"]

    me_resp = await client.get(
        "/v1/auth/me", headers={"Authorization": f"Bearer {password_change_token}"}
    )
    assert me_resp.status_code == 401


async def test_password_change_token_works_only_for_change_password_and_only_once(
    client: AsyncClient, session
):
    tenant = await _make_tenant(session)
    user = await _make_user(
        session, tenant_id=tenant.id, password="TempPassw0rd!", must_change_password=True
    )

    login_resp = await client.post(
        "/v1/auth/login", json={"email": user.email, "password": "TempPassw0rd!"}
    )
    password_change_token = login_resp.json()["password_change_token"]
    headers = {"Authorization": f"Bearer {password_change_token}"}

    change_resp = await client.post(
        "/v1/auth/change-password",
        json={"current_password": "TempPassw0rd!", "new_password": "PermanentPassw0rd!"},
        headers=headers,
    )
    assert change_resp.status_code == 200, change_resp.text
    assert "access_token" in change_resp.json()

    second_change_resp = await client.post(
        "/v1/auth/change-password",
        json={"current_password": "PermanentPassw0rd!", "new_password": "AnotherOne1!"},
        headers=headers,
    )
    assert second_change_resp.status_code == 401

    normal_login = await client.post(
        "/v1/auth/login", json={"email": user.email, "password": "PermanentPassw0rd!"}
    )
    assert normal_login.status_code == 200
    assert "access_token" in normal_login.json()
