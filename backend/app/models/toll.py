"""NSW toll-road reference data — real per-road (and, where the source data
resolves it, per-toll-point) pricing rules and the 141 physical toll gantries
that GPS-detect a trip's crossings, replacing the old
`Geofence(kind="toll")` flat-circle-with-one-amount model for the roads in
the authoritative NSW toll dataset (see `app/data/nsw_toll_roads.json` /
`app/data/nsw_toll_gantries.csv`, and `scripts/seed_toll_roads.py` which
loads them).

This module was corrected against Linkt's and the NSW Government's own
published price pages on 2026-09-07 (see each `TollRoadPriceRevision` /
`TollPointPriceRevision.source_url` for the exact page cited per figure).
That correction pass fixed two real defects in the previous version of this
registry, both documented in detail in `scripts/seed_toll_roads.py` and
`app/data/nsw_toll_roads.json`:

  1. Westlink M7 was charging a $/km rate DERIVED by dividing its cap by a
     self-measured gantry-to-gantry corridor length, instead of using
     Linkt's own published $0.5252/km rate. `derived_corridor_km` /
     `distance_rate_per_km_class_a` remain, now strictly a FALLBACK for a
     "distance" road with no published rate captured — see
     `app.services.tolls`.
  2. WestConnex (M4, M8, M5 East, M4-M8 Link) was seeded `confidence=
     "not_captured"` with every price field NULL — never charged. It is now
     priced from Linkt's published $1.80 flagfall + $0.6667/km formula, with
     each road's own cap AND a shared $12.74 (Class A) / $38.22 (Class B)
     network-wide cap across the whole WestConnex network for one trip (see
     `TollRoad.network_group` / `apply_toll_detection`'s network-cap step).

A follow-up pass on 2026-09-09 fixed two further real defects, this time in
`app.services.tolls` and this registry's own road/gantry assignments (see
that module and `scripts/seed_toll_roads.py` for the full detail):

  3. M12 Motorway (permanently toll-free by government policy, opened 14
     March 2026) and the Anzac Bridge/Iron Cove Bridge/City West Link
     gantries formerly assigned to "ROZELLE_INTERCHANGE" (which are on the
     Iron Cove Link — the one WestConnex/Rozelle Interchange component that
     is toll-free, NOT the genuinely still-unpriced Rozelle Interchange
     tolled tunnels) were both modelled `pricing_model="unpriced"`,
     wrongly triggering a "this toll needs a price, enter manually" driver
     prompt for a road that never charges anything. Both now use the new
     `pricing_model="toll_free"` (see `TOLL_PRICING_MODELS` below) —
     detected, zero charge, no prompt. The genuine Rozelle Interchange
     tolled segment (St Peters to Rozelle) stays `unpriced`, now correctly
     carrying zero gantries of its own rather than someone else's.
  4. A "distance_metered" road's running per-km distance
     (`Trip.toll_road_progress`) used to be revised only on a tick that
     also matched one of that SAME road's own gantries — silently freezing
     the bill at the last-gantry snapshot for the rest of the corridor on a
     road like Westlink M7 or WestConnex, whose real gantries sit
     kilometres apart. `apply_toll_detection` now keeps an already-charged
     distance-metered road's bill current on every tick while the vehicle
     is plausibly still on its corridor, finalizing it the tick that
     measures a genuine corridor exit (or, if none arrives first, at
     whatever the last real tick before the trip closes left it at).

A real NSW toll road is priced by one of several distinct MODELS (flat /
per_point / distance / distance_with_flagfall / time_of_day), is sometimes
directional (one-way, or northbound/southbound-only), and is detected by
MANY physical gantries. On top of the pricing model, a road also has a
CHARGING POLICY, because "charge every gantry crossed" is not one universal
rule:

  - "once_per_road": the common case (flat / time_of_day roads, and a
    per_point road where only one of several toll points is the one you'd
    realistically hit on a given trip through it, e.g. a tunnel's main
    carriageway vs. its one alternate ramp) — charge once per trip no matter
    how many of the road's own gantries fire.
  - "cumulative_per_point": Hills M2's own pricing page states prices "vary
    based on ... the number of toll points traversed along the motorway" —
    i.e. it charges ADDITIVELY for each distinct toll point a trip actually
    passes (up to 6: North Ryde mainline, Pennant Hills Rd, M2-NCX, Herring
    & Christie Rds, Windsor Rd, Lane Cove Rd on-ramp). Modelled with real
    `TollPoint` rows, one per named toll point, each independently priced
    and independently charged.
  - "distance_metered": a `pricing_model` of "distance" or
    "distance_with_flagfall" — the road's own running-distance-since-entry
    logic already caps and never double-charges a second gantry of the same
    road; this policy value exists for visibility/documentation, not a
    separate code branch (see `app.services.tolls.apply_toll_detection`).

Cross City Tunnel and Lane Cove Tunnel ALSO have >1 named toll point each
(main tunnel vs. a cheaper ramp), but neither source states point charges
there are cumulative, and — unlike M2's 6 sequential points along one
corridor — CCT's and LCT's two points are a main tunnel and an alternate
ramp a real vehicle would use ONE of, not both. Per this pass's brief
("where the evidence is genuinely ambiguous, pick the interpretation that
does NOT overcharge"), both are modelled `charging_policy="once_per_road"`
even though their `pricing_model` is "per_point" — the first toll point
actually crossed sets the (correct, real, per-point) price; a second,
different point of the same road crossed later in the same trip is not
charged again. This is a genuine interpretation call, not a stated fact —
flagged here and in the seed script.

Four tables:

- `TollRoad` — static identity (id, operator, pricing model, charging
  policy, directionality, network grouping). NEVER carries a price column
  directly.
- `TollRoadPriceRevision` — dated pricing snapshots, one-to-many per road.
  Real NSW tolls reindex QUARTERLY (WestConnex annually) — see
  `app/data/nsw_toll_roads.json`'s own `notes`. Splitting pricing out like
  this means a quarterly reindexation INSERTS a new revision instead of
  UPDATE-ing the old one away, so a disputed historical trip can always be
  checked against the price that was actually in force on the day it
  happened (fare evidence — see `app.services.tolls.current_price_revision`),
  not today's price. `TollRoad.current_revision` (the highest `effective_date`
  not in the future) is what `app.services.tolls` actually prices against.
  Every revision now also carries `source_url` / `retrieved_at` — the
  citable page and date this exact figure was confirmed against (a hard
  requirement for a fare-regulated meter: every number must be traceable).
- `TollPoint` / `TollPointPriceRevision` — the same static-identity /
  dated-revision split, one level down, for a road whose
  `pricing_model == "per_point"` (M2, CCT, LCT). A `TollGantry` links to at
  most one `TollPoint` via `toll_point_id`; a road with no per-point
  structure (every other pricing model) leaves every one of its gantries'
  `toll_point_id` NULL and prices at the road level instead.
- `TollGantry` — one row per physical tolling point (141 total, real NSW
  Transport coordinates). `direction` is populated only where the SOURCE
  data recorded a per-gantry carriageway direction (roughly a third of them
  — see `scripts/seed_toll_roads.py`); NULL otherwise, in which case a
  directional road's direction check falls back to the trip's own GPS
  bearing (see `app.services.tolls.classify_bearing`). `sequence_position` /
  `cumulative_distance_km` (2026-09-09 cross-check pass) carry a REAL
  position/distance along the road for the handful of roads whose source
  chain data resolves cleanly (see `scripts/seed_toll_roads.py`'s
  `_REAL_ROAD_CHAIN_WAYPOINTS`); NULL for every other road, which keeps
  using the pre-existing chord approximation in `app.services.tolls`.

All four tables are platform-wide reference data with NO tenant_id column at
all — unlike `app.models.geofence.Geofence` (which supports tenant-owned ad
hoc zones alongside a nullable-tenant_id global reference set), a toll ROAD
is never tenant-specific in the first place, so there is no ownership
dimension to model. Every tenant's trips price against the exact same NSW
toll registry. Writes (price revisions) are platform-owner-only — see
`app/api/v1/toll_roads.py`.
"""
from __future__ import annotations

