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

4. `flagged_for_review`, `review_notes`, `voucher_code`, `account_reference`,
   `split_payments` are added by a later feature step on top of the domain
   brief's original field list (blueprint 5.2.5's "Dispute" button / 6.1.3
   schema, and the "Account"/"Voucher"/"Split Fare" payment methods). See
   `app.services.trips.flag_trip_for_review` / `close_trip` and
   `app.services.payments.redeem_voucher` / `validate_account_reference` for
   the logic that populates/validates them.
5. `negotiated_total` is added by a later feature step on top of the domain
   brief's original field list — a competitor-matching "Set Price" fixed-fare
   feature (driver enters a fixed price before starting the meter; NSW law
   allows this for pre-arranged/negotiated fares). Reuses the same
   fare-engine mechanism the pre-existing `airport_fixed` trip type uses
   (`app.services.fare_engine.FareState.fixed_fare` /
   `FareEngine.close`) via a sibling `negotiated_total` field on `FareState`.
   2026-09 product correction: like `airport_fixed`, this is now
   ALL-INCLUSIVE — PSL and tolls are still recorded on `psl`/`tolls` below
   (for ledger/audit/remittance purposes; the obligation is real) but no
   longer add on top of what's billed (see `app.services.fare_engine`'s
   `negotiated_total` module comment for the full rationale). Settable only
   at trip creation (`TripCreate.negotiated_total`, not `TripUpdate` —
   matches the "set price before starting the meter" UX this mirrors);
   stored on the trip row distinct from `total` so it stays visible on the
   receipt/trip detail even after a cleaning fee/surcharge is layered on top
   at close.
6. `tip_amount` is added by a later feature step (Close & Pay "tips"
   pass) on top of the domain brief's original field list. A driver tip is
   NOT part of the NSW-regulated metered fare — it is never folded into
   `subtotal`/`surcharge`/`total`/`gst_component` (see
   `app.services.fare_engine.FareEngine.close`, which never reads it) and is
   deliberately kept as its own free-standing column so it can be shown on
   the receipt/trip detail without distorting the fare/GST figures a
   regulator or the ATO would read off this row. Nullable, no backfill
   needed (NULL/absent means "no tip recorded" — every existing trip has
   none). Settable only at close time (`TripCloseRequest.tip_amount` /
   `TripSyncItem.tip_amount`, not `TripCreate` — a tip is a post-fare,
   payment-time decision, unlike the pre-trip `negotiated_total` above); see
   `app.services.trips.close_trip` and `app.api.v1.trips.sync_trips` for
   where it's persisted.
