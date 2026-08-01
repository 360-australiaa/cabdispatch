"""Jobs domain business logic: driver-availability matching, the create+
broadcast flow, the accept/decline (first-accept-wins) state machine, lazy
offer expiry, and the in-process WS pub/sub used by `WS /v1/jobs/live`.

Matching reads (read-only, never writes) the sibling `shift` and `trips`
domains' tables to compose "is this driver currently available" — see
`app.models.jobs.DriverAvailability`'s module docstring for the exact rule.
This mirrors the read-only cross-domain join pattern already established by
`app.services.live_ops` (which reads `Vehicle`/`Device`/`Trip`/`Shift`/`User`
the same way, owning no table of its own).
"""
from __future__ import annotations

import asyncio
import logging
import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.jobs import (
    JOB_STATUS_ACCEPTED,
    JOB_STATUS_CANCELLED,
    JOB_STATUS_OFFERED,
    JOB_STATUS_QUEUED,
    JOB_TERMINAL_STATUSES,
    OFFER_STATUS_ACCEPTED,
    OFFER_STATUS_DECLINED,
    OFFER_STATUS_EXPIRED,
    OFFER_STATUS_PENDING,
    DriverAvailability,
    Job,
    JobOffer,
)
from app.models.shift import Shift
from app.models.trips import TRIP_STATUS_OPEN, Trip

logger = logging.getLogger("cab_dispatch.jobs")

# How long a single driver's offer stays pending before it lapses. Every
# offer fanned out for a given job shares this same window (they're all
# created in the same instant, at job-creation time).
OFFER_WINDOW_SECONDS = 20


class JobsError(Exception):
    """Base class for jobs-domain errors; the router translates each
    subclass to the appropriate HTTP status."""


class JobNotFoundError(JobsError):
    pass


class JobNotCancellableError(JobsError):
    pass


class OfferNotFoundError(JobsError):
    pass


class OfferNotPendingError(JobsError):
    pass


class OfferNotAssignedToDriverError(JobsError):
    pass


# ==================================================================================
# In-process pub/sub, keyed by driver_id — see app.services.duress.GPSBroadcaster /
# app.services.live_ops._FleetBroadcaster for the identical pattern this reuses.
# ==================================================================================


class JobOfferBroadcaster:
    """In-memory fan-out of `job_offer` push events to whichever driver the
    offer is addressed to, for every `WS /v1/jobs/live` connection that
    driver currently has open.

    Keyed by `driver_id` rather than `tenant_id` (unlike
    `live_ops._FleetBroadcaster`) because offers are addressed to one specific
    driver, not broadcast to a whole tenant's dashboard — a driver only ever
    needs to hear about jobs offered to them. Not Redis-backed, same tradeoff
    as the sibling GPS/position broadcasters: best-effort, in-process only,
    lost on restart, and a later pass could swap this for Redis pub/sub
    without changing the public interface (subscribe/unsubscribe/publish).
    """

    _MAX_QUEUE_SIZE = 50

    def __init__(self) -> None:
        self._subscribers: dict[str, set[asyncio.Queue]] = {}
        self._lock = asyncio.Lock()

    async def subscribe(self, driver_id: str) -> asyncio.Queue:
        queue: asyncio.Queue = asyncio.Queue(maxsize=self._MAX_QUEUE_SIZE)
        async with self._lock:
            self._subscribers.setdefault(driver_id, set()).add(queue)
        return queue

    async def unsubscribe(self, driver_id: str, queue: asyncio.Queue) -> None:
        async with self._lock:
            subs = self._subscribers.get(driver_id)
            if subs is not None:
                subs.discard(queue)
                if not subs:
                    self._subscribers.pop(driver_id, None)

    async def publish(self, driver_id: str, message: dict) -> int:
        """Broadcasts `message` to every connection this driver currently has
        open. Returns the number of connections reached (0 is normal — most
        offers are made to a driver whose app isn't connected right now; the
        offer row still exists and can be listed/accepted via HTTP)."""
        async with self._lock:
            subs = list(self._subscribers.get(driver_id, ()))

        delivered = 0
        for queue in subs:
            try:
                queue.put_nowait(message)
                delivered += 1
            except asyncio.QueueFull:
                logger.warning(
                    "Job offer broadcaster: subscriber queue full for driver %s, dropping message",
                    driver_id,
                )
        return delivered

    def listener_count(self, driver_id: str) -> int:
        return len(self._subscribers.get(driver_id, ()))


