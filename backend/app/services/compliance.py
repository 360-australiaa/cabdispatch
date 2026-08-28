"""Compliance Vault business logic: local-disk file storage for uploaded
documents (mirrors the sibling captaindash project's local-disk-upload
convention -- no S3 in this environment), the cl.14-checklist dossier
aggregation, and a real PDF render of that dossier (via the pure-Python
fpdf2 library -- same dependency and rendering convention as
app.services.receipts; no new PDF library was added).
"""
from __future__ import annotations

import os
import uuid
from pathlib import Path

from fpdf import FPDF
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.compliance import (
    DOC_TYPE_ACCURACY_TEST,
    DOC_TYPE_CALIBRATION_RECORD,
    DOC_TYPE_CAMERA_REGISTER,
    DOC_TYPE_DURESS_REGISTER,
    DOC_TYPE_MOUNTING_PHOTO,
    DOC_TYPE_TRACKING_REGISTER,
    ComplianceDocument,
)
from app.models.fleet import Vehicle

# Backend root: app/services/compliance.py -> parents[0]=services, [1]=app,
# [2]=backend project root. Uploads live at "<backend root>/uploads/...".
BACKEND_ROOT = Path(__file__).resolve().parents[2]
UPLOADS_ROOT = BACKEND_ROOT / "uploads"


class ComplianceError(Exception):
    """Base class for compliance-domain errors; the router translates each
    subclass to the appropriate HTTP status."""


class DocumentNotFoundError(ComplianceError):
    pass


class VehicleNotFoundError(ComplianceError):
    """Raised by get_vehicle_or_404 -- used only by the PDF dossier route.
    The JSON dossier route (build_dossier) deliberately does NOT raise this:
    a vehicle with zero documents simply comes back fully non-compliant.
    The PDF route is different: rendering a compliance handout for a
    vehicle_id that does not correspond to a real fleet.Vehicle row for this
    tenant would produce a misleading document, so it 404s instead."""

    pass


class InvalidUploadError(ComplianceError):
    pass


# --- file storage --------------------------------------------------------------


def _safe_component(value: str, *, max_len: int = 80) -> str:
    """Strips anything that could act as a path separator/traversal token from
    a value about to become part of an on-disk path (tenant_id/vehicle_id are
    normally UUIDs, but this is cheap insurance against a malformed one)."""
    cleaned = "".join(c for c in value if c.isalnum() or c in "-_.")
    cleaned = cleaned.strip(".") or "unknown"
    return cleaned[:max_len]


def vehicle_upload_dir(*, tenant_id: str, vehicle_id: str) -> Path:
    return UPLOADS_ROOT / _safe_component(tenant_id) / _safe_component(vehicle_id)


async def save_upload(
    *,
    tenant_id: str,
    vehicle_id: str,
    original_filename: str,
    content: bytes,
) -> str:
    """Writes `content` under `uploads/{tenant_id}/{vehicle_id}/`, creating the
    directory tree if missing, and returns the *relative* (to `BACKEND_ROOT`)
    file_path to persist on the `ComplianceDocument` row — never the absolute
    path, so the vault stays portable across machines/deployments.
    """
    if not content:
        raise InvalidUploadError("Uploaded file is empty")

    target_dir = vehicle_upload_dir(tenant_id=tenant_id, vehicle_id=vehicle_id)
    target_dir.mkdir(parents=True, exist_ok=True)

    # basename() strips any directory components the client-supplied filename
    # might (maliciously or not) contain; uuid-prefixing guarantees uniqueness
    # even when two uploads share the same original filename.
    safe_name = _safe_component(os.path.basename(original_filename), max_len=200) or "upload"
    stored_name = f"{uuid.uuid4().hex}_{safe_name}"
    absolute_path = target_dir / stored_name
    absolute_path.write_bytes(content)

    return absolute_path.relative_to(BACKEND_ROOT).as_posix()


def resolve_absolute_path(file_path: str) -> Path:
    """Resolves a stored (relative) `file_path` back to an absolute path for
    streaming/deletion. Rejects anything that would escape `BACKEND_ROOT`."""
    absolute_path = (BACKEND_ROOT / file_path).resolve()
    if BACKEND_ROOT not in absolute_path.parents and absolute_path != BACKEND_ROOT:
        raise InvalidUploadError("Stored file_path resolves outside the uploads root")
    return absolute_path


def delete_file_best_effort(file_path: str) -> None:
    """Deletes the on-disk file for a document being removed. Best-effort —
    a missing file (already cleaned up, moved, etc.) must not block the DB
    delete from succeeding."""
    try:
        absolute_path = resolve_absolute_path(file_path)
        absolute_path.unlink(missing_ok=True)
    except (InvalidUploadError, OSError):
        pass


