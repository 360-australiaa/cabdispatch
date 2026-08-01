"""Duress domain business logic: the trigger/cancel/escalate/close state
machine, and the in-process GPS pub/sub used by the live websocket feed.
"""
from __future__ import annotations

import asyncio
import logging
import uuid
from datetime import UTC, datetime, timedelta

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.duress import (
    CANCEL_WINDOW_SECONDS,
    DURESS_STATUS_CANCELLED,
    DURESS_STATUS_DISPATCHED,
    DURESS_STATUS_ESCALATING,
    DURESS_STATUS_OPEN,
    DURESS_STATUS_RESOLVED,
    DURESS_TERMINAL_STATUSES,
    ESCALATION_STAGE_CANCEL_WINDOW_EXPIRED,
    ESCALATION_STAGE_PRESENT_000_CALL_SCRIPT,
    ESCALATION_STAGES,
    DuressEvent,
)

logger = logging.getLogger("cab_dispatch.duress")


def _now_iso() -> str:
    return datetime.now(UTC).isoformat()


def _append_log_entry(event: DuressEvent, entry: dict, **bookkeeping_overrides) -> None:
    """Replaces `event.escalation_log_json` wholesale with a new dict carrying
    the appended entry, so SQLAlchemy's change tracking always sees a fresh
    object (see the field's docstring in app.models.duress)."""
    current = event.escalation_log_json or {}
    new_log = {
        **current,
        "entries": [*current.get("entries", []), entry],
        **bookkeeping_overrides,
    }
    event.escalation_log_json = new_log


# --- trigger / open -----------------------------------------------------------


async def trigger_event(
    session: AsyncSession,
    *,
    tenant_id: str,
    vehicle_id: str,
    driver_id: str,
    trigger: str,
    gps_stream_ref: str | None,
    audio_ref: str | None,
) -> DuressEvent:
    """Opens a new duress event: status=open, starts the cancel window."""
    opened_at = datetime.now(UTC)
    cancel_deadline_at = opened_at + timedelta(seconds=CANCEL_WINDOW_SECONDS)
    event_id = str(uuid.uuid4())

    event = DuressEvent(
        id=event_id,
        tenant_id=tenant_id,
        vehicle_id=vehicle_id,
        driver_id=driver_id,
        trigger=trigger,
        status=DURESS_STATUS_OPEN,
        opened_at=opened_at,
        closed_at=None,
        gps_stream_ref=gps_stream_ref or f"duress/{event_id}/gps",
        audio_ref=audio_ref,
        escalation_log_json={
            "cancel_window_seconds": CANCEL_WINDOW_SECONDS,
            "cancel_deadline_at": cancel_deadline_at.isoformat(),
            "next_stage_index": 0,
            "entries": [
                {
                    "stage": "opened",
                    "at": opened_at.isoformat(),
                    "detail": f"duress event opened via {trigger}",
                }
            ],
        },
    )
    session.add(event)
    await session.commit()
    await session.refresh(event)
    return event


# --- cancel --------------------------------------------------------------------


async def cancel_event(session: AsyncSession, event: DuressEvent, *, note: str | None) -> DuressEvent:
    """Cancels an event — only valid while status == open AND the wall-clock
    cancel window (recorded at trigger time) hasn't yet elapsed."""
    if event.status != DURESS_STATUS_OPEN:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Cannot cancel a duress event in status '{event.status}' "
            "(only 'open' events, inside the cancel window, may be cancelled)",
        )

    deadline_raw = (event.escalation_log_json or {}).get("cancel_deadline_at")
    deadline = datetime.fromisoformat(deadline_raw) if deadline_raw else None
    if deadline is not None and datetime.now(UTC) > deadline:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Cancel window has expired for this duress event",
        )

    now = datetime.now(UTC)
    event.status = DURESS_STATUS_CANCELLED
    event.closed_at = now
    _append_log_entry(event, {"stage": "cancelled", "at": now.isoformat(), "note": note})

    await session.commit()
    await session.refresh(event)
    return event


# --- escalate --------------------------------------------------------------------


