"""Vehicle compliance evidence-pack export
(GET /v1/fleet/vehicles/{id}/evidence-pack).

Bundles everything an NSW Point to Point Transport Commissioner inspection
might ask for about ONE vehicle into a single ZIP: the compliance vault's
documents for that vehicle (app.models.compliance.ComplianceDocument), the
tariff version history for the vehicle's tenant (app.models.tariffs.Tariff),
the vehicle's paired device(s) firmware/app-version history
(app.models.fleet.DeviceVersionHistory -- see app.services.fleet.record_heartbeat
for how that table is populated), any tamper-evidence audit-log extract for
the vehicle (app.models.audit_log.AuditLog), and an installation-record
placeholder (no such table exists in this codebase yet).

This domain owns no table of its own -- every function here reads across
domains (compliance, tariffs, fleet, audit_log), same cross-domain-read
pattern already established by app.services.reports (see that module's
docstring). Every query is filtered by tenant_id -- the sole multi-tenancy
enforcement mechanism in this system.

EVERY category is ALWAYS represented in the ZIP, even when there is no data
for it -- silently omitting a category would look, to an inspector, exactly
like negligence rather than nothing to report (see each _build_* helper's
note field below). This is a named sales differentiator
(audit-readiness as the product), so completeness here matters more than
brevity.

DEVIATION (flagged): the task brief said "query Tariff rows for the
vehicle's tenant/region" -- but app.models.fleet.Vehicle has no region
column (Tariff.region -- urban/country/exempt -- is a property of the RATE
CARD, not of a vehicle; nothing in this schema links one specific vehicle to
one specific region). This bundles ALL of the vehicle's tenant's tariff rows
(every region), each tagged with its own region field, rather than
silently dropping the tariff history requirement or guessing a mapping
that does not exist in the data model.
"""
from __future__ import annotations

import json
import re
import zipfile
from datetime import UTC, datetime
from decimal import Decimal
from io import BytesIO
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.audit_log import AuditLog
from app.models.compliance import ComplianceDocument
from app.models.fleet import Device, DeviceVersionHistory, Vehicle
from app.models.tariffs import Tariff
from app.services import compliance as compliance_service

_UNSAFE_FILENAME_CHARS = re.compile(r"[^A-Za-z0-9._-]+")


def _safe_filename_component(value: str) -> str:
    cleaned = _UNSAFE_FILENAME_CHARS.sub("-", value).strip("-")
    return cleaned or "unknown"


def _json_default(value: Any) -> Any:
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, datetime):
        return value.isoformat()
    raise TypeError(f"Object of type {type(value).__name__} is not JSON serializable")


def _write_json(zf: zipfile.ZipFile, arcname: str, payload: dict) -> None:
    zf.writestr(arcname, json.dumps(payload, indent=2, default=_json_default, sort_keys=False))


async def _build_compliance_section(
    zf: zipfile.ZipFile, session: AsyncSession, *, tenant_id: str, vehicle_id: str
) -> dict:
    result = await session.execute(
        select(ComplianceDocument)
        .where(ComplianceDocument.tenant_id == tenant_id, ComplianceDocument.vehicle_id == vehicle_id)
        .order_by(ComplianceDocument.uploaded_at.asc())
    )
    documents = list(result.scalars().all())

    items = []
    for doc in documents:
        entry: dict[str, Any] = {
            "id": doc.id,
            "doc_type": doc.doc_type,
            "original_filename": doc.original_filename,
            "content_type": doc.content_type,
            "uploaded_by": doc.uploaded_by,
            "uploaded_at": doc.uploaded_at,
            "notes": doc.notes,
        }
        packed = False
        try:
            absolute_path = compliance_service.resolve_absolute_path(doc.file_path)
            if absolute_path.is_file():
                arcname = (
                    f"compliance/{_safe_filename_component(doc.doc_type)}_"
                    f"{doc.id}_{_safe_filename_component(doc.original_filename)}"
                )
                zf.write(absolute_path, arcname)
                entry["packed_as"] = arcname
                packed = True
        except compliance_service.ComplianceError:
            pass
        if not packed:
            entry["packed_as"] = None
            entry["file_missing_on_disk"] = True
        items.append(entry)

    note = None if documents else "No compliance documents on file for this vehicle."
    _write_json(
        zf,
        "compliance/manifest.json",
        {
            "category": "compliance_documents",
            "count": len(documents),
            "items": items,
            "note": note,
        },
    )
    return {"count": len(documents)}


async def _build_tariff_section(zf: zipfile.ZipFile, session: AsyncSession, *, tenant_id: str) -> dict:
    result = await session.execute(
        select(Tariff).where(Tariff.tenant_id == tenant_id).order_by(Tariff.effective_from.asc())
    )
    tariffs = list(result.scalars().all())

    items = [
        {
            "id": t.id,
            "name": t.name,
            "region": t.region,
            "effective_from": t.effective_from,
            "effective_to": t.effective_to,
            "booked": t.booked,
            "flag_fall": t.flag_fall,
            "dist_rate_1": t.dist_rate_1,
            "dist_rate_2": t.dist_rate_2,
            "night_rate_1": t.night_rate_1,
            "night_rate_2": t.night_rate_2,
            "holiday_rate_1": t.holiday_rate_1,
            "holiday_rate_2": t.holiday_rate_2,
            "waiting_rate_per_min": t.waiting_rate_per_min,
        }
        for t in tariffs
    ]

    note = None if tariffs else "No tariff rows on file for this vehicle's tenant."
    _write_json(
        zf,
        "tariffs/tariff_history.json",
        {
            "category": "tariff_version_history",
            "count": len(tariffs),
            "items": items,
            "note": note,
            "deviation_note": (
                "app.models.fleet.Vehicle has no region field, so this bundles "
                "every tariff row for the vehicle's tenant across all regions "
                "(urban/country/exempt), each item tagged with its own region -- "
                "see app.services.evidence_pack module docstring."
            ),
        },
    )
    return {"count": len(tariffs)}


