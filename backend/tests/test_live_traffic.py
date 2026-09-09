"""Tests for the live NSW traffic domain: `app.services.live_traffic`'s
refresh functions (idempotency, ended-hazard cleanup, real-feed-shape
parsing) and `/v1/traffic/cameras` + `/v1/traffic/hazards` (bbox/category/
active_only filtering).

The fixture JSON below is a trimmed, but otherwise VERBATIM, sample of the
real shapes fetched from the live public feeds on 2026-09-09 (see
`app.services.live_traffic`'s module docstring) — not an invented shape.
"""
from __future__ import annotations

import json

import httpx
import pytest
from sqlalchemy import select

from app.models.traffic import TrafficCamera, TrafficHazard
from app.services import live_traffic
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


# --- realistic fixture data (trimmed, real shapes) ---------------------------

# A trimmed slice of the real `all-feeds-web.json` array: two `liveCams`
# entries plus a few of the OTHER real `eventType`s observed mixed into that
# same file (Roadwork, Fire, and one with no eventType at all) — included so
# the "filter to liveCams only" logic is actually exercised, not just
# assumed.
_CAMERA_FEED_SAMPLE = [
    {
        "type": "Feature",
        "id": "023651ee-389c-4677-978e-d39b6c24c1e7",
        "geometry": {"type": "Point", "coordinates": [151.10533, -34.02977]},
        "properties": {
            "region": "SYD_SOUTH",
            "title": "5 Ways (Miranda)",
            "view": "5 Ways at The Boulevarde looking west towards Sutherland.",
            "direction": "W",
            "href": "https://webcams.transport.nsw.gov.au/livetraffic-webcams/cameras/5_ways_miranda.jpeg",
            "searchDates": ", , , ",
        },
        "eventCategory": "liveCams",
        "path": "5-ways-miranda",
        "eventType": "liveCams",
    },
    {
        "type": "Feature",
        "id": "b1c2d3e4-5678-90ab-cdef-1234567890ab",
        "geometry": {"type": "Point", "coordinates": [151.2093, -33.8688]},
        "properties": {
            "region": "SYD_CBD",
            "title": "Sydney Harbour Bridge",
            "view": "Looking north across the bridge.",
            "direction": "N",
            "href": "https://webcams.transport.nsw.gov.au/livetraffic-webcams/cameras/shb.jpeg",
            "searchDates": "",
        },
        "eventCategory": "liveCams",
        "path": "shb",
        "eventType": "liveCams",
    },
    {
        "type": "Feature",
        "id": 445566,
        "geometry": {"type": "Point", "coordinates": [151.0, -33.9]},
        "properties": {"headline": "Lane closure"},
        "eventType": "Roadwork",
    },
    {
        "type": "Feature",
        "id": 778899,
        "geometry": {"type": "Point", "coordinates": [150.5, -34.5]},
        "properties": {"headline": "Bushfire"},
        "eventType": "Fire",
    },
    {
        "type": "Feature",
        "id": 990011,
        "geometry": {"type": "Point", "coordinates": [150.1, -34.1]},
        "properties": {},
        "eventType": None,
    },
]


def _incident_feed(features: list[dict], *, last_published: int = 1788962670000) -> dict:
    return {
        "type": "FeatureCollection",
        "lastPublished": last_published,
        "layerName": "Incident",
        "rights": {
            "copyright": "Transport for NSW",
            "licence": "https://opendata.transport.nsw.gov.au/dataset/live-traffic-hazards",
        },
        "features": features,
    }


# The real feature observed live, trimmed of a few purely-decorative fields
# but otherwise byte-for-byte the shape returned by
# https://www.livetraffic.com/traffic/hazards/incident.json on 2026-09-09,
# INCLUDING the float-formatted `id` (`225630.0`) and the `-1` "not
# provided" sentinels.
_REAL_INCIDENT_FEATURE = {
    "type": "Feature",
    "id": 225630.0,
    "geometry": {"type": "Point", "coordinates": [151.1448757, -33.9613516], "collections": []},
    "properties": {
        "webLinks": [{"linkText": "M6 Stage 1", "linkURL": "https://caportal.com.au/rms/m6/"}],
        "headline": "",
        "periods": [
            {
                "closureType": "ROAD_CLOSURE",
                "roadextent": "Affected",
                "roadtype": "",
                "direction": "Both directions",
                "finishTime": "",
                "fromDay": "Every Day",
                "startTime": "all day",
                "toDay": "",
            }
        ],
        "speedLimit": -1,
        "weblinkUrl": None,
        "expectedDelay": -1,
        "ended": False,
        "isNewIncident": False,
        "mainCategory": "CHANGED TRAFFIC CONDITIONS",
        "displayName": "CHANGED TRAFFIC CONDITIONS M6 Stage 1",
        "isMajor": False,
    },
}

