"""Live NSW traffic cameras + hazards — fetch, normalize, and cache the
public Transport for NSW "live traffic" feeds into `app.models.traffic`, on
the same "lazy refresh off the next read" mechanism this backend already
uses for every other periodic behaviour.

SOURCE FEEDS (confirmed live, no API key, no auth header, no CORS/bot gating
observed — 2026-09-09; open licence, "Transport for NSW" /
opendata.transport.nsw.gov.au/dataset/live-traffic-hazards):

  - `https://www.livetraffic.com/traffic/hazards/incident.json`
  - `https://www.livetraffic.com/traffic/hazards/roadwork.json`
  - `https://www.livetraffic.com/traffic/hazards/flood.json`
  - `https://www.livetraffic.com/traffic/hazards/fire.json`
  - `https://www.livetraffic.com/traffic/hazards/alpine.json`
  - `https://www.livetraffic.com/traffic/hazards/majorevent.json`

    Each of the six is a GeoJSON `FeatureCollection`:
    `{type, lastPublished, layerName, rights, features: [{type: "Feature",
    id, geometry: {type: "Point", coordinates: [lng, lat]}, properties:
    {webLinks, headline, periods: [{closureType, roadextent, roadtype,
    direction, finishTime, fromDay, startTime, toDay}], speedLimit,
    expectedDelay, ended, isNewIncident, ...}}]}`. `id` is sometimes a plain
    int (`95130`), sometimes a float-formatted number (`225630.0`) — always
    normalized to a plain decimal string by `_normalize_id` before it
    touches the database, so the SAME source id always maps to the SAME row
    regardless of which JSON number representation this particular fetch
    happened to serialize it as.

  - `https://www.livetraffic.com/datajson/all-feeds-web.json` — a single
    ~2 MB plain JSON ARRAY (NOT a FeatureCollection) mixing several
    `eventType`s together. Live traffic CAMERAS are the rows with
    `eventType == "liveCams"`: `{type: "Feature", id, geometry: {type:
    "Point", coordinates: [lng, lat]}, properties: {region, title, view,
    direction, href, searchDates}, path, eventType: "liveCams"}` —
    `properties.href` is a direct JPEG snapshot URL that updates live
    server-side; this module stores the URL only, never fetches the image.

    OTHER `eventType` VALUES OBSERVED IN THIS SAME FILE (2026-09-09 sample,
    3228 rows total), noted here for a later pass that might want them —
    none of these are ingested by this module:
      Roadwork (1375), restAreas (850), generalHazards (346),
      liveCams (217, the ones this module DOES ingest), Flood (202),
      None/missing (128), Fire (61), MajorEvent (32), hvcs (7) [heavy
      vehicle checking stations, presumably], Crash (5), Breakdown (5).
    `Roadwork`/`Flood`/`Fire`/`MajorEvent`/`Crash`/`Breakdown` here look
    like the same underlying hazard rows the six dedicated
    `hazards/<category>.json` feeds above already carry, folded into one
    combined file — this module keeps using the dedicated per-category
    feeds for hazards (simpler shape, one concept per file) and only reads
    `all-feeds-web.json` for `liveCams`, which has no dedicated feed of its
    own. `restAreas`/`generalHazards`/`hvcs` are genuinely new concepts not
    modeled anywhere in this codebase yet.

Both fetched with a plain `httpx.AsyncClient` GET — no auth header, no key,
verified working from this dev machine on 2026-09-09.

NO SCHEDULER, BY DESIGN
------------------------
This backend has no APScheduler, task queue, cron, or lifespan background
worker — see `app.services.live_ops`'s module docstring and
`app.services.lazy_maintenance`'s. Every periodic behaviour in this codebase
runs lazily, off the next read or write that happens to pass through the
relevant code path. This module follows the exact same shape:
`ensure_cameras_fresh` / `ensure_hazards_fresh` are called from
`app/api/v1/traffic.py`'s two GET handlers, before each one queries the
table it is about to serve, and no-op unless the relevant cadence
(`settings.LIVE_TRAFFIC_CAMERAS_REFRESH_MINUTES` /
`_HAZARDS_REFRESH_MINUTES`) has actually elapsed since the last attempt.

Cadence tracking is a plain in-process module-level timestamp (`_last_camera_
refresh_attempt` / `_last_hazard_refresh_attempt`), the same accepted
trade-off `app.services.live_ops`'s in-memory position broadcaster already
takes for similar "doesn't need to survive a restart or be shared across
workers" state: a restart or a second uvicorn worker just means one extra
(cheap, idempotent) refresh happens sooner than the cadence would otherwise
allow — never a correctness problem, since `refresh_cameras`/`refresh_hazards`
themselves are safe to call as often as anyone likes. The timestamp is
updated on every ATTEMPT (success or failure) rather than only on success,
so a genuinely down upstream is retried once per cadence window, not once
per request, while it's unreachable.

NEVER RAISES (same contract as `app.services.lazy_maintenance`): a broken or
slow upstream feed must not turn `GET /v1/traffic/cameras` or
`GET /v1/traffic/hazards` into a 500 — the read is answered from whatever is
already cached, and the failure is logged at ERROR. Each `ensure_*_fresh`
call runs the actual refresh on its OWN, isolated `AsyncSessionLocal()`
session (never the caller's request session) for the identical reason
`app.services.lazy_maintenance` does — see that module's "ISOLATED SESSION"
note: a caught exception's implicit rollback must never expire objects the
caller's own session already loaded.

IDEMPOTENCY / STALE-DATA POLICY
--------------------------------
- `refresh_cameras`: upserts every `liveCams` row by the source's own `id`.
  Cameras rarely move or disappear, and the source dataset gives no
  "retired" signal for one (unlike a hazard's `ended` flag) — a camera
  merely missing from one particular pull is far more likely a transient
  fetch/parse hiccup than a real decommission, so this function only ever
  inserts/updates, it never deletes on a camera's absence. Calling it twice
  with the same fetch never duplicates a row (upsert by primary key).
- `refresh_hazards`: upserts every NOT-`ended` feature by source `id`, then
  DELETES every existing row of that category whose id was not in this
  pull's not-ended set — which covers both a hazard the source explicitly
  marked `ended: true` in this pull AND one that simply stopped being
  returned at all (the more common real-world case: the source's own
  `ended` flag is not reliably set before a hazard just drops out of the
  feed). Each of the six category feeds is treated as the CURRENT AUTHORITATIVE
  snapshot for that category — the "never silently accumulate stale
  reference data" instinct this codebase already applies elsewhere (e.g.
  `app.services.jobs.expire_stale_offers`) — so a hazard is deleted, not
  soft-flagged and left forever: this table is a live cache, not history.
  Calling it twice with the same fetch is a no-op the second time (nothing
  new to upsert, nothing to delete since every row already matches).
"""
from __future__ import annotations

