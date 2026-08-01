"""Compliance Vault business logic: local-disk file storage for uploaded
documents (mirrors the sibling captaindash project's local-disk-upload
convention — no S3 in this environment) and the cl.14-checklist dossier
aggregation.
"""
from __future__ import annotations

import os
import uuid
from pathlib import Path

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

# Backend root: app/services/compliance.py -> parents[0]=services, [1]=app,
# [2]=backend project root. Uploads live at "<backend root>/uploads/...".
BACKEND_ROOT = Path(__file__).resolve().parents[2]
UPLOADS_ROOT = BACKEND_ROOT / "uploads"


class ComplianceError(Exception):
    """Base class for compliance-domain errors; the router translates each
    subclass to the appropriate HTTP status."""


class DocumentNotFoundError(ComplianceError):
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


# --- cl.14 checklist dossier ------------------------------------------------

# The four cl.14 checklist items this dossier is built against (task spec:
# "calibration, mounting, accuracy test, tamper measures"). `tamper_measures`
# is a composite of the three tamper-related registers — ASSUMPTION: full
# compliance for that line item requires ALL THREE registers on file (camera,
# duress/panic-button, GPS tracking), not just one, since each documents a
# distinct piece of tamper-evidence hardware. A real PDF render of this
# checklist is out of scope for this pass (see
# `app.schemas.compliance.ComplianceDossierRead`'s TODO).
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
