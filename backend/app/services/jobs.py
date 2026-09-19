"""Jobs domain business logic: driver-availability matching, the create+
broadcast flow, the accept/decline (first-accept-wins) state machine, lazy
offer expiry, and the in-process WS pub/sub used by `WS /v1/jobs/live`.

Matching reads (read-only, never writes) the sibling `shift` and `trips`
domains' tables to compose "is this driver currently available" -- see
`app.models.jobs.DriverAvailability`'s module docstring for the exact rule.
This mirrors the read-only cross-domain join pattern already established by
`app.services.live_ops` (which reads `Vehicle`/`Device`/`Trip`/`Shift`/`User`
the same way, owning no table of its own).

As of this pass, offers are also fanned out nearest-driver-first (by
haversine distance from the job's origin to each driver's last known
position, see `_haversine_km` / `create_job_and_broadcast` below) rather than
in the arbitrary order `available_driver_ids` returns them -- closing the
"proximity/ETA-ranked job matching" gap flagged in PROJECT_HANDOFF.md. This
only changes offer *creation* order; acceptance is still first-accept-wins at
the HTTP layer (see `accept_offer`'s docstring).
"""
from __future__ import annotations

import asyncio
import logging
import math
import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

from sqlalchemy import func, inspect, select
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

# Mean Earth radius in km, standard value used by _haversine_km below.
_EARTH_RADIUS_KM = 6371.0


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
# In-process pub/sub -- see app.services.duress.GPSBroadcaster /
# app.services.live_ops._FleetBroadcaster for the identical pattern this reuses.
#
# TWO channels, not one (2026-09-19). Until this pass the broadcaster was keyed by
# driver_id ALONE and `app/api/v1/jobs.py` subscribed every connection -- driver or
# dispatcher -- to its own token `sub`. A dispatcher has no offers addressed to
# their user id, so their socket connected and then sat PERMANENTLY SILENT; the
# dashboard hook `dashboard/src/pages/dispatch/useJobsLive.ts` admits as much in
# its "a dispatcher's socket may be open and quiet" comment and keeps a safety
# poll running to compensate.
#
# Adding a tenant-keyed channel ALONGSIDE the driver-keyed one -- rather than
# re-keying the existing one by tenant -- is deliberate. Re-keying would have
# started delivering every driver's job traffic to every tablet in the tenant,
# and the deployed Android app has no client-side filter (and updates on the
# DRIVERS' schedules, so some tablets run month-old code). That is a
# tenant-data-leak-shaped bug, not a UI nicety. The driver channel therefore
# stays exactly as it was; `test_jobs.py` pins that explicitly.
# ==================================================================================