import logging
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
from sqlalchemy import delete
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.database import AsyncSessionLocal
from app.models.traffic import TrafficCamera, TrafficHazard

logger = logging.getLogger("cab_dispatch.live_traffic")

CAMERA_FEED_URL = "https://www.livetraffic.com/datajson/all-feeds-web.json"

# category -> the dedicated hazards feed for it. Keys are exactly
# `app.models.traffic.TRAFFIC_HAZARD_CATEGORIES`.
HAZARD_FEEDS: dict[str, str] = {
    "incident": "https://www.livetraffic.com/traffic/hazards/incident.json",
    "roadwork": "https://www.livetraffic.com/traffic/hazards/roadwork.json",
    "flood": "https://www.livetraffic.com/traffic/hazards/flood.json",
    "fire": "https://www.livetraffic.com/traffic/hazards/fire.json",
    "alpine": "https://www.livetraffic.com/traffic/hazards/alpine.json",
    "majorevent": "https://www.livetraffic.com/traffic/hazards/majorevent.json",
}

_FETCH_TIMEOUT_S = 20.0

# In-process cadence gates -- see this module's docstring for why these are
# plain module-level timestamps rather than a persisted "last refreshed at"
# row.
_last_camera_refresh_attempt: datetime | None = None
_last_hazard_refresh_attempt: datetime | None = None