_REAL_INCIDENT_FEATURE_2 = {
    "type": "Feature",
    "id": 95130,
    "geometry": {"type": "Point", "coordinates": [148.386062, -35.753159]},
    "properties": {
        "webLinks": [],
        "headline": "Second real incident",
        "periods": [
            {
                "closureType": "LANE_CLOSURE",
                "roadextent": "Affected",
                "roadtype": "",
                "direction": "Northbound",
                "finishTime": "",
                "fromDay": "Every Day",
                "startTime": "",
                "toDay": "",
            }
        ],
        "speedLimit": 60,
        "expectedDelay": 15,
        "ended": False,
        "isNewIncident": True,
        "mainCategory": "INCIDENT",
    },
}


def _mock_client(handler) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def _json_route(payload):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=payload)

    return handler


# --- refresh_cameras ----------------------------------------------------------


async def test_refresh_cameras_filters_to_livecams_only(session):
    client = _mock_client(_json_route(_CAMERA_FEED_SAMPLE))
    written = await live_traffic.refresh_cameras(session, client=client)
    await client.aclose()
    await session.commit()

    assert written == 2
    rows = (await session.execute(select(TrafficCamera))).scalars().all()
    assert {r.id for r in rows} == {
        "023651ee-389c-4677-978e-d39b6c24c1e7",
        "b1c2d3e4-5678-90ab-cdef-1234567890ab",
    }
    shb = await session.get(TrafficCamera, "b1c2d3e4-5678-90ab-cdef-1234567890ab")
    assert shb.name == "Sydney Harbour Bridge"
    assert shb.latitude == pytest.approx(-33.8688)
    assert shb.longitude == pytest.approx(151.2093)
    assert shb.direction == "N"
    assert shb.image_url == "https://webcams.transport.nsw.gov.au/livetraffic-webcams/cameras/shb.jpeg"
    assert shb.region == "SYD_CBD"


async def test_refresh_cameras_twice_does_not_duplicate_rows(session):
    for _ in range(2):
        client = _mock_client(_json_route(_CAMERA_FEED_SAMPLE))
        await live_traffic.refresh_cameras(session, client=client)
        await client.aclose()
        await session.commit()

    rows = (await session.execute(select(TrafficCamera))).scalars().all()
    assert len(rows) == 2  # still exactly 2, not 4


async def test_refresh_cameras_updates_existing_row_in_place(session):
    client = _mock_client(_json_route(_CAMERA_FEED_SAMPLE))
    await live_traffic.refresh_cameras(session, client=client)
    await client.aclose()
    await session.commit()

    moved = json.loads(json.dumps(_CAMERA_FEED_SAMPLE))
    moved[1]["properties"]["title"] = "Sydney Harbour Bridge (renamed)"
    moved[1]["properties"]["direction"] = "S"

    client2 = _mock_client(_json_route(moved))
    await live_traffic.refresh_cameras(session, client=client2)
    await client2.aclose()
    await session.commit()

    rows = (await session.execute(select(TrafficCamera))).scalars().all()
    assert len(rows) == 2
    shb = await session.get(TrafficCamera, "b1c2d3e4-5678-90ab-cdef-1234567890ab")
    assert shb.name == "Sydney Harbour Bridge (renamed)"
    assert shb.direction == "S"


# --- refresh_hazards -----------------------------------------------------------


async def test_refresh_hazards_parses_real_feature_shape(session):
    feed = _incident_feed([_REAL_INCIDENT_FEATURE])

    def handler(request: httpx.Request) -> httpx.Response:
        assert str(request.url) == live_traffic.HAZARD_FEEDS["incident"]
        return httpx.Response(200, json=feed)

    client = _mock_client(handler)
    written = await live_traffic.refresh_hazards(session, categories=("incident",), client=client)
    await client.aclose()
    await session.commit()

    assert written == 1
    row = await session.get(TrafficHazard, "225630")  # normalized from the float 225630.0
    assert row is not None
    assert row.category == "incident"
    assert row.latitude == pytest.approx(-33.9613516)
    assert row.longitude == pytest.approx(151.1448757)
    assert row.closure_type == "ROAD_CLOSURE"
    assert row.direction == "Both directions"
    # -1 sentinels normalized to NULL, not stored literally.
    assert row.speed_limit is None
    assert row.expected_delay_minutes is None
    assert row.ended is False
    assert row.raw_json["mainCategory"] == "CHANGED TRAFFIC CONDITIONS"


