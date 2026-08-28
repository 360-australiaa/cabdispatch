"""Driver-license/authority and vehicle-registration/insurance expiry
detection (blueprint 7.2.3/7.2.4/10.1).

Mirrors `app.services.fatigue`'s shape exactly: functions here only
`session.add()` a `FatigueAlert` row — they never commit; the caller owns the
transaction. See that module for the precedent this one follows.

DEVIATION (flagged per task instructions): the blueprint calls for staged
renewal reminders at 30/60/90 days out. This pass raises a single
"expiring_soon" kind at one configurable threshold
(`settings.COMPLIANCE_EXPIRY_WARNING_DAYS`, default 30) instead of three
separate 30/60/90 alert kinds — same single-threshold-simplification
precedent as `app.services.fatigue.ASSUMED_NORMAL_SPEED_LIMIT_KMH`. A real
staged 30/60/90 reminder needs a design call (distinct kinds? repeat sends on
each threshold crossing? escalating severity?) that's out of scope here.

DEVIATION (dedup rule): `app.services.fatigue`'s shift-duration dedup checks
plain *existence* of an alert for a given (kind, shift) — safe because a shift
has a bounded lifetime, so "already alerted, ever" and "already alerted, and
still unresolved" are effectively the same thing. A driver's licence or a
vehicle's rego is not bounded like that — it can be renewed and then lapse
again years later. Plain existence-dedup would permanently block a second,
legitimate alert after the first renewal. So the dedup check here is instead
"does an UNACKNOWLEDGED alert of this kind already exist for this entity" —
acknowledging an alert (e.g. once the document is renewed/uploaded) clears the
way for a fresh one if the condition recurs.

Fail-open on missing data (matches this codebase's existing treatment of
nullable compliance-ish fields, e.g. `User.driver_licence_no` /
`Vehicle.vin`): a null expiry date means "unknown", not "expired" — nothing
here is ever triggered by, or blocks anything on, a null expiry date.
"""
from __future__ import annotations

from datetime import UTC, date, datetime, timedelta
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.models.fatigue_alert import (
    FATIGUE_ALERT_AUTHORITY_EXPIRED,
    FATIGUE_ALERT_AUTHORITY_EXPIRING_SOON,
    FATIGUE_ALERT_CALIBRATION_EXPIRED,
    FATIGUE_ALERT_CALIBRATION_EXPIRING_SOON,
    FATIGUE_ALERT_INSURANCE_EXPIRED,
    FATIGUE_ALERT_INSURANCE_EXPIRING_SOON,
    FATIGUE_ALERT_LICENSE_EXPIRED,
    FATIGUE_ALERT_LICENSE_EXPIRING_SOON,
    FATIGUE_ALERT_REGISTRATION_EXPIRED,
    FATIGUE_ALERT_REGISTRATION_EXPIRING_SOON,
    FatigueAlert,
)
from app.models.fleet import Device, Vehicle
from app.models.user import ROLE_DRIVER, User

# Compliance expiry (licence/rego/insurance/calibration) is a calendar-date
# concept and must be evaluated against the NSW local calendar date, not the
# UTC calendar date -- right around the UTC/Sydney day boundary these can
# differ by a whole day. Every current-date value derived in this module goes
# through _sydney_today() below rather than calling .date() on a UTC datetime.
SYDNEY_TZ = ZoneInfo('Australia/Sydney')


def _sydney_today(now: datetime | None = None) -> date:
    moment = now if now is not None else datetime.now(UTC)
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=UTC)
    return moment.astimezone(SYDNEY_TZ).date()


def is_expired(expiry: date | None, *, today: date | None = None) -> bool:
    """True only if `expiry` is a real date strictly in the past. A null
    expiry is "unknown" and never counts as expired — see module docstring.
    Used directly by `POST /v1/auth/driver-login` to fail-closed on an
    actually-expired driver_license_expiry (blueprint 5.2.1) without needing
    to touch the alerting machinery below."""
    if expiry is None:
        return False
    today = today if today is not None else _sydney_today()
    return expiry < today


def _status_for(expiry: date, *, today: date) -> str | None:
    """Returns "expired", "expiring_soon", or None (not within the warning
    window), for a single non-null expiry date."""
    if expiry < today:
        return "expired"
    if (expiry - today).days <= settings.COMPLIANCE_EXPIRY_WARNING_DAYS:
        return "expiring_soon"
    return None


async def _unacknowledged_alert_exists(
    session: AsyncSession,
    *,
    tenant_id: str,
    kind: str,
    driver_id: str | None = None,
    vehicle_id: str | None = None,
) -> bool:
    stmt = select(FatigueAlert.id).where(
        FatigueAlert.tenant_id == tenant_id,
        FatigueAlert.kind == kind,
        FatigueAlert.acknowledged.is_(False),
    )
    if driver_id is not None:
        stmt = stmt.where(FatigueAlert.driver_id == driver_id)
    if vehicle_id is not None:
        stmt = stmt.where(FatigueAlert.vehicle_id == vehicle_id)
    result = await session.execute(stmt)
    return result.scalar_one_or_none() is not None


