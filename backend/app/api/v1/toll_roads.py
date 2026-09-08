"""Toll roads domain router — read-only NSW toll-road registry
(`/v1/toll-roads`) for every authenticated user (this is platform-wide
reference data, not tenant-scoped — every tenant's trips price against the
exact same registry, see `app.models.toll`'s module docstring), plus a
platform-owner-only endpoint to add a new dated price revision (a quarterly
reindexation) WITHOUT destroying the old one — same pricing-is-platform-
admin-only rule `app/api/v1/geofences.py` already applies to toll geofences.

The dashboard's Tariff Studio "NSW Toll Roads" tab reads this to show the
real structure (road, pricing model, current price, direction, gantry count)
in place of the old flat-circle-per-road view.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.v1.platform import require_platform_owner
from app.core.database import get_session
from app.core.security import get_current_user, require_role
from app.models.toll import TollGantry, TollPoint, TollRoad, TollRoadPriceRevision
from app.models.user import User
from app.schemas.toll import (
    TollGantryRead,
    TollPointPriceRevisionRead,
    TollPointRead,
    TollRoadDetailRead,
    TollRoadPriceRevisionCreate,
    TollRoadPriceRevisionRead,
    TollRoadRead,
)
from app.services.tolls import current_price_revision, current_toll_point_price_revision

router = APIRouter(prefix="/v1/toll-roads", tags=["toll-roads"])

# Same two-step composition app/api/v1/geofences.py uses for its toll-kind
# write gate: role=owner first, then platform-tenant check.
_require_owner_role = require_role("owner")


async def _require_platform_owner(user: User = Depends(_require_owner_role)) -> None:
    await require_platform_owner(user=user)


async def _gantry_count(session: AsyncSession, road_id: str) -> int:
    result = await session.execute(
        select(func.count()).select_from(TollGantry).where(TollGantry.toll_road_id == road_id)
    )
    return result.scalar_one()


async def _toll_points(session: AsyncSession, road: TollRoad) -> list[TollPointRead]:
    """The named toll points of a `pricing_model == "per_point"` road, each
    with the price currently in force. Empty for every other road — those
    price at the road level, so a non-empty list here would be misleading
    rather than merely redundant.

    Returned on the LIST endpoint as well as the detail one, deliberately:
    M2/CCT/LCT have no meaningful road-level price at all (their min/max is a
    descriptive range across their points, never charged), so a road list
    without this has nothing real to show for them."""
    if road.pricing_model != "per_point":
        return []

    points = (
        await session.execute(select(TollPoint).where(TollPoint.toll_road_id == road.id).order_by(TollPoint.id))
    ).scalars().all()

    out: list[TollPointRead] = []
    for point in points:
        gantry_count = (
            await session.execute(
                select(func.count()).select_from(TollGantry).where(TollGantry.toll_point_id == point.id)
            )
        ).scalar_one()
        current = await current_toll_point_price_revision(session, toll_point_id=point.id)
        out.append(
            TollPointRead(
                id=point.id,
                toll_road_id=point.toll_road_id,
                name=point.name,
                description=point.description,
                source_note=point.source_note,
                gantry_count=gantry_count,
                current_price=(
                    TollPointPriceRevisionRead.model_validate(current) if current is not None else None
                ),
            )
        )
    return out


def _to_road_read(
    road: TollRoad,
    *,
    gantry_count: int,
    current: TollRoadPriceRevision | None,
    toll_points: list[TollPointRead],
) -> TollRoadRead:
    return TollRoadRead(
        id=road.id,
        api_code=road.api_code,
        name=road.name,
        operator=road.operator,
        pricing_model=road.pricing_model,
        charging_policy=road.charging_policy,
        network_group=road.network_group,
        directional=road.directional,
        description=road.description,
        derived_corridor_km=road.derived_corridor_km,
        source_note=road.source_note,
        gantry_count=gantry_count,
        current_price=TollRoadPriceRevisionRead.model_validate(current) if current is not None else None,
        toll_points=toll_points,
    )


@router.get("", response_model=list[TollRoadRead])
async def list_toll_roads(
    session: AsyncSession = Depends(get_session),
    _user: User = Depends(get_current_user),
) -> list[TollRoadRead]:
    result = await session.execute(select(TollRoad).order_by(TollRoad.id))
    roads = result.scalars().all()

    out: list[TollRoadRead] = []
    for road in roads:
        gantry_count = await _gantry_count(session, road.id)
        current = await current_price_revision(session, toll_road_id=road.id)
        out.append(
            _to_road_read(
                road,
                gantry_count=gantry_count,
                current=current,
                toll_points=await _toll_points(session, road),
            )
        )
    return out


@router.get("/gantries", response_model=list[TollGantryRead])
async def list_all_toll_gantries(
    session: AsyncSession = Depends(get_session),
    _user: User = Depends(get_current_user),
) -> list[TollGantryRead]:
    """Every physical toll gantry in the registry, flat, with real
    coordinates — 141 rows for the current NSW dataset.

    Exists so the dashboard can plot the whole registry on ONE map. The
    per-road detail endpoint already returns a road's own gantries, but
    building a registry-wide view from it costs one request per road and
    still leaves the caller stitching them together. 141 small rows is a
    single cheap response, and this is static reference data.

    MUST stay declared above `GET /{road_id}` — FastAPI matches routes in
    declaration order, so with the two swapped this path is swallowed as a
    road whose id is the literal string "gantries" and 404s.
    """
    gantries = (
        await session.execute(select(TollGantry).order_by(TollGantry.toll_road_id, TollGantry.id))
    ).scalars().all()
    return [TollGantryRead.model_validate(g) for g in gantries]


@router.get("/{road_id}", response_model=TollRoadDetailRead)
async def get_toll_road(
    road_id: str,
    session: AsyncSession = Depends(get_session),
    _user: User = Depends(get_current_user),
) -> TollRoadDetailRead:
    road = await session.get(TollRoad, road_id)
    if road is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Toll road not found")

    gantries = (
        await session.execute(
            select(TollGantry).where(TollGantry.toll_road_id == road_id).order_by(TollGantry.id)
        )
    ).scalars().all()
    revisions = (
        await session.execute(
            select(TollRoadPriceRevision)
            .where(TollRoadPriceRevision.toll_road_id == road_id)
            .order_by(TollRoadPriceRevision.effective_date)
        )
    ).scalars().all()
    current = await current_price_revision(session, toll_road_id=road_id)

    base = _to_road_read(
        road,
        gantry_count=len(gantries),
        current=current,
        toll_points=await _toll_points(session, road),
    )
    return TollRoadDetailRead(
        **base.model_dump(),
        gantries=[TollGantryRead.model_validate(g) for g in gantries],
        price_history=[TollRoadPriceRevisionRead.model_validate(r) for r in revisions],
    )


@router.post(
    "/{road_id}/price-revisions",
    response_model=TollRoadPriceRevisionRead,
    status_code=status.HTTP_201_CREATED,
)
async def create_price_revision(
    road_id: str,
    payload: TollRoadPriceRevisionCreate,
    session: AsyncSession = Depends(get_session),
    _admin: None = Depends(_require_platform_owner),
) -> TollRoadPriceRevision:
    """Adds a new dated pricing snapshot (a quarterly/annual reindexation)
    for `road_id` WITHOUT touching any earlier revision — see
    `app.models.toll.TollRoadPriceRevision`'s docstring for why history is
    never overwritten. 409s if a revision already exists at the exact same
    `effective_date` (use a different date, or this is a duplicate)."""
    road = await session.get(TollRoad, road_id)
    if road is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Toll road not found")

    existing = await session.execute(
        select(TollRoadPriceRevision).where(
            TollRoadPriceRevision.toll_road_id == road_id,
            TollRoadPriceRevision.effective_date == payload.effective_date,
        )
    )
    if existing.scalar_one_or_none() is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"A price revision for '{road_id}' already exists at effective_date={payload.effective_date}",
        )

    revision = TollRoadPriceRevision(toll_road_id=road_id, **payload.model_dump())
    session.add(revision)
    await session.commit()
    await session.refresh(revision)
    return revision