import uuid
from datetime import date
from decimal import Decimal

from sqlalchemy import JSON, Date, ForeignKey, Numeric, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base, TimestampMixin

_MONEY = Numeric(10, 2)
_RATE = Numeric(10, 4)

# Real pricing models present in the authoritative dataset, plus "unpriced"
# (this codebase's own label, not the source data's) for a road with no
# captured price at all (the genuine Rozelle Interchange tolled segment,
# St Peters to Rozelle — see nsw_toll_roads.json's ROZELLE_INTERCHANGE
# entry) or a toll point whose own revision is missing/incomplete — see
# app.services.tolls for why that's deliberately never auto-charged a
# guessed number.
#
# "toll_free" (2026-09-09 correction pass) is a DIFFERENT, distinct concept
# from "unpriced": a road in this state is not missing a price, it has a
# real, confirmed price of exactly zero (M12 Motorway — permanently
# toll-free by government policy; Iron Cove Link — the one WestConnex/
# Rozelle Interchange component that is toll-free). Before this pass both
# of these were incorrectly modelled as "unpriced", which made
# app.services.tolls treat a genuinely free road exactly like one whose
# price is merely unknown — flagging it in `Trip.unpriced_toll_road_ids`
# and surfacing a "this toll needs a price, enter manually" prompt to the
# driver for a road that should simply never charge anything. A
# "toll_free" road carries no TollRoadPriceRevision row at all (there is
# nothing to price) and is charged $0.00 the instant it's detected, with no
# revision lookup and no manual-price prompt — see
# app.services.tolls.apply_toll_detection's own docstring for the exact
# mechanism.
#
# "zone_flat" (a single ambiguous min-max range spanning a road's cheapest
# ramp toll to its full mainline toll, with no way to tell which gantry was
# which) is RETIRED as of the 2026-09-07 correction pass — every road that
# used to carry it (M2, CCT) now has the real per-toll-point prices Linkt
# actually publishes, modelled as "per_point" instead. See this module's
# docstring.
TOLL_PRICING_MODELS = {
    "flat",
    "per_point",
    "distance",
    "distance_with_flagfall",
    "time_of_day",
    "unpriced",
    "toll_free",
}

