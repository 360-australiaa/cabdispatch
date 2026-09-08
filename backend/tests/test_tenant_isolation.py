"""Multi-tenancy as a property, not as a per-router habit.

Tenant isolation in this system is enforced *purely by convention*: there is no
row-level security in the database, no query-level default filter, nothing at
all in the framework that stops the next router anyone writes from resolving a
row by id and forgetting `.where(Model.tenant_id == tenant_id)`. The docstring
on `TenantScopedMixin` (`app/core/database.py:80`) says so outright — "this is
the sole multi-tenancy enforcement mechanism in the system". The backend audit
§7 lists "multi-tenancy as a property" among the genuinely untested things.

So this file tests the property directly, over the whole model surface:

  * `test_every_tenant_scoped_model_is_accounted_for` walks every subclass of
    `TenantScopedMixin` and fails if it is neither exercised below nor listed
    as having no by-id API surface. A new tenant-scoped model therefore cannot
    be added without someone consciously deciding which of the two it is. That
    is the part that keeps this file honest a year from now.

  * `test_tenant_b_cannot_read_tenant_a_row` seeds a real row in tenant A and
    then, for each model, checks BOTH directions: tenant A's own token reads it
    (200) and tenant B's token does not (404). The positive control is the
    important half — without it a router that 404s unconditionally, or a path
    typo, would sail through the isolation assertion while proving nothing.

  * `test_tenant_b_cannot_write_tenant_a_row` checks no by-id write from
    tenant B ever succeeds.

Deliberately NOT tested here as a leak: the platform-owner escape hatch in
`get_current_tenant_id` (`app/core/security.py:319-331`), where a token with
role `owner` AND `tenant_id == PLATFORM_TENANT_ID` may pass `?tenant_id=<other>`
and read across tenants. That is designed cross-tenant access for the platform
console, not a leak. Both tenants below are ordinary uuid tenants, so the hatch
is closed for them.
"""
from __future__ import annotations

import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest

from app.core.database import Base, TenantScopedMixin
from app.models import (
    Announcement,
    AuditLog,
    ComplianceDocument,
    CorporateAccount,
    Device,
    DevicePairingCode,
    DeviceVersionHistory,
    DriverAvailability,
    DuressDevice,
    DuressEvent,
    DuressSnapshot,
    FatigueAlert,
    Incentive,
    Job,
    JobOffer,
    Message,
    Payment,
    PSLLedgerEntry,
    PSLTopUp,
    Shift,
    Subscription,
    Tenant,
    Trip,
    TripGpsTrace,
    TripRating,
    User,
    Vehicle,
    VehiclePositionHistory,
    Voucher,
    WalletTransaction,
    Zone,
)
from app.services.duress import resolve_absolute_path
from tests.conftest import auth_headers

NOW = datetime(2026, 3, 4, 9, 30, tzinfo=UTC)


# --- the world each tenant gets ---------------------------------------------


@dataclass
class World:
    """One tenant's worth of seeded rows, plus the auth header for a user in it.

    A single backbone (driver, vehicle, device, trip, job, duress event) is
    seeded because most tenant-scoped tables hang off one of those by foreign
    key, and the FK pragma is ON for SQLite in this project
    (`app/core/database.py:44`) so dangling ids would be rejected outright.
    """

    tenant_id: str
    headers: dict
    driver: User
    vehicle: Vehicle
    device: Device
    trip: Trip
    job: Job
    duress_event: DuressEvent
    rows: dict[str, object]


