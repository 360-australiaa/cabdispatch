"""Shared pytest fixtures for the whole test-suite.

IMPORTANT: environment variables that control app settings (DATABASE_URL etc.)
are set at MODULE level, before any `app.*` import, so the `Settings()`
singleton in app.core.config picks up test values the first time it's
constructed. Every sibling domain's test files should rely on this conftest
(via normal pytest auto-discovery) rather than re-pointing the DB themselves.
"""
from __future__ import annotations

import os
import uuid
from pathlib import Path

# --- test environment, MUST be set before any app import -------------------
_TEST_DB_FILE = Path(__file__).parent / "test_dev.db"
os.environ["DATABASE_URL"] = f"sqlite+aiosqlite:///{_TEST_DB_FILE.as_posix()}"
os.environ["JWT_SECRET"] = "test-only-secret-do-not-use-in-prod"
os.environ["ENV"] = "test"
os.environ["REDIS_URL"] = "redis://localhost:6380/0"  # intentionally unreachable in CI/dev

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.core import security
from app.core.database import AsyncSessionLocal, Base, engine
from app.models import Tenant, User


@pytest_asyncio.fixture(scope="session", autouse=True)
async def _test_database():
    """Fresh sqlite file DB for the whole test session: wiped before and after."""
    if _TEST_DB_FILE.exists():
        _TEST_DB_FILE.unlink()

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    yield

    await engine.dispose()
    if _TEST_DB_FILE.exists():
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