def _normalize_id(raw: Any) -> str:
    """A source `id` (int, float-formatted number, or string) as a stable
    plain-decimal string -- `225630.0` and `225630` and `"225630"` must all
    map to the same row. See this module's docstring."""
    if isinstance(raw, float) and raw.is_integer():
        return str(int(raw))
    return str(raw)


def _epoch_ms_to_datetime(epoch_ms: float | None) -> datetime:
    """`lastPublished`-style epoch milliseconds -> aware UTC datetime. Falls
    back to "now" for the (not observed in practice, but not contractually
    guaranteed) case of a missing/malformed value, rather than raising and
    losing the whole refresh over one cosmetic field."""
    if not epoch_ms:
        return datetime.now(UTC)
    try:
        return datetime.fromtimestamp(float(epoch_ms) / 1000.0, tz=UTC)
    except (OverflowError, OSError, ValueError):
        return datetime.now(UTC)


async def _fetch_json(client: httpx.AsyncClient, url: str) -> Any:
    response = await client.get(url, timeout=_FETCH_TIMEOUT_S)
    response.raise_for_status()
    return response.json()


# --- cameras ------------------------------------------------------------------


async def refresh_cameras(session: AsyncSession, *, client: httpx.AsyncClient | None = None) -> int:
    """Fetches `all-feeds-web.json`, filters to `eventType == "liveCams"`,
    and upserts each one by its source `id`. Returns the number of camera
    rows written (inserted + updated). Does not commit -- same
    "caller owns the transaction" contract as `app.services.tolls`; callers
    inside this module (`ensure_cameras_fresh`) commit their own isolated
    session, and a test calling this directly controls its own commit too.

    Safe to call repeatedly: a second call with the same fetched data
    updates the same rows in place rather than duplicating them (see this
    module's docstring for why cameras are never deleted on absence).
    """
    owns_client = client is None
    client = client or httpx.AsyncClient()
    try:
        rows = await _fetch_json(client, CAMERA_FEED_URL)
    finally:
        if owns_client:
            await client.aclose()

    cameras = [row for row in rows if isinstance(row, dict) and row.get("eventType") == "liveCams"]

    written = 0
    for entry in cameras:
        camera_id = _normalize_id(entry.get("id"))
        props = entry.get("properties") or {}
        geometry = entry.get("geometry") or {}
        coords = geometry.get("coordinates") or [None, None]
        lng, lat = coords[0], coords[1]
        if lat is None or lng is None:
            continue  # can't place a camera with no coordinates -- skip, don't crash the whole refresh

        href = props.get("href")
        if not href:
            continue  # a "camera" with no snapshot URL is useless to every caller of this endpoint

        existing = await session.get(TrafficCamera, camera_id)
        if existing is None:
            existing = TrafficCamera(id=camera_id)
            session.add(existing)
        existing.name = props.get("title") or camera_id
        existing.latitude = float(lat)
        existing.longitude = float(lng)
        existing.direction = props.get("direction") or None
        existing.image_url = href
        existing.region = props.get("region") or None
        written += 1

    return written


async def ensure_cameras_fresh(*, force: bool = False) -> None:
    """Lazy cadence gate for cameras — see this module's docstring. Called
    from `GET /v1/traffic/cameras` before it reads the table. Never raises."""
    global _last_camera_refresh_attempt
    now = datetime.now(UTC)
    if not force and _last_camera_refresh_attempt is not None:
        elapsed = now - _last_camera_refresh_attempt
        if elapsed < timedelta(minutes=settings.LIVE_TRAFFIC_CAMERAS_REFRESH_MINUTES):
            return

    _last_camera_refresh_attempt = now
    try:
        async with AsyncSessionLocal() as check_session:
            await refresh_cameras(check_session)
            await check_session.commit()
    except Exception:  # broad on purpose -- see this module's NEVER RAISES section
        logger.exception(
            "live traffic camera refresh failed; serving cameras from whatever is already cached"
        )


# --- hazards ------------------------------------------------------------------


