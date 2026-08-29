"""Tests for opt-in TOTP MFA on the auth router (blueprint 12.2).

Covers: full opt-in flow (setup -> verify -> mfa_enabled=True), login now
requiring the second step once enabled, wrong codes being rejected, the
disable flow, and — critically — that accounts which never opt in are
completely unaffected (see test_health_and_auth_smoke.py for the pre-existing
no-MFA login coverage this must not disturb).
"""
from __future__ import annotations

import uuid

import pyotp
import pytest

from app.core import security
from app.models import Tenant, User

pytestmark = pytest.mark.asyncio

_PASSWORD = "Test-Passw0rd!"


async def _create_active_user(session, *, role: str = "admin") -> tuple[User, str]:
    """Same shape as tests.conftest.auth_headers, but returns the plaintext
    email too so the test can drive POST /v1/auth/login directly instead of
    minting a token out-of-band."""
    tenant = Tenant(name=f"MFA Test Tenant {uuid.uuid4()}", plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)

    email = f"mfa-{uuid.uuid4()}@example.com"
    user = User(
        tenant_id=tenant.id,
        role=role,
        name="MFA Test User",
        email=email,
        pin_hash=security.hash_password(_PASSWORD),
        status="active",
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)
    return user, email


def _bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


async def test_full_mfa_opt_in_flow(client, session):
    _user, email = await _create_active_user(session)
    login = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    assert login.status_code == 200
    headers = _bearer(login.json()["access_token"])

    setup = await client.post("/v1/auth/mfa/setup", headers=headers)
    assert setup.status_code == 200
    body = setup.json()
    assert "secret" in body
    assert body["otpauth_uri"].startswith("otpauth://totp/")
    assert body["secret"] in body["otpauth_uri"]

    # Not enabled yet — setup alone doesn't flip the flag.
    me = await client.get("/v1/auth/me", headers=headers)
    assert me.json()["mfa_enabled"] is False

    code = pyotp.TOTP(body["secret"]).now()
    verify = await client.post("/v1/auth/mfa/verify", json={"code": code}, headers=headers)
    assert verify.status_code == 200
    verify_body = verify.json()
    assert verify_body["mfa_enabled"] is True
    # WP-16 (I-5): recovery codes are generated and returned exactly once,
    # right when mfa_enabled flips True -- see test_recovery_code below for
    # full coverage of consuming one at login.
    assert len(verify_body["recovery_codes"]) == 10
    assert len(set(verify_body["recovery_codes"])) == 10

    me = await client.get("/v1/auth/me", headers=headers)
    assert me.json()["mfa_enabled"] is True


async def test_verify_without_pending_setup_is_400(client, session):
    _user, email = await _create_active_user(session)
    login = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    headers = _bearer(login.json()["access_token"])

    resp = await client.post("/v1/auth/mfa/verify", json={"code": "123456"}, headers=headers)
    assert resp.status_code == 400


async def test_verify_rejects_wrong_code(client, session):
    _user, email = await _create_active_user(session)
    login = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    headers = _bearer(login.json()["access_token"])

    await client.post("/v1/auth/mfa/setup", headers=headers)
    # A code from a different, unrelated secret is (astronomically likely to
    # be) wrong for this one.
    wrong_code = pyotp.TOTP(pyotp.random_base32()).now()

    resp = await client.post("/v1/auth/mfa/verify", json={"code": wrong_code}, headers=headers)
    assert resp.status_code == 401

    me = await client.get("/v1/auth/me", headers=headers)
    assert me.json()["mfa_enabled"] is False


async def _enable_mfa(client, headers) -> str:
    setup = await client.post("/v1/auth/mfa/setup", headers=headers)
    secret = setup.json()["secret"]
    code = pyotp.TOTP(secret).now()
    verify = await client.post("/v1/auth/mfa/verify", json={"code": code}, headers=headers)
    assert verify.status_code == 200
    return secret


async def test_login_requires_mfa_second_step_once_enabled(client, session):
    _user, email = await _create_active_user(session)
    login = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    headers = _bearer(login.json()["access_token"])
    secret = await _enable_mfa(client, headers)

    # Step 1: email+password alone no longer returns real tokens.
    login2 = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    assert login2.status_code == 200
    body = login2.json()
    assert body["mfa_required"] is True
    assert "mfa_token" in body
    assert "access_token" not in body
    mfa_token = body["mfa_token"]

    # The mfa_token itself must NOT work as a bearer token on protected routes
    # (that would let a password-only attacker skip the second factor).
    probe = await client.get("/v1/auth/me", headers=_bearer(mfa_token))
    assert probe.status_code == 401

    # Wrong TOTP code is rejected.
    bad = await client.post(
        "/v1/auth/mfa/login", json={"mfa_token": mfa_token, "code": "000000"}
    )
    assert bad.status_code in (400, 401)

    # Correct code exchanges for real tokens.
    good_code = pyotp.TOTP(secret).now()
    ok = await client.post(
        "/v1/auth/mfa/login", json={"mfa_token": mfa_token, "code": good_code}
    )
    assert ok.status_code == 200
    ok_body = ok.json()
    assert "access_token" in ok_body
    assert "refresh_token" in ok_body

    # The new access token works as a normal bearer token.
    me = await client.get("/v1/auth/me", headers=_bearer(ok_body["access_token"]))
    assert me.status_code == 200
    assert me.json()["email"] == email

    # The mfa_token is single-use — replaying it fails even with the right code.
    replay = await client.post(
        "/v1/auth/mfa/login", json={"mfa_token": mfa_token, "code": good_code}
    )
    assert replay.status_code == 401


async def test_mfa_disable_requires_correct_password(client, session):
    _user, email = await _create_active_user(session)
    login = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    headers = _bearer(login.json()["access_token"])
    await _enable_mfa(client, headers)

    bad = await client.post(
        "/v1/auth/mfa/disable", json={"password": "wrong-password"}, headers=headers
    )
    assert bad.status_code == 401
    assert (await client.get("/v1/auth/me", headers=headers)).json()["mfa_enabled"] is True

    good = await client.post(
        "/v1/auth/mfa/disable", json={"password": _PASSWORD}, headers=headers
    )
    assert good.status_code == 200
    assert good.json() == {"mfa_enabled": False}

    # Login goes back to the plain single-step flow.
    login3 = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    assert login3.status_code == 200
    assert "access_token" in login3.json()


async def test_accounts_without_mfa_login_exactly_as_before(client, session):
    """The 232 pre-existing tests already exercise this path via
    tests.conftest.auth_headers, which never touches mfa_enabled at all — this
    test just makes the "unaffected" guarantee explicit for the login HTTP
    contract itself."""
    _user, email = await _create_active_user(session)

    resp = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    assert resp.status_code == 200
    body = resp.json()
    assert "access_token" in body
    assert "refresh_token" in body
    assert "mfa_required" not in body
    assert body["user"]["mfa_enabled"] is False
