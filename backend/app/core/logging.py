"""Structured JSON logging + request-id propagation.

Why this module exists
----------------------
Until it landed there was **no logging configuration at all** (backend audit
§6 "Logging"): every module did `logging.getLogger(__name__)` and then relied
entirely on whatever uvicorn happened to install. That has two concrete costs
that showed up in real debugging sessions:

1. **Nothing correlates.** A driver reports "the app 500'd at about 14:32".
   With plain-text uvicorn lines there is no way to tie that report to a
   specific request, and certainly no way to tie the request line to the
   handful of `logger.warning`s the same request emitted deeper in the stack.
2. **Nothing parses.** `docker compose logs` is the only log surface this
   deployment has (see docker-compose.yml's json-file caps). Free-text lines
   mean `grep`, not queries.

The fix is deliberately small and dependency-free -- no structlog, no
python-json-logger. One `logging.Formatter` subclass that emits one JSON object
per line, one `ContextVar` holding the current request id, and one ASGI
middleware that mints/propagates that id. A `ContextVar` (not a thread local)
is required here: this is an async app and a single thread interleaves many
requests, so a thread local would leak one request's id into another's logs.

The request id is:
  - taken from an inbound `X-Request-ID` header when the caller supplied one
    (so Caddy, or the Android app, can stitch its own trace to ours),
  - otherwise minted as a fresh uuid4 hex,
  - attached to **every** log record emitted while handling that request,
  - and echoed back on the response as `X-Request-ID` -- which is what makes
    "it 500'd at 14:32" answerable: the error envelope in `app.core.errors`
    carries the same id, so the user can read it off the failed response and
    we can grep one line out of the day's logs.
"""
from __future__ import annotations

import json
import logging
import os
import sys
import uuid
from contextvars import ContextVar

from starlette.types import ASGIApp, Message, Receive, Scope, Send

REQUEST_ID_HEADER = "x-request-id"

# The id of the request currently being handled on this task, or "-" outside of
# any request (startup, background work). Read by JsonFormatter for every record.
_request_id: ContextVar[str] = ContextVar("request_id", default="-")


def current_request_id() -> str:
    """The current request's id, or "-" if we are not inside a request."""
    return _request_id.get()


# Attributes `logging` puts on every LogRecord. Anything a caller passed via
# `extra=` will NOT be in this set, which is how we forward structured fields
# into the JSON object without having to enumerate them here.
_STANDARD_RECORD_ATTRS = frozenset(
    logging.LogRecord("", 0, "", 0, "", None, None).__dict__
) | {"message", "asctime", "taskName"}


class JsonFormatter(logging.Formatter):
    """One JSON object per line: ts, level, logger, msg, request_id (+ extras).

    Exceptions keep their full traceback in an `exc_info` string field -- the
    audit's instruction was to stop losing the CORS headers on a 500, NOT to
    stop recording what caused it. Swallowing the traceback server-side would
    trade one blind spot for a worse one.
    """

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, object] = {
            "ts": self.formatTime(record, "%Y-%m-%dT%H:%M:%S%z"),
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
            "request_id": getattr(record, "request_id", None) or current_request_id(),
        }

        if record.exc_info:
            payload["exc_info"] = self.formatException(record.exc_info)
        if record.stack_info:
            payload["stack_info"] = self.formatStack(record.stack_info)

        # Structured extras: logger.info("...", extra={"trip_id": t.id}) lands
        # as a real JSON field rather than being interpolated into the message.
        for key, value in record.__dict__.items():
            if key in _STANDARD_RECORD_ATTRS or key in payload:
                continue
            try:
                json.dumps(value)
            except (TypeError, ValueError):
                value = repr(value)
            payload[key] = value

        return json.dumps(payload, default=str)


class _RequestIdFilter(logging.Filter):
    """Fills in `record.request_id` for the text formatter's format string."""

    def filter(self, record: logging.LogRecord) -> bool:
        if not hasattr(record, "request_id"):
            record.request_id = current_request_id()
        return True


