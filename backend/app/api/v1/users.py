"""Users domain router: staff (owner/admin/dispatcher) and driver CRUD.

Added post-integration to close a real gap — none of the 12 parallel domain
slices owned "create a driver via the API" (only scripts/seed.py could create
users), which breaks the "full CRUD on every entity" requirement since Trips
requires a real driver_id and Fleet/Live-Ops both assume drivers already exist.

Every query is tenant-scoped via get_current_tenant_id, same as every other
domain router — see app/core/security.py.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, hash_password, require_role
from app.models.user import User
from app.schemas.user import Page, UserCreate, UserRead, UserUpdate
from app.services import user as user_service

router = APIRouter(prefix="/v1/users", tags=["users"])

_require_admin = require_role("owner", "admin")


def _user_error_to_http(exc: user_service.UserError) -> HTTPException:
    if isinstance(exc, user_service.UserNotFoundError):
        return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    if isinstance(exc, user_service.DuplicateEmailError):
        return HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail=f"email already in use: {exc}"
        )
    if isinstance(exc, user_service.DuplicateDriverCodeError):
        return HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail=f"driver_code already in use: {exc}"
        )
    return HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))


@router.get("", response_model=Page[UserRead])
async def list_users(
    skip: int = Query(0, ge=0),
    limit: int = Query(20, ge=1, le=100),
    role: str | None = Query(default=None),
    status_filter: str | None = Query(default=None, alias="status"),
    name: str | None = Query(default=None, description="Case-insensitive partial match"),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    stmt = select(User).where(User.tenant_id == tenant_id)
    count_stmt = select(func.count()).select_from(User).where(User.tenant_id == tenant_id)

    if role is not None:
        stmt = stmt.where(User.role == role)
        count_stmt = count_stmt.where(User.role == role)
    if status_filter is not None:
        stmt = stmt.where(User.status == status_filter)
        count_stmt = count_stmt.where(User.status == status_filter)
    if name is not None:
        pattern = f"%{name.lower()}%"
        stmt = stmt.where(func.lower(User.name).like(pattern))
        count_stmt = count_stmt.where(func.lower(User.name).like(pattern))

    total = (await session.execute(count_stmt)).scalar_one()
    result = await session.execute(stmt.order_by(User.name).offset(skip).limit(limit))
    items = result.scalars().all()
    return Page[UserRead](items=items, total=total, skip=skip, limit=limit)


@router.post("", response_model=UserRead, status_code=status.HTTP_201_CREATED)
async def create_user(
    payload: UserCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    try:
        await user_service.assert_email_available(session, email=payload.email)
        if payload.driver_code is not None:
            await user_service.assert_driver_code_available(session, driver_code=payload.driver_code)
    except user_service.UserError as exc:
        raise _user_error_to_http(exc) from exc

    data = payload.model_dump(exclude={"password", "driver_code"})
    driver_code = payload.driver_code
    # Auto-generate a driver_code for role="driver" accounts that didn't supply
    # one — only drivers get one (see app/models/user.py::User.driver_code).
    if driver_code is None and payload.role == "driver":
        driver_code = await user_service.generate_unique_driver_code(session)

    user = User(
        tenant_id=tenant_id,
        pin_hash=hash_password(payload.password),
        driver_code=driver_code,
        **data,
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)
    return user


@router.get("/{user_id}", response_model=UserRead)
async def get_user(
    user_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    try:
        return await user_service.get_user_or_404(session, tenant_id=tenant_id, user_id=user_id)
    except user_service.UserError as exc:
        raise _user_error_to_http(exc) from exc


@router.patch("/{user_id}", response_model=UserRead)
async def update_user(
    user_id: str,
    payload: UserUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    try:
        user = await user_service.get_user_or_404(session, tenant_id=tenant_id, user_id=user_id)
    except user_service.UserError as exc:
        raise _user_error_to_http(exc) from exc

    updates = payload.model_dump(exclude_unset=True, exclude={"password"})
    for field, value in updates.items():
        setattr(user, field, value)
    if payload.password is not None:
        user.pin_hash = hash_password(payload.password)

    await session.commit()
    await session.refresh(user)
    return user


@router.delete("/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_user(
    user_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    try:
        user = await user_service.get_user_or_404(session, tenant_id=tenant_id, user_id=user_id)
    except user_service.UserError as exc:
        raise _user_error_to_http(exc) from exc

    await session.delete(user)
    await session.commit()


__all__ = ["router"]
