"""Pydantic v2 schemas for the reports domain (`/v1/reports`).

This domain owns no table of its own — every schema here is a read-only
projection/aggregation over the existing `trips` table (plus lightweight
joins to `users`/`vehicles`/`tariffs`/`audit_log` for display names and
compliance linkage). See app/services/reports.py for the query logic.
"""
from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

ReportFormat = Literal["csv", "json"]
RevenueGroupBy = Literal["day", "week", "month", "driver", "vehicle", "tariff", "payment_method"]


# --- 1. NSW PtP compliance export -------------------------------------------


class ComplianceAuditLogRef(BaseModel):
    """One linked `audit_log` row for a trip — see
    app.services.audit_log.record_audit. Most trips will have zero of these
    today (no domain currently calls record_audit for trips), which is
    expected and not an error; the field exists so the export format is
    ready the moment some domain starts logging trip mutations."""

    model_config = ConfigDict(from_attributes=True)

    id: str
    action: str
    at: datetime


class NswPtpExportRow(BaseModel):
    """One trip's worth of NSW Point to Point Transport compliance export
    data (blueprint section 6.2.1 / Appendix B: meter transparency, fare
    auditability, receipt/record-retention evidence)."""

    trip_id: str
    client_uuid: str
    status: str
    type: str

    driver_id: str
    driver_name: str | None = Field(default=None, description="Resolved from users.name; null if no matching user row")

    vehicle_id: str
    vehicle_rego: str | None = Field(default=None, description="Resolved from vehicles.rego; null if no matching vehicle row")

    tariff_id: str
    tariff_name: str | None = Field(default=None, description="Resolved from tariffs.name; null if no matching tariff row")

    start_at: datetime
    end_at: datetime | None

    # --- fare breakdown (Decimal, never float) ---
    flag_fall: Decimal
    dist_amount: Decimal
    wait_amount: Decimal
    peak_amount: Decimal
    tolls: Decimal
    psl: Decimal
    extras: Decimal
    subtotal: Decimal
    surcharge: Decimal
    gst_component: Decimal
    total: Decimal

    payment_method: str
    gps_trace_ref: str | None
    receipt_ref: str | None
    max_fare_check_passed: bool
    variance_pct: Decimal | None

    compliance_audit_log: list[ComplianceAuditLogRef] = Field(default_factory=list)


class NswPtpExportResponse(BaseModel):
    tenant_id: str
    from_date: date
    to_date: date
    generated_at: datetime
    row_count: int
    rows: list[NswPtpExportRow]


# --- 2. Revenue dashboard -----------------------------------------------------


class RevenueGroupRow(BaseModel):
    """One aggregated bucket. `group_key` is the raw grouping value (e.g. an
    ISO date string for day/week/month, or the driver/vehicle/tariff/
    payment_method id); `group_label` is a resolved display name where one
    exists (driver name / vehicle rego / tariff name) and otherwise mirrors
    `group_key`."""

    group_key: str
    group_label: str

    trip_count: int
    gross_revenue: Decimal = Field(description="SUM(trips.total) — GST-inclusive amount actually charged")
    subtotal: Decimal = Field(description="SUM(trips.subtotal) — fare before surcharge")
    surcharge: Decimal
    gst_component: Decimal
    tolls: Decimal
    psl: Decimal
    extras: Decimal


class RevenueTotals(BaseModel):
    trip_count: int
    gross_revenue: Decimal
    subtotal: Decimal
    surcharge: Decimal
    gst_component: Decimal
    tolls: Decimal
    psl: Decimal
    extras: Decimal


class RevenueReportResponse(BaseModel):
    tenant_id: str
    from_date: date
    to_date: date
    group_by: RevenueGroupBy
    note: str = (
        "Only status='closed' trips are included — open trips have no final "
        "totals yet."
    )
    groups: list[RevenueGroupRow]
    totals: RevenueTotals


# --- 3. GST / BAS-prep summary -------------------------------------------------


class GstMonthRow(BaseModel):
    month: str = Field(description="YYYY-MM")
    trip_count: int
    gross_revenue: Decimal = Field(description="SUM(trips.total), GST-inclusive")
    gst_component: Decimal = Field(description="SUM(trips.gst_component)")
    net_of_gst: Decimal = Field(description="gross_revenue - gst_component")


class GstTotals(BaseModel):
    trip_count: int
    gross_revenue: Decimal
    gst_component: Decimal
    net_of_gst: Decimal


class GstSummaryResponse(BaseModel):
    tenant_id: str
    from_date: date
    to_date: date
    disclaimer: str = (
        "Internal GST totals derived from trips.gst_component, grouped by "
        "month. This is a clearly-labeled preparation aid ONLY — it is NOT "
        "an ATO BAS-format lodgment and does not constitute tax advice. "
        "Only status='closed' trips are included."
    )
    months: list[GstMonthRow]
    totals: GstTotals