class JobOfferBroadcaster:
    """In-memory fan-out of job push events over two independent channels:

      * the **driver channel**, keyed by `driver_id` (`subscribe` /
        `unsubscribe` / `publish`): a driver hears ONLY about offers, and
        transitions on offers, addressed to them. Unchanged since this class
        was written, and it must stay that way -- see the module comment
        above on why widening it would leak other drivers' work to a tablet.
      * the **tenant channel**, keyed by `tenant_id` (`subscribe_tenant` /
        `unsubscribe_tenant` / `publish_tenant`): the dispatch desk hears
        every job event in its own tenant, which is the same fleet-wide view
        `live_ops._FleetBroadcaster` already takes for positions.

    Every event is published to the addressed driver AND once to the tenant,
    so a dispatcher watching one driver's offer sees a single frame, not N.

    Not Redis-backed, same tradeoff as the sibling GPS/position broadcasters:
    best-effort, in-process only, lost on restart (this backend runs a single
    uvicorn worker, permanently, so in-process fan-out does reach every
    connection there is), and a later pass could swap this for Redis pub/sub
    without changing the public interface.
    """

    _MAX_QUEUE_SIZE = 50

    def __init__(self) -> None:
        self._subscribers: dict[str, set[asyncio.Queue]] = {}
        self._tenant_subscribers: dict[str, set[asyncio.Queue]] = {}
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
        open. Returns the number of connections reached (0 is normal -- most
        offers are made to a driver whose app isn't connected right now; the
        offer row still exists and can be listed/accepted via HTTP)."""
        return await self._publish_to(self._subscribers, driver_id, message, "driver")

    async def subscribe_tenant(self, tenant_id: str) -> asyncio.Queue:
        """Dispatch-side counterpart of `subscribe`, same shape on purpose so
        the websocket handler differs only in which call it makes."""
        queue: asyncio.Queue = asyncio.Queue(maxsize=self._MAX_QUEUE_SIZE)
        async with self._lock:
            self._tenant_subscribers.setdefault(tenant_id, set()).add(queue)
        return queue

    async def unsubscribe_tenant(self, tenant_id: str, queue: asyncio.Queue) -> None:
        async with self._lock:
            subs = self._tenant_subscribers.get(tenant_id)
            if subs is not None:
                subs.discard(queue)
                if not subs:
                    self._tenant_subscribers.pop(tenant_id, None)

    async def publish_tenant(self, tenant_id: str, message: dict) -> int:
        """Broadcasts `message` to every dispatch-side connection currently
        open for this tenant. Returns the number of connections reached."""
        return await self._publish_to(self._tenant_subscribers, tenant_id, message, "tenant")

    async def _publish_to(
        self, registry: dict[str, set[asyncio.Queue]], key: str, message: dict, kind: str
    ) -> int:
        async with self._lock:
            subs = list(registry.get(key, ()))

        delivered = 0
        for queue in subs:
            try:
                queue.put_nowait(message)
                delivered += 1
            except asyncio.QueueFull:
                logger.warning(
                    "Job offer broadcaster: subscriber queue full for %s %s, dropping message",
                    kind,
                    key,
                )
        return delivered

    def listener_count(self, driver_id: str) -> int:
        return len(self._subscribers.get(driver_id, ()))

    def tenant_listener_count(self, tenant_id: str) -> int:
        return len(self._tenant_subscribers.get(tenant_id, ()))

    def reset(self) -> None:
        """Drops every subscription on BOTH channels. Only the test fixtures
        call this: they used to reach in and clear `_subscribers` directly,
        which as of the second channel would have cleared only half the state
        and let tenant-channel queues leak from one test into the next. One
        method, so a future third channel cannot reintroduce that."""
        self._subscribers.clear()
        self._tenant_subscribers.clear()


# Process-wide singleton. See class docstring for the Redis swap-in path.
job_offer_broadcaster = JobOfferBroadcaster()


# Event `type` values carried on the wire. `job_offer` is the original one and is
# spelled exactly as before -- the Android app and `useJobsLive.ts` both already
# know it. The transition events are new: before this pass the ONLY publish site
# in this module was `create_job_and_broadcast`, so accept / decline / expiry /
# cancellation pushed nothing at all and the dispatch board only ever learned
# about them from its next poll.
EVENT_JOB_OFFER = "job_offer"
EVENT_OFFER_ACCEPTED = "job_offer_accepted"
EVENT_OFFER_DECLINED = "job_offer_declined"
EVENT_OFFER_EXPIRED = "job_offer_expired"
EVENT_JOB_CANCELLED = "job_cancelled"


async def _reload_if_expired(session: AsyncSession, row) -> None:
    """Re-loads `row` if the ORM has marked any of its columns unloaded.

    This exists because of a real 500. `Job.updated_at` / `JobOffer.updated_at`
    are refreshed by the database on UPDATE, so SQLAlchemy expires them after
    the flush; reading one later lazy-loads. Inside an async session that
    lazy-load happens OUTSIDE SQLAlchemy's greenlet context and raises
    `MissingGreenlet` -- which, on `POST .../accept`, turned a working
    acceptance into a 500 the instant the publish call was added. The fix is
    to do the IO here, awaited, before `_job_to_dict` touches anything, rather
    than to drop `updated_at` from the envelope.

    Conditional on `inspect(...).unloaded` so the common case (nothing
    expired, e.g. a row we just re-queried) costs no extra SELECT. These two
    models declare no relationships, so `unloaded` only ever means expired
    columns here."""
    if row is not None and inspect(row).unloaded:
        await session.refresh(row)


async def _publish_job_event(
    session: AsyncSession,
    *,
    event_type: str,
    tenant_id: str,
    driver_id: str | None,
    job: Job | None,
    offer: JobOffer | None,
) -> None:
    """The one place a job event leaves this module. Publishes the SAME
    envelope the original offer path used -- `{"type", "offer", "job"}` -- to
    the addressed driver's channel (when the event is addressed to a driver at
    all) and once to the tenant channel, so no frontend change is needed: the
    dashboard hook already treats any frame as "this job moved, refetch" and
    only reads `job.id` / `offer.job_id` out of it.

    ALWAYS call this AFTER `session.commit()`. A frame that arrives before the
    row is durable makes the dashboard refetch the PRE-transition state and
    then never hear about it again."""
    await _reload_if_expired(session, job)
    await _reload_if_expired(session, offer)
    message = {
        "type": event_type,
        "offer": _offer_to_dict(offer) if offer is not None else None,
        "job": _job_to_dict(job) if job is not None else None,
    }
    if driver_id:
        await job_offer_broadcaster.publish(driver_id, message)
    await job_offer_broadcaster.publish_tenant(tenant_id, message)


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


def _haversine_km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    """Great-circle distance between two lat/lng points, in km. Standard
    haversine formula, mean Earth radius (`_EARTH_RADIUS_KM` = 6371 km).
    Pure/stateless -- no DB access, no new dependency. Used by
    `create_job_and_broadcast` to rank offer-eligible drivers nearest-first
    against a job's origin."""
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lng2 - lng1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    return 2 * _EARTH_RADIUS_KM * math.asin(math.sqrt(a))


