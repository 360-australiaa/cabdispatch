"""Live NSW traffic reference data — cameras and hazards, sourced from the
public, no-auth-required Transport for NSW "live traffic" feeds
(`https://www.livetraffic.com/traffic/hazards/*.json`,
`https://www.livetraffic.com/datajson/all-feeds-web.json`; open licence, see
`app.services.live_traffic`'s module docstring for the exact URLs and the
2026-09-09 confirmation that they need no API key).

Same "platform-wide reference data, no tenant_id column at all" pattern
`app.models.toll.TollRoad`/`TollGantry` already use: a traffic camera or
hazard is not tenant-specific — every tenant's tablets/dashboards see the
exact same live NSW picture. Writes happen ONLY from
`app.services.live_traffic`'s refresh functions; there is no tenant-facing
write endpoint for either table (see `app/api/v1/traffic.py`, read-only).

Two tables, deliberately thin — the third-party feed carries far more detail
than either one models (see the real `properties` shapes cited in
`app.services.live_traffic`'s module docstring): each keeps only the columns
a caller is actually expected to filter/sort/render on, and stows everything
else verbatim in `raw_json` rather than growing a column per feed field —
the same "don't over-model a third-party feed" pragmatism this codebase
already applies to `Trip.details_json`-style fields.

- `TrafficCamera` — one row per live traffic camera (`eventType == "liveCams"`
  in `all-feeds-web.json`). Cameras rarely move, so a `POST`less lazy refresh
  cadence of roughly an hour is plenty (see
  `app.services.live_traffic.ensure_cameras_fresh`).
- `TrafficHazard` — one row per hazard (incident, roadwork, flood, fire,
  alpine, or major-event closure) across the six separate
  `.../hazards/<category>.json` feeds, unified into one table with a
  `category` column rather than five near-identical tables — those feeds
  share one FeatureCollection shape and differ only in volume/urgency. Real
  hazards churn constantly (open/close within minutes), so
  `app.services.live_traffic.refresh_hazards` deletes a hazard the instant
  the source either marks it `ended: true` or simply stops returning it in a
  category's feed — see that function's docstring for exactly why DELETE
  (not a soft `ended` flag left to accumulate forever) is this table's
  policy, and how `active_only=True` on `GET /v1/traffic/hazards` still
  serves the common case even though nothing here is ever "kept around
  ended".
"""
from __future__ import annotations

from datetime import datetime

from sqlalchemy import JSON, DateTime, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TimestampMixin

# Real `layerName`/feed-category values from the source dataset (see
# `app.services.live_traffic.HAZARD_FEEDS`) — every `TrafficHazard.category`
# must be one of these.
TRAFFIC_HAZARD_CATEGORIES = {
    "incident",
    "roadwork",
    "flood",
    "fire",
    "alpine",
    "majorevent",
}


class TrafficCamera(Base, TimestampMixin):
    __tablename__ = "traffic_cameras"

    # Natural key — the source feed's own `id` (a GUID string for every
    # camera observed so far), NOT a synthetic UUID, so a re-fetch upserts
    # the same row instead of duplicating it. See
    # `app.services.live_traffic.refresh_cameras`.
    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    latitude: Mapped[float] = mapped_column(nullable=False)
    longitude: Mapped[float] = mapped_column(nullable=False)
    # Compass-letter direction the camera looks (e.g. "W", "NE") — free text
    # copied verbatim from the source `properties.direction`; not every
    # camera carries one.
    direction: Mapped[str | None] = mapped_column(String(16), nullable=True)
    # Direct JPEG snapshot URL (`properties.href`) — updates live
    # server-side; this table stores the URL only, never the image itself.
    image_url: Mapped[str] = mapped_column(String(500), nullable=False)
    # Source `properties.region` (e.g. "SYD_SOUTH") — free text, not
    # validated against a fixed set (the source's own region list is neither
    # small nor stable enough to hard-code here).
    region: Mapped[str | None] = mapped_column(String(64), nullable=True)


class TrafficHazard(Base, TimestampMixin):
    __tablename__ = "traffic_hazards"

    # Natural key — the source feed's own `id`. The real feeds mix numeric
    # (sometimes float-formatted, e.g. `225630.0`) and string ids across
    # categories, so `app.services.live_traffic` always normalizes to a
    # plain string before writing here (see that module's `_normalize_id`).
    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    category: Mapped[str] = mapped_column(String(16), nullable=False, index=True)
    latitude: Mapped[float] = mapped_column(nullable=False)
    longitude: Mapped[float] = mapped_column(nullable=False)
    headline: Mapped[str | None] = mapped_column(String(1000), nullable=True)
    # From the first entry of the source's own `properties.periods` list
    # (a hazard's periods can in principle carry several closure windows —
    # this codebase's own pragmatic simplification, matching the
    # "don't over-model a third-party feed" instinct above, keeps only the
    # first/primary one as queryable columns; the full list survives in
    # `raw_json` for a caller that needs the rest).
    closure_type: Mapped[str | None] = mapped_column(String(64), nullable=True)
    direction: Mapped[str | None] = mapped_column(String(64), nullable=True)
    # -1 in the source means "not provided" — normalized to NULL by
    # `app.services.live_traffic` rather than stored as a fake speed/delay.
    speed_limit: Mapped[int | None] = mapped_column(Integer, nullable=True)
    expected_delay_minutes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    # Always False for a row that exists — see this module's docstring: an
    # `ended: true` hazard is deleted outright by `refresh_hazards`, never
    # written with `ended=True` and left in the table. Kept as a real column
    # (rather than assumed) anyway, so a future policy change (soft-delete
    # instead of hard-delete) is a service-layer change only, no migration.
    ended: Mapped[bool] = mapped_column(nullable=False, default=False)
    # The source FeatureCollection's own top-level `lastPublished` (epoch ms),
    # copied onto every hazard fetched in that pull — when NSW itself last
    # published the feed this row came from, distinct from this row's own
    # `updated_at` (when OUR refresh last wrote it).
    source_last_published: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    # Everything else from the source `properties` object verbatim (webLinks,
    # mainCategory, adviceA/B/C, isMajor, incidentKind, ...) — see this
    # module's docstring for why this table doesn't grow a column per field.
    raw_json: Mapped[dict] = mapped_column(JSON, nullable=False, default=dict)
