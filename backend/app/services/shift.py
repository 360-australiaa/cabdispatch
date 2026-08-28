"""Shifts domain business logic: opening/closing a shift and recomputing its
trip aggregates.

Cross-domain note: `_recompute_trip_aggregates` reads the sibling `trips`
domain's `Trip` model (`app.models.trips`) to sum up a shift's trips at close
time. That table is owned by another agent's slice of this codebase; the import
is wrapped so this domain degrades gracefully (aggregates left at zero, with a
logged warning) rather than hard-crashing if `trips` is ever absent, e.g. if
this module is exercised standalone before the two domains are integrated.

Reporting: `build_report` assembles the JSON summary dict; `render_report_pdf`
and `render_report_csv` below render that same summary (as a `ShiftReport`)
to real bytes/text. PDF rendering mirrors app.services.receipts._render_pdf_bytes's
simple header plus labeled-rows visual style, via the same pure-Python fpdf2
dependency ("fpdf2>=2.8.7" in pyproject.toml, no system dependencies). CSV
rendering mirrors app.api.v1.reports._ptp_rows_to_csv's stdlib io.StringIO
plus csv.writer pattern, no new dependency.
"""
from __future__ import annotations

import csv
import io
import json
import logging
from datetime import UTC, datetime
from decimal import Decimal

from fpdf import FPDF
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.shift import Shift
from app.schemas.shift import ShiftReport
from app.services.fare_engine import round_half_up

logger = logging.getLogger("cab_dispatch.shift")

# Payment methods treated as "cash" for the cash-vs-card reconciliation split.
# Everything else observed on a trip (tap_to_pay, link, cabcharge, ttss, ...) is
# counted into card_total. Mirrors the `payments.method` enum in the product spec.
_CASH_METHOD = "cash"


async def _recompute_trip_aggregates(
    session: AsyncSession, *, tenant_id: str, shift_id: str
) -> tuple[int, Decimal, Decimal, Decimal]:
    """Returns (trips_count, km_total, cash_total, card_total) computed fresh
    from the shift's own closed trips. Authoritative — never trusts client input."""
    try:
        from app.models.trips import Trip
    except ImportError:  # pragma: no cover - defensive only, see module docstring
        logger.warning(
            "app.models.trips.Trip not importable — leaving shift %s aggregates at "
            "zero. Expected only if this domain is exercised before the trips "
            "domain is present in the tree.",
            shift_id,
        )
        return 0, Decimal(0), Decimal(0), Decimal(0)

    base_filter = (Trip.tenant_id == tenant_id, Trip.shift_id == shift_id)

    trips_count = (
        await session.execute(select(func.count(Trip.id)).where(*base_filter))
    ).scalar_one() or 0

    distance_m_total = (
        await session.execute(
            select(func.coalesce(func.sum(Trip.distance_m), 0)).where(*base_filter)
        )
    ).scalar_one() or 0
    km_total = (Decimal(distance_m_total) / Decimal(1000)).quantize(Decimal("0.001"))

    cash_total = (
        await session.execute(
            select(func.coalesce(func.sum(Trip.total), 0)).where(
                *base_filter, Trip.payment_method == _CASH_METHOD
            )
        )
    ).scalar_one() or 0
    card_total = (
        await session.execute(
            select(func.coalesce(func.sum(Trip.total), 0)).where(
                *base_filter, Trip.payment_method != _CASH_METHOD
            )
        )
    ).scalar_one() or 0

    return (
        int(trips_count),
        km_total,
        round_half_up(Decimal(str(cash_total))),
        round_half_up(Decimal(str(card_total))),
    )


async def start_shift(
    session: AsyncSession,
    *,
    tenant_id: str,
    driver_id: str,
    vehicle_id: str,
    start_at: datetime | None,
    inspection_json: dict | None,
) -> Shift:
    shift = Shift(
        tenant_id=tenant_id,
        driver_id=driver_id,
        vehicle_id=vehicle_id,
        start_at=start_at or datetime.now(UTC),
        inspection_json=inspection_json,
    )
    session.add(shift)
    await session.commit()
    await session.refresh(shift)
    return shift


