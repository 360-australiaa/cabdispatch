"""Messages domain business logic: sending, listing a thread, mark-read, the
in-process pub/sub used by the live driver websocket feed, and the fixed
canned-template menu. The pub/sub piece mirrors app.services.duress.GPSBroadcaster
exactly (in-memory dict[str, set[asyncio.Queue]], no Redis) -- see that
class's docstring for the Redis swap-in path, which applies identically here.
"""
from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from datetime import UTC, datetime

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.messages import SENDER_TYPE_DISPATCH, SENDER_TYPE_DRIVER, Message

logger = logging.getLogger("cab_dispatch.messages")


class MessagesError(Exception):
    """Base class for messages-domain errors; the router translates each
    subclass to the appropriate HTTP status."""


class MessageNotFoundError(MessagesError):
    pass


class DriverIdRequiredError(MessagesError):
    """A dispatch-side sender omitted driver_id. Required so the message
    lands in the right driver's thread -- a driver-role sender's own id is
    always used automatically instead (see the router), so this only ever
    fires for owner/admin/dispatcher senders."""


class TemplateNotFoundError(MessagesError):
    """Unknown template code passed to POST /v1/messages/templates/{code}."""


class TemplateNotAllowedForSenderError(MessagesError):
    """The template's sender_type doesn't match the caller's own sender
    type -- e.g. a driver trying to send a dispatch-only "Return to depot"
    template, or vice versa."""


async def get_message_or_404(session: AsyncSession, *, tenant_id: str, message_id: str) -> Message:
    result = await session.execute(
        select(Message).where(Message.id == message_id, Message.tenant_id == tenant_id)
    )
    message = result.scalar_one_or_none()
    if message is None:
        raise MessageNotFoundError(message_id)
    return message


async def send_message(
    session: AsyncSession,
    *,
    tenant_id: str,
    driver_id: str | None,
    sender_type: str,
    sender_user_id: str | None,
    body: str,
) -> Message:
    """thread_id is always set to driver_id -- one thread per driver, see
    app.models.messages's module docstring."""
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
    """Oldest-first -- a chat thread reads top-to-bottom chronologically."""
    filters = (Message.tenant_id == tenant_id, Message.driver_id == driver_id)

    total = (await session.execute(select(func.count()).select_from(Message).where(*filters))).scalar_one()
    result = await session.execute(
        select(Message).where(*filters).order_by(Message.sent_at).offset(skip).limit(limit)
    )
    items = result.scalars().all()
    return items, total


async def mark_read(session: AsyncSession, message: Message) -> Message:
    """Idempotent: if already read, the existing read_at is left untouched
    (a read receipt shouldn't move backward/forward on repeat calls)."""
    if message.read_at is None:
        message.read_at = datetime.now(UTC)
        await session.commit()
        await session.refresh(message)
    return message


# --- canned message templates --------------------------------------------------
# A small fixed set -- no new table (per the domain brief), same rationale as
# the plain-string kind constants in app.models.fatigue_alert. Modeled on
# a real taxi-meter driver quick-request menu: the classic No Job /
# Recall / Job Query / Other buttons a driver taps when there is nothing
# to type, plus dispatch-initiated Vehicle Initiated Message style status
# templates dispatch can fire back at a driver's terminal with one tap.
#
# body is the literal text stored on the Message row (see
# send_template_message below); it is never displayed to the user as
# anything other than the message body itself, so it doubles as the
# human-readable canned text.


@dataclass(frozen=True)
class MessageTemplate:
    code: str
    label: str
    sender_type: str  # SENDER_TYPE_DRIVER or SENDER_TYPE_DISPATCH
    body: str


MESSAGE_TEMPLATES: list[MessageTemplate] = [
    # Driver-facing quick-request menu.
    MessageTemplate(code="no_job", label="No Job", sender_type=SENDER_TYPE_DRIVER, body="No job."),
    MessageTemplate(
        code="recall", label="Recall", sender_type=SENDER_TYPE_DRIVER, body="Requesting recall to base."
    ),
    MessageTemplate(
        code="job_query", label="Job Query", sender_type=SENDER_TYPE_DRIVER, body="Query regarding current job."
    ),
    # Other carries no fixed meaning on its own -- the driver's optional
    # note (see send_template_message) is what makes it useful; the body
    # below is only ever seen on its own if no note was supplied.
    MessageTemplate(code="other", label="Other", sender_type=SENDER_TYPE_DRIVER, body="Other."),
    # Dispatch-facing Vehicle Initiated Message style quick status templates.
    MessageTemplate(
        code="check_in", label="Please check in", sender_type=SENDER_TYPE_DISPATCH, body="Please check in."
    ),
    MessageTemplate(
        code="return_to_depot",
        label="Return to depot",
        sender_type=SENDER_TYPE_DISPATCH,
        body="Return to depot.",
    ),
    MessageTemplate(
        code="contact_base_urgent",
        label="Contact base urgently",
        sender_type=SENDER_TYPE_DISPATCH,
        body="Contact base urgently.",
    ),
]

_MESSAGE_TEMPLATES_BY_CODE: dict[str, MessageTemplate] = {t.code: t for t in MESSAGE_TEMPLATES}


def list_templates() -> list[MessageTemplate]:
    return MESSAGE_TEMPLATES


def get_template(code: str) -> MessageTemplate:
    template = _MESSAGE_TEMPLATES_BY_CODE.get(code)
    if template is None:
        raise TemplateNotFoundError(code)
    return template


async def send_template_message(
    session: AsyncSession,
    *,
    tenant_id: str,
    driver_id: str | None,
    sender_type: str,
    sender_user_id: str | None,
    code: str,
    note: str | None,
) -> Message:
    """Resolves code to a MessageTemplate, composes the body (appending
    note when given), and creates the Message row through the exact same
    send_message above that a normal free-text message goes through -- this
    is the sole message-creation path in the domain, templates are purely a
    body-composition step on top of it, not a parallel write path."""
    template = get_template(code)
    if template.sender_type != sender_type:
        raise TemplateNotAllowedForSenderError(code)

    body = f"{template.body} {note}".strip() if note else template.body

    return await send_message(
        session,
        tenant_id=tenant_id,
        driver_id=driver_id,
        sender_type=sender_type,
        sender_user_id=sender_user_id,
        body=body,
    )


# --- live pub/sub (driver-scoped) ---------------------------------------------


class MessageBroadcaster:
    """In-process pub/sub for a driver's live message thread, keyed by
    "{tenant_id}:{driver_id}" (tenant-qualified so two tenants can never
    cross-deliver even in the unlikely event a driver_id string were ever
    reused). Identical shape to app.services.duress.GPSBroadcaster -- see
    that class's docstring for the Redis swap-in path, which applies
    unchanged here: everything outside this class only ever calls
    subscribe / unsubscribe / publish.

    Each subscriber gets its own asyncio.Queue so a slow websocket consumer
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
        """Broadcasts payload to every current subscriber of this driver's
        thread. Returns the number of subscribers it was delivered to (0 if
        nobody is currently listening -- the message is simply dropped, not
        queued for later joiners; it's still safely persisted in the DB via
        send_message, so nothing is lost -- only the live push is missed)."""
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
