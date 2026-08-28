"""Compliance Vault router: per-vehicle regulatory document CRUD (calibration
records, mounting photos, accuracy tests, the cl.14 checklist scan, and the
camera/duress/tracking registers), local-disk file upload/download, and the
cl.14 checklist dossier summary.

Every query in this file is filtered by `tenant_id` resolved via
`get_current_tenant_id` — that is the sole multi-tenancy isolation mechanism in
this system (see app.core.security / app.core.database docstrings).

Full CRUD over `compliance_documents` (not append-only — a mis-tagged doc_type
or stale notes should be correctable, and a document should be deletable if
uploaded in error).
"""
from __future__ import annotations

from datetime import UTC, datetime

from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, UploadFile, status
from fastapi.responses import FileResponse, Response
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, get_current_user, require_role
from app.models.compliance import VALID_DOC_TYPES, ComplianceDocument
from app.models.user import User
from app.schemas.compliance import (
    ChecklistItemRead,
    ComplianceDocumentRead,
    ComplianceDocumentUpdate,
    ComplianceDossierRead,
    Page,
)
from app.services import compliance as compliance_service

router = APIRouter(prefix="/v1/compliance", tags=["compliance"])

# Roles permitted to upload/edit/delete compliance documents. Any authenticated
# tenant user may read (list/get/download/dossier) — e.g. a driver checking
# their own vehicle's compliance standing.
_WRITE_ROLES = ("owner", "admin", "dispatcher")
_require_write_role = require_role(*_WRITE_ROLES)


def _compliance_error_to_http(exc: compliance_service.ComplianceError) -> HTTPException:
    if isinstance(exc, compliance_service.DocumentNotFoundError):
        return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Compliance document not found")
    if isinstance(exc, compliance_service.VehicleNotFoundError):
        return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Vehicle not found")
    if isinstance(exc, compliance_service.InvalidUploadError):
        return HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))
    return HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))


# ==================================================================================
# Documents
# ==================================================================================


@router.get("/documents", response_model=Page[ComplianceDocumentRead])
async def list_documents(
    skip: int = Query(0, ge=0),
    limit: int = Query(20, ge=1, le=100),
    vehicle_id: str | None = Query(default=None),
    doc_type: str | None = Query(default=None),
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    stmt = select(ComplianceDocument).where(ComplianceDocument.tenant_id == tenant_id)
    count_stmt = (
        select(func.count()).select_from(ComplianceDocument).where(ComplianceDocument.tenant_id == tenant_id)
    )

    if vehicle_id is not None:
        stmt = stmt.where(ComplianceDocument.vehicle_id == vehicle_id)
        count_stmt = count_stmt.where(ComplianceDocument.vehicle_id == vehicle_id)
    if doc_type is not None:
        stmt = stmt.where(ComplianceDocument.doc_type == doc_type)
        count_stmt = count_stmt.where(ComplianceDocument.doc_type == doc_type)

    total = (await session.execute(count_stmt)).scalar_one()
    result = await session.execute(
        stmt.order_by(ComplianceDocument.uploaded_at.desc()).offset(skip).limit(limit)
    )
    items = result.scalars().all()
    return Page[ComplianceDocumentRead](items=items, total=total, skip=skip, limit=limit)


@router.post("/documents", response_model=ComplianceDocumentRead, status_code=status.HTTP_201_CREATED)
async def upload_document(
    vehicle_id: str = Form(...),
    doc_type: str = Form(...),
    notes: str | None = Form(default=None),
    file: UploadFile = File(...),
    tenant_id: str = Depends(get_current_tenant_id),
    user: User = Depends(_require_write_role),
    session: AsyncSession = Depends(get_session),
):
    """Multipart upload: saves the file under
    `uploads/{tenant_id}/{vehicle_id}/` (created if missing) on local disk (no
    S3 in this environment — matches the sibling captaindash project's
    local-disk-upload convention) and stores the *relative* path on the row.
    """
    if doc_type not in VALID_DOC_TYPES:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"doc_type must be one of: {', '.join(VALID_DOC_TYPES)}",
        )

    content = await file.read()
    try:
        relative_path = await compliance_service.save_upload(
            tenant_id=tenant_id,
            vehicle_id=vehicle_id,
            original_filename=file.filename or "upload",
            content=content,
        )
    except compliance_service.ComplianceError as exc:
        raise _compliance_error_to_http(exc) from exc

    document = ComplianceDocument(
        tenant_id=tenant_id,
        vehicle_id=vehicle_id,
        doc_type=doc_type,
        file_path=relative_path,
        original_filename=file.filename or "upload",
        content_type=file.content_type,
        uploaded_by=user.id,
        uploaded_at=datetime.now(UTC),
        notes=notes,
    )
    session.add(document)
    await session.commit()
    await session.refresh(document)
    return document


