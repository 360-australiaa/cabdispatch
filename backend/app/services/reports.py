"""Business logic for the reports domain (`/v1/reports`).

This domain owns no table of its own — every function here reads from the
existing `trips` table (source of truth), with lightweight joins to
`users`/`vehicles`/`tariffs` for display names and to `audit_log` for
compliance linkage. Every function takes `tenant_id` and filters by it —
the sole multi-tenancy enforcement mechanism in this system (see
app.core.security.get_current_tenant_id's docstring).

Money is aggregated entirely via SQL `SUM()`/`GROUP BY` (never summed
Python-side over a fetched trip list) so results stay correct at scale and
so the "no float for money" rule is respected end-to-end: SQLAlchemy's
Numeric SUM comes back through the DBAPI as a value the driver maps to
Decimal (sqlite via aiosqlite / postgres via asyncpg both preserve this for
NUMERIC columns bound with SQLAlchemy's `Numeric` type), never a plain
float.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, date, datetime, time, timedelta
from decimal import Decimal

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import engine
from app.models.audit_log import AuditLog
from app.models.fleet import Vehicle
from app.models.tariffs import Tariff
from app.models.trips import TRIP_STATUS_CLOSED, Trip
from app.models.user import User


def date_range_bounds(from_date: date, to_date: date) -> tuple[datetime, datetime]:
    """Converts an inclusive [from_date, to_date] calendar-day range into the
    [start, end) UTC datetime bounds used to filter `Trip.start_at`."""
    start = datetime.combine(from_date, time.min, tzinfo=UTC)
    end = datetime.combine(to_date, time.min, tzinfo=UTC) + timedelta(days=1)
    return start, end


class InvalidDateRangeError(ValueError):
    """Raised when `to_date` precedes `from_date`."""


def _validate_range(from_date: date, to_date: date) -> None:
    if to_date < from_date:
        raise InvalidDateRangeError("'to' must not be earlier than 'from'")


# ======================================================================================
# 1. NSW PtP compliance export
# ======================================================================================


@dataclass
class PtpExportRow:
    trip: Trip
    driver_name: str | None
    vehicle_rego: str | None
    tariff_name: str | None
    audit_log_entries: list[AuditLog]


async def build_nsw_ptp_export(
    session: AsyncSession, *, tenant_id: str, from_date: date, to_date: date
) -> list[PtpExportRow]:
    """One row per trip in range, resolved against driver name / vehicle rego
    / tariff name / linked audit_log entries. Deliberately includes ALL trip
    statuses (not just closed) — a PtP inspection export should account for
    every trip record, including any left open, not just financially
    finalized ones."""
    _validate_range(from_date, to_date)
    start, end = date_range_bounds(from_date, to_date)

    result = await session.execute(
        select(Trip)
        .where(Trip.tenant_id == tenant_id, Trip.start_at >= start, Trip.start_at < end)
        .order_by(Trip.start_at.asc())
    )
    trips = list(result.scalars().all())
    if not trips:
        return []

    driver_ids = {t.driver_id for t in trips}
    vehicle_ids = {t.vehicle_id for t in trips}
    tariff_ids = {t.tariff_id for t in trips}
    trip_ids = [t.id for t in trips]

    drivers_result = await session.execute(select(User.id, User.name).where(User.id.in_(driver_ids)))
    driver_names = dict(drivers_result.all())

    vehicles_result = await session.execute(
        select(Vehicle.id, Vehicle.rego).where(Vehicle.tenant_id == tenant_id, Vehicle.id.in_(vehicle_ids))
    )
    vehicle_regos = dict(vehicles_result.all())

    tariffs_result = await session.execute(
        select(Tariff.id, Tariff.name).where(Tariff.id.in_(tariff_ids))
    )
    tariff_names = dict(tariffs_result.all())

    audit_result = await session.execute(
        select(AuditLog)
        .where(
            AuditLog.tenant_id == tenant_id,
            AuditLog.entity_type == "trip",
            AuditLog.entity_id.in_(trip_ids),
        )
        .order_by(AuditLog.at.asc())
    )
    audit_by_trip: dict[str, list[AuditLog]] = {}
    for entry in audit_result.scalars().all():
        audit_by_trip.setdefault(entry.entity_id, []).append(entry)

    return [
        PtpExportRow(
            trip=t,
            driver_name=driver_names.get(t.driver_id),
            vehicle_rego=vehicle_regos.get(t.vehicle_id),
            tariff_name=tariff_names.get(t.tariff_id),
            audit_log_entries=audit_by_trip.get(t.id, []),
        )
        for t in trips
    ]


# ======================================================================================
# 2. Revenue dashboard (SQL GROUP BY)
# ======================================================================================

_MONEY_COLUMNS = ("total", "subtotal", "surcharge", "gst_component", "tolls", "psl", "extras")


def _dialect_name() -> str:
    return engine.dialect.name


def _period_bucket_expr(group_by: str):
    """Dialect-aware SQL expression that buckets `Trip.start_at` into a
    'YYYY-MM-DD' string for day, the Monday of the ISO week for week, or
    'YYYY-MM' for month. Grouping happens on this expression in SQL, not in
    Python."""
    dialect = _dialect_name()
    col = Trip.start_at

    if dialect == "sqlite":
        if group_by == "day":
            return func.strftime("%Y-%m-%d", col)
        if group_by == "week":
            # Monday of the ISO week containing `col` (see module notes).
            return func.strftime("%Y-%m-%d", func.date(col, "weekday 0", "-6 days"))
        if group_by == "month":
            return func.strftime("%Y-%m-01", col)
    else:
        # postgres (and any other dialect with date_trunc/to_char, e.g. asyncpg
        # in production per app.core.config.Settings.DATABASE_URL).
        if group_by == "day":
            return func.to_char(func.date_trunc("day", col), "YYYY-MM-DD")
        if group_by == "week":
            return func.to_char(func.date_trunc("week", col), "YYYY-MM-DD")
        if group_by == "month":
            return func.to_char(func.date_trunc("month", col), "YYYY-MM-01")

    raise ValueError(f"Unsupported period group_by: {group_by!r}")


def _group_expr_and_label_source(group_by: str):
    """Returns (group_key_expr, label_expr_or_None) for a given `group_by`.
    label_expr is None when the key IS the label (day/week/month/payment_method)."""
    if group_by in ("day", "week", "month"):
        expr = _period_bucket_expr(group_by)
        return expr, None
    if group_by == "driver":
        return Trip.driver_id, User.name
    if group_by == "vehicle":
        return Trip.vehicle_id, Vehicle.rego
    if group_by == "tariff":
        return Trip.tariff_id, Tariff.name
    if group_by == "payment_method":
        return Trip.payment_method, None
    raise ValueError(f"Unsupported group_by: {group_by!r}")


@dataclass
class RevenueGroupResult:
    group_key: str
    group_label: str
    trip_count: int
    gross_revenue: Decimal
    subtotal: Decimal
    surcharge: Decimal
    gst_component: Decimal
    tolls: Decimal
    psl: Decimal
    extras: Decimal


@dataclass
class RevenueTotalsResult:
    trip_count: int
    gross_revenue: Decimal
    subtotal: Decimal
    surcharge: Decimal
    gst_component: Decimal
    tolls: Decimal
    psl: Decimal
    extras: Decimal


def _zero_totals() -> RevenueTotalsResult:
    z = Decimal("0.00")
    return RevenueTotalsResult(trip_count=0, gross_revenue=z, subtotal=z, surcharge=z, gst_component=z, tolls=z, psl=z, extras=z)


async def revenue_report(
    session: AsyncSession, *, tenant_id: str, from_date: date, to_date: date, group_by: str
) -> tuple[list[RevenueGroupResult], RevenueTotalsResult]:
    """Aggregated revenue totals, grouped as requested. Only status='closed'
    trips are included (open trips have no final `total`/breakdown yet).
    All aggregation (SUM/COUNT/GROUP BY) runs in SQL."""
    _validate_range(from_date, to_date)
    start, end = date_range_bounds(from_date, to_date)

    base_filters = [
        Trip.tenant_id == tenant_id,
        Trip.status == TRIP_STATUS_CLOSED,
        Trip.start_at >= start,
        Trip.start_at < end,
    ]

    group_key_expr, label_expr = _group_expr_and_label_source(group_by)
    needs_user_join = group_by == "driver"
    needs_vehicle_join = group_by == "vehicle"
    needs_tariff_join = group_by == "tariff"

    label_col = label_expr if label_expr is not None else group_key_expr

    stmt = select(
        group_key_expr.label("group_key"),
        func.max(label_col).label("group_label"),
        func.count(Trip.id).label("trip_count"),
        func.sum(Trip.total).label("gross_revenue"),
        func.sum(Trip.subtotal).label("subtotal"),
        func.sum(Trip.surcharge).label("surcharge"),
        func.sum(Trip.gst_component).label("gst_component"),
        func.sum(Trip.tolls).label("tolls"),
        func.sum(Trip.psl).label("psl"),
        func.sum(Trip.extras).label("extras"),
    ).where(*base_filters)

    if needs_user_join:
        stmt = stmt.outerjoin(User, User.id == Trip.driver_id)
    if needs_vehicle_join:
        stmt = stmt.outerjoin(Vehicle, Vehicle.id == Trip.vehicle_id)
    if needs_tariff_join:
        stmt = stmt.outerjoin(Tariff, Tariff.id == Trip.tariff_id)

    stmt = stmt.group_by(group_key_expr).order_by(group_key_expr.asc())

    rows = (await session.execute(stmt)).all()

    groups = [
        RevenueGroupResult(
            group_key=str(r.group_key) if r.group_key is not None else "",
            group_label=str(r.group_label) if r.group_label is not None else (str(r.group_key) if r.group_key is not None else ""),
            trip_count=r.trip_count,
            gross_revenue=r.gross_revenue or Decimal("0.00"),
            subtotal=r.subtotal or Decimal("0.00"),
            surcharge=r.surcharge or Decimal("0.00"),
            gst_component=r.gst_component or Decimal("0.00"),
            tolls=r.tolls or Decimal("0.00"),
            psl=r.psl or Decimal("0.00"),
            extras=r.extras or Decimal("0.00"),
        )
        for r in rows
    ]

    totals_stmt = select(
        func.count(Trip.id).label("trip_count"),
        func.sum(Trip.total).label("gross_revenue"),
        func.sum(Trip.subtotal).label("subtotal"),
        func.sum(Trip.surcharge).label("surcharge"),
        func.sum(Trip.gst_component).label("gst_component"),
        func.sum(Trip.tolls).label("tolls"),
        func.sum(Trip.psl).label("psl"),
        func.sum(Trip.extras).label("extras"),
    ).where(*base_filters)
    totals_row = (await session.execute(totals_stmt)).one()

    if totals_row.trip_count == 0:
        totals = _zero_totals()
    else:
        totals = RevenueTotalsResult(
            trip_count=totals_row.trip_count,
            gross_revenue=totals_row.gross_revenue or Decimal("0.00"),
            subtotal=totals_row.subtotal or Decimal("0.00"),
            surcharge=totals_row.surcharge or Decimal("0.00"),
            gst_component=totals_row.gst_component or Decimal("0.00"),
            tolls=totals_row.tolls or Decimal("0.00"),
            psl=totals_row.psl or Decimal("0.00"),
            extras=totals_row.extras or Decimal("0.00"),
        )

    return groups, totals


# ======================================================================================
# 3. GST / BAS-prep summary (SQL GROUP BY month)
# ======================================================================================


@dataclass
class GstMonthResult:
    month: str
    trip_count: int
    gross_revenue: Decimal
    gst_component: Decimal
    net_of_gst: Decimal


@dataclass
class GstTotalsResult:
    trip_count: int
    gross_revenue: Decimal
    gst_component: Decimal
    net_of_gst: Decimal


async def gst_summary(
    session: AsyncSession, *, tenant_id: str, from_date: date, to_date: date
) -> tuple[list[GstMonthResult], GstTotalsResult]:
    """Sum of gst_component across closed trips in range, grouped by month.
    Clearly-labeled internal totals only — NOT an ATO BAS-format lodgment
    (see GstSummaryResponse.disclaimer)."""
    _validate_range(from_date, to_date)
    start, end = date_range_bounds(from_date, to_date)

    base_filters = [
        Trip.tenant_id == tenant_id,
        Trip.status == TRIP_STATUS_CLOSED,
        Trip.start_at >= start,
        Trip.start_at < end,
    ]

    month_expr = _period_bucket_expr("month")

    stmt = (
        select(
            month_expr.label("month"),
            func.count(Trip.id).label("trip_count"),
            func.sum(Trip.total).label("gross_revenue"),
            func.sum(Trip.gst_component).label("gst_component"),
        )
        .where(*base_filters)
        .group_by(month_expr)
        .order_by(month_expr.asc())
    )
    rows = (await session.execute(stmt)).all()

    months = [
        GstMonthResult(
            month=str(r.month)[:7],  # "YYYY-MM-01" -> "YYYY-MM" (sqlite path already yields this length)
            trip_count=r.trip_count,
            gross_revenue=r.gross_revenue or Decimal("0.00"),
            gst_component=r.gst_component or Decimal("0.00"),
            net_of_gst=(r.gross_revenue or Decimal("0.00")) - (r.gst_component or Decimal("0.00")),
        )
        for r in rows
    ]

    totals_stmt = select(
        func.count(Trip.id).label("trip_count"),
        func.sum(Trip.total).label("gross_revenue"),
        func.sum(Trip.gst_component).label("gst_component"),
    ).where(*base_filters)
    totals_row = (await session.execute(totals_stmt)).one()

    if totals_row.trip_count == 0:
        totals = GstTotalsResult(
            trip_count=0, gross_revenue=Decimal("0.00"), gst_component=Decimal("0.00"), net_of_gst=Decimal("0.00")
        )
    else:
        gross = totals_row.gross_revenue or Decimal("0.00")
        gst = totals_row.gst_component or Decimal("0.00")
        totals = GstTotalsResult(
            trip_count=totals_row.trip_count, gross_revenue=gross, gst_component=gst, net_of_gst=gross - gst
        )

    return months, totals