# Real `directional` values from the source dataset.
TOLL_DIRECTIONS = {
    "both",
    "one_way",
    "northbound_only",
    "southbound_only",
}

# Real `confidence` values from the source dataset.
TOLL_CONFIDENCE_LEVELS = {"verified", "needs_verification", "not_captured"}

# How a road's gantry crossings turn into a charge — see this module's
# docstring for what each value means and exactly which roads got which,
# including the CCT/LCT interpretation call.
TOLL_CHARGING_POLICIES = {
    "once_per_road",
    "cumulative_per_point",
    "distance_metered",
}


class TollRoad(Base, TimestampMixin):
    __tablename__ = "toll_roads"

    # Natural key -- the source dataset's own `id` (e.g. "M7", "SHB_SHT"),
    # not a synthetic UUID, so it can be embedded directly in
    # Trip.auto_tolled_roads / Trip.unpriced_toll_road_ids without a lookup.
    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    api_code: Mapped[str | None] = mapped_column(String(16), nullable=True)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    # Nullable: the one road in this table NOT from the authoritative
    # dataset (the "M12" stub -- see scripts/seed_toll_roads.py's module
    # docstring) has no known operator/directionality in the source data at
    # all, and NULL here is the honest way to say "not captured", rather than
    # defaulting to a guessed value like "both".
    operator: Mapped[str | None] = mapped_column(String(255), nullable=True)
    pricing_model: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    # See TOLL_CHARGING_POLICIES / this module's docstring. Defaults to the
    # ordinary case ("once_per_road") so every pre-existing/synthetic test
    # road that never set this explicitly keeps behaving exactly as before.
    charging_policy: Mapped[str] = mapped_column(
        String(32), nullable=False, default="once_per_road", server_default="once_per_road"
    )
    directional: Mapped[str | None] = mapped_column(String(32), nullable=True)
    description: Mapped[str | None] = mapped_column(String(1000), nullable=True)

    # Groups roads that share a single NETWORK-WIDE cap for one trip on top
    # of their own per-road cap -- today only WestConnex ("WESTCONNEX": M4,
    # M8, M5E, M4M8_LINK), whose Linkt page states a trip using more than one
    # WestConnex stage is capped at $12.74 (Class A) / $38.22 (Class B) for
    # the whole network. NULL for every road with no such group. The network
    # cap figures themselves live on TollRoadPriceRevision (network_cap_
    # class_a/b) since, like any other published toll figure, they can be
    # reindexed and must stay dated/traceable -- see app.services.tolls's
    # network-cap step in apply_toll_detection.
    network_group: Mapped[str | None] = mapped_column(String(32), nullable=True, index=True)

    # Real-data DERIVATION (not an invented figure) used ONLY as a FALLBACK
    # by the "distance" pricing model when no published $/km rate has been
    # captured on the revision itself (see TollRoadPriceRevision.
    # rate_per_km_class_a) -- backs out an effective $/km rate from the
    # road's published Class A cap divided by the max pairwise haversine
    # distance across the road's own real gantry coordinates. Westlink M7
    # (the only road that ever used this) now has a real published rate
    # (0.5252 $/km) and no longer needs it in practice -- kept, and still
    # populated at seed time, purely as an honest fallback for any future
    # "distance" road whose per-km rate hasn't been captured yet, per this
    # pass's instruction to demote (not delete) the derivation.
    derived_corridor_km: Mapped[Decimal | None] = mapped_column(Numeric(8, 3), nullable=True)

    # Free-text provenance note carried through from the raw dataset (or, for
    # a road that isn't in it -- see M12 below -- explaining why not).
    source_note: Mapped[str | None] = mapped_column(String(2000), nullable=True)

    gantries: Mapped[list[TollGantry]] = relationship(
        back_populates="road", cascade="all, delete-orphan"
    )
    price_revisions: Mapped[list[TollRoadPriceRevision]] = relationship(
        back_populates="road",
        cascade="all, delete-orphan",
        order_by="TollRoadPriceRevision.effective_date",
    )
    toll_points: Mapped[list[TollPoint]] = relationship(
        back_populates="road", cascade="all, delete-orphan"
    )