# Process-wide singleton. See class docstring for the Redis swap-in path.
job_offer_broadcaster = JobOfferBroadcaster()


# ==================================================================================
# Driver availability toggle
# ==================================================================================


async def set_driver_availability(
    session: AsyncSession, *, tenant_id: str, driver_id: str, is_available: bool
) -> DriverAvailability:
    result = await session.execute(
        select(DriverAvailability).where(
            DriverAvailability.tenant_id == tenant_id, DriverAvailability.driver_id == driver_id
        )
    )
    row = result.scalar_one_or_none()
    if row is None:
        row = DriverAvailability(
            id=str(uuid.uuid4()), tenant_id=tenant_id, driver_id=driver_id, is_available=is_available
        )
        session.add(row)
    else:
        row.is_available = is_available

    await session.commit()
    await session.refresh(row)
    return row


# ==================================================================================
# Matching: "which drivers are currently offer-eligible for this tenant"
# ==================================================================================


async def available_driver_ids(session: AsyncSession, *, tenant_id: str) -> list[str]:
    """A driver is offer-eligible iff: `DriverAvailability.is_available` is
    True, AND they have a currently-open `Shift` (`end_at IS NULL`), AND they
    do NOT have a currently-open `Trip` (`status == 'open'`). See
    `app.models.jobs.DriverAvailability`'s module docstring for the full
    rationale. Each stage below narrows the candidate set with one more
    tenant-scoped query rather than one large join, matching the composition
    style already used by `app.services.live_ops`."""
    available_result = await session.execute(
        select(DriverAvailability.driver_id).where(
            DriverAvailability.tenant_id == tenant_id,
            DriverAvailability.is_available.is_(True),
        )
    )
    available_ids = set(available_result.scalars().all())
    if not available_ids:
        return []

    on_shift_result = await session.execute(
        select(Shift.driver_id).where(
            Shift.tenant_id == tenant_id,
            Shift.end_at.is_(None),
            Shift.driver_id.in_(available_ids),
        )
    )
    on_shift_ids = set(on_shift_result.scalars().all())
    if not on_shift_ids:
        return []

    mid_trip_result = await session.execute(
        select(Trip.driver_id).where(
            Trip.tenant_id == tenant_id,
            Trip.status == TRIP_STATUS_OPEN,
            Trip.driver_id.in_(on_shift_ids),
        )
    )
    mid_trip_ids = set(mid_trip_result.scalars().all())

    return sorted(on_shift_ids - mid_trip_ids)


# ==================================================================================
# Create + broadcast
# ==================================================================================


async def create_job_and_broadcast(
    session: AsyncSession,
    *,
    tenant_id: str,
    created_by_user_id: str | None,
    origin_lat: float,
    origin_lng: float,
    origin_address: str,
    dest_lat: float,
    dest_lng: float,
    dest_address: str,
    fare_estimate_low: Decimal,
    fare_estimate_high: Decimal,
) -> tuple[Job, list[JobOffer]]:
    """Creates the `Job` row, then fans out one pending `JobOffer` (20s
    window) to every currently-available driver in the tenant, broadcasting
    each via `job_offer_broadcaster` to that driver's `WS /v1/jobs/live`
    connection (if any). If no driver is currently available the job is left
    in `queued` with zero offers — there is no retry/re-broadcast loop in this
    pass (no scheduler infra exists in this backend; see the module docstring
    on `expire_stale_offers` for the lazy-expiry equivalent on the other end
    of an offer's lifecycle)."""
    now = datetime.now(UTC)
    job = Job(
        id=str(uuid.uuid4()),
        tenant_id=tenant_id,
        origin_lat=origin_lat,
        origin_lng=origin_lng,
        origin_address=origin_address,
        dest_lat=dest_lat,
        dest_lng=dest_lng,
        dest_address=dest_address,
        status=JOB_STATUS_QUEUED,
        fare_estimate_low=fare_estimate_low,
        fare_estimate_high=fare_estimate_high,
        requested_at=now,
        created_by_user_id=created_by_user_id,
        accepted_by_driver_id=None,
    )
    session.add(job)

    driver_ids = await available_driver_ids(session, tenant_id=tenant_id)

    offers: list[JobOffer] = []
    if driver_ids:
        expires_at = now + timedelta(seconds=OFFER_WINDOW_SECONDS)
        for driver_id in driver_ids:
            offer = JobOffer(
                id=str(uuid.uuid4()),
                job_id=job.id,
                tenant_id=tenant_id,
                driver_id=driver_id,
                status=OFFER_STATUS_PENDING,
                offered_at=now,
                expires_at=expires_at,
                responded_at=None,
            )
            session.add(offer)
            offers.append(offer)
        job.status = JOB_STATUS_OFFERED

    await session.commit()
    await session.refresh(job)
    for offer in offers:
        await session.refresh(offer)

    for offer in offers:
        await job_offer_broadcaster.publish(
            offer.driver_id,
            {
                "type": "job_offer",
                "offer": _offer_to_dict(offer),
                "job": _job_to_dict(job),
            },
        )

    return job, offers


