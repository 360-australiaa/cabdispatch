"""Smoke tests for app boot, /health, and the conftest auth_headers helper —
exercised here so every sibling domain can trust this contract works before
they build 42 routes on top of it.

The `/health` block below is the point of workstream B7. The endpoint used to
be `return {"status": "ok"}` — it touched nothing, yet `docker compose up
--wait` and the Dockerfile HEALTHCHECK both gated on it, so a backend with a
dead Postgres reported healthy and a deploy went green. **A health check that
cannot fail is exactly the bug**, so the tests that matter here are the two
that force it to fail: database unreachable, and a database whose alembic
version does not match this code's migration head.
"""
import pytest
from fastapi import HTTPException

from app.core import security
from app.models import Tenant, User
from tests.conftest import auth_headers


async def test_health_endpoint(client):
    resp = await client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"


async def test_health_reports_each_dependency_it_actually_checked(client):
    """The body must name what was checked, not just say "ok" — an operator
    reading a green /health should be able to see that it really touched the
    database and really compared migration heads."""
    body = (await client.get("/health")).json()

    assert body["checks"]["database"]["status"] == "ok"
    assert body["checks"]["migrations"]["status"] == "ok"
    # conftest builds the schema with `alembic upgrade head`, so the reported
    # head is the real one and this asserts the comparison is live.
    assert body["checks"]["migrations"]["head"]
    # Redis is deliberately pointed at an unreachable port by conftest, and is
    # non-fatal outside production (see app/core/health.py) — but it must be
    # honestly reported as degraded rather than quietly omitted.
    assert body["checks"]["redis"]["status"] in ("degraded", "ok", "skipped")


async def test_health_live_is_trivial_and_touches_nothing(client, monkeypatch):
    """Liveness must not depend on Postgres: if it did, a slow database would
    mark the container unhealthy and restart a perfectly good API process,
    dropping every live WebSocket and fixing nothing."""
    import app.core.health as health_module

    monkeypatch.setattr(health_module, "engine", _BrokenEngine())

    resp = await client.get("/health/live")
    assert resp.status_code == 200
    assert resp.json()["status"] == "alive"


class _BrokenEngine:
    """Stands in for `app.core.health.engine` with the database down. Raises on
    `connect()` the way asyncpg/aiosqlite do when the server is unreachable."""

    def connect(self):
        raise OSError("connection refused (simulated dead database)")


async def test_health_fails_when_the_database_is_unreachable(client, monkeypatch):
    import app.core.health as health_module

    monkeypatch.setattr(health_module, "engine", _BrokenEngine())

    resp = await client.get("/health")

    assert resp.status_code == 503
    body = resp.json()
    assert body["status"] == "error"
    assert body["checks"]["database"]["status"] == "error"
    assert "connection refused" in body["checks"]["database"]["detail"]


async def test_health_fails_when_db_alembic_version_does_not_match_code_head(
    client, monkeypatch
):
    """A container running against an un-migrated (or ahead-of-code) database
    is a specific, likely failure — entrypoint.sh's `alembic upgrade head` can
    be skipped, fail, or the image can be rolled back under a newer schema —
    and it is currently invisible: it shows up as scattered 500s on unrelated
    routes, not as "the database is down"."""
    import app.core.health as health_module

    monkeypatch.setattr(health_module, "_code_head", lambda: "deadbeefcafe")

    resp = await client.get("/health")

    assert resp.status_code == 503
    body = resp.json()
    assert body["status"] == "error"
    migrations = body["checks"]["migrations"]
    assert migrations["status"] == "error"
    assert migrations["code_head"] == "deadbeefcafe"
    # The real head the test database was migrated to — reported, so the
    # operator can see both sides of the mismatch without opening psql.
    assert migrations["db_head"] and migrations["db_head"] != "deadbeefcafe"
    # The database itself is fine; only the version comparison failed.
    assert body["checks"]["database"]["status"] == "ok"


# --- unhandled 500s: CORS headers, envelope, request id ----------------------
# Regression tests for the "phantom 503": with no exception handlers registered,
# Starlette's bare 500 was produced OUTSIDE CORSMiddleware, so it carried no
# Access-Control-Allow-Origin and the browser showed only "Network Error" with
# no status code. See app/core/errors.py and the postmortem at the top of
# app/services/fleet.py.

_BOOM_PATH = "/__test_boom__"