async def _build_device_version_section(
    zf: zipfile.ZipFile, session: AsyncSession, *, tenant_id: str, vehicle_id: str
) -> dict:
    device_result = await session.execute(
        select(Device).where(Device.tenant_id == tenant_id, Device.vehicle_id == vehicle_id)
    )
    devices = list(device_result.scalars().all())

    history_items: list[dict[str, Any]] = []
    if devices:
        device_ids = [d.id for d in devices]
        history_result = await session.execute(
            select(DeviceVersionHistory)
            .where(
                DeviceVersionHistory.tenant_id == tenant_id,
                DeviceVersionHistory.device_id.in_(device_ids),
            )
            .order_by(DeviceVersionHistory.recorded_at.asc())
        )
        history_items = [
            {"device_id": h.device_id, "app_version": h.app_version, "recorded_at": h.recorded_at}
            for h in history_result.scalars().all()
        ]

    device_items = [
        {
            "id": d.id,
            "android_id": d.android_id,
            "model": d.model,
            "current_app_version": d.app_version,
            "last_seen_at": d.last_seen_at,
        }
        for d in devices
    ]

    if not devices:
        note = "No device is currently paired to this vehicle."
    elif not history_items:
        note = (
            "Device(s) paired but no app_version change has been recorded via "
            "POST /v1/fleet/devices/{id}/heartbeat yet."
        )
    else:
        note = None

    _write_json(
        zf,
        "device_versions/report.json",
        {
            "category": "device_firmware_app_version_history",
            "devices": device_items,
            "history_count": len(history_items),
            "history": history_items,
            "note": note,
        },
    )
    return {"device_count": len(devices), "history_count": len(history_items)}


async def _build_audit_log_section(
    zf: zipfile.ZipFile, session: AsyncSession, *, tenant_id: str, vehicle_id: str
) -> dict:
    result = await session.execute(
        select(AuditLog)
        .where(AuditLog.tenant_id == tenant_id, AuditLog.entity_id == vehicle_id)
        .order_by(AuditLog.at.asc())
    )
    rows = list(result.scalars().all())

    items = [
        {
            "id": r.id,
            "actor_user_id": r.actor_user_id,
            "action": r.action,
            "entity_type": r.entity_type,
            "before_json": r.before_json,
            "after_json": r.after_json,
            "at": r.at,
            "hash": r.hash,
            "previous_hash": r.previous_hash,
        }
        for r in rows
    ]

    note = (
        None
        if rows
        else (
            "No tamper-evidence audit-log entries reference this vehicle's id "
            "directly. See GET /v1/audit-log/verify to confirm this tenant's "
            "overall hash chain is intact."
        )
    )
    _write_json(
        zf,
        "audit_log/tamper_log.json",
        {
            "category": "tamper_event_log_extract",
            "count": len(items),
            "items": items,
            "note": note,
        },
    )
    return {"count": len(items)}


def _build_installation_section(zf: zipfile.ZipFile) -> dict:
    _write_json(
        zf,
        "installation/record.json",
        {
            "category": "installation_record",
            "count": 0,
            "items": [],
            "note": (
                "This backend has no dedicated installation-record table yet "
                "(meter/camera/tracking-device fitment record). Placeholder "
                "included so this category is never silently omitted from the "
                "evidence pack."
            ),
        },
    )
    return {"count": 0}


async def build_evidence_pack(session: AsyncSession, *, tenant_id: str, vehicle: Vehicle) -> bytes:
    buffer = BytesIO()
    with zipfile.ZipFile(buffer, mode="w", compression=zipfile.ZIP_DEFLATED) as zf:
        compliance_stats = await _build_compliance_section(
            zf, session, tenant_id=tenant_id, vehicle_id=vehicle.id
        )
        tariff_stats = await _build_tariff_section(zf, session, tenant_id=tenant_id)
        device_stats = await _build_device_version_section(
            zf, session, tenant_id=tenant_id, vehicle_id=vehicle.id
        )
        audit_stats = await _build_audit_log_section(zf, session, tenant_id=tenant_id, vehicle_id=vehicle.id)
        installation_stats = _build_installation_section(zf)

        _write_json(
            zf,
            "manifest.json",
            {
                "generated_at": datetime.now(UTC),
                "tenant_id": tenant_id,
                "vehicle": {
                    "id": vehicle.id,
                    "rego": vehicle.rego,
                    "vehicle_class": vehicle.vehicle_class,
                    "status": vehicle.status,
                },
                "categories": {
                    "compliance_documents": compliance_stats,
                    "tariff_version_history": tariff_stats,
                    "device_firmware_app_version_history": device_stats,
                    "tamper_event_log_extract": audit_stats,
                    "installation_record": installation_stats,
                },
            },
        )

    return buffer.getvalue()


def evidence_pack_filename(vehicle: Vehicle) -> str:
    return f"evidence-pack_{_safe_filename_component(vehicle.rego)}_{vehicle.id}.zip"
