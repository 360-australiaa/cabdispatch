"""Messages domain business logic: sending, listing a thread, mark-read, and
the in-process pub/sub used by the live driver websocket feed. The pub/sub
piece mirrors `app.services.duress.GPSBroadcaster` exactly (in-memory
`dict[str, set[asyncio.Queue]]`, no Redis) — see that class's docstring for
the Redis swap-in path, which applies identically here.
"""
from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.messages import Message

logger = logging.getLogger("cab_dispatch.messages")


class MessagesError(Exception):
    """Base class for messages-domain errors; the router translates each
    subclass to the appropriate HTTP status."""


class MessageNotFoundError(MessagesError):
    pass


class DriverIdRequiredError(MessagesError):
    """A dispatch-side sender omitted `driver_id`. Required so the message
    lands in the right driver's thread — a `driver`-role sender's own id is
    always used automatically instead (see the router), so this only ever
    fires for owner/admin/dispatcher senders."""


# --- lookups (tenant-scoped — every caller already has tenant_id from
# get_current_tenant_id) ------------------------------------------------------


async def get_message_or_404(session: AsyncSession, *, tenant_id: str, message_id: str) -> Message:
    result = await session.execute(
        select(Message).where(Message.id == message_id, Message.tenant_id == tenant_id)
    )
    message = result.scalar_one_or_none()
    if message is None:
        raise MessageNotFoundError(message_id)
    return message


# --- send / list / mark-read --------------------------------------------------


async def send_message(
    session: AsyncSession,
    *,
    tenant_id: str,
    driver_id: str | None,
    sender_type: str,
    sender_user_id: str | None,
    body: str,
) -> Message:
    """`thread_id` is always set to `driver_id` — one thread per driver, see
    `app.models.messages`'s module docstring."""
    if not driver_id:
        raise DriverIdRequiredError()

    message = Message(
        tenant_id=tenant_id,
        thread_id=driver_id,
        driver_id=driver_id,
        sender_type=sender_type,
        sender_user_id=sender_user_id,
        body=body,
        sent_at=datetime.now(UTC),
    )
    session.add(message)
    await session.commit()
    await session.refresh(message)
    return message


async def list_thread(
    session: AsyncSession, *, tenant_id: str, driver_id: str, skip: int, limit: int
) -> tuple[list[Message], int]:
    """Oldest-first — a chat thread reads top-to-bottom chronologically."""
    filters = (Message.tenant_id == tenant_id, Message.driver_id == driver_id)

    total = (await session.execute(select(func.count()).select_from(Message).where(*filters))).scalar_one()
    result = await session.execute(
        select(Message).where(*filters).order_by(Message.sent_at).offset(skip).limit(limit)
    )
    items = result.scalars().all()
    return items, total


async def mark_read(session: AsyncSession, message: Message) -> Message:
    """Idempotent: if already read, the existing `read_at` is left untouched
    (a read receipt shouldn't move backward/forward on repeat calls)."""
    if message.read_at is None:
        message.read_at = datetime.now(UTC)
        await session.commit()
        await session.refresh(message)
    return message


# --- live pub/sub (driver-scoped) ---------------------------------------------


class MessageBroadcaster:
    """In-process pub/sub for a driver's live message thread, keyed by
    `"{tenant_id}:{driver_id}"` (tenant-qualified so two tenants can never
    cross-deliver even in the unlikely event a `driver_id` string were ever
    reused). Identical shape to `app.services.duress.GPSBroadcaster` — see
    that class's docstring for the Redis swap-in path, which applies
    unchanged here: everything outside this class only ever calls
    `subscribe` / `unsubscribe` / `publish`.

    Each subscriber gets its own `asyncio.Queue` so a slow websocket consumer
    can't block delivery to others; messages are dropped (not buffered
    indefinitely) if a subscriber's queue fills up.
    """

    _MAX_QUEUE_SIZE = 100

    def __init__(self) -> None:
        self._subscribers: dict[str, set[asyncio.Queue]] = {}

    @staticmethod
    def _key(tenant_id: str, driver_id: str) -> str:
        return f"{tenant_id}:{driver_id}"

    def subscribe(self, tenant_id: str, driver_id: str) -> asyncio.Queue:
        queue: asyncio.Queue = asyncio.Queue(maxsize=self._MAX_QUEUE_SIZE)
        self._subscribers.setdefault(self._key(tenant_id, driver_id), set()).add(queue)
        return queue

    def unsubscribe(self, tenant_id: str, driver_id: str, queue: asyncio.Queue) -> None:
        key = self._key(tenant_id, driver_id)
        subscribers = self._subscribers.get(key)
        if not subscribers:
            return
        subscribers.discard(queue)
        if not subscribers:
            self._subscribers.pop(key, None)

    async def publish(self, tenant_id: str, driver_id: str, payload: dict) -> int:
        """Broadcasts `payload` to every current subscriber of this driver's
        thread. Returns the number of subscribers it was delivered to (0 if
        nobody is currently listening — the message is simply dropped, not
        queued for later joiners; it's still safely persisted in the DB via
        `send_message`, so nothing is lost — only the *live push* is missed)."""
        subscribers = self._subscribers.get(self._key(tenant_id, driver_id), set())
        delivered = 0
        for queue in list(subscribers):
            try:
                queue.put_nowait(payload)
                delivered += 1
            except asyncio.QueueFull:
                logger.warning(
                    "Message broadcaster: subscriber queue full for driver %s, dropping message",
                    driver_id,
                )
        return delivered

    def listener_count(self, tenant_id: str, driver_id: str) -> int:
        return len(self._subscribers.get(self._key(tenant_id, driver_id), ()))


# Process-wide singleton. See class docstring for the Redis swap-in path.
message_broadcaster = MessageBroadcaster()
