"""Tests for the physical duress device domain (/v1/duress-devices,
/v1/devices/auth, /v1/devices/{id}/heartbeat, /v1/duress/device/*) and the
Twilio "call the cab" / status-callback additions on /v1/duress. See
docs/DURESS_DEVICE_INTEGRATION.md for the wire contract these exercise.
"""
from __future__ import annotations

import hashlib
import hmac
import uuid

from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.tenant import Tenant
from tests.conftest import auth_headers

SECRET = "test-device-shared-secret-0123456789"


def _hmac_hex(secret: str, nonce: str) -> str:
    return hmac.new(secret.encode(), nonce.encode(), hashlib.sha256).hexdigest()


async def _make_tenant(session: AsyncSession, name: str = "Duress Device Test Tenant") -> str:
    tenant = Tenant(name=name, plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    return tenant.id


async def _provision_device(
    client: AsyncClient, headers: dict, *, device_code: str = "CTDPD-TEST", secret: str = SECRET, **overrides
) -> dict:
    body = {"device_code": device_code, "plaintext_secret": secret, **overrides}
    resp = await client.post("/v1/duress-devices", json=body, headers=headers)
    assert resp.status_code == 201, resp.text
    return resp.json()


async def _device_token(client: AsyncClient, *, tenant_id: str, device_code: str, secret: str, nonce: str) -> str:
    resp = await client.post(
        "/v1/devices/auth",
        json={
            "device_code": device_code,
            "tenant_id": tenant_id,
            "nonce": nonce,
            "hmac_hex": _hmac_hex(secret, nonce),
        },
    )
    assert resp.status_code == 200, resp.text
    return resp.json()["device_token"]


# --- provisioning / admin CRUD -------------------------------------------------


async def test_create_duress_device_never_returns_the_secret(client: AsyncClient, session: AsyncSession):
    tenant_id = await _make_tenant(session)
    headers = await auth_headers(client, session, role="owner", tenant_id=tenant_id)

    device = await _provision_device(client, headers)
    assert "secret_encrypted" not in device
    assert "plaintext_secret" not in device
    assert device["device_code"] == "CTDPD-TEST"
    assert device["active"] is True


async def test_create_duress_device_requires_dispatch_role(client: AsyncClient, session: AsyncSession):
    tenant_id = await _make_tenant(session)
    headers = await auth_headers(client, session, role="driver", tenant_id=tenant_id)

    resp = await client.post(
        "/v1/duress-devices",
        json={"device_code": "CTDPD-X", "plaintext_secret": SECRET},
        headers=headers,
    )
    assert resp.status_code == 403


async def test_duplicate_device_code_is_a_clean_409_not_a_500(client: AsyncClient, session: AsyncSession):
    """Regression test for a real bug found during manual verification: with
    no per-tenant uniqueness on device_code, a duplicate crashed
    authenticate_device's scalar_one_or_none() with MultipleResultsFound
    (an unhandled 500) the moment either device tried to authenticate."""
    tenant_id = await _make_tenant(session)
    headers = await auth_headers(client, session, role="owner", tenant_id=tenant_id)

    await _provision_device(client, headers, device_code="CTDPD-DUP")
    resp = await client.post(
        "/v1/duress-devices",
        json={"device_code": "CTDPD-DUP", "plaintext_secret": "a-different-secret-value-99999"},
        headers=headers,
    )
    assert resp.status_code == 409, resp.text


async def test_same_device_code_allowed_across_different_tenants(client: AsyncClient, session: AsyncSession):
    tenant_a = await _make_tenant(session, "Tenant A")
    tenant_b = await _make_tenant(session, "Tenant B")
    headers_a = await auth_headers(client, session, role="owner", tenant_id=tenant_a)
    headers_b = await auth_headers(client, session, role="owner", tenant_id=tenant_b)

    await _provision_device(client, headers_a, device_code="CTDPD-SHARED")
    resp = await client.post(
        "/v1/duress-devices",
        json={"device_code": "CTDPD-SHARED", "plaintext_secret": "another-secret-value-11111"},
        headers=headers_b,
    )
    assert resp.status_code == 201, resp.text


# --- device auth handshake ------------------------------------------------------


async def test_device_auth_success_mints_a_device_token(client: AsyncClient, session: AsyncSession):
    tenant_id = await _make_tenant(session)
    headers = await auth_headers(client, session, role="owner", tenant_id=tenant_id)
    device = await _provision_device(client, headers)

    token = await _device_token(
        client, tenant_id=tenant_id, device_code=device["device_code"], secret=SECRET, nonce="nonce-ok-12345"
    )
    assert len(token) > 20


async def test_device_auth_wrong_secret_is_401(client: AsyncClient, session: AsyncSession):
    tenant_id = await _make_tenant(session)
    headers = await auth_headers(client, session, role="owner", tenant_id=tenant_id)
    device = await _provision_device(client, headers)

    resp = await client.post(
        "/v1/devices/auth",
        json={
            "device_code": device["device_code"],
            "tenant_id": tenant_id,
            "nonce": "nonce-bad-123456",
            "hmac_hex": _hmac_hex("totally-wrong-secret", "nonce-bad-123456"),
        },
    )
    assert resp.status_code == 401


async def test_device_auth_unknown_device_code_is_401(client: AsyncClient, session: AsyncSession):
    tenant_id = await _make_tenant(session)
    resp = await client.post(
        "/v1/devices/auth",
        json={
            "device_code": "does-not-exist",
            "tenant_id": tenant_id,
            "nonce": "nonce-none-123456",
            "hmac_hex": _hmac_hex(SECRET, "nonce-none-123456"),
        },
    )
    assert resp.status_code == 401


async def test_a_human_access_token_cannot_call_a_device_only_route(client: AsyncClient, session: AsyncSession):
    """A user access token must never satisfy get_current_device -- this is
    the security property that motivates the separate "type" claim."""
    tenant_id = await _make_tenant(session)
    headers = await auth_headers(client, session, role="driver", tenant_id=tenant_id)

    resp = await client.post(
        "/v1/duress/device/alarm",
        json={"vehicle_id": str(uuid.uuid4()), "lat": -33.8, "lng": 151.2},
        headers=headers,
    )
    assert resp.status_code == 401


# --- alarm open / correlation ---------------------------------------------------


async def test_device_alarm_opens_a_new_event(client: AsyncClient, session: AsyncSession):
    tenant_id = await _make_tenant(session)
    headers = await auth_headers(client, session, role="owner", tenant_id=tenant_id)
    device = await _provision_device(client, headers)
    token = await _device_token(
        client, tenant_id=tenant_id, device_code=device["device_code"], secret=SECRET, nonce="alarm-nonce-1"
    )

    vehicle_id = str(uuid.uuid4())
    resp = await client.post(
        "/v1/duress/device/alarm",
        json={"vehicle_id": vehicle_id, "driver_id": "drv-1", "lat": -33.8, "lng": 151.2, "trigger_source": "button"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["source"] == "device"

    event = (await client.get(f"/v1/duress/{body['event_id']}", headers=headers)).json()
    assert event["device_id"] == device["id"]
    assert event["source"] == "device"


async def test_device_alarm_retrigger_is_idempotent(client: AsyncClient, session: AsyncSession):
    tenant_id = await _make_tenant(session)
    headers = await auth_headers(client, session, role="owner", tenant_id=tenant_id)
    device = await _provision_device(client, headers)
    token = await _device_token(
        client, tenant_id=tenant_id, device_code=device["device_code"], secret=SECRET, nonce="alarm-nonce-2"
    )
    alarm_body = {"vehicle_id": str(uuid.uuid4()), "driver_id": "drv-1", "lat": -33.8, "lng": 151.2}
    auth_hdr = {"Authorization": f"Bearer {token}"}

    first = await client.post("/v1/duress/device/alarm", json=alarm_body, headers=auth_hdr)
    second = await client.post("/v1/duress/device/alarm", json=alarm_body, headers=auth_hdr)
    assert first.json()["event_id"] == second.json()["event_id"]


async def test_device_attaches_to_an_already_open_tablet_event(client: AsyncClient, session: AsyncSession):
    tenant_id = await _make_tenant(session)
    headers = await auth_headers(client, session, role="owner", tenant_id=tenant_id)
    driver_headers = await auth_headers(client, session, role="driver", tenant_id=tenant_id)
    device = await _provision_device(client, headers)
    token = await _device_token(
        client, tenant_id=tenant_id, device_code=device["device_code"], secret=SECRET, nonce="alarm-nonce-3"
    )

    vehicle_id = str(uuid.uuid4())
    tablet_event = await client.post(
        "/v1/duress/trigger",
        json={"vehicle_id": vehicle_id, "driver_id": "drv-1", "trigger": "gesture"},
        headers=driver_headers,
    )
    assert tablet_event.status_code == 201
    tablet_event_id = tablet_event.json()["id"]

    attach = await client.post(
        "/v1/duress/device/alarm",
        json={"vehicle_id": vehicle_id, "driver_id": "drv-1", "lat": -33.8, "lng": 151.2},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert attach.status_code == 200
    assert attach.json()["event_id"] == tablet_event_id
    assert attach.json()["source"] == "both"


# --- GPS / audio ownership boundary ---------------------------------------------


async def test_device_cannot_post_gps_into_another_devices_event(client: AsyncClient, session: AsyncSession):
    tenant_id = await _make_tenant(session)
    headers = await auth_headers(client, session, role="owner", tenant_id=tenant_id)
    device_a = await _provision_device(client, headers, device_code="CTDPD-A", secret="secret-a-value-000000")
    device_b = await _provision_device(client, headers, device_code="CTDPD-B", secret="secret-b-value-111111")
    token_a = await _device_token(
        client, tenant_id=tenant_id, device_code="CTDPD-A", secret="secret-a-value-000000", nonce="own-nonce-a"
    )
    token_b = await _device_token(
        client, tenant_id=tenant_id, device_code="CTDPD-B", secret="secret-b-value-111111", nonce="own-nonce-b"
    )

    alarm = await client.post(
        "/v1/duress/device/alarm",
        json={"vehicle_id": str(uuid.uuid4()), "driver_id": "drv-a", "lat": -33.8, "lng": 151.2},
        headers={"Authorization": f"Bearer {token_a}"},
    )
    event_id = alarm.json()["event_id"]

    resp = await client.post(
        f"/v1/duress/device/{event_id}/gps",
        json={"points": [{"lat": -1.0, "lng": -1.0}]},
        headers={"Authorization": f"Bearer {token_b}"},
    )
    assert resp.status_code == 403
    assert device_a["id"] != device_b["id"]


async def test_device_gps_batch_is_accepted(client: AsyncClient, session: AsyncSession):
    tenant_id = await _make_tenant(session)
    headers = await auth_headers(client, session, role="owner", tenant_id=tenant_id)
    device = await _provision_device(client, headers)
    token = await _device_token(
        client, tenant_id=tenant_id, device_code=device["device_code"], secret=SECRET, nonce="gps-nonce-1"
    )
    alarm = await client.post(
        "/v1/duress/device/alarm",
        json={"vehicle_id": str(uuid.uuid4()), "driver_id": "drv-1", "lat": -33.8, "lng": 151.2},
        headers={"Authorization": f"Bearer {token}"},
    )
    event_id = alarm.json()["event_id"]

    resp = await client.post(
        f"/v1/duress/device/{event_id}/gps",
        json={"points": [{"lat": -33.81, "lng": 151.21}, {"lat": -33.82, "lng": 151.22}]},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 202


# --- heartbeat -------------------------------------------------------------------


async def test_heartbeat_updates_health_fields(client: AsyncClient, session: AsyncSession):
    tenant_id = await _make_tenant(session)
    headers = await auth_headers(client, session, role="owner", tenant_id=tenant_id)
    device = await _provision_device(client, headers)
    token = await _device_token(
        client, tenant_id=tenant_id, device_code=device["device_code"], secret=SECRET, nonce="hb-nonce-1"
    )

    resp = await client.post(
        f"/v1/devices/{device['id']}/heartbeat",
        json={"battery_pct": 77, "on_battery": True, "gnss_fix": True, "signal_csq": 18, "firmware_version": "1.2.0"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["battery_pct"] == 77
    assert body["on_battery"] is True
    assert body["firmware_version"] == "1.2.0"


# --- call the cab -----------------------------------------------------------------


async def test_call_requires_a_linked_device(client: AsyncClient, session: AsyncSession):
    tenant_id = await _make_tenant(session)
    driver_headers = await auth_headers(client, session, role="driver", tenant_id=tenant_id)
    owner_headers = await auth_headers(client, session, role="owner", tenant_id=tenant_id)

    tablet_event = await client.post(
        "/v1/duress/trigger",
        json={"vehicle_id": str(uuid.uuid4()), "driver_id": "drv-1", "trigger": "button"},
        headers=driver_headers,
    )
    event_id = tablet_event.json()["id"]

    resp = await client.post(f"/v1/duress/{event_id}/call", json={}, headers=owner_headers)
    assert resp.status_code == 409


async def test_call_the_cab_mock_fallback_when_twilio_and_call_centre_unconfigured(
    client: AsyncClient, session: AsyncSession
):
    """No live Twilio credentials and no DURESS_ESCALATION_CALL_PHONE are
    configured in the test environment -- place_duress_call must skip
    cleanly (mock=True, skipped=True) rather than raise."""
    tenant_id = await _make_tenant(session)
    owner_headers = await auth_headers(client, session, role="owner", tenant_id=tenant_id)
    device = await _provision_device(client, owner_headers, phone_number="+61400555666")
    token = await _device_token(
        client, tenant_id=tenant_id, device_code=device["device_code"], secret=SECRET, nonce="call-nonce-1"
    )

    alarm = await client.post(
        "/v1/duress/device/alarm",
        json={"vehicle_id": str(uuid.uuid4()), "driver_id": "drv-1", "lat": -33.8, "lng": 151.2},
        headers={"Authorization": f"Bearer {token}"},
    )
    event_id = alarm.json()["event_id"]

    resp = await client.post(f"/v1/duress/{event_id}/call", json={}, headers=owner_headers)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["mock"] is True
    assert body["skipped"] is True


# --- twilio status webhook ---------------------------------------------------------


async def test_twilio_status_webhook_accepts_unsigned_requests_when_unconfigured(client: AsyncClient):
    """settings.TWILIO_AUTH_TOKEN is unset in the test environment, so
    verify_twilio_signature must skip the check (return True) rather than
    reject every callback -- this is the documented dev/mock behavior."""
    resp = await client.post(
        "/v1/duress/twilio/status",
        data={"CallSid": "CAnonexistent", "CallStatus": "completed"},
    )
    assert resp.status_code == 200