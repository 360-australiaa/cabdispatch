"""GET/POST requests log WHO made them, not just a request_id.

See app.core.logging.set_current_actor's own doc: the request-completion
line RequestIdMiddleware emits carries user_id/tenant_id once auth has
resolved a real row, so "find everything driver X did today" is a log
grep, not a cross-reference against the audit_log table (which only
covers admin actions with their own explicit record_audit call, not every
request).
"""
from __future__ import annotations

import logging

import pytest
from httpx import AsyncClient

from app.core.logging import current_actor


def _the_request_completion_record(caplog: pytest.LogCaptureFixture) -> logging.LogRecord:
    """The one "app.request" completion line a real request must have logged.

    A plain `next(...)` here used to raise a bare, unhelpful `StopIteration` (wrapped by
    pytest-asyncio into an even less legible `RuntimeError: coroutine raised StopIteration`) with
    no clue why the record was missing -- exactly what happened for real, 2026-09-16, in full-suite
    order: `alembic/env.py`'s own `fileConfig()` call (run in-process by
    `tests/test_migrations.py`'s own migration tests) silently disabled every application logger
    not named in `alembic.ini`'s `[loggers]` section, permanently, for the rest of the session --
    fixed at the source (`disable_existing_loggers=False`, see that file's own comment), but this
    assertion stays informative rather than reverting to the bare crash, so a REAL future
    regression of the same shape (logging output simply missing) fails clearly instead of with a
    stack trace that names neither this file nor the actual cause.
    """
    candidates = [r for r in caplog.records if r.name == "app.request"]
    assert candidates, (
        "no 'app.request' completion record was captured at all "
        f"(caplog saw {len(caplog.records)} record(s) from: {sorted({r.name for r in caplog.records})}) -- "
        "check logging.getLogger('app.request').disabled; see this function's own doc for the "
        "2026-09-16 field report where that was the cause."
    )
    return candidates[0]


@pytest.mark.asyncio
async def test_authenticated_request_sets_the_current_actor(
    client: AsyncClient, session, caplog: pytest.LogCaptureFixture
):
    from tests.conftest import auth_headers

    headers = await auth_headers(client, session, role="owner")

    with caplog.at_level(logging.INFO, logger="app.request"):
        resp = await client.get("/v1/auth/me", headers=headers)
    assert resp.status_code == 200

    record = _the_request_completion_record(caplog)
    assert record.user_id != "-"
    assert record.tenant_id != "-"
    assert record.user_id == resp.json()["id"]


@pytest.mark.asyncio
async def test_unauthenticated_request_logs_a_placeholder_actor_not_a_guess(
    client: AsyncClient, caplog: pytest.LogCaptureFixture
):
    with caplog.at_level(logging.INFO, logger="app.request"):
        resp = await client.get("/health")
    assert resp.status_code == 200

    record = _the_request_completion_record(caplog)
    assert record.user_id == "-"
    assert record.tenant_id == "-"


@pytest.mark.asyncio
async def test_actor_never_leaks_between_requests(client: AsyncClient, session):
    from tests.conftest import auth_headers

    headers = await auth_headers(client, session, role="owner")
    await client.get("/v1/auth/me", headers=headers)
    # A later, unauthenticated request on the same test client/task must not
    # see the previous request's actor still set.
    await client.get("/health")
    assert current_actor() is None
