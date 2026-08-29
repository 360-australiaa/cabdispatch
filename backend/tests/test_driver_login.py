"""Tests for the real driver-facing PIN login (POST /v1/auth/driver-login),
distinct from the pre-existing staff email/password login (POST /v1/auth/login
— see test_health_and_auth_smoke.py / test_auth_mfa.py for that coverage).

This replaces the placeholder driverId->email / pin->password mapping
documented in the Android app's domain/DriverAuthRepository.kt with a real
Driver ID (User.driver_code) + PIN contract, reusing the exact same
TokenResponse / MfaRequiredResponse two-step shape POST /v1/auth/login
already has (see app/api/v1/auth.py::_login_result).
"""
from __future__ import annotations

import uuid

import pyotp
import pytest

from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio

_PASSWORD = "Driver-Pass1!"


def _unique_email(prefix: str = "driver-login") -> str:
    return f"{prefix}-{uuid.uuid4()}@example.com"


async def _create_driver(client, headers, **overrides):
    payload = {
        "name": overrides.pop("name", "PIN Driver"),
        "email": overrides.pop("email", _unique_email()),
        "password": overrides.pop("password", _PASSWORD),
        "role": overrides.pop("role", "driver"),
        **overrides,
    }
    return await client.post("/v1/users", json=payload, headers=headers)


async def test_create_driver_gets_a_generated_driver_code(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await _create_driver(client, headers)
    assert resp.status_code == 201
    body = resp.json()

    driver_code = body["driver_code"]
    assert driver_code is not None
    assert 4 <= len(driver_code) <= 6
    assert driver_code.isalnum()

    # Exposed on GET too.
    get_resp = await client.get(f"/v1/users/{body['id']}", headers=headers)
    assert get_resp.json()["driver_code"] == driver_code


async def test_non_driver_user_gets_no_driver_code(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await _create_driver(client, headers, role="dispatcher", email=_unique_email("dispatcher"))
    assert resp.status_code == 201
    assert resp.json()["driver_code"] is None


async def test_explicit_driver_code_is_respected(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await _create_driver(client, headers, driver_code="ABCD9")
    assert resp.status_code == 201
    assert resp.json()["driver_code"] == "ABCD9"


async def test_duplicate_driver_code_rejected(client, session):
    headers = await auth_headers(client, session, role="admin")

    first = await _create_driver(client, headers, driver_code="DUP99")
    assert first.status_code == 201

    second = await _create_driver(
        client, headers, driver_code="DUP99", email=_unique_email("dup2")
    )
    assert second.status_code == 409


async def test_driver_can_log_in_with_driver_code_and_pin(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await _create_driver(client, headers)
    driver_code = resp.json()["driver_code"]

    login = await client.post(
        "/v1/auth/driver-login", json={"driver_code": driver_code, "pin": _PASSWORD}
    )
    assert login.status_code == 200
    body = login.json()
    assert "access_token" in body
    assert "refresh_token" in body
    assert body["user"]["driver_code"] == driver_code
    assert body["user"]["role"] == "driver"

    me = await client.get(
        "/v1/auth/me", headers={"Authorization": f"Bearer {body['access_token']}"}
    )
    assert me.status_code == 200


async def test_driver_login_wrong_pin_is_401(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await _create_driver(client, headers)
    driver_code = resp.json()["driver_code"]

    login = await client.post(
        "/v1/auth/driver-login", json={"driver_code": driver_code, "pin": "wrong-pin"}
    )
    assert login.status_code == 401


async def test_driver_login_unknown_driver_code_is_401(client, session):
    login = await client.post(
        "/v1/auth/driver-login", json={"driver_code": "ZZZZZ", "pin": "whatever"}
    )
    assert login.status_code == 401


async def test_staff_cannot_log_in_via_driver_login_even_with_pin_hash(client, session):
    """driver-login is scoped to role == "driver" — an admin/dispatcher's own
    password (also stored in pin_hash) must not work through this endpoint
    even if driver_code were somehow set on their row."""
    headers = await auth_headers(client, session, role="admin")

    resp = await _create_driver(client, headers, role="dispatcher", email=_unique_email("staff"))
    user_id = resp.json()["id"]

    # Manually give the dispatcher a driver_code to prove the role check
    # (not just "driver_code is None") is what blocks this.
    from app.models.user import User

    dispatcher = await session.get(User, user_id)
    dispatcher.driver_code = "STAFF1"
    await session.commit()

    login = await client.post(
        "/v1/auth/driver-login", json={"driver_code": "STAFF1", "pin": _PASSWORD}
    )
    assert login.status_code == 401


async def test_mfa_enabled_driver_gets_two_step_driver_login(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await _create_driver(client, headers)
    driver_code = resp.json()["driver_code"]
    user_id = resp.json()["id"]

    # Enable MFA the same way test_auth_mfa.py does: log the driver in via
    # their own (now real) driver-login step 1, then run setup/verify.
    step1 = await client.post(
        "/v1/auth/driver-login", json={"driver_code": driver_code, "pin": _PASSWORD}
    )
    driver_headers = {"Authorization": f"Bearer {step1.json()['access_token']}"}

    setup = await client.post("/v1/auth/mfa/setup", headers=driver_headers)
    secret = setup.json()["secret"]
    code = pyotp.TOTP(secret).now()
    verify = await client.post("/v1/auth/mfa/verify", json={"code": code}, headers=driver_headers)
    assert verify.json()["mfa_enabled"] is True

    # Step 1 of driver-login now returns mfa_required instead of real tokens
    # — the exact same MfaRequiredResponse contract as POST /v1/auth/login.
    login2 = await client.post(
        "/v1/auth/driver-login", json={"driver_code": driver_code, "pin": _PASSWORD}
    )
    assert login2.status_code == 200
    body = login2.json()
    assert body["mfa_required"] is True
    assert "access_token" not in body
    mfa_token = body["mfa_token"]

    # Step 2: same POST /v1/auth/mfa/login endpoint the staff flow uses —
    # driver-login doesn't fork this logic.
    good_code = pyotp.TOTP(secret).now()
    ok = await client.post("/v1/auth/mfa/login", json={"mfa_token": mfa_token, "code": good_code})
    assert ok.status_code == 200
    ok_body = ok.json()
    assert "access_token" in ok_body

    me = await client.get(
        "/v1/auth/me", headers={"Authorization": f"Bearer {ok_body['access_token']}"}
    )
    assert me.status_code == 200
    assert me.json()["id"] == user_id
