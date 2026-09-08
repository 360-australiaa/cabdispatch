"""Exception handling that does not lose the CORS headers.

The bug this fixes, precisely
-----------------------------
Before this module there were **no exception handlers registered anywhere**
(backend audit §6 "Logging"). An unhandled exception therefore fell all the way
out to Starlette's `ServerErrorMiddleware`, which is installed *outside* every
user middleware -- including `CORSMiddleware`. Its bare `500 Internal Server
Error` response is built after CORS has already been passed, so it carries no
`Access-Control-Allow-Origin`. The browser then refuses to expose the response
to JS at all and the dashboard shows "Network Error" with no status code.

That is not a hypothetical: it is the documented root cause of the "phantom
503" that cost a real debugging session -- see the postmortem at the top of
`app/services/fleet.py`. The server was returning a perfectly informative 500;
the browser was throwing it away before anyone could read it.

Why a middleware and not just `@app.exception_handler(Exception)`
-----------------------------------------------------------------
Registering an `Exception` handler on the app does NOT fix it. FastAPI hands
that handler to `ServerErrorMiddleware`, which is still outside `CORSMiddleware`
-- the response it produces still never passes through CORS. The only placement
that works is a middleware installed *inside* CORS, so the error response it
returns travels back out through the CORS layer like any normal response and
gets its headers stamped on the way. See `app/main.py` for the exact ordering
and why `add_middleware` call order is inverted relative to wrapping order.

The `Exception` handler is still registered, as a second line of defence for
anything that blows up in a middleware installed *outside* this one.

What the client gets
--------------------
A stable JSON envelope, always with the same keys, and always with the request
id that `app.core.logging` minted for this request:

    {"detail": "Internal server error",
     "error": {"type": "ServerError", "request_id": "9f3c..."}}

`detail` is first and keeps the same name/shape FastAPI's own `HTTPException`
responses use, so a client that already reads `.detail` (the dashboard's
`apiClient` does) does not need to learn a second shape.

**The traceback is not swallowed.** It is logged at ERROR with `exc_info=True`
through the JSON formatter, tagged with the same `request_id` the caller was
handed. The class of failure being fixed here is "the operator cannot see what
happened", so hiding the cause server-side would be a regression, not a fix.
Only the *client* is told nothing beyond the id -- an internal error message can
carry a SQL fragment, a file path or a secret, and this API is internet-facing.
"""
from __future__ import annotations

import logging

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from app.core.logging import current_request_id

logger = logging.getLogger(__name__)

INTERNAL_ERROR_DETAIL = "Internal server error"


def internal_error_response(request_id: str) -> JSONResponse:
    """The one and only 500 body shape this API produces.

    No `X-Request-ID` header is set here on purpose: `RequestIdMiddleware`
    stamps it on every outgoing response, and setting it in both places
    produced a duplicated `id, id` header value.
    """
    return JSONResponse(
        status_code=500,
        content={
            "detail": INTERNAL_ERROR_DETAIL,
            "error": {"type": "ServerError", "request_id": request_id},
        },
    )


class ExceptionEnvelopeMiddleware:
    """Catches unhandled exceptions *inside* the CORS layer.

    Pure ASGI (not `BaseHTTPMiddleware`) to match `RequestIdMiddleware` and to
    avoid `BaseHTTPMiddleware`'s task hop, which would otherwise put the
    exception on a different task from the one holding the request-id context.

    Two cases are handled differently on purpose:

    * **Nothing sent yet** -- we own the response: log the traceback and emit
      the envelope, which then flows back out through CORS.
    * **Response already started** -- the status line and headers are on the
      wire (a streaming/file response that failed mid-body, for instance). The
      only honest thing to do is log the traceback and re-raise; fabricating a
      200-with-error-body would be a lie, and a second `http.response.start` is
      a protocol violation. `ServerErrorMiddleware` above us then tears the
      connection down, which is what the client should see.
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        response_started = False

        async def send_wrapper(message) -> None:
            nonlocal response_started
            if message["type"] == "http.response.start":
                response_started = True
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        except Exception:
            request_id = current_request_id()
            logger.exception(
                "Unhandled exception on %s %s (request_id=%s)",
                scope.get("method", "?"),
                scope.get("path", "?"),
                request_id,
                extra={"http_method": scope.get("method"), "http_path": scope.get("path")},
            )
            if response_started:
                raise
            await internal_error_response(request_id)(scope, receive, send)


async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    """Belt-and-braces handler for exceptions raised *outside*
    `ExceptionEnvelopeMiddleware` (i.e. in a middleware installed around it).
    Same envelope; CORS headers are not recoverable at this depth, which is
    precisely why the middleware above exists and this is only a fallback."""
    request_id = current_request_id()
    logger.exception(
        "Unhandled exception outside the envelope middleware on %s %s (request_id=%s)",
        request.method,
        request.url.path,
        request_id,
    )
    return internal_error_response(request_id)


def register_exception_handlers(app: FastAPI) -> None:
    """Registers the fallback handler. The middleware half of the fix is
    installed in `app/main.py`, where the ordering relative to CORS is
    visible and commented."""
    app.add_exception_handler(Exception, unhandled_exception_handler)