async def _raise_driver_alert(
    session: AsyncSession,
    *,
    tenant_id: str,
    driver_id: str,
    expiry: date,
    expiring_soon_kind: str,
    expired_kind: str,
    now: datetime,
) -> FatigueAlert | None:
    today = _sydney_today(now)
    outcome = _status_for(expiry, today=today)
    if outcome is None:
        return None

    kind = expired_kind if outcome == "expired" else expiring_soon_kind
    if await _unacknowledged_alert_exists(session, tenant_id=tenant_id, kind=kind, driver_id=driver_id):
        return None

    alert = FatigueAlert(
        tenant_id=tenant_id,
        driver_id=driver_id,
        kind=kind,
        triggered_at=now,
        details_json={"expiry_date": expiry.isoformat(), "days_remaining": (expiry - today).days},
        acknowledged=False,
    )
    session.add(alert)
    return alert


async def _raise_vehicle_alert(
    session: AsyncSession,
    *,
    tenant_id: str,
    vehicle_id: str,
    expiry: date,
    expiring_soon_kind: str,
    expired_kind: str,
    now: datetime,
) -> FatigueAlert | None:
    today = _sydney_today(now)
    outcome = _status_for(expiry, today=today)
    if outcome is None:
        return None

    kind = expired_kind if outcome == "expired" else expiring_soon_kind
    if await _unacknowledged_alert_exists(session, tenant_id=tenant_id, kind=kind, vehicle_id=vehicle_id):
        return None

    alert = FatigueAlert(
        tenant_id=tenant_id,
        vehicle_id=vehicle_id,
        kind=kind,
        triggered_at=now,
        details_json={"expiry_date": expiry.isoformat(), "days_remaining": (expiry - today).days},
        acknowledged=False,
    )
    session.add(alert)
    return alert


# --- per-field checks (each raises at most one alert; add()-only, no commit) --


async def check_driver_license_expiry(
    session: AsyncSession, *, tenant_id: str, driver: User, now: datetime | None = None
) -> FatigueAlert | None:
    if driver.driver_license_expiry is None:
        return None
    now = now if now is not None else datetime.now(UTC)
    return await _raise_driver_alert(
        session,
        tenant_id=tenant_id,
        driver_id=driver.id,
        expiry=driver.driver_license_expiry,
        expiring_soon_kind=FATIGUE_ALERT_LICENSE_EXPIRING_SOON,
        expired_kind=FATIGUE_ALERT_LICENSE_EXPIRED,
        now=now,
    )


async def check_driver_authority_expiry(
    session: AsyncSession, *, tenant_id: str, driver: User, now: datetime | None = None
) -> FatigueAlert | None:
    if driver.driver_authority_expiry is None:
        return None
    now = now if now is not None else datetime.now(UTC)
    return await _raise_driver_alert(
        session,
        tenant_id=tenant_id,
        driver_id=driver.id,
        expiry=driver.driver_authority_expiry,
        expiring_soon_kind=FATIGUE_ALERT_AUTHORITY_EXPIRING_SOON,
        expired_kind=FATIGUE_ALERT_AUTHORITY_EXPIRED,
        now=now,
    )


async def check_vehicle_registration_expiry(
    session: AsyncSession, *, tenant_id: str, vehicle: Vehicle, now: datetime | None = None
) -> FatigueAlert | None:
    if vehicle.registration_expiry is None:
        return None
    now = now if now is not None else datetime.now(UTC)
    return await _raise_vehicle_alert(
        session,
        tenant_id=tenant_id,
        vehicle_id=vehicle.id,
        expiry=vehicle.registration_expiry,
        expiring_soon_kind=FATIGUE_ALERT_REGISTRATION_EXPIRING_SOON,
        expired_kind=FATIGUE_ALERT_REGISTRATION_EXPIRED,
        now=now,
    )


async def check_vehicle_insurance_expiry(
    session: AsyncSession, *, tenant_id: str, vehicle: Vehicle, now: datetime | None = None
) -> FatigueAlert | None:
    if vehicle.insurance_expiry is None:
        return None
    now = now if now is not None else datetime.now(UTC)
    return await _raise_vehicle_alert(
        session,
        tenant_id=tenant_id,
        vehicle_id=vehicle.id,
        expiry=vehicle.insurance_expiry,
        expiring_soon_kind=FATIGUE_ALERT_INSURANCE_EXPIRING_SOON,
        expired_kind=FATIGUE_ALERT_INSURANCE_EXPIRED,
        now=now,
    )


async def check_device_calibration_expiry(
    session: AsyncSession, *, tenant_id: str, device: Device, now: datetime | None = None
) -> FatigueAlert | None:
    """Meter re-verification due-date check (operations-cycle tracking pass).
    Fails open (returns None, raises nothing) if the device isn't currently
    paired to a vehicle — same reasoning as every other fail-open path in
    this module: the alert is stored against `vehicle_id` (FatigueAlert has
    no device_id column), so an unpaired device has nowhere to attach an
    alert to."""
    if device.calibration_due is None or device.vehicle_id is None:
        return None
    now = now if now is not None else datetime.now(UTC)
    return await _raise_vehicle_alert(
        session,
        tenant_id=tenant_id,
        vehicle_id=device.vehicle_id,
        expiry=device.calibration_due,
        expiring_soon_kind=FATIGUE_ALERT_CALIBRATION_EXPIRING_SOON,
        expired_kind=FATIGUE_ALERT_CALIBRATION_EXPIRED,
        now=now,
    )


