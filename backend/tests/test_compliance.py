"""Tests for the Compliance Vault domain.

NOTE: `app/api/v1/compliance.py`'s router is not registered in `app.main` yet —
a later integration step does that. Until then, every request in this file
404s (FastAPI has no route for it) rather than exercising real behaviour. That
is expected; these tests are written correctly against the endpoints as built
so they pass once the integration step wires `compliance_router` into
`app.main`.

Importing `app.models.compliance` below (even though nothing else in this file
uses the name directly) is required so that table lands on `Base.metadata`
before the session-scoped `_test_database` fixture in conftest.py runs
`create_all` — `app/models/__init__.py` doesn't import this domain's models
yet either, for the same "integration step wires it up" reason.
"""
from __future__ import annotations

import uuid

import pytest

from app.models import Tenant
from app.models.compliance import ComplianceDocument  # noqa: F401
from app.models.fleet import Vehicle
from app.services.compliance import BACKEND_ROOT
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


# --- helpers --------------------------------------------------------------------


def _file(name: str = "cert.pdf", content: bytes = b"%PDF-1.4 fake calibration cert", ctype: str = "application/pdf"):
    return {"file": (name, content, ctype)}


async def _upload(client, headers, *, vehicle_id, doc_type="calibration_record", notes=None, **files_override):
    data = {"vehicle_id": vehicle_id, "doc_type": doc_type}
    if notes is not None:
        data["notes"] = notes
    files = files_override or _file()
    return await client.post("/v1/compliance/documents", data=data, files=files, headers=headers)


# --- upload / get / list --------------------------------------------------------