async def test_refresh_hazards_twice_does_not_duplicate_rows(session):
    feed = _incident_feed([_REAL_INCIDENT_FEATURE, _REAL_INCIDENT_FEATURE_2])
    for _ in range(2):
        client = _mock_client(_json_route(feed))
        await live_traffic.refresh_hazards(session, categories=("incident",), client=client)
        await client.aclose()
        await session.commit()

    rows = (
        await session.execute(select(TrafficHazard).where(TrafficHazard.category == "incident"))
    ).scalars().all()
    assert len(rows) == 2


async def test_refresh_hazards_ended_true_removes_row_on_next_refresh(session):
    feed = _incident_feed([_REAL_INCIDENT_FEATURE, _REAL_INCIDENT_FEATURE_2])
    client = _mock_client(_json_route(feed))
    await live_traffic.refresh_hazards(session, categories=("incident",), client=client)
    await client.aclose()
    await session.commit()
    assert await session.get(TrafficHazard, "225630") is not None

    ended_feature = json.loads(json.dumps(_REAL_INCIDENT_FEATURE))
    ended_feature["properties"]["ended"] = True
    feed_with_ended = _incident_feed([ended_feature, _REAL_INCIDENT_FEATURE_2])

    client2 = _mock_client(_json_route(feed_with_ended))
    await live_traffic.refresh_hazards(session, categories=("incident",), client=client2)
    await client2.aclose()
    await session.commit()

    assert await session.get(TrafficHazard, "225630") is None  # ended=true -> deleted, not kept
    assert await session.get(TrafficHazard, "95130") is not None  # untouched sibling stays


async def test_refresh_hazards_dropped_from_feed_removes_row_too(session):
    """A hazard that simply stops appearing in the feed at all (no explicit
    `ended: true` in this pull) is treated the same as one explicitly ended
    — see `app.services.live_traffic.refresh_hazards`'s docstring."""
    feed = _incident_feed([_REAL_INCIDENT_FEATURE, _REAL_INCIDENT_FEATURE_2])
    client = _mock_client(_json_route(feed))
    await live_traffic.refresh_hazards(session, categories=("incident",), client=client)
    await client.aclose()
    await session.commit()

    feed_dropped = _incident_feed([_REAL_INCIDENT_FEATURE_2])  # 225630 simply gone
    client2 = _mock_client(_json_route(feed_dropped))
    await live_traffic.refresh_hazards(session, categories=("incident",), client=client2)
    await client2.aclose()
    await session.commit()

    assert await session.get(TrafficHazard, "225630") is None
    assert await session.get(TrafficHazard, "95130") is not None


async def test_refresh_hazards_empty_feed_clears_category(session):
    feed = _incident_feed([_REAL_INCIDENT_FEATURE])
    client = _mock_client(_json_route(feed))
    await live_traffic.refresh_hazards(session, categories=("incident",), client=client)
    await client.aclose()
    await session.commit()
    assert await session.get(TrafficHazard, "225630") is not None

    empty_feed = _incident_feed([])
    client2 = _mock_client(_json_route(empty_feed))
    await live_traffic.refresh_hazards(session, categories=("incident",), client=client2)
    await client2.aclose()
    await session.commit()

    rows = (
        await session.execute(select(TrafficHazard).where(TrafficHazard.category == "incident"))
    ).scalars().all()
    assert rows == []


# --- API endpoints: bbox / category / active_only ------------------------------


async def _seed_camera(session, *, id_: str, lat: float, lng: float, name: str = "Cam") -> None:
    session.add(
        TrafficCamera(
            id=id_,
            name=name,
            latitude=lat,
            longitude=lng,
            direction=None,
            image_url=f"https://webcams.transport.nsw.gov.au/{id_}.jpeg",
            region=None,
        )
    )
    await session.commit()


async def _seed_hazard(
    session, *, id_: str, category: str, lat: float, lng: float, ended: bool = False
) -> None:
    import datetime as _dt

    session.add(
        TrafficHazard(
            id=id_,
            category=category,
            latitude=lat,
            longitude=lng,
            headline="test hazard",
            closure_type=None,
            direction=None,
            speed_limit=None,
            expected_delay_minutes=None,
            ended=ended,
            source_last_published=_dt.datetime.now(_dt.UTC),
            raw_json={},
        )
    )
    await session.commit()


