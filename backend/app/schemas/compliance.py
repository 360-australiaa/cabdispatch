"""Pydantic v2 schemas for the Compliance Vault domain.

Note: `POST /v1/compliance/documents` is a `multipart/form-data` upload (a
file plus a handful of form fields), not a JSON body, so there is no
`ComplianceDocumentCreate` schema — see `app.api.v1.compliance.upload_document`
for the `Form(...)`/`File(...)` parameters accepted instead. `ComplianceDocumentRead`
and `ComplianceDocumentUpdate` below cover the rest of the CRUD surface.
"""
from __future__ import annotations

from datetime import datetime
from typing import Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field

DocType = Literal[
    "calibration_record",
    "mounting_photo",
    "accuracy_test",
    "cl14_checklist",
    "camera_register",
    "duress_register",
    "tracking_register",
]

# --- Pagination (local to this domain, same shape as app.schemas.fleet.Page,
# until a shared one exists in app.core) -----------------------------------------

T = TypeVar("T")


class Page(BaseModel, Generic[T]):
    items: list[T]
    total: int
    skip: int
    limit: int


# --- ComplianceDocument -------------------------------------------------------


class ComplianceDocumentUpdate(BaseModel):
    """Partial update — every field optional. Corrections only (e.g. fixing a
    mis-tagged doc_type or amending notes); the underlying file/vehicle/uploader
    are not changeable — delete and re-upload instead."""

    doc_type: DocType | None = None
    notes: str | None = Field(default=None, max_length=2000)


class ComplianceDocumentRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    vehicle_id: str
    doc_type: DocType
    file_path: str
    original_filename: str
    content_type: str | None
    uploaded_by: str
    uploaded_at: datetime
    notes: str | None
    created_at: datetime
    updated_at: datetime


# --- Vehicle compliance dossier (cl.14 checklist summary) ----------------------


class ChecklistItemRead(BaseModel):
    """One line of the cl.14-derived checklist for a vehicle. `doc_types`
    lists which `doc_type` value(s) satisfy this item — `tamper_measures` is a
    composite of the three tamper-related registers (see
    `app.services.compliance.CL14_CHECKLIST`)."""

    key: str
    label: str
    doc_types: list[DocType]
    satisfied: bool
    document_count: int
    documents: list[ComplianceDocumentRead]


class ComplianceDossierRead(BaseModel):
    """Response for `GET /v1/compliance/vehicles/{vehicle_id}/dossier`.

    TODO(compliance-vault): a real PDF render of this dossier (the artifact an
    operator would actually hand to a P2P Commissioner inspector) is out of
    scope for this pass — this endpoint returns the equivalent JSON summary
    only. A follow-up should add a `GET .../dossier.pdf` variant.
    """

    tenant_id: str
    vehicle_id: str
    generated_at: datetime
    items: list[ChecklistItemRead]
    overall_compliant: bool
    missing_items: list[str]
