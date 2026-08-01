"""Compliance Vault domain model: `ComplianceDocument` — the per-vehicle store
of regulatory evidence (calibration certificates, mounting photos, accuracy
test results, the physical cl.14 checklist scan, and the camera/duress/tracking
device registers) that NSW Point to Point Transport Commissioner inspections
(TCT-METER-01 spec, section B3) require a taxi operator to produce on demand.

Multi-tenancy note: standard `TenantScopedMixin` — every row always carries a
real tenant_id.

DEVIATION (flagged per task instructions, matching the precedent already set by
the sibling `duress` domain's `vehicle_id`/`driver_id` columns): `vehicle_id` is
a plain indexed `String(36)` column with NO ForeignKey constraint. It *could*
validly FK `vehicles.id` (owned by the sibling `fleet` domain), but is left
unconstrained so `Base.metadata.create_all` / Alembic autogenerate stay
order-independent of tables this domain doesn't own (this agent must not touch
`app/models/__init__.py`, so it cannot guarantee the `fleet` domain's models are
registered on `Base.metadata` when this module is imported in isolation).
Multi-tenant + per-vehicle isolation is enforced at the application layer (via
`get_current_tenant_id` plus an explicit `vehicle_id` filter), not via a DB FK.
`uploaded_by` DOES carry a real FK to `users.id` — `User` is a foundation model
guaranteed to already be on `Base.metadata` (see `app.models.psl_ledger`'s
`driver_id` for the same precedent).

DEVIATION (flagged): the task's field list was `(id, tenant_id, vehicle_id,
doc_type, file_path, uploaded_by, uploaded_at, notes)`. Two columns were added
beyond that list — `original_filename` and `content_type` — captured verbatim
from the multipart upload at `POST /v1/compliance/documents` time so the
download endpoint can send an accurate `Content-Disposition` filename and
`Content-Type` instead of guessing from the stored (uuid-randomized) on-disk
name. Same spirit as the `fleet` domain's `force_update_pending` addition.
"""
from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin, TimestampMixin

# --- doc_type enum (plain string constants — sqlite/postgres portable, same
# convention as app.models.fleet's VEHICLE_CLASS_* / VEHICLE_STATUS_*) ----------

DOC_TYPE_CALIBRATION_RECORD = "calibration_record"
DOC_TYPE_MOUNTING_PHOTO = "mounting_photo"
DOC_TYPE_ACCURACY_TEST = "accuracy_test"
DOC_TYPE_CL14_CHECKLIST = "cl14_checklist"
DOC_TYPE_CAMERA_REGISTER = "camera_register"
DOC_TYPE_DURESS_REGISTER = "duress_register"
DOC_TYPE_TRACKING_REGISTER = "tracking_register"

VALID_DOC_TYPES = (
    DOC_TYPE_CALIBRATION_RECORD,
    DOC_TYPE_MOUNTING_PHOTO,
    DOC_TYPE_ACCURACY_TEST,
    DOC_TYPE_CL14_CHECKLIST,
    DOC_TYPE_CAMERA_REGISTER,
    DOC_TYPE_DURESS_REGISTER,
    DOC_TYPE_TRACKING_REGISTER,
)


class ComplianceDocument(Base, TimestampMixin, TenantScopedMixin):
    __tablename__ = "compliance_documents"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))

    # Unconstrained cross-domain ref — see module docstring.
    vehicle_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)

    doc_type: Mapped[str] = mapped_column(String(30), nullable=False, index=True)

    # Relative to the backend's `uploads/` root, e.g.
    # "uploads/<tenant_id>/<vehicle_id>/<uuid>_<original_filename>". See
    # app.services.compliance for the exact save/resolve logic.
    file_path: Mapped[str] = mapped_column(String(500), nullable=False)

    original_filename: Mapped[str] = mapped_column(String(255), nullable=False)  # see deviation note
    content_type: Mapped[str | None] = mapped_column(String(150), nullable=True)  # see deviation note

    uploaded_by: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False, index=True)
    uploaded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
