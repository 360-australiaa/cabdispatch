"""Tests for the per-vehicle compliance evidence-pack export
(GET /v1/fleet/vehicles/{id}/evidence-pack) and the device app_version
history it draws from (app.models.fleet.DeviceVersionHistory, populated by
app.services.fleet.record_heartbeat)."""
from __future__ import annotations

import io
import json
import zipfile

import pytest

from app.models.fleet import DeviceVersionHistory  # noqa: F401
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


def _zip_from(resp) -> zipfile.ZipFile:
    assert resp.status_code == 200
    assert resp.headers["content-type"] == "application/zip"
    assert "attachment" in resp.headers.get("content-disposition", "")
    return zipfile.ZipFile(io.BytesIO(resp.content))


def _read_json(zf: zipfile.ZipFile, arcname: str) -> dict:
    return json.loads(zf.read(arcname))


async def _create_vehicle(client, headers, **overrides):
    payload = {"rego": overrides.pop("rego", "EVP-001"), **overrides}
    return await client.post("/v1/fleet/vehicles", json=payload, headers=headers)


async def test_evidence_pack_has_every_category_as_placeholder_when_empty(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Evidence Empty Tenant")
    resp = await _create_vehicle(client, headers)
    vehicle_id = resp.json()["id"]

    resp = await client.get(f"/v1/fleet/vehicles/{vehicle_id}/evidence-pack", headers=headers)
    zf = _zip_from(resp)
    names = set(zf.namelist())

    assert {
        "manifest.json",
        "compliance/manifest.json",
        "tariffs/tariff_history.json",
        "device_versions/report.json",
        "audit_log/tamper_log.json",
        "installation/record.json",
    } <= names

    compliance = _read_json(zf, "compliance/manifest.json")
    assert compliance["count"] == 0
    assert compliance["note"]

    device_versions = _read_json(zf, "device_versions/report.json")
    assert device_versions["devices"] == []
    assert device_versions["note"]

    audit_log = _read_json(zf, "audit_log/tamper_log.json")
    assert audit_log["count"] == 0
    assert audit_log["note"]

    installation = _read_json(zf, "installation/record.json")
    assert installation["count"] == 0
    assert installation["note"]

    manifest = _read_json(zf, "manifest.json")
    assert manifest["vehicle"]["id"] == vehicle_id


async def test_evidence_pack_includes_compliance_documents(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Evidence Compliance Tenant")
    resp = await _create_vehicle(client, headers)
    vehicle_id = resp.json()["id"]

    resp = await client.post(
        "/v1/compliance/documents",
        data={"vehicle_id": vehicle_id, "doc_type": "calibration_record"},
        files={"file": ("cert.pdf", b"%PDF-1.4 fake cert", "application/pdf")},
        headers=headers,
    )
    assert resp.status_code == 201

    resp = await client.get(f"/v1/fleet/vehicles/{vehicle_id}/evidence-pack", headers=headers)
    zf = _zip_from(resp)

    compliance = _read_json(zf, "compliance/manifest.json")
    assert compliance["count"] == 1
    assert compliance["note"] is None
    packed_name = compliance["items"][0]["packed_as"]
    assert packed_name is not None
    assert zf.read(packed_name) == b"%PDF-1.4 fake cert"


async def test_evidence_pack_includes_device_version_history(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Evidence Device Tenant")
    resp = await _create_vehicle(client, headers)
    vehicle_id = resp.json()["id"]

    resp = await client.post(
        "/v1/fleet/devices",
        json={"android_id": "evidence-android-1", "vehicle_id": vehicle_id},
        headers=headers,
    )
    device_id = resp.json()["id"]

    await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat", json={"app_version": "1.0.0"}, headers=headers
    )
    await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat", json={"app_version": "1.0.0"}, headers=headers
    )
    await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat", json={"app_version": "1.1.0"}, headers=headers
    )

    resp = await client.get(f"/v1/fleet/vehicles/{vehicle_id}/evidence-pack", headers=headers)
    zf = _zip_from(resp)
    device_versions = _read_json(zf, "device_versions/report.json")

    assert device_versions["history_count"] == 2
    versions = [h["app_version"] for h in device_versions["history"]]
    assert versions == ["1.0.0", "1.1.0"]
    assert device_versions["devices"][0]["current_app_version"] == "1.1.0"


async def test_evidence_pack_is_tenant_scoped(client, session):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Evidence Scope A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Evidence Scope B")
    resp = await _create_vehicle(client, headers_a)
    vehicle_id = resp.json()["id"]

    resp = await client.get(f"/v1/fleet/vehicles/{vehicle_id}/evidence-pack", headers=headers_b)
    assert resp.status_code == 404


async def test_evidence_pack_404_for_unknown_vehicle(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await client.get("/v1/fleet/vehicles/does-not-exist/evidence-pack", headers=headers)
    assert resp.status_code == 404