@pytest.fixture(autouse=True)
def _no_live_fetch(monkeypatch):
    """Every endpoint test below seeds the DB directly and must never trigger
    a real network call via the lazy refresh gate — patch both `ensure_*_fresh`
    functions (as imported into the router module) to no-ops."""

    async def _noop(*args, **kwargs):
        return None

    monkeypatch.setattr("app.api.v1.traffic.ensure_cameras_fresh", _noop)
    monkeypatch.setattr("app.api.v1.traffic.ensure_hazards_fresh", _noop)


async def test_cameras_endpoint_bbox_filters(client, session):
    headers = await auth_headers(client, session, role="driver")
    await _seed_camera(session, id_="cam-in", lat=-33.86, lng=151.21, name="In bbox")
    await _seed_camera(session, id_="cam-out", lat=-37.8, lng=144.9, name="Out of bbox (Melbourne)")

    resp = await client.get(
        "/v1/traffic/cameras",
        params={"bbox": "150.5,-34.5,152.0,-33.5"},
        headers=headers,
    )
    assert resp.status_code == 200
    body = resp.json()
    ids = {row["id"] for row in body["items"]}
    # Other tests in this shared-DB session may have left other in-bbox rows
    # behind (see tests/conftest.py's session-scoped test database) -- assert
    # membership, not an exact set, but "cam-in" must be present and
    # "cam-out" (genuinely outside this bbox) must never be.
    assert "cam-in" in ids
    assert "cam-out" not in ids


async def test_cameras_endpoint_no_bbox_returns_all(client, session):
    headers = await auth_headers(client, session, role="admin")
    await _seed_camera(session, id_="cam-a", lat=-33.86, lng=151.21)
    await _seed_camera(session, id_="cam-b", lat=-37.8, lng=144.9)

    resp = await client.get("/v1/traffic/cameras", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] >= 2
    assert {"cam-a", "cam-b"}.issubset({row["id"] for row in body["items"]})


async def test_cameras_endpoint_bad_bbox_422(client, session):
    headers = await auth_headers(client, session, role="driver")
    resp = await client.get("/v1/traffic/cameras", params={"bbox": "not,a,bbox"}, headers=headers)
    assert resp.status_code == 422


async def test_hazards_endpoint_active_only_default_excludes_ended(client, session):
    headers = await auth_headers(client, session, role="driver")
    await _seed_hazard(session, id_="haz-active", category="incident", lat=-33.9, lng=151.1, ended=False)
    await _seed_hazard(session, id_="haz-ended", category="incident", lat=-33.9, lng=151.1, ended=True)

    resp = await client.get("/v1/traffic/hazards", headers=headers)
    assert resp.status_code == 200
    ids = {row["id"] for row in resp.json()["items"]}
    assert "haz-active" in ids
    assert "haz-ended" not in ids

    resp2 = await client.get("/v1/traffic/hazards", params={"active_only": "false"}, headers=headers)
    ids2 = {row["id"] for row in resp2.json()["items"]}
    assert {"haz-active", "haz-ended"}.issubset(ids2)


async def test_hazards_endpoint_category_filter(client, session):
    headers = await auth_headers(client, session, role="driver")
    await _seed_hazard(session, id_="haz-incident", category="incident", lat=-33.9, lng=151.1)
    await _seed_hazard(session, id_="haz-flood", category="flood", lat=-33.9, lng=151.1)

    resp = await client.get("/v1/traffic/hazards", params={"category": "flood"}, headers=headers)
    assert resp.status_code == 200
    items = resp.json()["items"]
    assert all(row["category"] == "flood" for row in items)
    assert any(row["id"] == "haz-flood" for row in items)
    assert not any(row["id"] == "haz-incident" for row in items)


async def test_hazards_endpoint_invalid_category_422(client, session):
    headers = await auth_headers(client, session, role="driver")
    resp = await client.get("/v1/traffic/hazards", params={"category": "nonsense"}, headers=headers)
    assert resp.status_code == 422


async def test_hazards_endpoint_bbox_filters(client, session):
    headers = await auth_headers(client, session, role="driver")
    await _seed_hazard(session, id_="haz-in", category="incident", lat=-33.86, lng=151.21)
    await _seed_hazard(session, id_="haz-out", category="incident", lat=-37.8, lng=144.9)

    resp = await client.get(
        "/v1/traffic/hazards",
        params={"bbox": "150.5,-34.5,152.0,-33.5"},
        headers=headers,
    )
    assert resp.status_code == 200
    ids = {row["id"] for row in resp.json()["items"]}
    # Same shared-DB caveat as test_cameras_endpoint_bbox_filters above.
    assert "haz-in" in ids
    assert "haz-out" not in ids