async def run_driver_compliance_checks(
    session: AsyncSession, *, tenant_id: str, driver: User, now: datetime | None = None
) -> list[FatigueAlert]:
    """Runs both driver checks (license + authority). Convenience wrapper for
    call sites that want "everything for this driver" in one call — see
    `PATCH /v1/trips/{id}/tick` in app/api/v1/trips.py."""
    alerts = [
        await check_driver_license_expiry(session, tenant_id=tenant_id, driver=driver, now=now),
        await check_driver_authority_expiry(session, tenant_id=tenant_id, driver=driver, now=now),
    ]
    return [a for a in alerts if a is not None]


async def run_vehicle_compliance_checks(
    session: AsyncSession, *, tenant_id: str, vehicle: Vehicle, now: datetime | None = None
) -> list[FatigueAlert]:
    """Runs both vehicle checks (registration + insurance), plus a
    calibration-due check for every Device currently paired to this vehicle
    (operations-cycle tracking pass — a vehicle can only have one *active*
    paired device in practice, but this loops over all matches rather than
    assuming exactly one, since nothing in app.models.fleet.Device enforces
    that). See `run_driver_compliance_checks` above."""
    alerts = [
        await check_vehicle_registration_expiry(session, tenant_id=tenant_id, vehicle=vehicle, now=now),
        await check_vehicle_insurance_expiry(session, tenant_id=tenant_id, vehicle=vehicle, now=now),
    ]
    device_result = await session.execute(
        select(Device).where(Device.tenant_id == tenant_id, Device.vehicle_id == vehicle.id)
    )
    for device in device_result.scalars():
        alerts.append(
            await check_device_calibration_expiry(session, tenant_id=tenant_id, device=device, now=now)
        )
    return [a for a in alerts if a is not None]


# --- dashboard listing (GET /v1/fleet/compliance-expiry) ---------------------


async def list_compliance_expiry(
    session: AsyncSession, *, tenant_id: str, within_days: int
) -> list[dict]:
    """Returns one dict per expiring/expired field, across both drivers and
    vehicles, tenant-scoped. Not tied to (and independent of) the FatigueAlert
    rows raised above — this always reflects the CURRENT state of
    User/Vehicle expiry columns, so it stays correct even if an alert was
    acknowledged or never raised yet. Sorted soonest-expiring (most negative
    days_remaining) first."""
    today = _sydney_today()
    horizon = today + timedelta(days=within_days)
    items: list[dict] = []

    driver_result = await session.execute(
        select(User).where(User.tenant_id == tenant_id, User.role == ROLE_DRIVER)
    )
    for driver in driver_result.scalars():
        for field_name, expiry in (
            ("driver_license_expiry", driver.driver_license_expiry),
            ("driver_authority_expiry", driver.driver_authority_expiry),
        ):
            if expiry is None or expiry > horizon:
                continue
            items.append(
                {
                    "entity_type": "driver",
                    "entity_id": driver.id,
                    "label": driver.name,
                    "field": field_name,
                    "expiry_date": expiry,
                    "status": "expired" if expiry < today else "expiring_soon",
                    "days_remaining": (expiry - today).days,
                }
            )

    vehicle_result = await session.execute(select(Vehicle).where(Vehicle.tenant_id == tenant_id))
    for vehicle in vehicle_result.scalars():
        for field_name, expiry in (
            ("registration_expiry", vehicle.registration_expiry),
            ("insurance_expiry", vehicle.insurance_expiry),
        ):
            if expiry is None or expiry > horizon:
                continue
            items.append(
                {
                    "entity_type": "vehicle",
                    "entity_id": vehicle.id,
                    "label": vehicle.rego,
                    "field": field_name,
                    "expiry_date": expiry,
                    "status": "expired" if expiry < today else "expiring_soon",
                    "days_remaining": (expiry - today).days,
                }
            )

    # Device meter re-verification due-dates (operations-cycle tracking pass).
    # entity_id/label are the DEVICE's own id/android_id (not the vehicle it's
    # paired to) — calibration is a property of the physical meter instrument,
    # not the car, so this stays correct across a re-pair. A device with no
    # calibration_due set, or not currently paired to a vehicle (nothing for a
    # dashboard consumer to action on without a vehicle context), is skipped.
    device_result = await session.execute(select(Device).where(Device.tenant_id == tenant_id))
    for device in device_result.scalars():
        expiry = device.calibration_due
        if expiry is None or expiry > horizon or device.vehicle_id is None:
            continue
        items.append(
            {
                "entity_type": "device",
                "entity_id": device.id,
                "label": device.android_id,
                "field": "calibration_due",
                "expiry_date": expiry,
                "status": "expired" if expiry < today else "expiring_soon",
                "days_remaining": (expiry - today).days,
            }
        )

    items.sort(key=lambda i: i["days_remaining"])
    return items
