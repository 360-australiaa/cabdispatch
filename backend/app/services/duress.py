"""Duress domain business logic: the trigger/cancel/escalate/close state
machine, and the in-process GPS pub/sub used by the live websocket feed.
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import logging
import os
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path

import httpx
from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
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

# Backend root: app/services/duress.py -> parents[0]=services, [1]=app,
# [2]=backend project root. Same BACKEND_ROOT/UPLOADS_ROOT local-disk-upload
# convention as app.services.compliance / app.services.receipts — uploads
# live at "<backend root>/uploads/...". No S3 in this environment.
BACKEND_ROOT = Path(__file__).resolve().parents[2]
UPLOADS_ROOT = BACKEND_ROOT / "uploads"


class DuressAudioError(Exception):
    """Raised on invalid duress audio uploads/lookups; the router translates
    this to an HTTP 400."""


def _safe_component(value: str, *, max_len: int = 80) -> str:
    """Strips anything that could act as a path separator/traversal token —
    identical helper to app.services.compliance._safe_component /
    app.services.receipts._safe_component, duplicated here rather than
    imported so this domain stays independently deployable (neither depends
    on the other's internals — same rationale documented in those modules)."""
    cleaned = "".join(c for c in value if c.isalnum() or c in "-_.")
    cleaned = cleaned.strip(".") or "unknown"
    return cleaned[:max_len]


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


# --- audio recording upload ----------------------------------------------------


def duress_audio_dir(*, tenant_id: str, event_id: str) -> Path:
    return UPLOADS_ROOT / _safe_component(tenant_id) / "duress" / _safe_component(event_id)


def _audio_filename(event_id: str, *, original_filename: str) -> str:
    """Deterministic filename per event id (`audio_{event_id}` plus the
    original extension, if any) — same idempotent-on-re-upload spirit as
    app.services.receipts._receipt_filename, so a second recording for the
    same event overwrites the first rather than accumulating duplicates."""
    suffix = Path(os.path.basename(original_filename or "")).suffix
    safe_suffix = _safe_component(suffix, max_len=10) if suffix else ""
    ext = f".{safe_suffix}" if safe_suffix else ""
    return f"audio_{_safe_component(event_id)}{ext}"


async def save_duress_audio(
    *,
    tenant_id: str,
    event_id: str,
    original_filename: str,
    content: bytes,
) -> str:
    """Writes `content` under `uploads/{tenant_id}/duress/{event_id}/`
    (created if missing), following the EXACT same local-disk-upload
    convention as `app.services.compliance.save_upload` /
    `app.services.receipts.ensure_receipt_pdf` (BACKEND_ROOT-relative paths,
    the `_safe_component` path-traversal guard). Returns the *relative* (to
    BACKEND_ROOT) path to persist on `DuressEvent.audio_ref` — never the
    absolute path, so the recording stays portable across
    machines/deployments."""
    if not content:
        raise DuressAudioError("Uploaded audio file is empty")

    target_dir = duress_audio_dir(tenant_id=tenant_id, event_id=event_id)
    target_dir.mkdir(parents=True, exist_ok=True)

    absolute_path = target_dir / _audio_filename(event_id, original_filename=original_filename)
    absolute_path.write_bytes(content)

    return absolute_path.relative_to(BACKEND_ROOT).as_posix()


def resolve_absolute_path(relative_path: str) -> Path:
    """Resolves a stored (relative-to-BACKEND_ROOT) `audio_ref` back to an
    absolute path, rejecting anything that would escape BACKEND_ROOT. Same
    contract as `app.services.compliance.resolve_absolute_path` /
    `app.services.receipts.resolve_absolute_path`."""
    absolute_path = (BACKEND_ROOT / relative_path).resolve()
    if BACKEND_ROOT not in absolute_path.parents and absolute_path != BACKEND_ROOT:
        raise DuressAudioError("Stored audio path resolves outside the uploads root")
    return absolute_path


# --- Twilio Voice automated escalation call (blueprint 8.3) --------------------


def _twilio_voice_configured() -> bool:
    """Identical three-credential check as
    `app.services.receipts._twilio_configured`, duplicated here per that
    module's own documented duplication rationale so the two domains stay
    independently deployable."""
    return bool(settings.TWILIO_ACCOUNT_SID and settings.TWILIO_AUTH_TOKEN and settings.TWILIO_FROM_NUMBER)


def _escalation_call_twiml(event: DuressEvent) -> str:
    """TwiML announced (via Twilio's text-to-speech voice) when the call
    connects, passed inline as the `Twiml=` form field on the Calls.json
    request — there is no publicly hosted TwiML URL in this dev environment,
    so (same reasoning as `app.services.receipts._receipt_sms_body`'s
    omitted link) the vehicle/driver ids stand in for a fully resolved
    street address."""
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        "<Response><Say voice=\"alice\">"
        f"Emergency alert. A duress signal has been raised for vehicle {event.vehicle_id}, "
        f"driver {event.driver_id}. Trigger type: {event.trigger}. "
        "This is an automated call from Cab Dispatch. Please respond immediately."
        "</Say></Response>"
    )