def _job_to_dict(job: Job) -> dict:
    return {
        "id": job.id,
        "tenant_id": job.tenant_id,
        "origin_lat": job.origin_lat,
        "origin_lng": job.origin_lng,
        "origin_address": job.origin_address,
        "dest_lat": job.dest_lat,
        "dest_lng": job.dest_lng,
        "dest_address": job.dest_address,
        "status": job.status,
        "fare_estimate_low": str(job.fare_estimate_low),
        "fare_estimate_high": str(job.fare_estimate_high),
        "requested_at": job.requested_at.isoformat(),
        "created_by_user_id": job.created_by_user_id,
        "accepted_by_driver_id": job.accepted_by_driver_id,
        "created_at": job.created_at.isoformat(),
        "updated_at": job.updated_at.isoformat(),
    }


def _offer_to_dict(offer: JobOffer) -> dict:
    return {
        "id": offer.id,
        "job_id": offer.job_id,
        "tenant_id": offer.tenant_id,
        "driver_id": offer.driver_id,
        "status": offer.status,
        "offered_at": offer.offered_at.isoformat(),
        "expires_at": offer.expires_at.isoformat(),
        "responded_at": offer.responded_at.isoformat() if offer.responded_at else None,
    }


# ==================================================================================
# Lazy offer expiry — called on read, not a scheduled job (no scheduler infra
# exists in this backend; see the module docstring).
# ==================================================================================


async def expire_stale_offers(session: AsyncSession, *, tenant_id: str, job_id: str | None = None) -> int:
    """Flips any `pending` offer whose `expires_at` has passed to `expired`.
    Scoped to `tenant_id` always; optionally narrowed to one `job_id`. Called
    lazily at the top of every read/action path that touches offers (list
    offers, get job, accept, decline) so state is always correct-as-of-now
    without any background process. Returns the number of offers flipped."""
    filters = [
        JobOffer.tenant_id == tenant_id,
        JobOffer.status == OFFER_STATUS_PENDING,
        JobOffer.expires_at < datetime.now(UTC),
    ]
    if job_id is not None:
        filters.append(JobOffer.job_id == job_id)

    result = await session.execute(select(JobOffer).where(*filters))
    stale = result.scalars().all()
    if not stale:
        return 0

    now = datetime.now(UTC)
    for offer in stale:
        offer.status = OFFER_STATUS_EXPIRED
        offer.responded_at = now

    await session.commit()
    return len(stale)


# ==================================================================================
# Accept / decline
# ==================================================================================


async def get_job_or_404(session: AsyncSession, *, tenant_id: str, job_id: str) -> Job:
    result = await session.execute(select(Job).where(Job.id == job_id, Job.tenant_id == tenant_id))
    job = result.scalar_one_or_none()
    if job is None:
        raise JobNotFoundError(job_id)
    return job


async def get_offer_or_404(session: AsyncSession, *, tenant_id: str, job_id: str, offer_id: str) -> JobOffer:
    result = await session.execute(
        select(JobOffer).where(
            JobOffer.id == offer_id, JobOffer.job_id == job_id, JobOffer.tenant_id == tenant_id
        )
    )
    offer = result.scalar_one_or_none()
    if offer is None:
        raise OfferNotFoundError(offer_id)
    return offer