7. `planned_dest_lat`/`planned_dest_lng` are added by a later feature step
   (dispatcher Live Map route-line pass) on top of the domain brief's
   original field list. Confirmed by direct investigation: before this pass
   there was no server-side concept at all of "a driver picked a destination
   mid-trip" — `end_lat`/`end_lng` above are the REAL final stop and are
   written ONLY once, at `close_trip()`; a dispatcher watching a trip live
   has never had anything to draw a route line towards. These two columns
   are the INTENDED destination instead: set via `PATCH /v1/trips/{id}/tick`
   (see `app.schemas.trips.TripTickRequest.dest_lat`/`dest_lng` and
   `app.services.trips.apply_tick`) whenever a driver's device sends one,
   left alone (never cleared) on any tick that omits them — a driver who
   already picked a destination is not required to keep re-sending it on
   every subsequent tick — and surfaced read-only on `GET /v1/vehicles` via
   `app.services.live_ops._compose_vehicle_live`'s
   `planned_dest_lat`/`planned_dest_lng`. May legitimately differ from where
   the trip actually ends (a passenger changes their mind, a diversion, a
   driver never sends one at all — NULL is the honest "no destination picked
   yet" answer, never a fabricated 0/0, same convention as the rest of this
   model's optional columns). Nullable, no backfill needed.

`TripGpsTrace` (below, own class -- GPS-trace persistence pass) is a
DIFFERENT table, not a column added to `Trip` itself. `Trip.gps_trace_ref`
(the pre-existing nullable Text pointer above) is left completely
untouched -- it was never resolved by anything and the Android client never
even set it; it is not repurposed to point at the new table. See
`TripGpsTrace`'s own class docstring for why the real trace lives in its own
table instead.
"""
from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import JSON, Boolean, DateTime, ForeignKey, Integer, Numeric, String, Text, UniqueConstraint
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

    # True when the device drove this trip on FABRICATED GPS from its own test
    # simulator rather than a real road (`GpsSimulator` in the Android app).
    #
    # This is not a diagnostic flag, it is an integrity one. A simulated trip's
    # `gps_trace` is internally consistent and replays cleanly through
    # `app.services.trips.recompute_from_trace`, so it passes the fare-variance
    # check exactly like a genuine fare -- which means that without this column
    # a test drive is indistinguishable from real revenue in an operator's
    # ledger, and from real evidence for a fare-regulated meter.
    #
    # Indexed because the whole point is filtering it out of (or into) reporting.
    # Defaults false, so every pre-existing trip and every real trip is real: a
    # trip is only ever marked by a device that knows it fabricated the fixes.
    simulated: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=False, server_default="false", index=True
    )

    # --- journey-commencement fare-engine inputs, fixed for trip lifetime (see
    # module docstring, deviation #3) ---
    time_class: Mapped[str] = mapped_column(String(10), nullable=False, default="day")
    is_peak: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    # Resolved server-side from Vehicle.vehicle_class at trip-creation time
    # (see app.services.trips.resolve_is_maxi_vehicle) — a snapshot of "was
    # this vehicle a maxi-cab when the trip started", never a raw
    # client-supplied billing flag. Combined with passenger_count/
    # wheelchair_hiring/airport_rank_requested_maxi below via
    # FareState.maxi_applied to decide whether the 150% rate actually
    # applied — see app.services.fare_engine's module docstring.
    maxi: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    passenger_count: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    wheelchair_hiring: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    airport_rank_requested_maxi: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=False
    )

    # --- location / timing ---
    start_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    end_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    start_lat: Mapped[float] = mapped_column(nullable=False)
    start_lng: Mapped[float] = mapped_column(nullable=False)
    end_lat: Mapped[float | None] = mapped_column(nullable=True)
    end_lng: Mapped[float | None] = mapped_column(nullable=True)

    # --- driver-picked mid-trip destination (module docstring deviation #7).
    # Set via PATCH /v1/trips/{id}/tick whenever a driver's device sends
    # dest_lat/dest_lng (app.services.trips.apply_tick), left untouched on
    # any tick that omits them. Deliberately distinct from end_lat/end_lng
    # above: those are the REAL final stop, known only once the trip
    # closes; these are the INTENDED destination while the trip is still
    # open, and may never match where the trip actually ends. NULL means
    # "no destination picked (yet)" — never a fabricated 0/0.
    planned_dest_lat: Mapped[float | None] = mapped_column(nullable=True)
    planned_dest_lng: Mapped[float | None] = mapped_column(nullable=True)

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

    # --- NSW toll-registry auto-detection (app.models.toll / app.services.tolls),
    # added on top of the legacy per-geofence `auto_tolls_applied` above (which
    # keeps working unchanged for tenant-defined ad hoc toll circles). These
    # three columns are maintained EXCLUSIVELY by app.services.tolls /
    # app.services.trips.apply_tick — not exposed for direct editing via
    # TripUpdate.
    #
    # auto_tolled_roads: {key: "<current charged amount>"} — ONE entry per
    # real NSW toll road (or, for a `charging_policy == "cumulative_per_
    # point"` road, per distinct TOLL POINT — see app.models.toll's module
    # docstring) this trip has been auto-charged for. Most keys are a plain
    # `TollRoad.id` (not a gantry id — this is exactly what fixes the old
    # once-per-gantry overcharge bug); a cumulative_per_point road's keys are
    # instead its `TollPoint.id` (e.g. "M2:north_ryde"), so a trip through 3
    # of Hills M2's 6 toll points shows 3 separate summed line items instead
    # of one. `tolls` above always includes the sum of every value here.
    # Most roads write their entry once and never touch it again; a
    # "distance_metered" (`pricing_model in ("distance",
    # "distance_with_flagfall")`) road instead REVISES its own entry in
    # place as the trip covers more of the corridor (see
    # app.services.tolls.apply_toll_detection) — still only one line item,
    # never a second entry for that road, so "once per road" holds even
    # though the amount itself isn't frozen at first crossing for that
    # policy.
    auto_tolled_roads: Mapped[dict[str, str] | None] = mapped_column(JSON, nullable=True, default=dict)

    # toll_road_progress: {toll_road_id: "<cumulative trip distance_km at
    # entry>"} — only ever written for a "distance_metered" pricing model
    # road (`pricing_model in ("distance", "distance_with_flagfall")`), so
    # its running charge can be recomputed as min(flagfall + rate_per_km *
    # distance_since_entry, cap) on every subsequent tick. Irrelevant (and
    # never written) for every other pricing model.
    toll_road_progress: Mapped[dict[str, str] | None] = mapped_column(JSON, nullable=True, default=dict)

    # unpriced_toll_road_ids: real NSW toll roads (or toll points, for a
    # per_point road — same key convention as `auto_tolled_roads` above)
    # this trip has genuinely crossed (GPS-detected via a real gantry) but
    # that app.services.tolls deliberately did NOT auto-charge a dollar
    # amount for -- either because the source dataset has no captured price
    # at all for that road (e.g. Rozelle Interchange, confidence=
    # "not_captured"), or because a specific toll point's own revision is
    # missing/incomplete. Surfaced on the dashboard/meter so a driver/
    # dispatcher knows to add a manual toll for these rather than the
    # passenger being silently undercharged. Deliberately never populated
    # with a guessed dollar amount.
    unpriced_toll_road_ids: Mapped[list[str] | None] = mapped_column(JSON, nullable=True, default=list)

    # --- dispute flagging (blueprint 5.2.5 "Dispute" button / 6.1.3 schema,
    # module docstring deviation #4). Settable via PATCH /v1/trips/{id}/flag by
    # the trip's own driver or a staff role (owner/admin/dispatcher) — see
    # app.services.trips.flag_trip_for_review and app.api.v1.trips.flag_trip
    # for the exact authorization rule. Indexed so the trip-listing endpoint's
    # ?flagged_for_review= filter (dashboard "flagged" view) is cheap.
    flagged_for_review: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False, index=True)
    review_notes: Mapped[str | None] = mapped_column(Text, nullable=True)

    # --- new payment methods (blueprint 5.2.5: Account / Voucher / Split Fare,
    # module docstring deviation #4). voucher_code / account_reference are
    # free-text, v1-scope fields — no real voucher/promo-code table or
    # corporate-account table exists yet in this codebase; see
    # app.services.payments.redeem_voucher / validate_account_reference for
    # the stub validation this pass adds. split_payments is only populated
    # (and validated to sum to `total`) when payment_method == "split_fare";
    # see app.services.trips.close_trip / SplitPaymentMismatchError. Same
    # plain-JSON column pattern as `auto_tolls_applied` above.
    voucher_code: Mapped[str | None] = mapped_column(String(50), nullable=True)
    account_reference: Mapped[str | None] = mapped_column(String(100), nullable=True)
    split_payments: Mapped[list[dict] | None] = mapped_column(JSON, nullable=True, default=list)

    # --- negotiated / "Set Price" fixed fare (module docstring deviation #5).
    # Set only at trip creation (see app.schemas.trips.TripCreate.negotiated_total
    # / app.services.fare_engine.validate_negotiated_total for the sanity-cap
    # validation). NULL means "this is a normal metered trip" — the fare
    # engine falls back to its usual flag/distance/time computation whenever
    # this column is NULL (see app.services.trips.build_fare_state).
    negotiated_total: Mapped[Decimal | None] = mapped_column(Numeric(10, 2), nullable=True)

    # --- driver tip (module docstring deviation #6). Deliberately separate from
    # `total`/`subtotal`/`gst_component` — see app.services.fare_engine.FareEngine.close,
    # which never reads this column, and app.services.trips.close_trip, which assigns it
    # directly rather than folding it into the fare-engine breakdown. NULL means "no tip
    # recorded" (every trip before this pass, and every trip closed without one).
    tip_amount: Mapped[Decimal | None] = mapped_column(Numeric(10, 2), nullable=True)


class TripGpsTrace(Base, TenantScopedMixin):
    """The real, raw GPS/speed trace recorded on-device for one trip -- the
    durable answer to "what route did this vehicle actually drive" (product
    decision: draw the real route on the dashboard trip-detail map, and
    retain it as route evidence to defend a disputed fare -- this is a
    legally fare-regulated taxi meter, same audit-record posture as the
    existing compliance "evidence pack" export).

    Persisted on `POST /v1/trips/sync` (see `app.api.v1.trips.sync_trips`),
    the only place the server ever sees a trip's raw `gps_trace` today --
    `app.services.trips.recompute_from_trace` already consumes that same
    payload once, transiently, purely to independently verify
    `TripSyncItem.device_total`; this table is the ADDITIVE, durable copy of
    the exact same points, written alongside (never instead of) that
    verification. `POST /v1/trips`, `PATCH .../tick` and `POST .../close`
    (the *online* create/tick/close flow) never receive a raw trace at all --
    a trip opened+closed that way simply has no row here, same as a synced
    trip whose device sent `gps_trace: []` (see below).

    WHY A SEPARATE TABLE, NOT A COLUMN ON `Trip` (design rationale, since
    Trip's own module docstring's "no column renamed/dropped" note doesn't
    cover new tables): `GET /v1/trips` returns PAGES of trips
    (`TripListResponse`, default page size 50). At roughly one point per
    second, an hour-long fare's trace is on the order of 3,600 points
    (~100-300KB serialized) -- embedding that in `TripRead` would multiply a
    50-row list response by up to ~15MB for data the list view never renders.
    Keeping the trace in its own table, fetched only by the dedicated
    `GET /v1/trips/{id}/gps-trace` endpoint the dashboard's trip-detail modal
    calls when (and only when) it actually opens, keeps every existing
    list/detail read (`TripRead`/`TripListResponse`) exactly as cheap as it
    was before this pass -- `TripRead` gained no new field.

    ONE ROW PER TRIP (not one row per point, unlike the sibling
    `VehiclePositionHistory`/`DeviceVersionHistory` many-rows-per-parent
    append-only tables in `app.models.fleet`): every real consumer of a
    trip's trace (the route-map polyline, the evidence-pack export) always
    wants "the whole ordered trace for this trip" as one unit, never a
    time-sliced subset of it -- so a single JSON column holding the full
    ordered point list avoids thousands of per-trip row inserts/reads for no
    query-pattern benefit. Same plain-JSON-column convention already used by
    `Trip.auto_tolls_applied`/`Trip.split_payments` above. Trade-off accepted
    knowingly: a single JSON column is read/written as one blob (no
    server-side range query "points between t1 and t2"); nothing in this
    system needs that today, and if it ever does, the ordered `points` list
    is trivially sliceable in Python after the one fetch.

    EMPTY TRACE (today's reality -- see `app.schemas.trips.TripSyncItem.
    gps_trace`'s doc comment: every synced trip currently arrives with
    `gps_trace: []` because of an Android-side bug a parallel workstream is
    fixing): `app.services.trips.build_gps_trace_row` deliberately returns
    `None` for an empty list, and the sync router only adds a row when it
    gets one back -- an empty trace creates NO row at all, never a row with
    `points: []`. This is what keeps "route recorded" honest: whether a row
    exists is the one, unambiguous signal `GET /v1/trips/{id}/gps-trace`
    reads to decide between "here is the real trace" and "nothing was
    recorded" (returned as `points: []`, a 200 -- the trip itself is real,
    only the trace is missing; see that endpoint's own doc comment for its
    separate 404-vs-200-empty distinction), with no third "recorded-but-empty"
    state to ever misrepresent as a route.

    IDEMPOTENCY: written in the same flush as the `Trip` row it belongs to,
    inside `sync_trips`'s existing per-item "does this client_uuid already
    exist" duplicate check -- a duplicate resync never reaches the code path
    that builds a new `TripGpsTrace` at all (it `continue`s straight to
    recording `duplicate=True` against the trip already on file), and if that
    flush's client_uuid unique-constraint insert races and fails, the whole
    flush (trip row AND trace row together) rolls back, so no orphaned trace
    is ever left behind for a trip that itself failed to insert.

    `TenantScopedMixin` (not the unconstrained-cross-domain-ref convention
    `Trip`'s own columns use) since `trip_id` here references a row this SAME
    domain owns, already guaranteed on `Base.metadata` by the time this class
    is defined (it's declared earlier in this very module) -- same reasoning
    `app.models.fleet.VehiclePositionHistory`/`DevicePairingCode` already
    apply to `vehicle_id`/`device_id` FKs within their own file.
    """

    __tablename__ = "trip_gps_traces"
    __table_args__ = (
        UniqueConstraint("tenant_id", "trip_id", name="uq_trip_gps_traces_tenant_trip_id"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    trip_id: Mapped[str] = mapped_column(String(36), ForeignKey("trips.id"), nullable=False, index=True)
    # Ordered list of {"lat": float, "lng": float, "speed_kmh": float, "ts": "<ISO-8601>"}
    # -- the wire shape of app.schemas.trips.TelemetryPoint, JSON-serialized
    # (JSON has no native datetime type) via that schema's own
    # `.model_dump(mode="json")` -- see app.services.trips.build_gps_trace_row.
    # Never empty -- see this class's own "EMPTY TRACE" doc section above; a
    # `[]` trace never gets a row at all.
    points: Mapped[list[dict]] = mapped_column(JSON, nullable=False)
    # Denormalized len(points), set once at insert time -- lets a caller (or a
    # future evidence-pack summary line) know "how many points" without
    # deserializing/loading the full JSON blob, same spirit as
    # Trip.distance_m being precomputed rather than derived on every read.
    point_count: Mapped[int] = mapped_column(Integer, nullable=False)
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
