"""Tests for I-3/I-4/I-5 fixes (plan Part 4 Phase 1 WP-14/15/16,
docs/ARCHITECTURE_TENANCY_FLEET_COMPLIANCE.md):

- WP-14 (I-3): the get_current_tenant_id ?tenant_id= override for
  platform-owner tokens now validates against a real Tenant row (404
  otherwise) and audit-logs every USE of the override with a different
  tenant_id than the token own.
- WP-15 (I-4): refresh-token rotation revokes the OLD refresh jti before
  issuing a new one; POST /v1/auth/logout actually revokes the caller
  access-token jti (and refresh jti, if supplied in an optional body).
- WP-16 (I-5): login attempt throttling (per-email for POST /v1/auth/login,
  per-driver_code for POST /v1/auth/driver-login) plus MFA one-time
  recovery/backup codes.
"""
from __future__ import annotations

import uuid

import pyotp
import pytest
from sqlalchemy import select

from app.core import security
from app.core.config import settings
from app.core.security import PLATFORM_TENANT_ID
from app.models import Tenant, User
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio

_PASSWORD = "Test-Passw0rd!"


async def _create_active_user(session, *, role: str = "admin") -> tuple[User, str]:
    tenant = Tenant(name=f"Sec Fix Tenant {uuid.uuid4()}", plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)

    email = f"sec-{uuid.uuid4()}@example.com"
    user = User(
        tenant_id=tenant.id,
        role=role,
        name="Sec Fix User",
        email=email,
        pin_hash=security.hash_password(_PASSWORD),
        status="active",
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)
    return user, email


async def _platform_owner_headers(client, session) -> dict:
    result = await session.execute(select(Tenant).where(Tenant.id == PLATFORM_TENANT_ID))
    platform_tenant = result.scalar_one_or_none()
    if platform_tenant is None:
        platform_tenant = Tenant(id=PLATFORM_TENANT_ID, name="TCT", plan="platform")
        session.add(platform_tenant)
        await session.commit()

    return await auth_headers(client, session, role="owner", tenant_id=PLATFORM_TENANT_ID)


def _bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


# --- I-4: refresh rotation revokes the old refresh token --------------------


async def test_refresh_rotation_revokes_old_refresh_token(client, session):
    _user, email = await _create_active_user(session)
    login = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    assert login.status_code == 200
    old_refresh = login.json()["refresh_token"]

    refreshed = await client.post("/v1/auth/refresh", json={"refresh_token": old_refresh})
    assert refreshed.status_code == 200
    new_refresh = refreshed.json()["refresh_token"]
    assert new_refresh != old_refresh

    # The OLD refresh token is now revoked -- replaying it fails.
    replay = await client.post("/v1/auth/refresh", json={"refresh_token": old_refresh})
    assert replay.status_code == 401

    # The NEW one still works.
    ok_again = await client.post("/v1/auth/refresh", json={"refresh_token": new_refresh})
    assert ok_again.status_code == 200


# --- I-4: logout actually revokes -------------------------------------------


async def test_logout_revokes_access_token(client, session):
    _user, email = await _create_active_user(session)
    login = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    headers = _bearer(login.json()["access_token"])

    assert (await client.get("/v1/auth/me", headers=headers)).status_code == 200

    out = await client.post("/v1/auth/logout", headers=headers)
    assert out.status_code == 204

    assert (await client.get("/v1/auth/me", headers=headers)).status_code == 401


async def test_logout_with_no_body_still_works(client, session):
    """Matches the real dashboard/Android call sites exactly -- POST with no
    body at all -- so this contract change stays backward compatible."""
    _user, email = await _create_active_user(session)
    login = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    headers = _bearer(login.json()["access_token"])

    out = await client.post("/v1/auth/logout", headers=headers)
    assert out.status_code == 204


async def test_logout_revokes_refresh_token_when_supplied(client, session):
    _user, email = await _create_active_user(session)
    login = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    access = login.json()["access_token"]
    refresh = login.json()["refresh_token"]

    out = await client.post(
        "/v1/auth/logout", json={"refresh_token": refresh}, headers=_bearer(access)
    )
    assert out.status_code == 204

    replay = await client.post("/v1/auth/refresh", json={"refresh_token": refresh})
    assert replay.status_code == 401


# --- I-5: login attempt throttling ------------------------------------------


async def test_login_locks_out_after_max_failed_attempts(client, session):
    _user, email = await _create_active_user(session)

    for _ in range(settings.LOGIN_MAX_FAILED_ATTEMPTS):
        bad = await client.post("/v1/auth/login", json={"email": email, "password": "wrong"})
        assert bad.status_code == 401

    # Even the CORRECT password is now rejected -- locked out, not "wrong
    # password" -- and the response is IDENTICAL to a normal invalid-creds
    # 401 (no distinct "you are locked out" signal leaked to the caller).
    locked = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    assert locked.status_code == 401
    assert locked.json() == {"detail": "Invalid email or password"}


