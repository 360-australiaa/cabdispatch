"""Live NSW traffic domain router — read-only `/v1/traffic/cameras` and
`/v1/traffic/hazards`, backed by `app.models.traffic` and kept fresh by
`app.services.live_traffic`'s lazy refresh (see that module's docstring for
the no-scheduler-in-this-codebase rationale and the exact cadence each table
refreshes on).

Platform-wide reference data, same visibility rule `app/api/v1/toll_roads.py`
already uses for the NSW toll registry: any authenticated user of any role
may read it (this is informational — "where are the cameras/hazards right
now" — not pricing, so no `require_platform_owner`/admin gate). There is no
tenant-id column on either table and therefore no tenant filtering to apply
(every tenant's tablets/dashboards see the exact same live NSW picture).

Computing "how far ahead on THIS route is this hazard" is explicitly out of
scope here — that's the tablet/dashboard's job, client-side, from the raw
`latitude`/`longitude` these endpoints return.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_user
from app.models.traffic import TRAFFIC_HAZARD_CATEGORIES, TrafficCamera, TrafficHazard
from app.models.user import User
from app.schemas.traffic import Page, TrafficCameraRead, TrafficHazardRead
from app.services.live_traffic import ensure_cameras_fresh, ensure_hazards_fresh

router = APIRouter(prefix="/v1/traffic", tags=["traffic"])


def _parse_bbox(bbox: str | None) -> tuple[float, float, float, float] | None:
    """`"minLng,minLat,maxLng,maxLat"` -> a 4-tuple of floats, or None if
    `bbox` itself is None. 422s (via HTTPException, caught by the route) on
    anything malformed rather than silently ignoring a bad filter."""
    if bbox is None:
        return None
    parts = bbox.split(",")
    if len(parts) != 4:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="bbox must be 'minLng,minLat,maxLng,maxLat'",
        )
    try:
        min_lng, min_lat, max_lng, max_lat = (float(p) for p in parts)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="bbox must be 'minLng,minLat,maxLng,maxLat' (four numbers)",
        ) from exc
    if min_lng > max_lng or min_lat > max_lat:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="bbox min must not exceed max on either axis",
        )
    return (min_lng, min_lat, max_lng, max_lat)


@router.get("/cameras", response_model=Page[TrafficCameraRead])
async def list_traffic_cameras(
    bbox: str | None = Query(default=None, description="minLng,minLat,maxLng,maxLat"),
    skip: int = Query(default=0, ge=0),
    limit: int = Query(default=50, ge=1, le=200),
    session: AsyncSession = Depends(get_session),
    _user: User = Depends(get_current_user),
) -> Page[TrafficCameraRead]:
    parsed_bbox = _parse_bbox(bbox)
    await ensure_cameras_fresh()

    stmt = select(TrafficCamera)
    count_stmt = select(func.count()).select_from(TrafficCamera)
    if parsed_bbox is not None:
        min_lng, min_lat, max_lng, max_lat = parsed_bbox
        bounds = (
            TrafficCamera.longitude >= min_lng,
            TrafficCamera.longitude <= max_lng,
            TrafficCamera.latitude >= min_lat,
            TrafficCamera.latitude <= max_lat,
        )
        stmt = stmt.where(*bounds)
        count_stmt = count_stmt.where(*bounds)

    total = (await session.execute(count_stmt)).scalar_one()
    rows = (
        (await session.execute(stmt.order_by(TrafficCamera.id).offset(skip).limit(limit))).scalars().all()
    )
    return Page(items=list(rows), total=total, skip=skip, limit=limit)


@router.get("/hazards", response_model=Page[TrafficHazardRead])
async def list_traffic_hazards(
    bbox: str | None = Query(default=None, description="minLng,minLat,maxLng,maxLat"),
    category: str | None = Query(default=None),
    active_only: bool = Query(default=True),
    skip: int = Query(default=0, ge=0),
    limit: int = Query(default=50, ge=1, le=200),
    session: AsyncSession = Depends(get_session),
    _user: User = Depends(get_current_user),
) -> Page[TrafficHazardRead]:
    if category is not None and category not in TRAFFIC_HAZARD_CATEGORIES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"category must be one of {sorted(TRAFFIC_HAZARD_CATEGORIES)}",
        )
    parsed_bbox = _parse_bbox(bbox)
    await ensure_hazards_fresh()

    stmt = select(TrafficHazard)
    count_stmt = select(func.count()).select_from(TrafficHazard)

    # `active_only=True` (the default) excludes `ended` rows — in practice
    # this rarely filters anything out, since `app.services.live_traffic.
    # refresh_hazards` deletes an ended hazard outright rather than leaving
    # it flagged (see that module's docstring); kept as a real, honoured
    # filter anyway so a caller that explicitly wants history-that-happens-
    # to-still-be-cached (`active_only=false`) gets it, and so this
    # endpoint's contract does not silently change if that deletion policy
    # is ever revisited.
    if active_only:
        stmt = stmt.where(TrafficHazard.ended.is_(False))
        count_stmt = count_stmt.where(TrafficHazard.ended.is_(False))
    if category is not None:
        stmt = stmt.where(TrafficHazard.category == category)
        count_stmt = count_stmt.where(TrafficHazard.category == category)
    if parsed_bbox is not None:
        min_lng, min_lat, max_lng, max_lat = parsed_bbox
        bounds = (
            TrafficHazard.longitude >= min_lng,
            TrafficHazard.longitude <= max_lng,
            TrafficHazard.latitude >= min_lat,
            TrafficHazard.latitude <= max_lat,
        )
        stmt = stmt.where(*bounds)
        count_stmt = count_stmt.where(*bounds)

    total = (await session.execute(count_stmt)).scalar_one()
    rows = (
        (await session.execute(stmt.order_by(TrafficHazard.id).offset(skip).limit(limit))).scalars().all()
    )
    return Page(items=list(rows), total=total, skip=skip, limit=limit)