async def end_shift(
    session: AsyncSession,
    shift: Shift,
    *,
    end_at: datetime | None,
    psl_owed: Decimal,
    reconciled: bool,
) -> Shift:
    """Closes a shift: stamps `end_at`, recomputes the four trip-derived
    aggregates from that shift's own trips, and records the reconciliation
    figures supplied by the caller."""
    trips_count, km_total, cash_total, card_total = await _recompute_trip_aggregates(
        session, tenant_id=shift.tenant_id, shift_id=shift.id
    )

    shift.end_at = end_at or datetime.now(UTC)
    shift.trips_count = trips_count
    shift.km_total = km_total
    shift.cash_total = cash_total
    shift.card_total = card_total
    shift.psl_owed = psl_owed
    shift.reconciled = reconciled

    await session.commit()
    await session.refresh(shift)
    return shift


async def start_break(session: AsyncSession, shift: Shift) -> Shift:
    """Starts a break on `shift`: stamps `break_started_at` = now. The caller
    (the router) owns the 409 conflict checks -- break already in progress,
    or the shift already ended -- before calling this, mirroring how
    `start`/`end` in app/api/v1/shifts.py own the end_at-already-set check
    around `end_shift` above."""
    shift.break_started_at = datetime.now(UTC)
    await session.commit()
    await session.refresh(shift)
    return shift


async def end_break(session: AsyncSession, shift: Shift) -> Shift:
    """Ends the in-progress break on `shift`: clears `break_started_at` back
    to None and flips `break_taken` to True. Deliberately just one break slot
    per shift, not a break-history log -- see app.models.shift.Shift's
    DEVIATION note. The caller owns the 409 check (no break in progress)
    before calling this."""
    shift.break_started_at = None
    shift.break_taken = True
    await session.commit()
    await session.refresh(shift)
    return shift


def build_report(shift: Shift) -> dict:
    """Builds the JSON summary payload for `GET /v1/shifts/{id}/report`.

    Real PDF/CSV export lives below: `render_report_pdf`/`render_report_csv`
    render this same dict (wrapped as a `ShiftReport`) without changing this
    function's contract.
    """
    duration_minutes: float | None = None
    if shift.end_at is not None:
        duration_minutes = round((shift.end_at - shift.start_at).total_seconds() / 60, 2)

    return {
        "shift_id": shift.id,
        "tenant_id": shift.tenant_id,
        "driver_id": shift.driver_id,
        "vehicle_id": shift.vehicle_id,
        "start_at": shift.start_at,
        "end_at": shift.end_at,
        "duration_minutes": duration_minutes,
        "trips_count": shift.trips_count,
        "km_total": shift.km_total,
        "cash_total": shift.cash_total,
        "card_total": shift.card_total,
        "total_takings": round_half_up(shift.cash_total + shift.card_total),
        "psl_owed": shift.psl_owed,
        "reconciled": shift.reconciled,
        "inspection_json": shift.inspection_json,
        "generated_at": datetime.now(UTC),
    }


# --- PDF / CSV export ---------------------------------------------------------


def _fmt(amount: Decimal) -> str:
    return f"${amount:.2f}"


def _report_row(pdf: FPDF, label: str, value) -> None:
    pdf.set_font("Helvetica", "B", 10)
    pdf.cell(60, 6, label)
    pdf.set_font("Helvetica", "", 10)
    pdf.cell(0, 6, str(value), new_x="LMARGIN", new_y="NEXT")