class TollRoadPriceRevision(Base, TimestampMixin):
    """A dated pricing snapshot for one `TollRoad` -- see that model's
    docstring for why prices are versioned here rather than columns on
    TollRoad itself. Every field below (other than `id`/`toll_road_id`) is
    copied verbatim from `app/data/nsw_toll_roads.json` for that road -- see
    `scripts/seed_toll_roads.py` -- `confidence`/`verify_note` in particular
    are NOT decoration: a `needs_verification` or `not_captured` row must
    stay visibly flagged as such all the way to the dashboard, since this is
    fare evidence for a legally rate-capped taxi meter.

    APPEND-ONLY: a price correction or quarterly reindexation always INSERTS
    a new row with its own `effective_date`; an existing row is never
    mutated or deleted, so a disputed historical trip always resolves to the
    price genuinely in force that day (see
    `app.services.tolls.current_price_revision`). The one exception this
    codebase takes deliberately, documented at the call site
    (`scripts/seed_toll_roads.py`): filling in a field that never previously
    existed/was never populated (e.g. adding `rate_per_km_class_a` to
    Westlink M7's existing 1-Jul-2026 row, or `confidence`/`source_url` on a
    `not_captured` WestConnex row whose price fields were all NULL) is
    completing a record that never priced -- and therefore never charged --
    anyone, not rewriting a price a passenger already paid.
    """

    __tablename__ = "toll_road_price_revisions"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    toll_road_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("toll_roads.id"), nullable=False, index=True
    )

    price_class_a_min: Mapped[Decimal | None] = mapped_column(_MONEY, nullable=True)
    price_class_a_max: Mapped[Decimal | None] = mapped_column(_MONEY, nullable=True)
    price_class_b_min: Mapped[Decimal | None] = mapped_column(_MONEY, nullable=True)
    price_class_b_max: Mapped[Decimal | None] = mapped_column(_MONEY, nullable=True)
    cap_class_a: Mapped[Decimal | None] = mapped_column(_MONEY, nullable=True)
    cap_class_b: Mapped[Decimal | None] = mapped_column(_MONEY, nullable=True)

    # "distance" / "distance_with_flagfall" pricing models only. A published
    # $/km rate (e.g. Westlink M7's real $0.5252, WestConnex's real
    # $0.6667) -- see app.services.tolls.effective_rate_per_km_class_a for
    # exactly how this takes priority over TollRoad.derived_corridor_km.
    rate_per_km_class_a: Mapped[Decimal | None] = mapped_column(_RATE, nullable=True)
    rate_per_km_class_b: Mapped[Decimal | None] = mapped_column(_RATE, nullable=True)
    # "distance_with_flagfall" only (WestConnex: $1.80 published flagfall).
    flagfall_class_a: Mapped[Decimal | None] = mapped_column(_MONEY, nullable=True)
    flagfall_class_b: Mapped[Decimal | None] = mapped_column(_MONEY, nullable=True)
    # Shared network-wide cap for TollRoad.network_group roads (WestConnex:
    # $12.74 / $38.22 for the whole network in one trip) -- duplicated onto
    # each network member's own revision rather than a separate table, since
    # every WestConnex road cites the exact same Linkt page/figure; see
    # app.services.tolls's network-cap step.
    network_cap_class_a: Mapped[Decimal | None] = mapped_column(_MONEY, nullable=True)
    network_cap_class_b: Mapped[Decimal | None] = mapped_column(_MONEY, nullable=True)

    # time_of_day pricing model only (SHB_SHT today) -- a JSON list of
    # {"band": "peak"|"off_peak"|"night", "price": "4.55", "windows": "..."}
    # objects, Class A only (what a taxi pays) -- copied verbatim from the
    # source dataset's own `time_of_day_rates_class_a`.
    time_of_day_rates_class_a: Mapped[list | None] = mapped_column(JSON, nullable=True)

    currency: Mapped[str] = mapped_column(String(3), nullable=False, default="AUD")
    gst_included: Mapped[bool] = mapped_column(nullable=False, default=True)
    effective_date: Mapped[date] = mapped_column(Date, nullable=False, index=True)
    indexation: Mapped[str] = mapped_column(String(32), nullable=False)
    confidence: Mapped[str] = mapped_column(String(32), nullable=False)
    verify_note: Mapped[str | None] = mapped_column(String(2000), nullable=True)

    # Provenance -- REQUIRED for every price seeded/corrected in the
    # 2026-09-07 pass (hard constraint: "every price must be traceable to a
    # cited source"). Nullable only because it did not exist as a column
    # before this pass, so historical rows predating it have nothing to
    # backfill it with; every row this pass writes or corrects sets both.
    source_url: Mapped[str | None] = mapped_column(String(500), nullable=True)
    retrieved_at: Mapped[date | None] = mapped_column(Date, nullable=True)

    road: Mapped[TollRoad] = relationship(back_populates="price_revisions")