@router.get("/documents/{document_id}", response_model=ComplianceDocumentRead)
async def get_document(
    document_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    try:
        return await compliance_service.get_document_or_404(session, tenant_id=tenant_id, document_id=document_id)
    except compliance_service.ComplianceError as exc:
        raise _compliance_error_to_http(exc) from exc


@router.patch("/documents/{document_id}", response_model=ComplianceDocumentRead)
async def update_document(
    document_id: str,
    payload: ComplianceDocumentUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(_require_write_role),
    session: AsyncSession = Depends(get_session),
):
    """Corrections only (doc_type/notes) — the underlying file/vehicle/uploader
    are not changeable through this endpoint; delete and re-upload instead."""
    try:
        document = await compliance_service.get_document_or_404(
            session, tenant_id=tenant_id, document_id=document_id
        )
    except compliance_service.ComplianceError as exc:
        raise _compliance_error_to_http(exc) from exc

    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(document, field, value)

    await session.commit()
    await session.refresh(document)
    return document


@router.delete("/documents/{document_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_document(
    document_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(_require_write_role),
    session: AsyncSession = Depends(get_session),
):
    try:
        document = await compliance_service.get_document_or_404(
            session, tenant_id=tenant_id, document_id=document_id
        )
    except compliance_service.ComplianceError as exc:
        raise _compliance_error_to_http(exc) from exc

    file_path = document.file_path
    await session.delete(document)
    await session.commit()
    # Best-effort disk cleanup happens after the DB commit succeeds so a
    # filesystem hiccup never leaves the row deleted-but-uncommitted.
    compliance_service.delete_file_best_effort(file_path)


@router.get("/documents/{document_id}/download")
async def download_document(
    document_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    try:
        document = await compliance_service.get_document_or_404(
            session, tenant_id=tenant_id, document_id=document_id
        )
    except compliance_service.ComplianceError as exc:
        raise _compliance_error_to_http(exc) from exc

    try:
        absolute_path = compliance_service.resolve_absolute_path(document.file_path)
    except compliance_service.ComplianceError as exc:
        raise _compliance_error_to_http(exc) from exc

    if not absolute_path.is_file():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Document row exists but its file is missing on disk",
        )

    return FileResponse(
        path=absolute_path,
        filename=document.original_filename,
        media_type=document.content_type or "application/octet-stream",
    )


# ==================================================================================
# Vehicle dossier
# ==================================================================================


@router.get("/vehicles/{vehicle_id}/dossier", response_model=ComplianceDossierRead)
async def get_vehicle_dossier(
    vehicle_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    """Cl.14-checklist summary of which required doc types are on file for this
    vehicle and which are missing. See the sibling GET .../dossier.pdf route below
    for a real PDF render of this same data."""
    dossier = await compliance_service.build_dossier(session, tenant_id=tenant_id, vehicle_id=vehicle_id)
    return ComplianceDossierRead(
        tenant_id=dossier["tenant_id"],
        vehicle_id=dossier["vehicle_id"],
        generated_at=dossier["generated_at"],
        overall_compliant=dossier["overall_compliant"],
        missing_items=dossier["missing_items"],
        items=[ChecklistItemRead(**item) for item in dossier["items"]],
    )


@router.get("/vehicles/{vehicle_id}/dossier.pdf")
async def get_vehicle_dossier_pdf(
    vehicle_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> Response:
    """Real PDF render of the same cl.14-checklist dossier the JSON route
    above returns -- see app.services.compliance.render_dossier_pdf. Unlike
    that JSON route, this one 404s when vehicle_id does not correspond to a
    real fleet.Vehicle row for this tenant (see
    compliance_service.VehicleNotFoundError's docstring for why): handing an
    inspector a PDF for a vehicle that does not exist would be misleading in
    a way an empty JSON checklist is not.
    """
    try:
        vehicle = await compliance_service.get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=vehicle_id)
    except compliance_service.ComplianceError as exc:
        raise _compliance_error_to_http(exc) from exc

    dossier = await compliance_service.build_dossier(session, tenant_id=tenant_id, vehicle_id=vehicle_id)
    dossier["vehicle_rego"] = vehicle.rego
    pdf_bytes = compliance_service.render_dossier_pdf(dossier)

    return Response(
        content=pdf_bytes,
        media_type="application/pdf",
        headers={"Content-Disposition": f'inline; filename="dossier_{vehicle_id}.pdf"'},
    )
