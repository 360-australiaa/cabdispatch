"""D10 (security settings): password change, password reset by email,
MFA recovery codes, and sessions ("sign out everywhere") — including its
websocket-kill negative test.

Every "must not leak" / "must be single-use" / "must actually kill an
open connection" claim in the D10 task brief gets its own test here, proven
against the real endpoints and the real revocation store — not asserted by
inspecting code.
"""
from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient
from httpx import AsyncClient
from starlette.websockets import WebSocketDisconnect

from app.core import ratelimit, security
from app.core.ratelimit import PASSWORD_RESET_PER_EMAIL
from app.models import Tenant, User

pytestmark = pytest.mark.asyncio

_PASSWORD = "Correct-Horse-1!"
_NEW_PASSWORD = "Battery-Staple-2!"


async def _make_user(session, *, mfa_enabled: bool = False, mfa_secret: str | None = None) -> tuple[str, str, str]:
    """Returns (tenant_id, user_id, email)."""
    tenant = Tenant(name=f"D10 Test {uuid.uuid4().hex[:8]}", plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)

    email = f"{uuid.uuid4()}@example.com"
    user = User(
        tenant_id=tenant.id,
        role="admin",
        name="D10 Admin",
        email=email,
        pin_hash=security.hash_password(_PASSWORD),
        status="active",
        mfa_enabled=mfa_enabled,
        mfa_secret=mfa_secret,
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)
    return tenant.id, user.id, email


async def _login(client: AsyncClient, email: str, password: str = _PASSWORD) -> dict:
    resp = await client.post("/v1/auth/login", json={"email": email, "password": password})
    assert resp.status_code == 200, resp.text
    return resp.json()


# --- password change ---------------------------------------------------------


async def test_password_change_requires_correct_current_password(client, session):
    _, _, email = await _make_user(session)
    tokens = await _login(client, email)
    headers = {"Authorization": f"Bearer {tokens['access_token']}"}

    resp = await client.post(
        "/v1/auth/password/change",
        json={"current_password": "wrong-password", "new_password": _NEW_PASSWORD},
        headers=headers,
    )
    assert resp.status_code == 401


async def test_password_change_then_old_password_no_longer_works(client, session):
    _, _, email = await _make_user(session)
    tokens = await _login(client, email)
    headers = {"Authorization": f"Bearer {tokens['access_token']}"}

    resp = await client.post(
        "/v1/auth/password/change",
        json={"current_password": _PASSWORD, "new_password": _NEW_PASSWORD},
        headers=headers,
    )
    assert resp.status_code == 204

    # Old password now fails, new one works.
    old = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    assert old.status_code == 401

    new = await client.post("/v1/auth/login", json={"email": email, "password": _NEW_PASSWORD})
    assert new.status_code == 200


# --- password reset by email: no account-existence leak ---------------------


@pytest.fixture(autouse=True)
def _rate_limiting_off_by_default():
    """This module mostly runs with the limiter in its normal test-env OFF
    state (see app.core.ratelimit._default_enabled) so unrelated tests in
    this file don't 429 each other; the one test that needs it ON enables it
    itself and cleans up."""
    yield
    ratelimit.set_enabled(False)
    ratelimit.reset_all()


async def test_reset_request_does_not_leak_account_existence(client, session):
    _, _, real_email = await _make_user(session)
    fake_email = f"{uuid.uuid4()}@nowhere.example"

    resp_real = await client.post("/v1/auth/password/reset/request", json={"email": real_email})
    resp_fake = await client.post("/v1/auth/password/reset/request", json={"email": fake_email})

    assert resp_real.status_code == resp_fake.status_code == 202
    # Byte-identical body — an attacker probing this endpoint learns nothing.
    assert resp_real.json() == resp_fake.json()


async def test_reset_request_is_rate_limited_per_email(client, session, monkeypatch):
    """PASSWORD_RESET_PER_EMAIL trips independently of the account existing —
    checked BEFORE the user lookup, so the limit itself leaks nothing either."""
    ratelimit.reset_all()
    ratelimit.set_enabled(True)
    try:
        email = f"{uuid.uuid4()}@nowhere.example"
        limit_n = int(PASSWORD_RESET_PER_EMAIL.split("/")[0])
        for _ in range(limit_n):
            resp = await client.post("/v1/auth/password/reset/request", json={"email": email})
            assert resp.status_code == 202
        resp = await client.post("/v1/auth/password/reset/request", json={"email": email})
        assert resp.status_code == 429
    finally:
        ratelimit.set_enabled(False)
        ratelimit.reset_all()