class TollPoint(Base, TimestampMixin):
    """One named, individually-priced toll point on a `pricing_model ==
    "per_point"` road (M2's 6 points, CCT's 2, LCT's 2) -- see
    `app.models.toll`'s module docstring for the once-per-road vs
    cumulative-per-point charging-policy distinction this exists to serve.
    Static identity only, same TollRoad/TollRoadPriceRevision split, one
    level down -- see `TollPointPriceRevision` for the dated pricing."""

    __tablename__ = "toll_points"

    # Natural key, "{toll_road_id}:{slug}" (e.g. "M2:north_ryde") so it can
    # be embedded directly in Trip.auto_tolled_roads for a cumulative_per_
    # point road without a lookup -- same convention TollRoad.id itself
    # uses.
    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    toll_road_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("toll_roads.id"), nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    description: Mapped[str | None] = mapped_column(String(1000), nullable=True)
    source_note: Mapped[str | None] = mapped_column(String(2000), nullable=True)

    road: Mapped[TollRoad] = relationship(back_populates="toll_points")
    gantries: Mapped[list[TollGantry]] = relationship(back_populates="toll_point")
    price_revisions: Mapped[list[TollPointPriceRevision]] = relationship(
        back_populates="point",
        cascade="all, delete-orphan",
        order_by="TollPointPriceRevision.effective_date",
    )