def place_escalation_call(event: DuressEvent, phone_number: str) -> dict:
    """Places a real (or mocked) Twilio Voice call to `phone_number`
    announcing the emergency at the vehicle's location, via a `POST` to
    Twilio's `Calls.json` REST endpoint. Same mock-fallback pattern as
    `app.services.receipts.send_receipt_sms`: requires all three of
    TWILIO_ACCOUNT_SID/TWILIO_AUTH_TOKEN/TWILIO_FROM_NUMBER to be configured;
    any missing falls back to a clearly-flagged `{"mock": True, ...}`
    response, so this is testable without live Twilio credentials."""
    twiml = _escalation_call_twiml(event)

    if _twilio_voice_configured():
        try:
            with httpx.Client(timeout=10.0) as http_client:
                resp = http_client.post(
                    f"https://api.twilio.com/2010-04-01/Accounts/{settings.TWILIO_ACCOUNT_SID}/Calls.json",
                    auth=(settings.TWILIO_ACCOUNT_SID, settings.TWILIO_AUTH_TOKEN),
                    data={"From": settings.TWILIO_FROM_NUMBER, "To": phone_number, "Twiml": twiml},
                )
                resp.raise_for_status()
                data = resp.json()
            return {"mock": False, "to_phone": phone_number, "twilio_call_sid": data.get("sid")}
        except (httpx.HTTPError, ValueError) as exc:
            logger.warning("Twilio Voice call failed (%s) — returning mock call response.", exc)

    logger.info("Mock emergency call to %s for duress event %s", phone_number, event.id)
    return {"mock": True, "would_call": phone_number, "twiml": twiml}


def _fire_escalation_call(event: DuressEvent, emergency_contact_phone: str | None) -> dict:
    """Resolves the phone number to dial (explicit per-call override, else
    the deployment-wide `settings.DURESS_ESCALATION_CALL_PHONE` default) and
    fires `place_escalation_call` — or, if no phone number is configured
    either way, returns a clearly-flagged skipped result instead of dialing a
    bogus number. Called exactly once, only from `escalate_event`, only when
    the cascade reaches its final stage."""
    phone = emergency_contact_phone or settings.DURESS_ESCALATION_CALL_PHONE or None
    if not phone:
        return {"mock": True, "skipped": True, "reason": "no emergency contact phone configured"}
    return place_escalation_call(event, phone)


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