async def test_successful_login_resets_failed_attempt_counter(client, session):
    _user, email = await _create_active_user(session)

    for _ in range(settings.LOGIN_MAX_FAILED_ATTEMPTS - 1):
        bad = await client.post("/v1/auth/login", json={"email": email, "password": "wrong"})
        assert bad.status_code == 401

    good = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    assert good.status_code == 200

    # Counter was reset by the success above -- one more wrong attempt does
    # NOT trigger a lockout (it would if the pre-success failures had
    # carried over).
    bad_again = await client.post("/v1/auth/login", json={"email": email, "password": "wrong"})
    assert bad_again.status_code == 401

    still_ok = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    assert still_ok.status_code == 200


async def test_driver_login_locks_out_after_max_failed_attempts(client, session):
    tenant = Tenant(name=f"Driver Lockout Tenant {uuid.uuid4()}", plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)

    driver_code = f"D{uuid.uuid4().hex[:5].upper()}"
    driver = User(
        tenant_id=tenant.id,
        role="driver",
        name="Lockout Driver",
        email=f"lockout-driver-{uuid.uuid4()}@example.com",
        driver_code=driver_code,
        pin_hash=security.hash_password("1234"),
        status="active",
    )
    session.add(driver)
    await session.commit()

    for _ in range(settings.LOGIN_MAX_FAILED_ATTEMPTS):
        bad = await client.post(
            "/v1/auth/driver-login", json={"driver_code": driver_code, "pin": "0000"}
        )
        assert bad.status_code == 401

    locked = await client.post(
        "/v1/auth/driver-login", json={"driver_code": driver_code, "pin": "1234"}
    )
    assert locked.status_code == 401


# --- I-5: MFA recovery codes -------------------------------------------------


async def test_mfa_recovery_code_login(client, session):
    _user, email = await _create_active_user(session)
    login = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    headers = _bearer(login.json()["access_token"])

    setup = await client.post("/v1/auth/mfa/setup", headers=headers)
    secret = setup.json()["secret"]
    code = pyotp.TOTP(secret).now()
    verify = await client.post("/v1/auth/mfa/verify", json={"code": code}, headers=headers)
    assert verify.status_code == 200
    recovery_codes = verify.json()["recovery_codes"]
    assert len(recovery_codes) == 10

    login2 = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    mfa_token = login2.json()["mfa_token"]

    recovery_code = recovery_codes[0]

    # A recovery code works in place of a TOTP code.
    ok = await client.post(
        "/v1/auth/mfa/login", json={"mfa_token": mfa_token, "code": recovery_code}
    )
    assert ok.status_code == 200
    assert "access_token" in ok.json()

    # Single-use: the SAME recovery code cannot be replayed, even against a
    # fresh mfa_token.
    login3 = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    mfa_token2 = login3.json()["mfa_token"]
    replay = await client.post(
        "/v1/auth/mfa/login", json={"mfa_token": mfa_token2, "code": recovery_code}
    )
    assert replay.status_code == 401


# --- I-3: cross-tenant override validation + audit logging -----------------


async def test_cross_tenant_override_404_for_nonexistent_tenant(client, session):
    headers = await _platform_owner_headers(client, session)
    fake_tenant_id = str(uuid.uuid4())

    resp = await client.get(f"/v1/audit-log?tenant_id={fake_tenant_id}", headers=headers)
    assert resp.status_code == 404


async def test_cross_tenant_override_writes_audit_log(client, session):
    headers = await _platform_owner_headers(client, session)

    target = Tenant(name=f"Cross Tenant Target {uuid.uuid4()}", plan="standard")
    session.add(target)
    await session.commit()
    await session.refresh(target)

    resp = await client.get(f"/v1/audit-log?tenant_id={target.id}", headers=headers)
    assert resp.status_code == 200

    entries = resp.json()["items"]
    cross_tenant_entries = [e for e in entries if e["action"] == "cross_tenant_access"]
    assert len(cross_tenant_entries) >= 1
    assert cross_tenant_entries[0]["tenant_id"] == target.id


async def test_same_tenant_override_does_not_audit_log(client, session):
    """Passing ?tenant_id= equal to the token own tenant_id (PLATFORM_TENANT_ID
    itself) is a no-op override, not a cross-tenant access -- no audit entry
    for it specifically, even though the platform tenant own audit log may
    already contain other entries."""
    headers = await _platform_owner_headers(client, session)

    resp = await client.get(f"/v1/audit-log?tenant_id={PLATFORM_TENANT_ID}", headers=headers)
    assert resp.status_code == 200

    entries = resp.json()["items"]
    cross_tenant_entries = [
        e for e in entries if e["action"] == "cross_tenant_access" and e["entity_id"] == PLATFORM_TENANT_ID
    ]
    assert cross_tenant_entries == []
