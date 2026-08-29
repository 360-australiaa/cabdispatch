"""Users domain router: staff (owner/admin/dispatcher) and driver CRUD.

Added post-integration to close a real gap — none of the 12 parallel domain
slices owned "create a driver via the API" (only scripts/seed.py could create
users), which breaks the "full CRUD on every entity" requirement since Trips
requires a real driver_id and Fleet/Live-Ops both assume drivers already exist.

Every query is tenant-scoped via get_current_tenant_id, same as every other
domain router — see app/core/security.py.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, File, HTTPException, Query, UploadFile, status
from fastapi.responses import FileResponse
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, get_current_user, hash_password, require_role
from app.models.user import User
from app.schemas.user import Page, UserCreate, UserRead, UserUpdate
from app.services import user as user_service

router = APIRouter(prefix="/v1/users", tags=["users"])

_require_admin = require_role("owner", "admin")
_require_staff = require_role("owner", "admin", "dispatcher")


# --- Role hierarchy (fixes I-1: unchecked-role privilege escalation) --------
# No role hierarchy was declared anywhere in this codebase before this fix --
# grepped app/models/user.py ROLE_* constants (a flat set of four string
# constants, no ordering) and app/core/security.py require_role (checks only
# set membership, e.g. role in (owner, admin), never rank). Declared here,
# local to this router, since only POST/PATCH /v1/users create or mutate a
# User.role field -- no other router does.
#
# Rule (documented per task instructions, a real judgment call): a caller may
# create or set a target role only STRICTLY BELOW their own rank, EXCEPT an
# owner caller, who may also create/set other owner rows. Concretely:
#   - admin (rank 2) may create/set dispatcher/driver, but NOT admin or owner
#     -- an admin minting a same-rank admin (or an owner) is still a privilege
#     escalation: the caller ends up controlling credentials for an account
#     with equal-or-greater power than their own.
#   - owner (rank 3) may create/set ANY role, including another owner -- an
#     owner is already the top of a tenant hierarchy and needs to be able to
#     onboard co-owners or promote a trusted admin to owner.
# dispatcher/driver callers never reach this check at all -- _require_admin
# above already 403s them before create_user/update_user body runs.
_ROLE_RANK = {"driver": 0, "dispatcher": 1, "admin": 2, "owner": 3}


def _assert_role_change_permitted(*, caller_role: str, target_role: str) -> None:
    if caller_role == "owner":
        return
    if _ROLE_RANK.get(target_role, 0) >= _ROLE_RANK.get(caller_role, 0):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                f"Cannot create or set a user to role {target_role!r}: that role is "
                f"at or above your own role {caller_role!r}. Only an owner may do this."
            ),
        )


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
    if isinstance(exc, user_service.InvalidPhotoUploadError):
        return HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))
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
    caller: User = Depends(_require_admin),
):
    _assert_role_change_permitted(caller_role=caller.role, target_role=payload.role)

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
    caller: User = Depends(_require_admin),
):
    if payload.role is not None:
        _assert_role_change_permitted(caller_role=caller.role, target_role=payload.role)

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


# ==================================================================================
# Photo (closes a real gap: a monitoring partner receiving a duress alarm
# needs to see the driver photo to verify identity -- today there was
# nowhere for one to even be stored). Mirrors the compliance vault's
# local-disk-upload convention exactly -- see app.services.user's photo
# storage helpers.
# ==================================================================================


@router.post("/{user_id}/photo", response_model=UserRead)
async def upload_user_photo(
    user_id: str,
    file: UploadFile = File(...),
    tenant_id: str = Depends(get_current_tenant_id),
    caller: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    """Multipart upload: saves the file under
    uploads/{tenant_id}/users/{user_id}/ (created if missing) on local disk
    and stores the relative path on User.photo_url.

    Self-or-staff gated: any authenticated user may upload their OWN photo
    (caller.id == user_id -- the common self-service profile-picture case,
    and what the Android app's Profile screen actually does), and staff
    (owner/admin/dispatcher) may additionally upload on behalf of ANY user in
    their tenant (the admin-managed-onboarding case). Fixed from an earlier,
    stricter staff-only gate that blocked a driver from uploading their own
    photo entirely -- a real cross-agent contract mismatch caught during
    integration: the Android client was built to let a driver capture/upload
    their own photo from their own Profile screen, which would have 403'd
    against the original gate on every real device. Re-verifying a
    duress-identity photo's authenticity, if ever needed, is a staff/ops
    process concern, not something enforced by this upload gate."""
    is_self = caller.id == user_id
    is_staff = caller.role in ("owner", "admin", "dispatcher")
    if not (is_self or is_staff):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Can only upload your own photo unless you are staff (owner/admin/dispatcher)",
        )

    try:
        user = await user_service.get_user_or_404(session, tenant_id=tenant_id, user_id=user_id)
    except user_service.UserError as exc:
        raise _user_error_to_http(exc) from exc

    content = await file.read()
    try:
        relative_path = await user_service.save_user_photo(
            tenant_id=tenant_id,
            user_id=user_id,
            original_filename=file.filename or "photo",
            content=content,
        )
    except user_service.UserError as exc:
        raise _user_error_to_http(exc) from exc

    user.photo_url = relative_path
    await session.commit()
    await session.refresh(user)
    return user


@router.get("/{user_id}/photo")
async def get_user_photo(
    user_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    """Any authenticated tenant user may fetch a photo (e.g. a monitoring
    partner / dispatcher confirming a driver identity during a duress
    alert) -- same read convention as GET /v1/compliance/documents/{id}/download."""
    try:
        user = await user_service.get_user_or_404(session, tenant_id=tenant_id, user_id=user_id)
    except user_service.UserError as exc:
        raise _user_error_to_http(exc) from exc

    if user.photo_url is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No photo on file for this user")

    try:
        absolute_path = user_service.resolve_photo_path(user.photo_url)
    except user_service.UserError as exc:
        raise _user_error_to_http(exc) from exc

    if not absolute_path.is_file():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Photo row exists but its file is missing on disk",
        )

    return FileResponse(path=absolute_path)


__all__ = ["router"]
