"""Reports domain router — `/v1/reports`.

Pure reporting/export layer over the existing `trips` table (and its
`compliance_audit_log` / driver / vehicle / tariff linkages) — this domain
creates no new source-of-truth table. Every query is filtered by `tenant_id`
resolved via `get_current_tenant_id`, the sole multi-tenancy enforcement
mechanism in this system.

Three endpoints, per the domain brief:
  1. GET /v1/reports/nsw-ptp-export  — one-click NSW PtP compliance export
     (blueprint 6.2.1 / Appendix B), CSV or JSON.
  2. GET /v1/reports/revenue         — aggregated revenue, grouped by
     day/week/month/driver/vehicle/tariff/payment_method (SQL GROUP BY).
  3. GET /v1/reports/gst-summary     — GST totals by month, BAS-prep-shaped
     (NOT an ATO-format lodgment — see GstSummaryResponse.disclaimer).

Read access: owner/admin/dispatcher (same tier as the other back-office
reporting/compliance domains in this codebase, e.g. app/api/v1/compliance.py
and app/api/v1/billing.py). No write endpoints — this domain has nothing to
write.
"""
from __future__ import annotations

import csv
import io
from datetime import UTC, date, datetime

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, require_role
from app.models.user import User
from app.schemas.reports import (
    ComplianceAuditLogRef,
    GstMonthRow,
    GstSummaryResponse,
    GstTotals,
    NswPtpExportResponse,
    NswPtpExportRow,
    ReportFormat,
    RevenueGroupBy,
    RevenueGroupRow,
    RevenueReportResponse,
    RevenueTotals,
)
from app.services import reports as reports_service

router = APIRouter(prefix="/v1/reports", tags=["reports"])

_READ_ROLES = ("owner", "admin", "dispatcher")
_require_read_role = require_role(*_READ_ROLES)


def _reraise_invalid_range(exc: reports_service.InvalidDateRangeError) -> HTTPException:
    return HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc))


# ======================================================================================
# 1. NSW PtP compliance export
# ======================================================================================


def _ptp_row_to_schema(row: reports_service.PtpExportRow) -> NswPtpExportRow:
    t = row.trip
    return NswPtpExportRow(
        trip_id=t.id,
        client_uuid=t.client_uuid,
        status=t.status,
        type=t.type,
        driver_id=t.driver_id,
        driver_name=row.driver_name,
        vehicle_id=t.vehicle_id,
        vehicle_rego=row.vehicle_rego,
        tariff_id=t.tariff_id,
        tariff_name=row.tariff_name,
        start_at=t.start_at,
        end_at=t.end_at,
        flag_fall=t.flag_fall,
        dist_amount=t.dist_amount,
        wait_amount=t.wait_amount,
        peak_amount=t.peak_amount,
        tolls=t.tolls,
        psl=t.psl,
        extras=t.extras,
        subtotal=t.subtotal,
        surcharge=t.surcharge,
        gst_component=t.gst_component,
        total=t.total,
        payment_method=t.payment_method,
        gps_trace_ref=t.gps_trace_ref,
        receipt_ref=t.receipt_ref,
        max_fare_check_passed=t.max_fare_check_passed,
        variance_pct=t.variance_pct,
        compliance_audit_log=[
            ComplianceAuditLogRef(id=e.id, action=e.action, at=e.at) for e in row.audit_log_entries
        ],
    )


_PTP_CSV_HEADER = [
    "trip_id",
    "client_uuid",
    "status",
    "type",
    "driver_id",
    "driver_name",
    "vehicle_id",
    "vehicle_rego",
    "tariff_id",
    "tariff_name",
    "start_at",
    "end_at",
    "flag_fall",
    "dist_amount",
    "wait_amount",
    "peak_amount",
    "tolls",
    "psl",
    "extras",
    "subtotal",
    "surcharge",
    "gst_component",
    "total",
    "payment_method",
    "gps_trace_ref",
    "receipt_ref",
    "max_fare_check_passed",
    "variance_pct",
    "compliance_audit_log_count",
    "compliance_audit_log_ids",
]


