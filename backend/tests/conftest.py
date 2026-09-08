"""Shared pytest fixtures for the whole test-suite.

IMPORTANT: environment variables that control app settings (DATABASE_URL etc.)
are set at MODULE level, before any `app.*` import, so the `Settings()`
singleton in app.core.config picks up test values the first time it's
constructed. Every sibling domain's test files should rely on this conftest
(via normal pytest auto-discovery) rather than re-pointing the DB themselves.
"""
from __future__ import annotations

import os

# --- test environment, MUST be set before any app import -------------------
import os as _os
import uuid
from pathlib import Path

_TEST_DB_FILE = Path(_os.environ.get("_TEST_DB_FILE_OVERRIDE") or (Path(__file__).parent / "test_dev.db"))

# TEST_DATABASE_URL lets CI point the whole suite at a real Postgres (production
# is Postgres; SQLite is a dev-parity approximation and differs on numeric
# precision, JSON operators and concurrency — backend audit §7). It is opt-in on
# purpose: local dev must never require a running Postgres to run `pytest`.
_TEST_DATABASE_URL = _os.environ.get("TEST_DATABASE_URL") or ""
_USING_SQLITE_FILE = not _TEST_DATABASE_URL
os.environ["DATABASE_URL"] = _TEST_DATABASE_URL or f"sqlite+aiosqlite:///{_TEST_DB_FILE.as_posix()}"
os.environ["JWT_SECRET"] = "test-only-secret-do-not-use-in-prod"
os.environ["ENV"] = "test"
os.environ["REDIS_URL"] = "redis://localhost:6380/0"  # intentionally unreachable in CI/dev

import asyncio

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.core import security
from app.core.database import AsyncSessionLocal, Base, engine
from app.models import Tenant, User

# --- how the test schema gets built ----------------------------------------
# Historically this was `Base.metadata.create_all` — which meant a fully green
# suite said *nothing* about whether the alembic chain applies. That is not a
# theoretical gap: the production-only NOT NULL crash documented at the top of
# `app/services/fleet.py` was invisible to pytest for exactly this reason, and
# during Wave 1 `alembic upgrade head` was found to be broken on SQLite
# outright (a bare `op.create_foreign_key` at revision 7060b390bade), meaning
# no fresh database could be built by migration on any machine — with 883
# passing tests. The default is therefore now the migration chain: the tests
# run against the same schema production runs against.
#
# `TEST_SCHEMA_MODE=create_all` restores the old path. It is deliberately an
# explicit opt-in rather than a silent fallback, so a suite that only passes
# under `create_all` is visibly doing so.
SCHEMA_MODE = _os.environ.get("TEST_SCHEMA_MODE", "migrations").strip().lower()
if SCHEMA_MODE not in ("migrations", "create_all"):
    raise RuntimeError(
        f"TEST_SCHEMA_MODE must be 'migrations' or 'create_all', got {SCHEMA_MODE!r}"
    )

ALEMBIC_INI = Path(__file__).resolve().parent.parent / "alembic.ini"


def alembic_config():
    """An alembic Config pinned to this repo's alembic.ini by absolute path, so
    it resolves identically no matter what cwd pytest was launched from.

    The URL is left alone on purpose: `alembic/env.py` sets it from
    `app.core.config.settings.DATABASE_URL`, which this module has already
    pointed at the test database above.
    """
    from alembic.config import Config

    cfg = Config(str(ALEMBIC_INI))
    cfg.set_main_option("script_location", str(ALEMBIC_INI.parent / "alembic"))
    return cfg


def _upgrade_head_blocking() -> None:
    """Runs `alembic upgrade head`. Blocking, and must NOT be called from a
    thread with a running event loop: `env.py` drives the async engine via
    `asyncio.run()`, which raises if a loop is already running. Callers inside
    async fixtures go through `asyncio.to_thread`."""
    from alembic.script import ScriptDirectory

    from alembic import command

    cfg = alembic_config()

    # Check the head count first. A forked graph makes `upgrade head` fail with
    # alembic's generic "Multiple head revisions are present" deep inside the
    # session fixture, which reads as a broken test harness rather than as the
    # real, entirely fixable problem it is. Say what actually happened instead.
    heads = ScriptDirectory.from_config(cfg).get_heads()
    if len(heads) != 1:
        raise RuntimeError(
            f"alembic has {len(heads)} heads: {heads}. The migration graph is forked, so "
            "`alembic upgrade head` cannot run and no fresh database can be built. Write a "
            "merge revision: alembic merge -m 'merge heads' " + " ".join(heads)
        )

    command.upgrade(cfg, "head")


@pytest_asyncio.fixture(scope="session", autouse=True)
async def _test_database():
    """Fresh DB for the whole test session: wiped before and after.

    The schema is built ONCE per session (not per test) — a full migration
    chain is ~40 revisions and running it per test would be unusable.
    """
    if _USING_SQLITE_FILE and _TEST_DB_FILE.exists():
        _TEST_DB_FILE.unlink()

    if SCHEMA_MODE == "migrations":
        if not _USING_SQLITE_FILE:
            # Postgres target: the database may be re-used between runs, so
            # start from a known-empty schema rather than assuming it is.
            async with engine.begin() as conn:
                await conn.run_sync(Base.metadata.drop_all)
                await conn.exec_driver_sql("DROP TABLE IF EXISTS alembic_version")
            await engine.dispose()
        await asyncio.to_thread(_upgrade_head_blocking)
    else:
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)

    yield

    await engine.dispose()
    if _USING_SQLITE_FILE and _TEST_DB_FILE.exists():
        _TEST_DB_FILE.unlink()


@pytest.fixture
def app():
    """Builds/returns the FastAPI app under test, imported after test DB env is set."""
    from app.main import app as fastapi_app

    return fastapi_app


@pytest_asyncio.fixture
async def session():
    """A DB session scoped to a single test — same engine as the app's own
    `get_session` dependency, so rows created here are visible to requests
    made via the `client` fixture."""
    async with AsyncSessionLocal() as s:
        yield s


@pytest_asyncio.fixture
async def client(app):
    """Async httpx client wired directly to the ASGI app — no real socket."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as c:
        yield c


async def auth_headers(
    client: AsyncClient,
    session,
    *,
    role: str = "admin",
    tenant_id: str | None = None,
    tenant_name: str = "Test Tenant",
) -> dict:
    """Creates a test tenant (unless `tenant_id` of an existing one is given) +
    a user with the given `role`, and returns a ready-to-use
    `{"Authorization": "Bearer <token>"}` header dict for that user.

    Domain test files import this helper directly:
        from tests.conftest import auth_headers
    """
    if tenant_id is None:
        tenant = Tenant(name=tenant_name, plan="standard")
        session.add(tenant)
        await session.commit()
        await session.refresh(tenant)
        tenant_id = tenant.id

    user = User(
        tenant_id=tenant_id,
        role=role,
        name=f"Test {role.capitalize()}",
        email=f"{uuid.uuid4()}@example.com",
        pin_hash=security.hash_password("Test-Passw0rd!"),
        status="active",
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)

    token = security.create_access_token(user_id=user.id, tenant_id=tenant_id, role=user.role)
    return {"Authorization": f"Bearer {token}"}