async def test_upload_and_get_document(client, session):
    headers = await auth_headers(client, session, role="admin")
    vehicle_id = str(uuid.uuid4())

    resp = await _upload(client, headers, vehicle_id=vehicle_id, notes="annual cal")
    assert resp.status_code == 201
    body = resp.json()
    assert body["vehicle_id"] == vehicle_id
    assert body["doc_type"] == "calibration_record"
    assert body["original_filename"] == "cert.pdf"
    assert body["content_type"] == "application/pdf"
    assert body["notes"] == "annual cal"
    assert body["file_path"].startswith("uploads/")
    document_id = body["id"]

    # File actually landed on disk under uploads/{tenant_id}/{vehicle_id}/.
    on_disk = BACKEND_ROOT / body["file_path"]
    assert on_disk.is_file()
    assert on_disk.read_bytes() == b"%PDF-1.4 fake calibration cert"

    resp = await client.get(f"/v1/compliance/documents/{document_id}", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["id"] == document_id


async def test_upload_requires_write_role(client, session):
    headers = await auth_headers(client, session, role="driver")
    resp = await _upload(client, headers, vehicle_id=str(uuid.uuid4()))
    assert resp.status_code == 403


async def test_upload_rejects_invalid_doc_type(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await _upload(client, headers, vehicle_id=str(uuid.uuid4()), doc_type="not_a_real_type")
    assert resp.status_code == 400


async def test_upload_rejects_empty_file(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await _upload(client, headers, vehicle_id=str(uuid.uuid4()), file=("empty.pdf", b"", "application/pdf"))
    assert resp.status_code == 400


async def test_document_is_tenant_isolated(client, session):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Tenant A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Tenant B")
    vehicle_id = str(uuid.uuid4())

    resp = await _upload(client, headers_a, vehicle_id=vehicle_id)
    document_id = resp.json()["id"]

    resp = await client.get(f"/v1/compliance/documents/{document_id}", headers=headers_b)
    assert resp.status_code == 404


async def test_list_documents_filters_by_vehicle_and_doc_type(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="List Tenant")
    vehicle_a = str(uuid.uuid4())
    vehicle_b = str(uuid.uuid4())

    await _upload(client, headers, vehicle_id=vehicle_a, doc_type="calibration_record")
    await _upload(client, headers, vehicle_id=vehicle_a, doc_type="mounting_photo")
    await _upload(client, headers, vehicle_id=vehicle_b, doc_type="calibration_record")

    resp = await client.get(f"/v1/compliance/documents?vehicle_id={vehicle_a}", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] == 2

    resp = await client.get(
        f"/v1/compliance/documents?vehicle_id={vehicle_a}&doc_type=mounting_photo", headers=headers
    )
    body = resp.json()
    assert body["total"] == 1
    assert body["items"][0]["doc_type"] == "mounting_photo"

    resp = await client.get("/v1/compliance/documents?limit=2&skip=0", headers=headers)
    body = resp.json()
    assert body["total"] == 3
    assert len(body["items"]) == 2


# --- update / delete --------------------------------------------------------------


async def test_update_document_notes_and_doc_type(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await _upload(client, headers, vehicle_id=str(uuid.uuid4()), doc_type="mounting_photo")
    document_id = resp.json()["id"]

    resp = await client.patch(
        f"/v1/compliance/documents/{document_id}",
        json={"notes": "re-tagged after review", "doc_type": "accuracy_test"},
        headers=headers,
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["notes"] == "re-tagged after review"
    assert body["doc_type"] == "accuracy_test"


async def test_update_requires_write_role(client, session):
    admin_headers = await auth_headers(client, session, role="admin", tenant_name="Update Role Tenant")
    driver_headers = await auth_headers(client, session, role="driver", tenant_id=None)
    resp = await _upload(client, admin_headers, vehicle_id=str(uuid.uuid4()))
    document_id = resp.json()["id"]

    resp = await client.patch(
        f"/v1/compliance/documents/{document_id}", json={"notes": "nope"}, headers=driver_headers
    )
    assert resp.status_code == 403


async def test_delete_document_removes_row_and_file(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await _upload(client, headers, vehicle_id=str(uuid.uuid4()))
    document_id = resp.json()["id"]
    on_disk = BACKEND_ROOT / resp.json()["file_path"]
    assert on_disk.is_file()

    resp = await client.delete(f"/v1/compliance/documents/{document_id}", headers=headers)
    assert resp.status_code == 204
    assert not on_disk.exists()

    resp = await client.get(f"/v1/compliance/documents/{document_id}", headers=headers)
    assert resp.status_code == 404


async def test_delete_requires_write_role(client, session):
    admin_headers = await auth_headers(client, session, role="admin", tenant_name="Delete Role Tenant")
    driver_headers = await auth_headers(client, session, role="driver", tenant_id=None)
    resp = await _upload(client, admin_headers, vehicle_id=str(uuid.uuid4()))
    document_id = resp.json()["id"]

    resp = await client.delete(f"/v1/compliance/documents/{document_id}", headers=driver_headers)
    assert resp.status_code == 403


# --- download ----------------------------------------------------------------------


async def test_download_document_streams_original_content(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await _upload(
        client, headers, vehicle_id=str(uuid.uuid4()), file=("photo.jpg", b"\xff\xd8\xfffakejpeg", "image/jpeg")
    )
    document_id = resp.json()["id"]

    resp = await client.get(f"/v1/compliance/documents/{document_id}/download", headers=headers)
    assert resp.status_code == 200
    assert resp.content == b"\xff\xd8\xfffakejpeg"
    assert resp.headers["content-type"] == "image/jpeg"
    assert "photo.jpg" in resp.headers.get("content-disposition", "")


async def test_download_is_tenant_isolated(client, session):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Download Tenant A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Download Tenant B")
    resp = await _upload(client, headers_a, vehicle_id=str(uuid.uuid4()))
    document_id = resp.json()["id"]

    resp = await client.get(f"/v1/compliance/documents/{document_id}/download", headers=headers_b)
    assert resp.status_code == 404


# --- dossier -------------------------------------------------------------------


async def test_dossier_reports_all_missing_for_untouched_vehicle(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Dossier Empty Tenant")
    vehicle_id = str(uuid.uuid4())

    resp = await client.get(f"/v1/compliance/vehicles/{vehicle_id}/dossier", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["vehicle_id"] == vehicle_id
    assert body["overall_compliant"] is False
    assert len(body["missing_items"]) == 4
    assert all(item["satisfied"] is False for item in body["items"])


async def test_dossier_tamper_measures_requires_all_three_registers(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Dossier Tamper Tenant")
    vehicle_id = str(uuid.uuid4())

    await _upload(client, headers, vehicle_id=vehicle_id, doc_type="calibration_record")
    await _upload(client, headers, vehicle_id=vehicle_id, doc_type="mounting_photo")
    await _upload(client, headers, vehicle_id=vehicle_id, doc_type="accuracy_test")
    await _upload(client, headers, vehicle_id=vehicle_id, doc_type="camera_register")
    await _upload(client, headers, vehicle_id=vehicle_id, doc_type="duress_register")

    resp = await client.get(f"/v1/compliance/vehicles/{vehicle_id}/dossier", headers=headers)
    body = resp.json()
    assert body["overall_compliant"] is False
    tamper_item = next(i for i in body["items"] if i["key"] == "tamper_measures")
    assert tamper_item["satisfied"] is False
    assert tamper_item["document_count"] == 2

    await _upload(client, headers, vehicle_id=vehicle_id, doc_type="tracking_register")

    resp = await client.get(f"/v1/compliance/vehicles/{vehicle_id}/dossier", headers=headers)
    body = resp.json()
    assert body["overall_compliant"] is True
    assert body["missing_items"] == []
    tamper_item = next(i for i in body["items"] if i["key"] == "tamper_measures")
    assert tamper_item["satisfied"] is True
    assert tamper_item["document_count"] == 3


async def test_dossier_is_tenant_scoped(client, session):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Dossier Scope A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Dossier Scope B")
    vehicle_id = str(uuid.uuid4())

    await _upload(client, headers_a, vehicle_id=vehicle_id, doc_type="calibration_record")

    resp = await client.get(f"/v1/compliance/vehicles/{vehicle_id}/dossier", headers=headers_b)
    body = resp.json()
    calibration_item = next(i for i in body["items"] if i["key"] == "calibration")
    assert calibration_item["satisfied"] is False  # tenant B sees none of tenant A's docs


# --- dossier PDF ---------------------------------------------------------------


async def _tenant_and_headers(client, session, *, role="admin", tenant_name="Dossier PDF Tenant"):
    tenant = Tenant(name=tenant_name, plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    headers = await auth_headers(client, session, role=role, tenant_id=tenant.id)
    return tenant.id, headers


async def _make_vehicle(session, *, tenant_id, rego="TX-DOSSIER"):
    vehicle = Vehicle(tenant_id=tenant_id, rego=rego, vehicle_class="standard", status="active")
    session.add(vehicle)
    await session.commit()
    await session.refresh(vehicle)
    return vehicle


async def test_dossier_pdf_returns_real_pdf_for_seeded_vehicle(client, session):
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Dossier PDF Seeded Tenant")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-PDF1")

    await _upload(client, headers, vehicle_id=vehicle.id, doc_type="calibration_record")
    await _upload(client, headers, vehicle_id=vehicle.id, doc_type="mounting_photo")

    resp = await client.get(f"/v1/compliance/vehicles/{vehicle.id}/dossier.pdf", headers=headers)
    assert resp.status_code == 200
    assert resp.headers["content-type"] == "application/pdf"
    assert resp.content.startswith(b"%PDF")
    assert len(resp.content) > 0


async def test_dossier_pdf_404_for_unknown_vehicle(client, session):
    _tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Dossier PDF Unknown Tenant")
    unknown_vehicle_id = str(uuid.uuid4())

    resp = await client.get(f"/v1/compliance/vehicles/{unknown_vehicle_id}/dossier.pdf", headers=headers)
    assert resp.status_code == 404


async def test_dossier_pdf_is_tenant_isolated(client, session):
    tenant_a_id, headers_a = await _tenant_and_headers(client, session, tenant_name="Dossier PDF Tenant A")
    _tenant_b_id, headers_b = await _tenant_and_headers(client, session, tenant_name="Dossier PDF Tenant B")
    vehicle = await _make_vehicle(session, tenant_id=tenant_a_id, rego="TX-PDF2")

    await _upload(client, headers_a, vehicle_id=vehicle.id, doc_type="calibration_record")

    resp = await client.get(f"/v1/compliance/vehicles/{vehicle.id}/dossier.pdf", headers=headers_a)
    assert resp.status_code == 200
    assert resp.content.startswith(b"%PDF")

    resp = await client.get(f"/v1/compliance/vehicles/{vehicle.id}/dossier.pdf", headers=headers_b)
    assert resp.status_code == 404
