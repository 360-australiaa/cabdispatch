"""Tests for VehicleAssignment (plan WP-24 model half) -- the driver roster:
which drivers are authorised to drive which vehicles. See
app.models.vehicle_assignment for the full design rationale, including why
the uniqueness rule has to be a *partial* unique index rather than a plain
one.
"""
from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Tenant, User, Vehicle, VehicleAssignment


async def _make_tenant(session: AsyncSession) -> Tenant:
    tenant = Tenant(name=f"Test Tenant {uuid.uuid4()}", plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    return tenant


async def _make_driver(session: AsyncSession, tenant_id: str) -> User:
    driver = User(
        tenant_id=tenant_id,
        role="driver",
        name="Test Driver",
        email=f"{uuid.uuid4()}@example.com",
        status="active",
    )
    session.add(driver)
    await session.commit()
    await session.refresh(driver)
    return driver


async def _make_admin(session: AsyncSession, tenant_id: str) -> User:
    admin = User(
        tenant_id=tenant_id,
        role="admin",
        name="Test Admin",
        email=f"{uuid.uuid4()}@example.com",
        status="active",
    )
    session.add(admin)
    await session.commit()
    await session.refresh(admin)
    return admin


async def _make_vehicle(session: AsyncSession, tenant_id: str) -> Vehicle:
    vehicle = Vehicle(tenant_id=tenant_id, rego=f"T-{uuid.uuid4().hex[:6].upper()}")
    session.add(vehicle)
    await session.commit()
    await session.refresh(vehicle)
    return vehicle


async def test_vehicle_assignment_constructs_and_persists(session: AsyncSession):
    tenant = await _make_tenant(session)
    driver = await _make_driver(session, tenant.id)
    admin = await _make_admin(session, tenant.id)
    vehicle = await _make_vehicle(session, tenant.id)

    assignment = VehicleAssignment(
        tenant_id=tenant.id,
        vehicle_id=vehicle.id,
        driver_id=driver.id,
        authorised_by_user_id=admin.id,
        authorised_at=datetime.now(UTC),
    )
    session.add(assignment)
    await session.commit()
    await session.refresh(assignment)

    assert assignment.id is not None
    assert assignment.revoked_at is None
    assert assignment.revoked_reason is None
    assert assignment.vehicle_id == vehicle.id
    assert assignment.driver_id == driver.id
    assert assignment.authorised_by_user_id == admin.id
    assert assignment.created_at is not None
    assert assignment.updated_at is not None


async def test_partial_unique_index_blocks_double_active_authorisation(session: AsyncSession):
    """Two simultaneously-active (revoked_at IS NULL) authorisations for the
    same (tenant, vehicle, driver) triple must be rejected by the DB."""
    tenant = await _make_tenant(session)
    driver = await _make_driver(session, tenant.id)
    admin = await _make_admin(session, tenant.id)
    vehicle = await _make_vehicle(session, tenant.id)

    first = VehicleAssignment(
        tenant_id=tenant.id,
        vehicle_id=vehicle.id,
        driver_id=driver.id,
        authorised_by_user_id=admin.id,
        authorised_at=datetime.now(UTC),
    )
    session.add(first)
    await session.commit()

    second = VehicleAssignment(
        tenant_id=tenant.id,
        vehicle_id=vehicle.id,
        driver_id=driver.id,
        authorised_by_user_id=admin.id,
        authorised_at=datetime.now(UTC),
    )
    session.add(second)
    with pytest.raises(IntegrityError):
        await session.commit()

    await session.rollback()


async def test_partial_unique_index_allows_reauthorisation_after_revoke(session: AsyncSession):
    """Once the first authorisation is revoked (revoked_at set), a second,
    fresh authorisation for the exact same (tenant, vehicle, driver) triple
    must succeed -- the partial index only guards *currently active* rows."""
    tenant = await _make_tenant(session)
    driver = await _make_driver(session, tenant.id)
    admin = await _make_admin(session, tenant.id)
    vehicle = await _make_vehicle(session, tenant.id)

    first = VehicleAssignment(
        tenant_id=tenant.id,
        vehicle_id=vehicle.id,
        driver_id=driver.id,
        authorised_by_user_id=admin.id,
        authorised_at=datetime.now(UTC),
    )
    session.add(first)
    await session.commit()

    first.revoked_at = datetime.now(UTC)
    first.revoked_reason = "roster change"
    await session.commit()

    second = VehicleAssignment(
        tenant_id=tenant.id,
        vehicle_id=vehicle.id,
        driver_id=driver.id,
        authorised_by_user_id=admin.id,
        authorised_at=datetime.now(UTC),
    )
    session.add(second)
    await session.commit()
    await session.refresh(second)

    assert second.id is not None
    assert second.revoked_at is None