@pytest.fixture
def exploding_route(app):
    """Temporarily mounts a route that raises, on the REAL app — so this
    exercises main.py's actual middleware ordering rather than a lookalike."""

    async def boom():
        raise RuntimeError("boom: deliberate unhandled error")

    app.add_api_route(_BOOM_PATH, boom, methods=["GET"])
    try:
        yield _BOOM_PATH
    finally:
        app.router.routes[:] = [
            r for r in app.router.routes if getattr(r, "path", None) != _BOOM_PATH
        ]


async def test_unhandled_500_still_carries_cors_headers(client, exploding_route):
    origin = "http://localhost:5174"

    resp = await client.get(exploding_route, headers={"Origin": origin})

    assert resp.status_code == 500
    assert resp.headers["access-control-allow-origin"] == origin
    assert resp.headers["access-control-allow-credentials"] == "true"


async def test_unhandled_500_returns_a_consistent_json_envelope(client, exploding_route):
    resp = await client.get(exploding_route)

    assert resp.status_code == 500
    body = resp.json()
    assert body["detail"] == "Internal server error"
    assert body["error"]["type"] == "ServerError"
    # The id in the body is the same one on the response header, which is the
    # same one on the ERROR log line — that triple is what makes "it 500'd at
    # 14:32" answerable.
    assert body["error"]["request_id"] == resp.headers["x-request-id"]
    # The internal message must NOT reach the client (it can carry SQL, paths
    # or secrets) — but it must reach the log, asserted separately below.
    assert "boom: deliberate unhandled error" not in resp.text


async def test_unhandled_500_logs_the_traceback_server_side(client, exploding_route, caplog):
    import logging

    with caplog.at_level(logging.ERROR, logger="app.core.errors"):
        resp = await client.get(exploding_route)

    assert resp.status_code == 500
    errors = [r for r in caplog.records if r.levelno >= logging.ERROR]
    assert errors, "the traceback must not be swallowed server-side"
    assert any(r.exc_info for r in errors)
    assert any(resp.headers["x-request-id"] in r.getMessage() for r in errors)


async def test_a_normal_http_exception_is_left_alone(client, app):
    """The envelope is for *unhandled* errors only. A deliberate
    HTTPException must keep FastAPI's own `{"detail": ...}` shape, because 42
    routes and the dashboard's apiClient already depend on it."""

    async def teapot():
        raise HTTPException(status_code=418, detail="I am a teapot")

    app.add_api_route("/__test_teapot__", teapot, methods=["GET"])
    try:
        resp = await client.get("/__test_teapot__")
    finally:
        app.router.routes[:] = [
            r for r in app.router.routes if getattr(r, "path", None) != "/__test_teapot__"
        ]

    assert resp.status_code == 418
    assert resp.json() == {"detail": "I am a teapot"}


# --- request id --------------------------------------------------------------


async def test_every_response_carries_a_request_id(client):
    resp = await client.get("/health/live")
    assert resp.headers.get("x-request-id")


async def test_an_inbound_request_id_is_propagated_not_replaced(client):
    """Caddy or the Android app can supply its own id so a client-side trace
    stitches to ours."""
    resp = await client.get("/health/live", headers={"X-Request-ID": "caller-supplied-123"})
    assert resp.headers["x-request-id"] == "caller-supplied-123"


async def test_a_hostile_inbound_request_id_is_sanitised(client):
    """An inbound id is a correlation label, never data: it must not be able
    to inject a header or forge a log line."""
    resp = await client.get(
        "/health/live", headers={"X-Request-ID": "abc" + "!" * 5 + "-def"}
    )
    request_id = resp.headers["x-request-id"]
    assert request_id == "abc-def"
    assert len(request_id) <= 64


# --- single-worker guard -----------------------------------------------------


def test_multi_worker_is_detected_and_refused_loudly(monkeypatch, caplog):
    """Above one worker the three in-process broadcasters and the in-memory
    JWT revocation fallback break with no error at all. Documentation was not
    enough; this asserts the guard actually fires."""
    import logging

    import app.main as main_module

    monkeypatch.setattr(
        main_module.sys, "argv", ["uvicorn", "app.main:app", "--workers", "4"]
    )

    # Non-production: loud, but boots (a dev running 4 workers deserves to know).
    monkeypatch.setattr(main_module.settings, "ENV", "development")
    with caplog.at_level(logging.CRITICAL, logger="app.main"):
        main_module.assert_single_worker()
    assert any("MULTI-WORKER DEPLOY DETECTED" in r.getMessage() for r in caplog.records)

    # Production: refuses to boot rather than serve silently-wrong traffic.
    monkeypatch.setattr(main_module.settings, "ENV", "production")
    with pytest.raises(RuntimeError, match="MULTI-WORKER DEPLOY DETECTED"):
        main_module.assert_single_worker()