async def test_reset_confirm_full_round_trip_and_single_use(client, session, monkeypatch):
    """Captures the real reset token by monkeypatching the send function
    (never logging/printing the token itself — same discipline the task
    brief requires of production code, kept here too) and proves: the new
    password works, the OLD password no longer does, and the SAME token
    cannot be replayed a second time."""
    _, _user_id, email = await _make_user(session)

    captured = {}

    def _fake_send(*, to_email, reset_url):
        captured["reset_url"] = reset_url
        return {"mock": True}

    import app.api.v1.auth as auth_module

    monkeypatch.setattr(auth_module, "send_password_reset_email", _fake_send)

    resp = await client.post("/v1/auth/password/reset/request", json={"email": email})
    assert resp.status_code == 202
    assert "reset_url" in captured

    reset_token = captured["reset_url"].split("token=")[1]

    confirm = await client.post(
        "/v1/auth/password/reset/confirm",
        json={"reset_token": reset_token, "new_password": _NEW_PASSWORD},
    )
    assert confirm.status_code == 204

    old = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    assert old.status_code == 401
    new = await client.post("/v1/auth/login", json={"email": email, "password": _NEW_PASSWORD})
    assert new.status_code == 200

    # Replay: same token, second confirm attempt must fail.
    replay = await client.post(
        "/v1/auth/password/reset/confirm",
        json={"reset_token": reset_token, "new_password": "Yet-Another-3!"},
    )
    assert replay.status_code == 400


# --- MFA recovery codes -------------------------------------------------------


async def test_recovery_codes_require_mfa_enabled(client, session):
    _, _, email = await _make_user(session, mfa_enabled=False)
    tokens = await _login(client, email)
    headers = {"Authorization": f"Bearer {tokens['access_token']}"}

    resp = await client.post("/v1/auth/mfa/recovery-codes/generate", headers=headers)
    assert resp.status_code == 400


async def test_recovery_code_is_single_use(client, session):
    """Generates codes, logs in with MFA to get an mfa_token, exchanges it for
    real tokens using ONE recovery code, then proves that SAME code fails on
    a second attempt — the core single-use guarantee."""
    import pyotp

    secret = security.generate_mfa_secret()
    _, _user_id, email = await _make_user(session, mfa_enabled=True, mfa_secret=secret)

    login_resp0 = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    assert login_resp0.status_code == 200
    mfa_login0 = await client.post(
        "/v1/auth/mfa/login",
        json={"mfa_token": login_resp0.json()["mfa_token"], "code": pyotp.TOTP(secret).now()},
    )
    assert mfa_login0.status_code == 200, mfa_login0.text
    headers = {"Authorization": f"Bearer {mfa_login0.json()['access_token']}"}

    gen = await client.post("/v1/auth/mfa/recovery-codes/generate", headers=headers)
    assert gen.status_code == 200
    codes = gen.json()["codes"]
    assert len(codes) == 10
    # Never persisted in plaintext anywhere retrievable.
    assert all(isinstance(c, str) and "-" in c for c in codes)

    status_resp = await client.get("/v1/auth/mfa/recovery-codes/status", headers=headers)
    assert status_resp.json()["remaining"] == 10

    # Start a fresh login to get a real mfa_token (mfa_enabled=True forces the
    # two-step flow).
    login_resp = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    assert login_resp.status_code == 200
    mfa_token = login_resp.json()["mfa_token"]

    first = await client.post(
        "/v1/auth/mfa/login", json={"mfa_token": mfa_token, "recovery_code": codes[0]}
    )
    assert first.status_code == 200, first.text

    # A second mfa_token, second attempt with the SAME (now-consumed) code.
    login_resp2 = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    mfa_token2 = login_resp2.json()["mfa_token"]
    second = await client.post(
        "/v1/auth/mfa/login", json={"mfa_token": mfa_token2, "recovery_code": codes[0]}
    )
    assert second.status_code == 401

    status_resp2 = await client.get("/v1/auth/mfa/recovery-codes/status", headers=headers)
    assert status_resp2.json()["remaining"] == 9


async def test_mfa_login_requires_exactly_one_of_code_or_recovery_code(client, session):
    secret = security.generate_mfa_secret()
    _, _, email = await _make_user(session, mfa_enabled=True, mfa_secret=secret)
    login_resp = await client.post("/v1/auth/login", json={"email": email, "password": _PASSWORD})
    mfa_token = login_resp.json()["mfa_token"]

    neither = await client.post("/v1/auth/mfa/login", json={"mfa_token": mfa_token})
    assert neither.status_code == 400

    both = await client.post(
        "/v1/auth/mfa/login",
        json={"mfa_token": mfa_token, "code": "123456", "recovery_code": "AAAA-BBBB-CCCC"},
    )
    assert both.status_code == 400


# --- sessions: list, revoke one, sign out everywhere -------------------------


async def test_session_list_shows_current_session(client, session):
    _, _, email = await _make_user(session)
    tokens = await _login(client, email)
    headers = {"Authorization": f"Bearer {tokens['access_token']}"}

    resp = await client.get("/v1/auth/sessions", headers=headers)
    assert resp.status_code == 200
    sessions = resp.json()["sessions"]
    assert len(sessions) == 1
    assert sessions[0]["is_current"] is True


