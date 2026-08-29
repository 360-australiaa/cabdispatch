"""Tests for DeviceAssignment (plan D-3, WP-20 model half) -- the meter
binding as an assignment with history. See app.models.device_assignment for
the full design rationale, including the derivation of the dual-dialect
postgresql_where/sqlite_where partial-unique-index pattern (no precedent for
it existed elsewhere in this codebase at the time this was written).

Mirrors the analogous, already-landed test_vehicle_assignment.py pattern for
proving a partial unique index actually fires at the DB level (construct two
active rows -> IntegrityError; a third row with unbound_at SET must NOT
collide).
"""
from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Device, DeviceAssignment, Tenant, User, Vehicle


async def _make_tenant(session: AsyncSession) -> Tenant:
    tenant = Tenant(name=f"Test Tenant {uuid.uuid4()}", plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    return tenant


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


async def _make_device(session: AsyncSession, tenant_id: str) -> Device:
    device = Device(tenant_id=tenant_id, android_id=f"AND-{uuid.uuid4().hex[:10]}")
    session.add(device)
    await session.commit()
    await session.refresh(device)
    return device


async def test_device_assignment_constructs_and_persists(session: AsyncSession):
    tenant = await _make_tenant(session)
    admin = await _make_admin(session, tenant.id)
    vehicle = await _make_vehicle(session, tenant.id)
    device = await _make_device(session, tenant.id)

    assignment = DeviceAssignment(
        tenant_id=tenant.id,
        device_id=device.id,
        vehicle_id=vehicle.id,
        bound_at=datetime.now(UTC),
        bound_by_user_id=admin.id,
        pairing_code_id=None,
        unbound_reason=None,
    )
    session.add(assignment)
    await session.commit()
    await session.refresh(assignment)

    assert assignment.id is not None
    assert assignment.unbound_at is None
    assert assignment.unbound_reason is None
    assert assignment.pairing_code_id is None
    assert assignment.device_id == device.id
    assert assignment.vehicle_id == vehicle.id
    assert assignment.bound_by_user_id == admin.id
    assert assignment.created_at is not None
    assert assignment.updated_at is not None


async def test_partial_unique_index_blocks_double_active_assignment_per_vehicle(
    session: AsyncSession,
):
    """Two simultaneously-active (unbound_at IS NULL) assignments for the same
    (tenant, vehicle) -- i.e. two different meters both currently bound to the
    same car -- must be rejected by the DB."""
    tenant = await _make_tenant(session)
    admin = await _make_admin(session, tenant.id)
    vehicle = await _make_vehicle(session, tenant.id)
    device_a = await _make_device(session, tenant.id)
    device_b = await _make_device(session, tenant.id)

    first = DeviceAssignment(
        tenant_id=tenant.id,
        device_id=device_a.id,
        vehicle_id=vehicle.id,
        bound_at=datetime.now(UTC),
        bound_by_user_id=admin.id,
    )
    session.add(first)
    await session.commit()

    second = DeviceAssignment(
        tenant_id=tenant.id,
        device_id=device_b.id,
        vehicle_id=vehicle.id,
        bound_at=datetime.now(UTC),
        bound_by_user_id=admin.id,
    )
    session.add(second)
    with pytest.raises(IntegrityError):
        await session.commit()

    await session.rollback()


async def test_partial_unique_index_blocks_double_active_assignment_per_device(
    session: AsyncSession,
):
    """Two simultaneously-active (unbound_at IS NULL) assignments for the same
    (tenant, device) -- i.e. one meter currently bound to two different cars
    at once -- must be rejected by the DB."""
    tenant = await _make_tenant(session)
    admin = await _make_admin(session, tenant.id)
    vehicle_a = await _make_vehicle(session, tenant.id)
    vehicle_b = await _make_vehicle(session, tenant.id)
    device = await _make_device(session, tenant.id)

    first = DeviceAssignment(
        tenant_id=tenant.id,
        device_id=device.id,
        vehicle_id=vehicle_a.id,
        bound_at=datetime.now(UTC),
        bound_by_user_id=admin.id,
    )
    session.add(first)
    await session.commit()

    second = DeviceAssignment(
        tenant_id=tenant.id,
        device_id=device.id,
        vehicle_id=vehicle_b.id,
        bound_at=datetime.now(UTC),
        bound_by_user_id=admin.id,
    )
    session.add(second)
    with pytest.raises(IntegrityError):
        await session.commit()

    await session.rollback()


async def test_partial_unique_index_allows_reassignment_after_unbind(session: AsyncSession):
    """Once the first assignment is closed (unbound_at set), a second, fresh
    assignment for the exact same (tenant, vehicle) pair must succeed -- the
    partial index only guards *currently active* rows, proving it really is
    partial and not a plain unique constraint."""
    tenant = await _make_tenant(session)
    admin = await _make_admin(session, tenant.id)
    vehicle = await _make_vehicle(session, tenant.id)
    device_a = await _make_device(session, tenant.id)
    device_b = await _make_device(session, tenant.id)

    first = DeviceAssignment(
        tenant_id=tenant.id,
        device_id=device_a.id,
        vehicle_id=vehicle.id,
        bound_at=datetime.now(UTC),
        bound_by_user_id=admin.id,
    )
    session.add(first)
    await session.commit()

    first.unbound_at = datetime.now(UTC)
    first.unbound_reason = "re-paired"
    await session.commit()

    second = DeviceAssignment(
        tenant_id=tenant.id,
        device_id=device_b.id,
        vehicle_id=vehicle.id,
        bound_at=datetime.now(UTC),
        bound_by_user_id=admin.id,
    )
    session.add(second)
    await session.commit()
    await session.refresh(second)

    assert second.id is not None
    assert second.unbound_at is None