# --- lookups ---------------------------------------------------------------


async def get_document_or_404(
    session: AsyncSession, *, tenant_id: str, document_id: str
) -> ComplianceDocument:
    result = await session.execute(
        select(ComplianceDocument).where(
            ComplianceDocument.id == document_id, ComplianceDocument.tenant_id == tenant_id
        )
    )
    document = result.scalar_one_or_none()
    if document is None:
        raise DocumentNotFoundError(document_id)
    return document


async def get_vehicle_or_404(session: AsyncSession, *, tenant_id: str, vehicle_id: str) -> Vehicle:
    """Tenant-scoped lookup of the fleet.Vehicle row -- used only by the PDF
    dossier route (see VehicleNotFoundError's docstring for why the JSON
    dossier route does not do this same check)."""
    result = await session.execute(select(Vehicle).where(Vehicle.id == vehicle_id, Vehicle.tenant_id == tenant_id))
    vehicle = result.scalar_one_or_none()
    if vehicle is None:
        raise VehicleNotFoundError(vehicle_id)
    return vehicle


# --- cl.14 checklist dossier ------------------------------------------------

# The four cl.14 checklist items this dossier is built against (task spec:
# "calibration, mounting, accuracy test, tamper measures"). `tamper_measures`
# is a composite of the three tamper-related registers -- ASSUMPTION: full
# compliance for that line item requires ALL THREE registers on file (camera,
# duress/panic-button, GPS tracking), not just one, since each documents a
# distinct piece of tamper-evidence hardware. See render_dossier_pdf below
# for the real PDF render of this checklist.
CL14_CHECKLIST: list[dict] = [
    {
        "key": "calibration",
        "label": "Meter calibration record",
        "doc_types": [DOC_TYPE_CALIBRATION_RECORD],
    },
    {
        "key": "mounting",
        "label": "Meter/camera mounting photo",
        "doc_types": [DOC_TYPE_MOUNTING_PHOTO],
    },
    {
        "key": "accuracy_test",
        "label": "Accuracy test result",
        "doc_types": [DOC_TYPE_ACCURACY_TEST],
    },
    {
        "key": "tamper_measures",
        "label": "Tamper-evidence measures (camera, duress button, GPS tracking registers)",
        "doc_types": [DOC_TYPE_CAMERA_REGISTER, DOC_TYPE_DURESS_REGISTER, DOC_TYPE_TRACKING_REGISTER],
    },
]


async def build_dossier(session: AsyncSession, *, tenant_id: str, vehicle_id: str) -> dict:
    """Aggregates every `ComplianceDocument` on file for this vehicle into the
    cl.14 checklist shape. Does not verify the vehicle itself exists (this
    domain does not own the `fleet.Vehicle` table — see module docstring in
    `app.models.compliance`); a vehicle with zero documents simply comes back
    fully non-compliant rather than 404ing.
    """
    from datetime import UTC, datetime

    result = await session.execute(
        select(ComplianceDocument).where(
            ComplianceDocument.tenant_id == tenant_id,
            ComplianceDocument.vehicle_id == vehicle_id,
        )
    )
    documents = list(result.scalars().all())
    by_doc_type: dict[str, list[ComplianceDocument]] = {}
    for doc in documents:
        by_doc_type.setdefault(doc.doc_type, []).append(doc)

    items = []
    missing_items = []
    for entry in CL14_CHECKLIST:
        matched: list[ComplianceDocument] = []
        for dt in entry["doc_types"]:
            matched.extend(by_doc_type.get(dt, []))
        # AND semantics across every required doc_type for this item (see
        # CL14_CHECKLIST docstring above for the tamper_measures rationale).
        satisfied = all(by_doc_type.get(dt) for dt in entry["doc_types"])

        items.append(
            {
                "key": entry["key"],
                "label": entry["label"],
                "doc_types": entry["doc_types"],
                "satisfied": satisfied,
                "document_count": len(matched),
                "documents": matched,
            }
        )
        if not satisfied:
            missing_items.append(entry["label"])

    return {
        "tenant_id": tenant_id,
        "vehicle_id": vehicle_id,
        "generated_at": datetime.now(UTC),
        "items": items,
        "overall_compliant": len(missing_items) == 0,
        "missing_items": missing_items,
    }


# --- PDF rendering -----------------------------------------------------------


