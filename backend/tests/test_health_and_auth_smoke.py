"""Smoke tests for app boot, /health, and the conftest auth_headers helper —
exercised here so every sibling domain can trust this contract works before
they build 42 routes on top of it."""
from app.core import security
from app.models import Tenant, User
from tests.conftest import auth_headers


async def test_health_endpoint(client):
    resp = await client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"


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