async def escalate_event(session: AsyncSession, event: DuressEvent, *, note: str | None) -> DuressEvent:
    """Advances the escalation cascade by exactly one stage.

    Cascade (see app.models.duress.ESCALATION_STAGES):
        cancel_window_expired -> notify_dispatch -> sms_emergency_contacts
        -> present_000_call_script

    The first stage flips status open -> escalating; the last stage flips
    escalating -> dispatched. Each call is a discrete, manually-triggered step
    (from a background job or a dispatcher action) — there is no server-side
    timer in this pass.
    """
    if event.status in DURESS_TERMINAL_STATUSES:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Cannot escalate a duress event already in terminal status '{event.status}'",
        )
    if event.status not in (DURESS_STATUS_OPEN, DURESS_STATUS_ESCALATING):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Cannot escalate a duress event in status '{event.status}'",
        )

    log = event.escalation_log_json or {}
    next_stage_index = log.get("next_stage_index", 0)
    if next_stage_index >= len(ESCALATION_STAGES):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="All escalation stages have already been completed for this event",
        )

    stage = ESCALATION_STAGES[next_stage_index]
    now = datetime.now(UTC)

    _append_log_entry(
        event,
        {"stage": stage, "at": now.isoformat(), "note": note},
        next_stage_index=next_stage_index + 1,
    )

    if stage == ESCALATION_STAGE_CANCEL_WINDOW_EXPIRED:
        event.status = DURESS_STATUS_ESCALATING
    elif stage == ESCALATION_STAGE_PRESENT_000_CALL_SCRIPT:
        event.status = DURESS_STATUS_DISPATCHED

    await session.commit()
    await session.refresh(event)
    return event


# --- close --------------------------------------------------------------------


async def close_event(session: AsyncSession, event: DuressEvent, *, note: str | None) -> DuressEvent:
    """Closes/resolves an event. Valid from any non-terminal status (an event
    can be resolved directly from `open` too — e.g. a false alarm confirmed by
    dispatch without ever escalating)."""
    if event.status in DURESS_TERMINAL_STATUSES:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Duress event already in terminal status '{event.status}'",
        )

    now = datetime.now(UTC)
    event.status = DURESS_STATUS_RESOLVED
    event.closed_at = now
    _append_log_entry(event, {"stage": "resolved", "at": now.isoformat(), "note": note})

    await session.commit()
    await session.refresh(event)
    return event


# --- GPS pub/sub ----------------------------------------------------------------


class GPSBroadcaster:
    """In-process pub/sub for a duress event's live GPS feed, keyed by event id.

    Deliberately structured so a later swap to Redis pub/sub (or any other
    broker) is a small, localized change: everything outside this class only
    ever calls `subscribe` / `unsubscribe` / `publish`. A Redis-backed
    implementation would keep this exact interface, replacing the in-memory
    `dict[str, set[asyncio.Queue]]` with `PUBLISH`/`SUBSCRIBE` calls against a
    per-event channel name.

    Each subscriber gets its own `asyncio.Queue` so a slow websocket consumer
    can't block delivery to others; points are dropped (not buffered
    indefinitely) if a subscriber's queue fills up, since GPS feeds are
    inherently "latest position matters most".
    """

    _MAX_QUEUE_SIZE = 100

    def __init__(self) -> None:
        self._subscribers: dict[str, set[asyncio.Queue]] = {}

    def subscribe(self, event_id: str) -> asyncio.Queue:
        queue: asyncio.Queue = asyncio.Queue(maxsize=self._MAX_QUEUE_SIZE)
        self._subscribers.setdefault(event_id, set()).add(queue)
        return queue

    def unsubscribe(self, event_id: str, queue: asyncio.Queue) -> None:
        subscribers = self._subscribers.get(event_id)
        if not subscribers:
            return
        subscribers.discard(queue)
        if not subscribers:
            self._subscribers.pop(event_id, None)

    async def publish(self, event_id: str, point: dict) -> int:
        """Broadcasts `point` to every current subscriber of `event_id`.
        Returns the number of subscribers it was delivered to (0 if nobody is
        currently listening — the point is simply dropped, not queued for
        later joiners)."""
        subscribers = self._subscribers.get(event_id, set())
        delivered = 0
        for queue in list(subscribers):
            try:
                queue.put_nowait(point)
                delivered += 1
            except asyncio.QueueFull:
                logger.warning(
                    "GPS broadcaster: subscriber queue full for duress event %s, dropping point",
                    event_id,
                )
        return delivered

    def listener_count(self, event_id: str) -> int:
        return len(self._subscribers.get(event_id, ()))


# Process-wide singleton. See class docstring for the Redis swap-in path.
gps_broadcaster = GPSBroadcaster()