def test_single_worker_and_unset_worker_count_both_pass(monkeypatch):
    import app.main as main_module

    monkeypatch.setattr(main_module.settings, "ENV", "production")

    monkeypatch.setattr(main_module.sys, "argv", ["uvicorn", "app.main:app", "--workers", "1"])
    main_module.assert_single_worker()

    monkeypatch.setattr(main_module.sys, "argv", ["uvicorn", "app.main:app"])
    monkeypatch.delenv("WEB_CONCURRENCY", raising=False)
    main_module.assert_single_worker()

    monkeypatch.setenv("WEB_CONCURRENCY", "8")
    with pytest.raises(RuntimeError):
        main_module.assert_single_worker()


async def test_auth_headers_helper_issues_a_valid_bearer_token(client, session):
    headers = await auth_headers(client, session, role="admin")
    token = headers["Authorization"].split(" ", 1)[1]

    payload = security.decode_token(token)
    assert payload["role"] == "admin"
    assert payload["tenant_id"] is not None
    assert payload["type"] == "access"


async def test_tenant_scoping_locks_non_owner_to_their_own_tenant(session):
    from fastapi import Depends, FastAPI
    from httpx import ASGITransport, AsyncClient

    from app.core.security import get_current_tenant_id

    probe_app = FastAPI()

    @probe_app.get("/whoami-tenant")
    async def whoami(tenant_id: str = Depends(get_current_tenant_id)):
        return {"tenant_id": tenant_id}

    tenant_a = Tenant(name="Tenant A")
    tenant_b = Tenant(name="Tenant B")
    session.add_all([tenant_a, tenant_b])
    await session.commit()
    await session.refresh(tenant_a)
    await session.refresh(tenant_b)

    driver = User(
        tenant_id=tenant_a.id,
        role="driver",
        name="Driver A",
        email="driver-a@example.com",
        status="active",
    )
    session.add(driver)
    await session.commit()
    await session.refresh(driver)

    token = security.create_access_token(user_id=driver.id, tenant_id=tenant_a.id, role="driver")

    transport = ASGITransport(app=probe_app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as c:
        # Attempting to override tenant_id via query param must be ignored for
        # a non-owner role — hard-locked to their own token's tenant_id.
        resp = await c.get(
            f"/whoami-tenant?tenant_id={tenant_b.id}",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert resp.status_code == 200
        assert resp.json()["tenant_id"] == tenant_a.id


async def test_owner_on_platform_tenant_may_cross_tenant_via_query_param(session):
    from fastapi import Depends, FastAPI
    from httpx import ASGITransport, AsyncClient

    from app.core.security import PLATFORM_TENANT_ID, get_current_tenant_id

    probe_app = FastAPI()

    @probe_app.get("/whoami-tenant")
    async def whoami(tenant_id: str = Depends(get_current_tenant_id)):
        return {"tenant_id": tenant_id}

    target_tenant = Tenant(name="Some Operator")
    session.add(target_tenant)
    await session.commit()
    await session.refresh(target_tenant)

    owner_token = security.create_access_token(
        user_id="platform-owner-1", tenant_id=PLATFORM_TENANT_ID, role="owner"
    )

    transport = ASGITransport(app=probe_app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as c:
        resp = await c.get(
            f"/whoami-tenant?tenant_id={target_tenant.id}",
            headers={"Authorization": f"Bearer {owner_token}"},
        )
        assert resp.status_code == 200
        assert resp.json()["tenant_id"] == target_tenant.id


async def test_require_role_rejects_wrong_role(session):
    from fastapi import Depends, FastAPI
    from httpx import ASGITransport, AsyncClient

    from app.core.security import require_role

    probe_app = FastAPI()

    @probe_app.get("/admin-only")
    async def admin_only(user=Depends(require_role("admin", "owner"))):
        return {"ok": True}

    tenant = Tenant(name="Tenant RBAC")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)

    driver = User(
        tenant_id=tenant.id, role="driver", name="Driver", email="driver-rbac@example.com", status="active"
    )
    session.add(driver)
    await session.commit()
    await session.refresh(driver)

    token = security.create_access_token(user_id=driver.id, tenant_id=tenant.id, role="driver")

    transport = ASGITransport(app=probe_app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as c:
        resp = await c.get("/admin-only", headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 403