async def escalate_event(
    session: AsyncSession,
    event: DuressEvent,
    *,
    note: str | None,
    emergency_contact_phone: str | None = None,
) -> DuressEvent:
    """Advances the escalation cascade by exactly one stage.

    Cascade (see app.models.duress.ESCALATION_STAGES):
        cancel_window_expired -> notify_dispatch -> sms_emergency_contacts
        -> present_000_call_script

    The first stage flips status open -> escalating; the last stage flips
    escalating -> dispatched. Each call is a discrete, manually-triggered step
    (from a background job or a dispatcher action) — there is no server-side
    timer in this pass.

    Reaching the final stage (`present_000_call_script`) ALSO fires the real
    Twilio Voice automated escalation call (blueprint 8.3) via
    `_fire_escalation_call` / `place_escalation_call` — and only that stage;
    every earlier stage advances the cascade without dialing anything.
    `emergency_contact_phone`, if given, overrides the deployment-wide
    `settings.DURESS_ESCALATION_CALL_PHONE` default for that one call. The
    call's outcome (`{"mock": ...}` dict) is folded into the same
    `present_000_call_script` log entry under `call_result`, and mirrored at
    the top level of `escalation_log_json` as `escalation_call_result` — no
    new column needed.
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

    entry: dict = {"stage": stage, "at": now.isoformat(), "note": note}
    bookkeeping: dict = {"next_stage_index": next_stage_index + 1}

    if stage == ESCALATION_STAGE_CANCEL_WINDOW_EXPIRED:
        event.status = DURESS_STATUS_ESCALATING
    elif stage == ESCALATION_STAGE_PRESENT_000_CALL_SCRIPT:
        event.status = DURESS_STATUS_DISPATCHED
        # Fires on this final stage ONLY — never on the earlier three.
        call_result = _fire_escalation_call(event, emergency_contact_phone)
        entry["call_result"] = call_result
        bookkeeping["escalation_call_result"] = call_result

    _append_log_entry(event, entry, **bookkeeping)

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


# --- Twilio Voice "call the cab" operator action --------------------------------


def _duress_call_twiml(event: DuressEvent) -> str:
    """TwiML for the operator "call the cab" action: this call rings the
    duress DEVICE's own SIM directly (not an emergency contact), and per the
    device integration contract firmware is required to auto-answer it
    silently (no ringtone played out its speaker) so the operator lands
    straight on an open line. Once answered, `<Dial>` bridges that line to
    the deployment's call-centre number so the operator can talk through the
    device's speaker/mic.

    `operator_number` reuses `settings.DURESS_ESCALATION_CALL_PHONE` --
    deliberately -- since both represent "the human who should be on the
    line for this incident", and this pass has no dedicated call-centre
    number setting; a future pass could add a dedicated call-centre number
    setting if the two ever need to differ.
    """
    operator_number = settings.DURESS_ESCALATION_CALL_PHONE
    if not operator_number:
        # Harmless fallback TwiML only -- place_duress_call refuses to place
        # the call at all in this case rather than dialing with no bridge
        # target (see its "no call-centre number configured" skip path
        # below).
        return (
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<Response><Say voice="alice">Connecting.</Say></Response>'
        )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        f"<Response><Dial>{operator_number}</Dial></Response>"
    )


def place_duress_call(event: DuressEvent, device_phone: str) -> dict:
    """Places a real (or mocked) Twilio Voice call to the duress DEVICE's own
    SIM (`device_phone`) so a call-centre operator can talk through its
    speaker/mic -- the operator "call the cab" action, distinct from
    `place_escalation_call`'s call to an emergency contact. Mirrors
    `place_escalation_call` exactly (same `httpx.Client` pattern, same
    `except (httpx.HTTPError, ValueError)` -> log warning + fall back to
    mock, same `_twilio_voice_configured()` real-vs-mock gate), except:

    - From number: `settings.DURESS_CALL_FROM_NUMBER` if set, else
      `settings.TWILIO_FROM_NUMBER`.
    - To number: `device_phone` (the device's own SIM, not an emergency
      contact).
    - Refuses to dial at all if no call-centre bridge target is configured
      (see `_duress_call_twiml`), returning a skipped result instead --
      mirrors `_fire_escalation_call`'s "no phone configured -> skip, don't
      dial" behavior.
    """
    twiml = _duress_call_twiml(event)

    if not settings.DURESS_ESCALATION_CALL_PHONE:
        # No call-centre bridge target configured -- refuse to dial rather
        # than connect the device to a TwiML that just says "Connecting."
        # and then hangs with nobody on the other end.
        return {
            "mock": True,
            "skipped": True,
            "reason": "no call-centre number configured (DURESS_ESCALATION_CALL_PHONE)",
        }

    from_number = settings.DURESS_CALL_FROM_NUMBER or settings.TWILIO_FROM_NUMBER

    if _twilio_voice_configured():
        try:
            with httpx.Client(timeout=10.0) as http_client:
                resp = http_client.post(
                    f"https://api.twilio.com/2010-04-01/Accounts/{settings.TWILIO_ACCOUNT_SID}/Calls.json",
                    auth=(settings.TWILIO_ACCOUNT_SID, settings.TWILIO_AUTH_TOKEN),
                    # StatusCallback intentionally omitted -- this dev
                    # environment has no publicly reachable base URL for
                    # Twilio to call back to (grep confirmed no
                    # PUBLIC_BASE_URL-style setting exists in
                    # app.core.config). POST /v1/duress/twilio/status below
                    # exists and is ready to receive callbacks once a real
                    # deployment sets one via Twilio console webhook config
                    # directly on the Twilio number, or a future pass adds a
                    # PUBLIC_BASE_URL setting.
                    data={"From": from_number, "To": device_phone, "Twiml": twiml},
                )
                resp.raise_for_status()
                data = resp.json()
            return {"mock": False, "to_phone": device_phone, "twilio_call_sid": data.get("sid")}
        except (httpx.HTTPError, ValueError) as exc:
            logger.warning(
                "Twilio Voice call-the-cab call failed (%s) - returning mock call response.", exc
            )

    logger.info("Mock call-the-cab call to %s for duress event %s", device_phone, event.id)
    return {"mock": True, "would_call": device_phone, "twiml": twiml}


def verify_twilio_signature(url: str, params: dict, signature: str | None) -> bool:
    """Verifies Twilio's `X-Twilio-Signature` header per Twilio's documented
    RequestValidator algorithm: sort `params` by key, concatenate each
    key+value pair onto `url`, HMAC-SHA1 the result keyed by
    `settings.TWILIO_AUTH_TOKEN`, base64-encode, and compare to `signature`
    using a constant-time comparison (`hmac.compare_digest`). Implemented
    directly with hashlib/hmac/base64 -- no new dependency added.

    Dev/mock mode: no auth token configured, signature check skipped -- if
    `settings.TWILIO_AUTH_TOKEN` is unset (the same mock/dev state
    `_twilio_voice_configured()` treats as "no real Twilio"), there is
    nothing to verify a signature against, so this returns True
    unconditionally, matching the mock-fallback spirit used throughout this
    module.
    """
    if not settings.TWILIO_AUTH_TOKEN:
        return True
    if not signature:
        return False

    data = url
    for key in sorted(params.keys()):
        data += key + str(params[key])

    computed = base64.b64encode(
        hmac.new(settings.TWILIO_AUTH_TOKEN.encode("utf-8"), data.encode("utf-8"), hashlib.sha1).digest()
    ).decode("utf-8")

    return hmac.compare_digest(computed, signature)