async def accept_offer(
    session: AsyncSession, *, tenant_id: str, job_id: str, offer_id: str, driver_id: str
) -> JobOffer:
    """First-accept-wins: whichever driver's accept lands first gets the job.
    NOTE (intentional v1 scope cut): there is no proximity/ETA ranking here —
    literally first HTTP request to reach this handler wins, full stop; a
    later pass could add distance/ETA-based offer prioritization without
    changing this endpoint's contract."""
    await expire_stale_offers(session, tenant_id=tenant_id, job_id=job_id)

    offer = await get_offer_or_404(session, tenant_id=tenant_id, job_id=job_id, offer_id=offer_id)
    if offer.driver_id != driver_id:
        raise OfferNotAssignedToDriverError(offer_id)
    if offer.status != OFFER_STATUS_PENDING:
        raise OfferNotPendingError(offer.status)

    job = await get_job_or_404(session, tenant_id=tenant_id, job_id=job_id)

    now = datetime.now(UTC)
    offer.status = OFFER_STATUS_ACCEPTED
    offer.responded_at = now

    job.status = JOB_STATUS_ACCEPTED
    job.accepted_by_driver_id = driver_id

    # Expire every OTHER still-pending offer for this job — first-accept-wins.
    siblings_result = await session.execute(
        select(JobOffer).where(
            JobOffer.job_id == job_id,
            JobOffer.tenant_id == tenant_id,
            JobOffer.id != offer.id,
            JobOffer.status == OFFER_STATUS_PENDING,
        )
    )
    for sibling in siblings_result.scalars().all():
        sibling.status = OFFER_STATUS_EXPIRED
        sibling.responded_at = now

    await session.commit()
    await session.refresh(offer)
    return offer


async def decline_offer(
    session: AsyncSession, *, tenant_id: str, job_id: str, offer_id: str, driver_id: str
) -> JobOffer:
    """Declining doesn't change the job — it stays `offered` while any
    sibling offers are still pending (or lapses to `expired` on its own via
    `expire_stale_offers` once every offer has been declined/expired; there is
    no auto re-broadcast to a fresh driver pool in this pass, see the module
    docstring on `create_job_and_broadcast`)."""
    await expire_stale_offers(session, tenant_id=tenant_id, job_id=job_id)

    offer = await get_offer_or_404(session, tenant_id=tenant_id, job_id=job_id, offer_id=offer_id)
    if offer.driver_id != driver_id:
        raise OfferNotAssignedToDriverError(offer_id)
    if offer.status != OFFER_STATUS_PENDING:
        raise OfferNotPendingError(offer.status)

    offer.status = OFFER_STATUS_DECLINED
    offer.responded_at = datetime.now(UTC)

    await session.commit()
    await session.refresh(offer)
    return offer


# ==================================================================================
# List / cancel
# ==================================================================================


async def list_jobs(
    session: AsyncSession,
    *,
    tenant_id: str,
    status_filter: str | None = None,
    skip: int = 0,
    limit: int = 20,
) -> tuple[list[Job], int]:
    stmt = select(Job).where(Job.tenant_id == tenant_id)
    count_stmt = select(func.count()).select_from(Job).where(Job.tenant_id == tenant_id)

    if status_filter is not None:
        stmt = stmt.where(Job.status == status_filter)
        count_stmt = count_stmt.where(Job.status == status_filter)

    total = (await session.execute(count_stmt)).scalar_one()
    result = await session.execute(stmt.order_by(Job.requested_at.desc()).offset(skip).limit(limit))
    return list(result.scalars().all()), total


async def list_offers_for_job(session: AsyncSession, *, tenant_id: str, job_id: str) -> list[JobOffer]:
    """Lazily expires stale offers for this job first, so the returned list is
    always correct-as-of-now."""
    await expire_stale_offers(session, tenant_id=tenant_id, job_id=job_id)
    result = await session.execute(
        select(JobOffer)
        .where(JobOffer.job_id == job_id, JobOffer.tenant_id == tenant_id)
        .order_by(JobOffer.offered_at)
    )
    return list(result.scalars().all())


async def cancel_job(session: AsyncSession, *, tenant_id: str, job_id: str) -> Job:
    job = await get_job_or_404(session, tenant_id=tenant_id, job_id=job_id)
    if job.status in JOB_TERMINAL_STATUSES:
        raise JobNotCancellableError(job.status)

    job.status = JOB_STATUS_CANCELLED

    # A cancelled job's outstanding offers are no longer actionable — flip
    # every still-pending one to expired rather than leaving them dangling
    # (a driver could otherwise still POST .../accept on a dead job).
    now = datetime.now(UTC)
    pending_result = await session.execute(
        select(JobOffer).where(
            JobOffer.job_id == job_id,
            JobOffer.tenant_id == tenant_id,
            JobOffer.status == OFFER_STATUS_PENDING,
        )
    )
    for offer in pending_result.scalars().all():
        offer.status = OFFER_STATUS_EXPIRED
        offer.responded_at = now

    await session.commit()
    await session.refresh(job)
    return job