def _extract_hazard_fields(feature: dict) -> dict:
    props = feature.get("properties") or {}
    periods = props.get("periods") or []
    first_period = periods[0] if periods else {}

    speed_limit = props.get("speedLimit")
    if speed_limit is None or speed_limit == -1:
        speed_limit = None

    expected_delay = props.get("expectedDelay")
    if expected_delay is None or expected_delay == -1:
        expected_delay = None

    return {
        "headline": props.get("headline") or None,
        "closure_type": first_period.get("closureType") or None,
        "direction": first_period.get("direction") or None,
        "speed_limit": speed_limit,
        "expected_delay_minutes": expected_delay,
        "raw_json": props,
    }


async def refresh_hazards(
    session: AsyncSession,
    *,
    categories: tuple[str, ...] = tuple(HAZARD_FEEDS),
    client: httpx.AsyncClient | None = None,
) -> int:
    """Fetches each of `categories`' own hazards feed and makes
    `traffic_hazards` for that category match it exactly: every NOT-`ended`
    feature is upserted by source `id`; every existing row of that category
    whose id is not among them is deleted. See this module's docstring for
    why deletion (not a soft `ended` flag left to accumulate) is the policy,
    and why an absent-from-the-feed hazard is treated the same as one
    explicitly marked `ended: true`.

    Returns the number of hazard rows upserted (not counting deletions).
    Does not commit -- same contract as `refresh_cameras`.
    """
    owns_client = client is None
    client = client or httpx.AsyncClient()
    try:
        written = 0
        for category in categories:
            url = HAZARD_FEEDS[category]
            feed = await _fetch_json(client, url)
            last_published = _epoch_ms_to_datetime(feed.get("lastPublished"))
            features = feed.get("features") or []

            active_ids: set[str] = set()
            for feature in features:
                geometry = feature.get("geometry") or {}
                coords = geometry.get("coordinates") or [None, None]
                lng, lat = coords[0], coords[1]
                if lat is None or lng is None:
                    continue  # can't place a hazard with no coordinates

                props = feature.get("properties") or {}
                if props.get("ended"):
                    continue  # never written -- an ended hazard is simply not upserted

                hazard_id = _normalize_id(feature.get("id"))
                active_ids.add(hazard_id)

                fields = _extract_hazard_fields(feature)
                existing = await session.get(TrafficHazard, hazard_id)
                if existing is None:
                    existing = TrafficHazard(id=hazard_id, category=category)
                    session.add(existing)
                existing.category = category
                existing.latitude = float(lat)
                existing.longitude = float(lng)
                existing.ended = False
                existing.source_last_published = last_published
                for field, value in fields.items():
                    setattr(existing, field, value)
                written += 1

            # Anything of THIS category left in the table but not in this
            # pull's active set is stale -- either explicitly ended, or just
            # no longer returned at all. Delete it (see docstring).
            # SQLAlchemy compiles `NOT IN ()` (an empty collection) to an
            # always-true predicate, so an empty `active_ids` (e.g. `fire`,
            # which returns zero features most days) correctly deletes every
            # existing row of that category rather than deleting nothing.
            await session.execute(
                delete(TrafficHazard).where(
                    TrafficHazard.category == category,
                    TrafficHazard.id.not_in(active_ids),
                )
            )
        return written
    finally:
        if owns_client:
            await client.aclose()


async def ensure_hazards_fresh(*, force: bool = False) -> None:
    """Lazy cadence gate for hazards — see this module's docstring. Called
    from `GET /v1/traffic/hazards` before it reads the table. Never raises."""
    global _last_hazard_refresh_attempt
    now = datetime.now(UTC)
    if not force and _last_hazard_refresh_attempt is not None:
        elapsed = now - _last_hazard_refresh_attempt
        if elapsed < timedelta(minutes=settings.LIVE_TRAFFIC_HAZARDS_REFRESH_MINUTES):
            return

    _last_hazard_refresh_attempt = now
    try:
        async with AsyncSessionLocal() as check_session:
            await refresh_hazards(check_session)
            await check_session.commit()
    except Exception:  # broad on purpose -- see this module's NEVER RAISES section
        logger.exception(
            "live traffic hazard refresh failed; serving hazards from whatever is already cached"
        )
