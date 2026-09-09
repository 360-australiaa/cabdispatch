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


@pytest.mark.asyncio
async def test_authenticated_request_sets_the_current_actor(
    client: AsyncClient, session, caplog: pytest.LogCaptureFixture
):
    from tests.conftest import auth_headers

    headers = await auth_headers(client, session, role="owner")

    with caplog.at_level(logging.INFO, logger="app.request"):
        resp = await client.get("/v1/auth/me", headers=headers)
    assert resp.status_code == 200

    record = next(r for r in caplog.records if r.name == "app.request")
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

    record = next(r for r in caplog.records if r.name == "app.request")
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
