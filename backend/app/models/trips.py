"""Trip model — the offline-first taxi-meter journey record.

DEVIATIONS from the literal field list in the domain brief (flagged per the task
instructions — everything else is exactly as specified):

1. `vehicle_id`, `driver_id`, `shift_id`, `tariff_id` are plain indexed
   String(36) columns with NO ForeignKey constraint, matching the precedent
   already set by the sibling `shifts` domain (`app/models/shift.py`) for the
   same `driver_id`/`vehicle_id` pair. `driver_id` *could* validly FK
   `users.id`, and `tariff_id` now *could* validly FK the sibling `tariffs`
   domain's `tariffs.id` (both tables happen to already exist in this tree),
   but all four are left unconstrained so `Base.metadata.create_all` /
   Alembic autogenerate stay order-independent of tables this domain doesn't
   own — this agent must not touch `app/models/__init__.py`, so it cannot
   guarantee sibling models are registered on `Base.metadata` when this
   module is imported in isolation. Multi-tenant isolation and referential
   validity are enforced at the application layer instead: every query
   filters by `get_current_tenant_id`, and `app.services.trips.resolve_tariff`
   looks `tariff_id` up against the real `app.models.tariffs.Tariff` table
   (via `app.services.tariffs.to_fare_engine_tariff`), scoped to the
   requesting tenant, and 422s if it doesn't resolve. A later integration
   step can add the FKs once all 12 domains are registered together.
2. `last_lat` / `last_lng` / `last_ts` are added (not in the brief's field
   list). The offline-replay design means `PATCH /trips/{id}/tick` is called
   repeatedly with batches of raw telemetry points that carry only
   {lat, lng, speed_kmh, ts} — no client-supplied distance delta. The fare
   engine's `tick()` needs a distance-delta-per-tick, so the server must
   compute a haversine delta between the previous known fix and each new
   point. Without persisting "the last point we ticked from", a second
   PATCH call in a later request would have no anchor to measure from. These
   three columns are that anchor; they carry no independent business meaning
   and are not exposed for editing via the update schema.
3. `time_class`, `is_peak`, `maxi` are added (not in the brief's field list).
   `FareState.time_class` / `is_peak` are fixed at journey commencement per
   the fare engine's own docstring, and `maxi` gates the 1.5x multiplier —
   none of these are derivable from the listed columns (tariff_id only
   selects urban vs country rates), so they must be captured at trip-open
   time and persisted to reconstruct `FareState` identically on every
   subsequent tick/close call.

No column in the brief's list was renamed, dropped, or retyped.
"""
from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import JSON, Boolean, DateTime, Integer, Numeric, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin, TimestampMixin

# --- trip type / status enums (plain strings, sqlite/postgres portable) -----

TRIP_TYPE_RANK_HAIL = "rank_hail"
TRIP_TYPE_BOOKED = "booked"
TRIP_TYPE_AIRPORT_FIXED = "airport_fixed"
TRIP_TYPE_MULTI_HIRE = "multi_hire"
TRIP_TYPES = {
    TRIP_TYPE_RANK_HAIL,
    TRIP_TYPE_BOOKED,
    TRIP_TYPE_AIRPORT_FIXED,
    TRIP_TYPE_MULTI_HIRE,
}

TRIP_STATUS_OPEN = "open"
TRIP_STATUS_CLOSED = "closed"
TRIP_STATUSES = {TRIP_STATUS_OPEN, TRIP_STATUS_CLOSED}

TIME_CLASSES = {"day", "night", "holiday"}


class Trip(Base, TenantScopedMixin, TimestampMixin):
    __tablename__ = "trips"
    __table_args__ = (
        UniqueConstraint("tenant_id", "client_uuid", name="uq_trips_tenant_client_uuid"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))

    # --- offline idempotency key ---
    client_uuid: Mapped[str] = mapped_column(String(36), nullable=False, index=True)

    # --- assignment (unconstrained cross-domain refs, see module docstring) ---
    vehicle_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)
    driver_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)
    shift_id: Mapped[str | None] = mapped_column(String(36), nullable=True, index=True)
    tariff_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)

    type: Mapped[str] = mapped_column(String(20), nullable=False)
    status: Mapped[str] = mapped_column(String(10), nullable=False, default=TRIP_STATUS_OPEN, index=True)

    # --- journey-commencement fare-engine inputs, fixed for trip lifetime (see
    # module docstring, deviation #3) ---
    time_class: Mapped[str] = mapped_column(String(10), nullable=False, default="day")
    is_peak: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    maxi: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)

    # --- location / timing ---
    start_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    end_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    start_lat: Mapped[float] = mapped_column(nullable=False)
    start_lng: Mapped[float] = mapped_column(nullable=False)
    end_lat: Mapped[float | None] = mapped_column(nullable=True)
    end_lng: Mapped[float | None] = mapped_column(nullable=True)

    # --- tick continuity anchor (deviation #2) ---
    last_lat: Mapped[float | None] = mapped_column(nullable=True)
    last_lng: Mapped[float | None] = mapped_column(nullable=True)
    last_ts: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    # --- running / final meter totals ---
    distance_m: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    moving_s: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    waiting_s: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    flag_fall: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False, default=Decimal(0))
    dist_amount: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False, default=Decimal(0))
    wait_amount: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False, default=Decimal(0))
    peak_amount: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False, default=Decimal(0))
    tolls: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False, default=Decimal(0))
    psl: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False, default=Decimal(0))
    extras: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False, default=Decimal(0))
    subtotal: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False, default=Decimal(0))
    surcharge: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False, default=Decimal(0))
    total: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False, default=Decimal(0))
    gst_component: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False, default=Decimal(0))

    payment_method: Mapped[str] = mapped_column(String(20), nullable=False, default="cash")
    gps_trace_ref: Mapped[str | None] = mapped_column(Text, nullable=True)

    max_fare_check_passed: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    variance_pct: Mapped[Decimal | None] = mapped_column(Numeric(6, 2), nullable=True)
    receipt_ref: Mapped[str | None] = mapped_column(String(100), nullable=True)

    # --- geofence toll auto-detection (blueprint 5.2.4), added by a later
    # feature step on top of the domain brief's original field list. Tracks
    # which toll-kind Geofence ids (app.models.geofence.Geofence) have
    # already had their toll_amount folded into `tolls` above, so a vehicle
    # lingering inside — or re-crossing — the same zone across multiple
    # PATCH .../tick calls is never charged twice. Not exposed for direct
    # editing via TripUpdate — it's maintained exclusively by
    # app.services.trips.apply_tick.
    auto_tolls_applied: Mapped[list[str] | None] = mapped_column(JSON, nullable=True, default=list)