async def _nearest_first_driver_ids(
    session: AsyncSession, *, tenant_id: str, driver_ids: list[str], origin_lat: float, origin_lng: float
) -> list[str]:
    """Re-orders `driver_ids` (already offer-eligible, from
    `available_driver_ids`) nearest-to-`origin_lat`/`origin_lng` first, using
    each driver's `DriverAvailability.last_lat`/`last_lng` (persisted by
    `app.services.live_ops.publish_position`, see that module). A single
    query fetches every candidate's row at once rather than one query per
    driver. Drivers with no recorded position (new `DriverAvailability` row,
    or one never enriched by a position publish) sort to the END of the
    list -- unknown position is lowest priority, not an error, and they
    still get an offer."""
    if not driver_ids:
        return []

    result = await session.execute(
        select(DriverAvailability).where(
            DriverAvailability.tenant_id == tenant_id,
            DriverAvailability.driver_id.in_(driver_ids),
        )
    )
    positions = {row.driver_id: (row.last_lat, row.last_lng) for row in result.scalars().all()}

    def _sort_key(driver_id: str) -> tuple[int, float]:
        lat, lng = positions.get(driver_id, (None, None))
        if lat is None or lng is None:
            return (1, 0.0)
        return (0, _haversine_km(origin_lat, origin_lng, lat, lng))

    return sorted(driver_ids, key=_sort_key)


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
    connection (if any).

    Offers are created (and therefore broadcast/sorted-for-listing)
    nearest-driver-first: eligible driver_ids are re-ordered by
    `_nearest_first_driver_ids` (haversine distance from this job's origin to
    each driver's last known `DriverAvailability` position) before the
    `JobOffer` rows are built, closing the "proximity/ETA-ranked job
    matching" gap flagged in PROJECT_HANDOFF.md. A driver with no recorded
    position still gets an offer, just sorted to the end. This changes
    offer-creation order only -- acceptance is still first-accept-wins at the
    HTTP layer regardless of this ordering (see `accept_offer`'s docstring).

    If no driver is currently available the job is left in `queued` with zero
    offers -- there is no retry/re-broadcast loop in this pass (no scheduler
    infra exists in this backend; see the module docstring on
    `expire_stale_offers` for the lazy-expiry equivalent on the other end of
    an offer's lifecycle)."""
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
    driver_ids = await _nearest_first_driver_ids(
        session, tenant_id=tenant_id, driver_ids=driver_ids, origin_lat=origin_lat, origin_lng=origin_lng
    )

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

    # Same frames as before on the driver channel, byte for byte; the tenant
    # channel is the new half (see `_publish_job_event`), which is what finally
    # makes a dispatcher's socket say anything at all.
    for offer in offers:
        await _publish_job_event(
            session,
            event_type=EVENT_JOB_OFFER,
            tenant_id=tenant_id,
            driver_id=offer.driver_id,
            job=job,
            offer=offer,
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
# Lazy offer expiry -- called on read, not a scheduled job (no scheduler infra
# exists in this backend; see the module docstring).
# ==================================================================================


async def expire_stale_offers(session: AsyncSession, *, tenant_id: str, job_id: str | None = None) -> int:
    """Flips any `pending` offer whose `expires_at` has passed to `expired`.
    Scoped to `tenant_id` always; optionally narrowed to one `job_id`. Called
    lazily at the top of every read/action path that touches offers (list
    jobs, list offers, get job, accept, decline) so state is always correct-as-of-now
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

    # Push the expiry (2026-09-19). Expiry is the one transition nobody
    # requests -- it happens inside whatever read/action path happened to run
    # `expire_stale_offers` -- so before this pass a driver's tablet kept
    # showing a live offer countdown for an offer the server had already
    # lapsed, and the dispatch board only noticed on its next poll. Jobs are
    # fetched in ONE query keyed by the stale offers' job_ids rather than one
    # per offer; a job that has since vanished simply publishes `job: null`,
    # which the dashboard hook handles (it falls back to `offer.job_id`).
    jobs_by_id: dict[str, Job] = {}
    job_ids = {offer.job_id for offer in stale}
    if job_ids:
        jobs_result = await session.execute(
            select(Job).where(Job.tenant_id == tenant_id, Job.id.in_(job_ids))
        )
        jobs_by_id = {row.id: row for row in jobs_result.scalars().all()}

    for offer in stale:
        await _publish_job_event(
            session,
            event_type=EVENT_OFFER_EXPIRED,
            tenant_id=tenant_id,
            driver_id=offer.driver_id,
            job=jobs_by_id.get(offer.job_id),
            offer=offer,
        )

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
    NOTE: offers are now created nearest-driver-first (see
    `create_job_and_broadcast`), so the driver closest to the job's origin is
    generally the one whose offer arrives -- and gets accepted -- first. But
    that is only a soft, best-effort bias from creation order and delivery
    latency, not a guarantee: acceptance itself remains first-accept-wins at
    this HTTP layer, full stop, with no distance/ETA check here. A later pass
    could add a harder proximity/ETA gate on acceptance itself without
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

    # Expire every OTHER still-pending offer for this job -- first-accept-wins.
    siblings_result = await session.execute(
        select(JobOffer).where(
            JobOffer.job_id == job_id,
            JobOffer.tenant_id == tenant_id,
            JobOffer.id != offer.id,
            JobOffer.status == OFFER_STATUS_PENDING,
        )
    )
    siblings = list(siblings_result.scalars().all())
    for sibling in siblings:
        sibling.status = OFFER_STATUS_EXPIRED
        sibling.responded_at = now

    await session.commit()
    await session.refresh(offer)

    # After the commit, never before -- see `_publish_job_event`. The winning
    # driver and the dispatch desk hear the acceptance; each losing driver
    # hears their own offer lapse (on their own channel only, so no tablet
    # learns who won), and the desk hears those too.
    await _publish_job_event(
        session,
        event_type=EVENT_OFFER_ACCEPTED,
        tenant_id=tenant_id,
        driver_id=offer.driver_id,
        job=job,
        offer=offer,
    )
    for sibling in siblings:
        await _publish_job_event(
            session,
            event_type=EVENT_OFFER_EXPIRED,
            tenant_id=tenant_id,
            driver_id=sibling.driver_id,
            job=job,
            offer=sibling,
        )
    return offer


async def decline_offer(
    session: AsyncSession, *, tenant_id: str, job_id: str, offer_id: str, driver_id: str
) -> JobOffer:
    """Declining doesn't change the job -- it stays `offered` while any
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

    # After the commit, never before -- see `_publish_job_event`. The job row
    # is re-read rather than carried from above so the envelope shows the
    # committed state; a declined offer does not move the job, but the desk
    # still needs to see the pool shrink in real time.
    job = await get_job_or_404(session, tenant_id=tenant_id, job_id=job_id)
    await _publish_job_event(
        session,
        event_type=EVENT_OFFER_DECLINED,
        tenant_id=tenant_id,
        driver_id=offer.driver_id,
        job=job,
        offer=offer,
    )
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
    # Lazy offer expiry on the jobs LIST read (workstream B6). Every other
    # offer-touching path already did this, but the jobs list did not — so a
    # job whose offers nobody ever opened kept them `pending` forever and the
    # job was never re-offered to anyone. The dashboard's jobs page is in
    # practice the read that happens; hanging expiry off it is what makes
    # "expires_at" mean anything for an unattended job.
    await expire_stale_offers(session, tenant_id=tenant_id)

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

    # A cancelled job's outstanding offers are no longer actionable -- flip
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
    cancelled_offers = list(pending_result.scalars().all())
    for offer in cancelled_offers:
        offer.status = OFFER_STATUS_EXPIRED
        offer.responded_at = now

    await session.commit()
    await session.refresh(job)

    # After the commit, never before -- see `_publish_job_event`. Cancellation
    # is this domain's only completion/terminal transition (there is no
    # `completed` job status: `JOB_TERMINAL_STATUSES` is accepted/expired/
    # cancelled, and the ride itself is completed in the `trips` domain). The
    # job-level frame goes to the tenant only -- it is addressed to no single
    # driver -- and each driver whose pending offer just died hears that on
    # their own channel, which is what stops a tablet ringing for a job the
    # desk already killed.
    await _publish_job_event(
        session,
        event_type=EVENT_JOB_CANCELLED,
        tenant_id=tenant_id,
        driver_id=None,
        job=job,
        offer=None,
    )
    for offer in cancelled_offers:
        await _publish_job_event(
            session,
            event_type=EVENT_OFFER_EXPIRED,
            tenant_id=tenant_id,
            driver_id=offer.driver_id,
            job=job,
            offer=offer,
        )
    return job