def render_report_pdf(report: ShiftReport) -> bytes:
    """Pure rendering step (no I/O): one-page PDF laying out every
    ShiftReport field, mirroring app.services.receipts._render_pdf_bytes's
    simple header plus labeled-rows visual style."""
    pdf = FPDF(format="A4")
    pdf.set_auto_page_break(auto=True, margin=15)
    pdf.add_page()

    pdf.set_font("Helvetica", "B", 18)
    pdf.cell(0, 10, "SHIFT REPORT", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", "", 8)
    pdf.set_text_color(120, 120, 120)
    pdf.cell(0, 5, f"Shift ID: {report.shift_id}", new_x="LMARGIN", new_y="NEXT")
    pdf.cell(0, 5, f"Tenant ID: {report.tenant_id}", new_x="LMARGIN", new_y="NEXT")
    pdf.set_text_color(0, 0, 0)
    pdf.ln(3)
    pdf.set_font("Helvetica", "B", 11)
    pdf.cell(0, 7, "Driver & Vehicle", new_x="LMARGIN", new_y="NEXT")
    _report_row(pdf, "Driver ID", report.driver_id)
    _report_row(pdf, "Vehicle ID", report.vehicle_id)
    pdf.ln(3)

    pdf.set_font("Helvetica", "B", 11)
    pdf.cell(0, 7, "Timing", new_x="LMARGIN", new_y="NEXT")
    _report_row(pdf, "Start", report.start_at.strftime("%Y-%m-%d %H:%M UTC"))
    _report_row(
        pdf, "End", report.end_at.strftime("%Y-%m-%d %H:%M UTC") if report.end_at else "-"
    )
    _report_row(
        pdf,
        "Duration (minutes)",
        report.duration_minutes if report.duration_minutes is not None else "-",
    )
    pdf.ln(3)

    pdf.set_font("Helvetica", "B", 11)
    pdf.cell(0, 7, "Trip Summary", new_x="LMARGIN", new_y="NEXT")
    _report_row(pdf, "Trips", report.trips_count)
    _report_row(pdf, "Distance (km)", f"{report.km_total:.3f}")
    pdf.ln(3)

    pdf.set_font("Helvetica", "B", 11)
    pdf.cell(0, 7, "Takings", new_x="LMARGIN", new_y="NEXT")
    _report_row(pdf, "Cash total", _fmt(report.cash_total))
    _report_row(pdf, "Card total", _fmt(report.card_total))
    pdf.set_font("Helvetica", "B", 12)
    pdf.cell(60, 8, "Total takings")
    pdf.cell(0, 8, _fmt(report.total_takings), new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", "", 10)
    _report_row(pdf, "PSL owed", _fmt(report.psl_owed))
    _report_row(pdf, "Reconciled", "Yes" if report.reconciled else "No")
    pdf.ln(3)
    pdf.set_font("Helvetica", "B", 11)
    pdf.cell(0, 7, "Pre-Shift Inspection", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", "", 10)
    if report.inspection_json:
        for key, value in report.inspection_json.items():
            _report_row(pdf, str(key), value)
    else:
        pdf.cell(0, 6, "No inspection checklist recorded.", new_x="LMARGIN", new_y="NEXT")
    pdf.ln(3)

    pdf.set_font("Helvetica", "", 8)
    pdf.set_text_color(120, 120, 120)
    pdf.cell(
        0,
        5,
        f"Generated: {report.generated_at.strftime('%Y-%m-%d %H:%M UTC')}",
        new_x="LMARGIN",
        new_y="NEXT",
    )

    return bytes(pdf.output())


_REPORT_CSV_FIELDS = (
    "shift_id",
    "tenant_id",
    "driver_id",
    "vehicle_id",
    "start_at",
    "end_at",
    "duration_minutes",
    "trips_count",
    "km_total",
    "cash_total",
    "card_total",
    "total_takings",
    "psl_owed",
    "reconciled",
    "inspection_json",
    "generated_at",
)


def render_report_csv(report: ShiftReport) -> str:
    """Renders `report` as a one-row CSV (header row plus one value row) via
    the stdlib `csv` module, mirroring app.api.v1.reports._ptp_rows_to_csv's
    io.StringIO plus csv.writer pattern, no new dependency. Same field set as
    render_report_pdf above, in ShiftReport declaration order."""
    buffer = io.StringIO()
    writer = csv.writer(buffer)
    writer.writerow(_REPORT_CSV_FIELDS)
    writer.writerow(
        [
            report.shift_id,
            report.tenant_id,
            report.driver_id,
            report.vehicle_id,
            report.start_at.isoformat(),
            report.end_at.isoformat() if report.end_at else "",
            report.duration_minutes if report.duration_minutes is not None else "",
            report.trips_count,
            report.km_total,
            report.cash_total,
            report.card_total,
            report.total_takings,
            report.psl_owed,
            report.reconciled,
            json.dumps(report.inspection_json) if report.inspection_json else "",
            report.generated_at.isoformat(),
        ]
    )
    return buffer.getvalue()