def render_dossier_pdf(dossier: dict) -> bytes:
    """Pure rendering step (no I/O) -- a real PDF render of the cl.14
    checklist dossier (the artifact an operator would actually hand to a P2P
    Commissioner inspector), replacing the JSON-only summary that used to be
    the only shape this domain returned. Takes the exact dict shape
    build_dossier returns, optionally with a "vehicle_rego" key merged in
    by the caller (the PDF route looks the rego up via get_vehicle_or_404
    before calling this; build_dossier itself has no fleet.Vehicle access).
    Mirrors app.services.receipts._render_pdf_bytes's exact
    FPDF(format="A4") construction and layout-call conventions (header +
    section blocks, new_x="LMARGIN", new_y="NEXT" cell chaining) rather than
    inventing a new visual style, and is trivially unit-testable in isolation
    the same way that function is -- no DB/session access here.
    """
    pdf = FPDF(format="A4")
    pdf.set_auto_page_break(auto=True, margin=15)
    pdf.add_page()

    # --- Header ---
    pdf.set_font("Helvetica", "B", 18)
    pdf.cell(0, 10, "VEHICLE COMPLIANCE DOSSIER", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", "", 8)
    pdf.set_text_color(120, 120, 120)
    pdf.cell(
        0,
        5,
        "NSW Point to Point Transport Commissioner -- cl.14 checklist summary",
        new_x="LMARGIN",
        new_y="NEXT",
    )
    pdf.set_text_color(0, 0, 0)
    pdf.ln(3)

    # --- Vehicle ---
    pdf.set_font("Helvetica", "B", 11)
    pdf.cell(0, 7, "Vehicle", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", "", 10)
    vehicle_rego = dossier.get("vehicle_rego")
    if vehicle_rego:
        pdf.cell(0, 6, f"Rego: {vehicle_rego}", new_x="LMARGIN", new_y="NEXT")
    pdf.cell(0, 6, f"Vehicle ID: {dossier['vehicle_id']}", new_x="LMARGIN", new_y="NEXT")
    generated_at = dossier["generated_at"]
    generated_at_str = (
        generated_at.strftime("%Y-%m-%d %H:%M UTC") if hasattr(generated_at, "strftime") else str(generated_at)
    )
    pdf.cell(0, 6, f"Generated: {generated_at_str}", new_x="LMARGIN", new_y="NEXT")
    pdf.ln(3)

    # --- Overall status ---
    pdf.set_font("Helvetica", "B", 12)
    overall_compliant = dossier["overall_compliant"]
    if overall_compliant:
        pdf.set_text_color(0, 120, 0)
    else:
        pdf.set_text_color(180, 0, 0)
    pdf.cell(
        0,
        8,
        f"Overall status: {'COMPLIANT' if overall_compliant else 'NOT COMPLIANT'}",
        new_x="LMARGIN",
        new_y="NEXT",
    )
    pdf.set_text_color(0, 0, 0)
    pdf.ln(3)

    # --- Checklist ---
    pdf.set_font("Helvetica", "B", 11)
    pdf.cell(0, 7, "Checklist", new_x="LMARGIN", new_y="NEXT")

    for item in dossier["items"]:
        satisfied = item["satisfied"]
        pdf.set_font("Helvetica", "B", 10)
        pdf.cell(140, 7, item["label"])
        if satisfied:
            pdf.set_text_color(0, 120, 0)
        else:
            pdf.set_text_color(180, 0, 0)
        pdf.cell(0, 7, "OK" if satisfied else "MISSING", new_x="LMARGIN", new_y="NEXT", align="R")
        pdf.set_text_color(0, 0, 0)

        pdf.set_font("Helvetica", "", 9)
        pdf.cell(0, 5, f"Documents on file: {item['document_count']}", new_x="LMARGIN", new_y="NEXT")
        for doc in item["documents"]:
            uploaded_str = doc.uploaded_at.strftime("%Y-%m-%d") if doc.uploaded_at else "-"
            pdf.cell(
                0,
                5,
                f"  - {doc.doc_type}: {doc.original_filename} (uploaded {uploaded_str})",
                new_x="LMARGIN",
                new_y="NEXT",
            )
        pdf.ln(2)

    # --- Missing items summary ---
    missing_items = dossier["missing_items"]
    if missing_items:
        pdf.ln(1)
        pdf.set_font("Helvetica", "B", 11)
        pdf.cell(0, 7, "Missing items", new_x="LMARGIN", new_y="NEXT")
        pdf.set_font("Helvetica", "", 10)
        for label in missing_items:
            pdf.cell(0, 6, f"- {label}", new_x="LMARGIN", new_y="NEXT")

    return bytes(pdf.output())