def configure_logging(level: str | None = None, *, force_json: bool | None = None) -> None:
    """Installs a single stdout handler on the root logger.

    Idempotent: calling it twice replaces the handler rather than doubling
    every line (uvicorn's reloader imports `app.main` more than once).

    JSON is the default because the only log sink this deployment has is
    `docker compose logs`. `LOG_FORMAT=text` switches to a plain formatter for
    local work where a human is reading the terminal directly; `LOG_LEVEL`
    overrides the level. Neither is required for the app to boot.
    """
    resolved_level = (level or os.environ.get("LOG_LEVEL") or "INFO").upper()
    if force_json is None:
        force_json = os.environ.get("LOG_FORMAT", "json").strip().lower() != "text"

    handler = logging.StreamHandler(sys.stdout)
    if force_json:
        handler.setFormatter(JsonFormatter())
    else:
        handler.setFormatter(
            logging.Formatter("%(asctime)s %(levelname)-8s %(name)s [%(request_id)s] %(message)s")
        )
        handler.addFilter(_RequestIdFilter())

    handler._cabdispatch_handler = True  # type: ignore[attr-defined]

    root = logging.getLogger()
    # Remove only OUR previous handler, not every handler on root. Blanket
    # removal is tempting (it is the usual "stop uvicorn double-logging" trick)
    # but it also rips out pytest's caplog handler and anything a host process
    # attached, which turns "no logging config" into "logging that silently
    # disappears" -- a strictly worse version of the bug being fixed.
    for existing in list(root.handlers):
        if getattr(existing, "_cabdispatch_handler", False):
            root.removeHandler(existing)
    root.addHandler(handler)
    root.setLevel(resolved_level)

    # uvicorn installs its own handlers on these three loggers at startup and
    # would otherwise double every access line (once its way, once ours).
    # Clearing them and letting propagation carry the records to the root
    # handler above means uvicorn's own output is JSON too.
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        uvicorn_logger = logging.getLogger(name)
        uvicorn_logger.handlers.clear()
        uvicorn_logger.propagate = True


class RequestIdMiddleware:
    """Pure-ASGI middleware: mint/propagate a request id, echo it, log the request.

    Deliberately raw ASGI rather than `BaseHTTPMiddleware`, for two reasons: it
    also covers `websocket` scopes (this app has four WS routes, and a live map
    that stops updating is exactly the kind of thing you need to correlate),
    and `BaseHTTPMiddleware` runs the downstream app in a separate task, which
    breaks `ContextVar` propagation.

    It does NOT catch exceptions -- that is `app.core.errors`'s job, and that
    middleware is installed *inside* this one so its 500 response still gets an
    id and still passes back out through CORS.
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app
        self._logger = logging.getLogger("app.request")

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] not in ("http", "websocket"):
            await self.app(scope, receive, send)
            return

        # An inbound id is trusted only as a correlation label, never as data:
        # it is length-capped and stripped of anything that could forge a log
        # line or a response header (CR/LF in particular).
        request_id = _sanitise(_header(scope, REQUEST_ID_HEADER)) or uuid.uuid4().hex
        token = _request_id.set(request_id)

        status_code: int | None = None

        async def send_wrapper(message: Message) -> None:
            nonlocal status_code
            if message["type"] == "http.response.start":
                status_code = message["status"]
                wanted = REQUEST_ID_HEADER.encode()
                # Replace rather than append: a response that set the header
                # itself would otherwise come out as a duplicated `id, id`.
                headers = [
                    (k, v) for k, v in (message.get("headers") or []) if k.lower() != wanted
                ]
                headers.append((wanted, request_id.encode()))
                message["headers"] = headers
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        finally:
            if scope["type"] == "http":
                self._logger.info(
                    "%s %s -> %s",
                    scope.get("method", "?"),
                    scope.get("path", "?"),
                    status_code if status_code is not None else "no-response",
                    extra={
                        "http_method": scope.get("method"),
                        "http_path": scope.get("path"),
                        "http_status": status_code,
                    },
                )
            _request_id.reset(token)


def _header(scope: Scope, name: str) -> str | None:
    wanted = name.encode()
    for key, value in scope.get("headers") or []:
        if key.lower() == wanted:
            return value.decode("latin-1")
    return None


def _sanitise(value: str | None) -> str | None:
    if not value:
        return None
    cleaned = "".join(ch for ch in value if ch.isalnum() or ch in "-_.:")[:64]
    return cleaned or None