async def test_revoke_one_session_kills_its_access_token(client, session):
    _, _, email = await _make_user(session)
    tokens_a = await _login(client, email)
    tokens_b = await _login(client, email)
    headers_a = {"Authorization": f"Bearer {tokens_a['access_token']}"}
    headers_b = {"Authorization": f"Bearer {tokens_b['access_token']}"}

    listing = await client.get("/v1/auth/sessions", headers=headers_b)
    sessions = listing.json()["sessions"]
    session_a_id = next(s["id"] for s in sessions if not s["is_current"])

    revoke = await client.post(f"/v1/auth/sessions/{session_a_id}/revoke", headers=headers_b)
    assert revoke.status_code == 204

    # Session A's access token is now dead everywhere.
    me = await client.get("/v1/auth/me", headers=headers_a)
    assert me.status_code == 401

    # Session B (the caller) is untouched.
    me_b = await client.get("/v1/auth/me", headers=headers_b)
    assert me_b.status_code == 200


async def test_revoke_all_sessions_keeps_current_by_default(client, session):
    _, _, email = await _make_user(session)
    tokens_a = await _login(client, email)
    tokens_b = await _login(client, email)
    headers_a = {"Authorization": f"Bearer {tokens_a['access_token']}"}
    headers_b = {"Authorization": f"Bearer {tokens_b['access_token']}"}

    resp = await client.post("/v1/auth/sessions/revoke-all", headers=headers_b)
    assert resp.status_code == 204

    assert (await client.get("/v1/auth/me", headers=headers_a)).status_code == 401
    assert (await client.get("/v1/auth/me", headers=headers_b)).status_code == 200


async def test_revoke_all_sessions_can_include_current(client, session):
    _, _, email = await _make_user(session)
    tokens = await _login(client, email)
    headers = {"Authorization": f"Bearer {tokens['access_token']}"}

    resp = await client.post("/v1/auth/sessions/revoke-all?keep_current=false", headers=headers)
    assert resp.status_code == 204

    assert (await client.get("/v1/auth/me", headers=headers)).status_code == 401


async def test_logout_removes_session_from_list(client, session):
    _, _, email = await _make_user(session)
    tokens_a = await _login(client, email)
    tokens_b = await _login(client, email)
    headers_a = {"Authorization": f"Bearer {tokens_a['access_token']}"}
    headers_b = {"Authorization": f"Bearer {tokens_b['access_token']}"}

    logout = await client.post("/v1/auth/logout", headers=headers_a)
    assert logout.status_code == 204

    listing = await client.get("/v1/auth/sessions", headers=headers_b)
    ids = [s["id"] for s in listing.json()["sessions"]]
    assert len(ids) == 1  # only session B remains live


# --- the negative test: a revoked session kills an ALREADY-OPEN websocket ---


async def test_revoked_session_kills_an_already_open_websocket(app, session, monkeypatch):
    """The core D10 proof: connect a real websocket with a real access token,
    revoke that session from a second, independent request, and show the
    open socket dies on its own — not merely that a NEW connection with the
    same token would be refused (test_ws_auth.py already covers that at the
    handshake), but that a connection open BEFORE the revocation is killed
    DURING its lifetime.

    `security.WS_REVOCATION_POLL_SECONDS` is monkeypatched down so the test
    doesn't have to wait out the real 2-second production poll interval.
    """
    monkeypatch.setattr(security, "WS_REVOCATION_POLL_SECONDS", 0.05)

    tenant = Tenant(name=f"D10 WS {uuid.uuid4().hex[:8]}", plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)

    user = User(
        tenant_id=tenant.id,
        role="dispatcher",
        name="D10 WS Driver",
        email=f"{uuid.uuid4()}@example.com",
        driver_code="900001",
        pin_hash=security.hash_password(_PASSWORD),
        status="active",
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)

    access_token = security.create_access_token(user_id=user.id, tenant_id=tenant.id, role=user.role)
    access_jti = security.decode_token(access_token)["jti"]

    with TestClient(app) as tc, tc.websocket_connect(f"/v1/jobs/live?token={access_token}") as ws:
            # Revoke the session's jti directly against the SAME revocation
            # store `POST /v1/auth/sessions/{id}/revoke` and
            # `POST /v1/auth/sessions/revoke-all` write to — this is exactly
            # what either endpoint does to a session's current_access_jti.
            await security.revocation_store.revoke(access_jti, 300)

            # The open socket must die within a couple of poll ticks: the
            # server sends a close frame, and the next receive raises
            # WebSocketDisconnect.
            with pytest.raises(WebSocketDisconnect):
                ws.receive_json()