def _ptp_rows_to_csv(rows: list[reports_service.PtpExportRow]) -> str:
    buffer = io.StringIO()
    writer = csv.writer(buffer)
    writer.writerow(_PTP_CSV_HEADER)
    for row in rows:
        t = row.trip
        writer.writerow(
            [
                t.id,
                t.client_uuid,
                t.status,
                t.type,
                t.driver_id,
                row.driver_name or "",
                t.vehicle_id,
                row.vehicle_rego or "",
                t.tariff_id,
                row.tariff_name or "",
                t.start_at.isoformat(),
                t.end_at.isoformat() if t.end_at else "",
                t.flag_fall,
                t.dist_amount,
                t.wait_amount,
                t.peak_amount,
                t.tolls,
                t.psl,
                t.extras,
                t.subtotal,
                t.surcharge,
                t.gst_component,
                t.total,
                t.payment_method,
                t.gps_trace_ref or "",
                t.receipt_ref or "",
                t.max_fare_check_passed,
                t.variance_pct if t.variance_pct is not None else "",
                len(row.audit_log_entries),
                ";".join(e.id for e in row.audit_log_entries),
            ]
        )
    return buffer.getvalue()


@router.get("/nsw-ptp-export")
async def nsw_ptp_export(
    from_date: date = Query(..., alias="from"),
    to_date: date = Query(..., alias="to"),
    export_format: ReportFormat = Query(default="json", alias="format"),
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(_require_read_role),
    session: AsyncSession = Depends(get_session),
):
    """One-click NSW Point to Point Transport compliance export (blueprint
    section 6.2.1 / Appendix B). Every trip in the inclusive [from, to]
    calendar-day range, with driver/vehicle/tariff names resolved, fare
    breakdown, payment method, GPS trace reference, and any linked
    `compliance_audit_log` (audit_log) entries.

    `format=json` (default) returns `NswPtpExportResponse`.
    `format=csv` returns a `text/csv` attachment built with Python's stdlib
    `csv` module — no new dependency.
    """
    try:
        rows = await reports_service.build_nsw_ptp_export(
            session, tenant_id=tenant_id, from_date=from_date, to_date=to_date
        )
    except reports_service.InvalidDateRangeError as exc:
        raise _reraise_invalid_range(exc) from exc

    if export_format == "csv":
        csv_body = _ptp_rows_to_csv(rows)
        filename = f"nsw-ptp-export_{from_date.isoformat()}_{to_date.isoformat()}.csv"
        return Response(
            content=csv_body,
            media_type="text/csv",
            headers={"Content-Disposition": f'attachment; filename="{filename}"'},
        )

    return NswPtpExportResponse(
        tenant_id=tenant_id,
        from_date=from_date,
        to_date=to_date,
        generated_at=datetime.now(UTC),
        row_count=len(rows),
        rows=[_ptp_row_to_schema(r) for r in rows],
    )


# ======================================================================================
# 2. Revenue dashboard
# ======================================================================================


@router.get("/revenue", response_model=RevenueReportResponse)
async def revenue_report(
    from_date: date = Query(..., alias="from"),
    to_date: date = Query(..., alias="to"),
    group_by: RevenueGroupBy = Query(default="day"),
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(_require_read_role),
    session: AsyncSession = Depends(get_session),
) -> RevenueReportResponse:
    """Aggregated revenue totals over closed trips in the inclusive [from,
    to] calendar-day range, grouped as requested. All SUM/COUNT/GROUP BY runs
    in SQL via SQLAlchemy (see app.services.reports.revenue_report)."""
    try:
        groups, totals = await reports_service.revenue_report(
            session, tenant_id=tenant_id, from_date=from_date, to_date=to_date, group_by=group_by
        )
    except reports_service.InvalidDateRangeError as exc:
        raise _reraise_invalid_range(exc) from exc

    return RevenueReportResponse(
        tenant_id=tenant_id,
        from_date=from_date,
        to_date=to_date,
        group_by=group_by,
        groups=[RevenueGroupRow(**vars(g)) for g in groups],
        totals=RevenueTotals(**vars(totals)),
    )


# ======================================================================================
# 3. GST / BAS-prep summary
# ======================================================================================


@router.get("/gst-summary", response_model=GstSummaryResponse)
async def gst_summary(
    from_date: date = Query(..., alias="from"),
    to_date: date = Query(..., alias="to"),
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(_require_read_role),
    session: AsyncSession = Depends(get_session),
) -> GstSummaryResponse:
    """Sum of `gst_component` across closed trips in the inclusive [from, to]
    calendar-day range, broken down by month. Clearly-labeled internal
    totals only — NOT an ATO BAS-format lodgment (see
    GstSummaryResponse.disclaimer)."""
    try:
        months, totals = await reports_service.gst_summary(
            session, tenant_id=tenant_id, from_date=from_date, to_date=to_date
        )
    except reports_service.InvalidDateRangeError as exc:
        raise _reraise_invalid_range(exc) from exc

    return GstSummaryResponse(
        tenant_id=tenant_id,
        from_date=from_date,
        to_date=to_date,
        months=[GstMonthRow(**vars(m)) for m in months],
        totals=GstTotals(**vars(totals)),
    )