class TollPointPriceRevision(Base, TimestampMixin):
    """A dated pricing snapshot for one `TollPoint` -- same append-only
    contract as `TollRoadPriceRevision`, see that model's docstring."""

    __tablename__ = "toll_point_price_revisions"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    toll_point_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("toll_points.id"), nullable=False, index=True
    )

    price_class_a: Mapped[Decimal | None] = mapped_column(_MONEY, nullable=True)
    price_class_b: Mapped[Decimal | None] = mapped_column(_MONEY, nullable=True)
    currency: Mapped[str] = mapped_column(String(3), nullable=False, default="AUD")
    gst_included: Mapped[bool] = mapped_column(nullable=False, default=True)
    effective_date: Mapped[date] = mapped_column(Date, nullable=False, index=True)
    indexation: Mapped[str] = mapped_column(String(32), nullable=False)
    confidence: Mapped[str] = mapped_column(String(32), nullable=False)
    verify_note: Mapped[str | None] = mapped_column(String(2000), nullable=True)
    source_url: Mapped[str | None] = mapped_column(String(500), nullable=True)
    retrieved_at: Mapped[date | None] = mapped_column(Date, nullable=True)

    point: Mapped[TollPoint] = relationship(back_populates="price_revisions")


class TollGantry(Base, TimestampMixin):
    __tablename__ = "toll_gantries"

    # Natural key -- the source dataset's own `gantry_id` string (already
    # globally unique across all 141 rows).
    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    toll_road_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("toll_roads.id"), nullable=False, index=True
    )
    # Which named TollPoint (of a per_point road) this physical gantry
    # belongs to -- NULL for every gantry of a non-per_point road (it prices
    # at the road level instead), and NULL for a per_point road's toll point
    # that has no real gantry coordinates captured at all (e.g. LCT's
    # Military Road E-Ramp -- see scripts/seed_toll_roads.py).
    toll_point_id: Mapped[str | None] = mapped_column(
        String(64), ForeignKey("toll_points.id"), nullable=True, index=True
    )

    location: Mapped[str] = mapped_column(String(255), nullable=False)
    ramp: Mapped[str | None] = mapped_column(String(16), nullable=True)  # entry|exit|NULL
    # north/south/east/westbound, where the source data records one (see
    # module docstring) -- NULL otherwise.
    direction: Mapped[str | None] = mapped_column(String(16), nullable=True)
    latitude: Mapped[float] = mapped_column(nullable=False)
    longitude: Mapped[float] = mapped_column(nullable=False)
    source_sheet: Mapped[str | None] = mapped_column(String(32), nullable=True)

    # REAL sequence position / cumulative distance along this gantry's own
    # road, sourced (2026-09-09 cross-check pass) from the official NSW toll
    # CartoDB source's `tollpoints_data` table -- a genuine ordered
    # point-chain with real point-to-point distances (`closest_cw` /
    # `km_to_closest_cw`), which `app/data/nsw_toll_gantries.csv` itself does
    # not carry at all (see `app.services.tolls`'s module docstring and
    # `_distance_to_road_corridor_m`'s docstring for the chord-approximation
    # gap this exists to close).
    #
    # NULL for every gantry whose road's real chain data does not resolve to
    # a single, consistent, non-cyclic sequence (see
    # `scripts/seed_toll_roads.py`'s `_REAL_ROAD_CHAIN_WAYPOINTS` for exactly
    # which roads qualified and which didn't, and why) -- a road left NULL
    # here falls back to the pre-existing chord approximation
    # (`_distance_to_road_corridor_m`), unchanged. `sequence_position` is
    # 0-based along the road's real chain (the direction the source data
    # itself walks it in, arbitrary but consistent); `cumulative_distance_km`
    # is the real distance from position 0 to this gantry along that chain,
    # NOT a straight-line/haversine distance. Both are set identically on
    # every gantry that shares one real waypoint location regardless of
    # carriageway direction (a position along the road does not depend on
    # which way traffic is moving past it).
    sequence_position: Mapped[int | None] = mapped_column(nullable=True)
    cumulative_distance_km: Mapped[Decimal | None] = mapped_column(Numeric(8, 3), nullable=True)

    road: Mapped[TollRoad] = relationship(back_populates="gantries")
    toll_point: Mapped[TollPoint | None] = relationship(back_populates="gantries")