async def _seed_world(client, session, *, name: str) -> World:
    # The tenant is created here rather than left to `auth_headers` so its id is
    # known directly. Recovering it afterwards by querying for the user it makes
    # is not safe: both tenants are seeded in the same test and `created_at` has
    # nowhere near enough resolution to order them apart.
    tenant = Tenant(name=name, plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    tenant_id = tenant.id

    headers = await auth_headers(client, session, role="owner", tenant_id=tenant_id)

    driver = User(
        tenant_id=tenant_id,
        role="driver",
        name=f"{name} Driver",
        email=f"{uuid.uuid4()}@example.com",
        status="active",
    )
    vehicle = Vehicle(tenant_id=tenant_id, rego=f"RG{uuid.uuid4().hex[:6].upper()}")
    session.add_all([driver, vehicle])
    await session.commit()

    device = Device(tenant_id=tenant_id, vehicle_id=vehicle.id, android_id=uuid.uuid4().hex)
    trip = Trip(
        tenant_id=tenant_id,
        client_uuid=str(uuid.uuid4()),
        vehicle_id=vehicle.id,
        driver_id=driver.id,
        tariff_id=str(uuid.uuid4()),
        type="street",
        start_at=NOW,
        start_lat=-33.87,
        start_lng=151.21,
    )
    job = Job(
        tenant_id=tenant_id,
        origin_lat=-33.87,
        origin_lng=151.21,
        origin_address="1 Test St",
        dest_lat=-33.88,
        dest_lng=151.22,
        dest_address="2 Test St",
        fare_estimate_low=Decimal("10.00"),
        fare_estimate_high=Decimal("20.00"),
        requested_at=NOW,
    )
    duress_event = DuressEvent(
        tenant_id=tenant_id,
        vehicle_id=vehicle.id,
        driver_id=driver.id,
        trigger="manual",
        opened_at=NOW,
        gps_stream_ref=f"gps/{uuid.uuid4().hex}",
        escalation_log_json={},
    )
    session.add_all([device, trip, job, duress_event])
    await session.commit()

    rows: dict[str, object] = {}

    def add(key: str, obj):
        rows[key] = obj
        session.add(obj)
        return obj

    add("Trip", trip)
    add("Vehicle", vehicle)
    add("Device", device)
    add("Job", job)
    add("DuressEvent", duress_event)

    add(
        "TripGpsTrace",
        TripGpsTrace(
            tenant_id=tenant_id,
            trip_id=trip.id,
            points=[{"lat": -33.87, "lng": 151.21, "speed_kmh": 42.0, "ts": NOW.isoformat()}],
            point_count=1,
            recorded_at=NOW,
        ),
    )
    add(
        "Shift",
        Shift(tenant_id=tenant_id, driver_id=driver.id, vehicle_id=vehicle.id, start_at=NOW),
    )
    add(
        "Payment",
        Payment(tenant_id=tenant_id, trip_id=trip.id, method="cash", amount=Decimal("42.50")),
    )
    add(
        "Subscription",
        Subscription(
            tenant_id=tenant_id, vehicle_id=vehicle.id, plan="pro", price_aud=Decimal("99.00")
        ),
    )
    add(
        "ComplianceDocument",
        ComplianceDocument(
            tenant_id=tenant_id,
            vehicle_id=vehicle.id,
            doc_type="calibration_record",
            file_path=f"compliance/{uuid.uuid4().hex}.pdf",
            original_filename="calibration.pdf",
            uploaded_by=driver.id,
            uploaded_at=NOW,
        ),
    )
    add(
        "VehiclePositionHistory",
        VehiclePositionHistory(
            tenant_id=tenant_id,
            vehicle_id=vehicle.id,
            lat=-33.87,
            lng=151.21,
            status="available",
            recorded_at=NOW,
        ),
    )
    # The snapshot endpoint streams the image off disk and 404s when the row
    # exists but the file does not, so a DB-only fixture would fail the positive
    # control for a reason that has nothing to do with tenancy. Write a real
    # (tiny) file through the app's own path resolver.
    # Under `uploads/`, not the production-shaped `duress/`: uploads/ is the
    # gitignored scratch root (.gitignore:10), so the suite does not leave an
    # untracked directory in the repo behind it.
    snapshot_relative = f"uploads/duress-isolation-test/{uuid.uuid4().hex}.jpg"
    snapshot_path = resolve_absolute_path(snapshot_relative)
    snapshot_path.parent.mkdir(parents=True, exist_ok=True)
    snapshot_path.write_bytes(b"\xff\xd8\xff\xd9")  # smallest thing shaped like a JPEG
    add(
        "DuressSnapshot",
        DuressSnapshot(
            tenant_id=tenant_id,
            event_id=duress_event.id,
            relative_path=snapshot_relative,
            captured_at=NOW,
        ),
    )
    add(
        "DuressDevice",
        DuressDevice(
            tenant_id=tenant_id,
            device_code=uuid.uuid4().hex[:12],
            secret_encrypted="not-a-real-secret",
        ),
    )
    add("FatigueAlert", FatigueAlert(tenant_id=tenant_id, kind="no_break_taken", triggered_at=NOW))
    add(
        "Zone",
        Zone(
            tenant_id=tenant_id,
            name=f"{name} Zone",
            number=uuid.uuid4().hex[:6],
            center_lat=-33.87,
            center_lng=151.21,
            radius_m=500.0,
        ),
    )
    add(
        "Voucher",
        Voucher(tenant_id=tenant_id, code=uuid.uuid4().hex[:12], value_aud=Decimal("25.00")),
    )
    add(
        "CorporateAccount",
        CorporateAccount(
            tenant_id=tenant_id, reference=uuid.uuid4().hex[:12], company_name=f"{name} Pty Ltd"
        ),
    )
    add(
        "Announcement",
        Announcement(tenant_id=tenant_id, title="Notice", body="Body text", starts_at=NOW),
    )
    add(
        "Incentive",
        Incentive(
            tenant_id=tenant_id,
            title="Bonus",
            target_trips=10,
            reward_aud=Decimal("50.00"),
            starts_at=NOW,
            ends_at=NOW + timedelta(days=7),
        ),
    )
    add(
        "PSLLedgerEntry",
        PSLLedgerEntry(tenant_id=tenant_id, driver_id=driver.id, period="2026-03"),
    )

    await session.commit()
    for obj in rows.values():
        await session.refresh(obj)

    return World(
        tenant_id=tenant_id,
        headers=headers,
        driver=driver,
        vehicle=vehicle,
        device=device,
        trip=trip,
        job=job,
        duress_event=duress_event,
        rows=rows,
    )


# --- the registry ------------------------------------------------------------


@dataclass(frozen=True)
class Probe:
    model: type
    path: Callable[[World], str]


# Every tenant-scoped model that has a single-row-by-id GET, and the path that
# reads it. Where the route is keyed on a parent id rather than the row's own
# (gps trace, position history), the path reflects that — what matters is that
# the endpoint serves tenant A's data.
PROBES: dict[str, Probe] = {
    "Trip": Probe(Trip, lambda w: f"/v1/trips/{w.trip.id}"),
    "TripGpsTrace": Probe(TripGpsTrace, lambda w: f"/v1/trips/{w.trip.id}/gps-trace"),
    "Shift": Probe(Shift, lambda w: f"/v1/shifts/{w.rows['Shift'].id}"),
    "Job": Probe(Job, lambda w: f"/v1/jobs/{w.job.id}"),
    "Payment": Probe(Payment, lambda w: f"/v1/payments/{w.rows['Payment'].id}"),
    "Subscription": Probe(
        Subscription, lambda w: f"/v1/billing/subscriptions/{w.rows['Subscription'].id}"
    ),
    "ComplianceDocument": Probe(
        ComplianceDocument,
        lambda w: f"/v1/compliance/documents/{w.rows['ComplianceDocument'].id}",
    ),
    "Vehicle": Probe(Vehicle, lambda w: f"/v1/fleet/vehicles/{w.vehicle.id}"),
    "Device": Probe(Device, lambda w: f"/v1/fleet/devices/{w.device.id}"),
    "VehiclePositionHistory": Probe(
        VehiclePositionHistory, lambda w: f"/v1/vehicles/{w.vehicle.id}/position-history"
    ),
    "DuressEvent": Probe(DuressEvent, lambda w: f"/v1/duress/{w.duress_event.id}"),
    "DuressSnapshot": Probe(
        DuressSnapshot,
        lambda w: f"/v1/duress/{w.duress_event.id}/snapshot/{w.rows['DuressSnapshot'].id}",
    ),
    "DuressDevice": Probe(
        DuressDevice, lambda w: f"/v1/duress-devices/{w.rows['DuressDevice'].id}"
    ),
    "FatigueAlert": Probe(
        FatigueAlert, lambda w: f"/v1/fatigue-alerts/{w.rows['FatigueAlert'].id}"
    ),
    "Zone": Probe(Zone, lambda w: f"/v1/zones/{w.rows['Zone'].id}"),
    "Voucher": Probe(Voucher, lambda w: f"/v1/vouchers/{w.rows['Voucher'].id}"),
    "CorporateAccount": Probe(
        CorporateAccount, lambda w: f"/v1/corporate-accounts/{w.rows['CorporateAccount'].id}"
    ),
    "Announcement": Probe(
        Announcement, lambda w: f"/v1/announcements/{w.rows['Announcement'].id}"
    ),
    "Incentive": Probe(Incentive, lambda w: f"/v1/incentives/{w.rows['Incentive'].id}"),
    "PSLLedgerEntry": Probe(
        PSLLedgerEntry, lambda w: f"/v1/psl/ledger/{w.rows['PSLLedgerEntry'].id}"
    ),
}

# Tenant-scoped models with no single-row-by-id GET anywhere in app/api/v1/.
# These are NOT exempt from tenant scoping — several are reachable through a
# list endpoint that does filter tenant_id (audit log, ratings, messages,
# PSL top-ups, wallet). They are listed here only because there is no by-id
# route for the probe above to aim at. If you add one, move the model up into
# PROBES; the coverage test below will tell you if you forget.
NO_BY_ID_ROUTE: set[type] = {
    AuditLog,  # list only: GET /v1/audit-log, filters tenant_id
    Message,  # thread list only: GET /v1/messages
    TripRating,  # list only: GET /v1/ratings, filters tenant_id
    WalletTransaction,  # read via GET /v1/wallet/drivers/{driver_id}, keyed on driver
    PSLTopUp,  # list only: GET /v1/psl/topups
    JobOffer,  # list under its parent job: GET /v1/jobs/{job_id}/offers
    DevicePairingCode,  # write-only surface (POST .../pairing-code)
    DeviceVersionHistory,  # no route at all
    DriverAvailability,  # no route at all
}


def _tenant_scoped_models() -> set[type]:
    return {
        mapper.class_
        for mapper in Base.registry.mappers
        if issubclass(mapper.class_, TenantScopedMixin)
    }


def test_every_tenant_scoped_model_is_accounted_for():
    """The forcing function. Isolation here is convention, so the one thing that
    must not happen quietly is a new tenant-scoped table appearing with nobody
    having decided whether its API surface is covered."""
    covered = {probe.model for probe in PROBES.values()} | NO_BY_ID_ROUTE
    missing = _tenant_scoped_models() - covered
    assert not missing, (
        "these TenantScopedMixin models are neither probed nor declared route-less: "
        + ", ".join(sorted(m.__name__ for m in missing))
        + ". Add a by-id GET to PROBES, or add the model to NO_BY_ID_ROUTE with a "
        "comment saying where its rows are actually reachable from."
    )

    stale = covered - _tenant_scoped_models()
    assert not stale, (
        "these entries no longer inherit TenantScopedMixin: "
        + ", ".join(sorted(m.__name__ for m in stale))
    )


@pytest.fixture
async def two_tenants(client, session):
    a = await _seed_world(client, session, name="Isolation Tenant A")
    b = await _seed_world(client, session, name="Isolation Tenant B")
    assert a.tenant_id != b.tenant_id
    return a, b


@pytest.mark.asyncio
@pytest.mark.parametrize("name", sorted(PROBES))
async def test_tenant_b_cannot_read_tenant_a_row(client, session, two_tenants, name):
    """Both halves matter. The 200 proves the probe is aimed at a live endpoint
    holding real tenant-A data; the 404 is the isolation claim itself. Drop the
    200 and this test would pass just as happily against a typo'd URL."""
    a, b = two_tenants
    path = PROBES[name].path(a)

    own = await client.get(path, headers=a.headers)
    assert own.status_code == 200, (
        f"{name}: tenant A cannot read its OWN row at {path} (got {own.status_code}: "
        f"{own.text[:200]}). The probe is wrong, or the endpoint is broken — either way "
        "the isolation assertion below would be vacuous."
    )

    cross = await client.get(path, headers=b.headers)
    assert cross.status_code == 404, (
        f"TENANT ISOLATION LEAK — {name}: tenant B read tenant A's row at {path} and got "
        f"{cross.status_code}. Expected 404 (never 403, which would confirm the row exists). "
        f"Body: {cross.text[:300]}"
    )


@pytest.mark.asyncio
@pytest.mark.parametrize("name", sorted(PROBES))
async def test_tenant_b_cannot_write_tenant_a_row(client, two_tenants, name):
    """No by-id write from the wrong tenant may ever succeed.

    Weaker than the read probe by design: a 404/405/403/422 are all acceptable
    outcomes, because this sweeps methods without knowing which routes exist.
    Its job is to catch the specific disaster of a mutating route resolving a
    row by id alone — a PATCH that returns 200 here means tenant B just wrote
    tenant A's data.
    """
    a, b = two_tenants
    path = PROBES[name].path(a)

    for method in ("patch", "delete"):
        response = await getattr(client, method)(
            path, headers=b.headers, **({"json": {}} if method == "patch" else {})
        )
        assert response.status_code not in (200, 201, 202, 204), (
            f"TENANT ISOLATION LEAK — {name}: tenant B's {method.upper()} on tenant A's row at "
            f"{path} succeeded with {response.status_code}. Body: {response.text[:300]}"
        )
