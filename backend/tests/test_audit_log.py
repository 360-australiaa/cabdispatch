"""Tests for the Audit Log domain (`/v1/audit-log`).

NOTE: this router is not yet registered in app.main — a later integration
step wires it (and the other 11 domains) into the app. Until then these
tests will 404 if run in total isolation. They are written correctly against
the endpoints as built; do not try to make them pass standalone.

The `AuditLog` model also is not yet imported into app/models/__init__.py
(out of scope for this domain agent), so it's imported directly below purely
to register its table on `Base.metadata` before the session-scoped
`_test_database` fixture in conftest.py runs `create_all` — same pattern
already used by the sibling fleet/compliance domain test files.
"""
from __future__ import annotations

from sqlalchemy import select

from app.models import Tenant, User
from app.models.audit_log import AuditLog  # noqa: F401 — registers table on Base.metadata
from app.services.audit_log import record_audit
from tests.conftest import auth_headers

# --- create ---------------------------------------------------------------


async def test_create_audit_log_entry_attributes_caller_as_actor(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await client.post(
        "/v1/audit-log",
        json={
            "action": "update",
            "entity_type": "trip",
            "entity_id": "trip-123",
            "before_json": {"status": "in_progress"},
            "after_json": {"status": "completed"},
        },
        headers=headers,
    )

    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["action"] == "update"
    assert body["entity_type"] == "trip"
    assert body["entity_id"] == "trip-123"
    assert body["before_json"] == {"status": "in_progress"}
    assert body["after_json"] == {"status": "completed"}
    assert body["actor_user_id"] is not None
    assert body["at"] is not None


async def test_create_audit_log_entry_cannot_forge_actor(client, session):
    """AuditLogCreate has no actor_user_id field — a client-supplied one (if
    somehow smuggled in) must be ignored; the actor is always the caller."""
    headers = await auth_headers(client, session, role="admin")

    resp = await client.post(
        "/v1/audit-log",
        json={
            "action": "create",
            "entity_type": "vehicle",
            "entity_id": "veh-1",
            "actor_user_id": "someone-else",
        },
        headers=headers,
    )

    assert resp.status_code == 201, resp.text
    assert resp.json()["actor_user_id"] != "someone-else"


async def test_create_audit_log_entry_allows_null_before_and_after(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await client.post(
        "/v1/audit-log",
        json={"action": "create", "entity_type": "vehicle", "entity_id": "veh-2"},
        headers=headers,
    )

    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["before_json"] is None
    assert body["after_json"] is None


# --- list + filtering + pagination -----------------------------------------


async def test_list_audit_log_filters_by_entity_type_entity_id_actor_and_action(client, session):
    headers = await auth_headers(client, session, role="admin")

    await client.post(
        "/v1/audit-log",
        json={"action": "create", "entity_type": "trip", "entity_id": "trip-a"},
        headers=headers,
    )
    await client.post(
        "/v1/audit-log",
        json={"action": "update", "entity_type": "trip", "entity_id": "trip-a"},
        headers=headers,
    )
    await client.post(
        "/v1/audit-log",
        json={"action": "create", "entity_type": "vehicle", "entity_id": "veh-b"},
        headers=headers,
    )

    all_resp = await client.get("/v1/audit-log", headers=headers)
    assert all_resp.status_code == 200
    assert all_resp.json()["total"] == 3

    by_entity_type = await client.get("/v1/audit-log?entity_type=trip", headers=headers)
    assert by_entity_type.json()["total"] == 2

    by_entity_id = await client.get("/v1/audit-log?entity_id=trip-a", headers=headers)
    assert by_entity_id.json()["total"] == 2

    by_action = await client.get("/v1/audit-log?action=update", headers=headers)
    assert by_action.json()["total"] == 1
    assert by_action.json()["items"][0]["entity_id"] == "trip-a"

    actor_id = all_resp.json()["items"][0]["actor_user_id"]
    by_actor = await client.get(f"/v1/audit-log?actor_user_id={actor_id}", headers=headers)
    assert by_actor.json()["total"] == 3

    paginated = await client.get("/v1/audit-log?limit=1&offset=0", headers=headers)
    assert len(paginated.json()["items"]) == 1
    assert paginated.json()["total"] == 3


async def test_list_audit_log_is_ordered_most_recent_first(client, session):
    headers = await auth_headers(client, session, role="admin")

    for i in range(3):
        resp = await client.post(
            "/v1/audit-log",
            json={"action": "create", "entity_type": "trip", "entity_id": f"trip-{i}"},
            headers=headers,
        )
        assert resp.status_code == 201

    resp = await client.get("/v1/audit-log", headers=headers)
    entity_ids = [item["entity_id"] for item in resp.json()["items"]]
    assert entity_ids == ["trip-2", "trip-1", "trip-0"]


async def test_tenant_isolation_on_audit_log(client, session):
    tenant_a = Tenant(name="Audit Tenant A")
    tenant_b = Tenant(name="Audit Tenant B")
    session.add_all([tenant_a, tenant_b])
    await session.commit()
    await session.refresh(tenant_a)
    await session.refresh(tenant_b)

    headers_a = await auth_headers(client, session, role="admin", tenant_id=tenant_a.id)
    headers_b = await auth_headers(client, session, role="admin", tenant_id=tenant_b.id)

    create = await client.post(
        "/v1/audit-log",
        json={"action": "create", "entity_type": "trip", "entity_id": "isolated-trip"},
        headers=headers_a,
    )
    assert create.status_code == 201

    list_b = await client.get("/v1/audit-log", headers=headers_b)
    assert list_b.json()["total"] == 0

    list_a = await client.get("/v1/audit-log", headers=headers_a)
    assert list_a.json()["total"] == 1


# --- append-only: no update/delete surface ---------------------------------


async def test_audit_log_has_no_update_or_delete_endpoints(client, session):
    """Explicit, deliberate: an audit trail that can be edited or deleted
    after the fact provides no tamper evidence. There is no id-scoped route
    at all (`/v1/audit-log/{id}`) — every method against it 404s/405s."""
    headers = await auth_headers(client, session, role="admin")

    create = await client.post(
        "/v1/audit-log",
        json={"action": "create", "entity_type": "trip", "entity_id": "no-mutation"},
        headers=headers,
    )
    entry_id = create.json()["id"]

    patch_resp = await client.patch(
        f"/v1/audit-log/{entry_id}", json={"action": "tampered"}, headers=headers
    )
    assert patch_resp.status_code in (404, 405)

    delete_resp = await client.delete(f"/v1/audit-log/{entry_id}", headers=headers)
    assert delete_resp.status_code in (404, 405)

    # entry is unaffected, still present exactly as written
    still_there = await client.get("/v1/audit-log?entity_id=no-mutation", headers=headers)
    assert still_there.json()["total"] == 1
    assert still_there.json()["items"][0]["action"] == "create"


# --- self-test: record_audit called directly (as another domain would) ----


async def test_record_audit_self_test_shows_up_in_list(client, session):
    """Demonstrates the intended cross-domain usage: another domain's service
    calls `record_audit(session, ...)` directly (no HTTP call to this
    router's own POST endpoint) as part of its own mutation, commits, and the
    resulting row is then visible through `GET /v1/audit-log` exactly like
    any entry created via the API. Wiring this into the other 11 domains'
    actual routers is out of scope for this domain agent — this test is the
    demonstration that the helper itself works end-to-end."""
    tenant = Tenant(name="Self-Test Tenant")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)

    actor = User(
        tenant_id=tenant.id,
        role="dispatcher",
        name="Self Test Actor",
        email="selftest-actor@example.com",
        status="active",
    )
    session.add(actor)
    await session.commit()
    await session.refresh(actor)

    entry = await record_audit(
        session,
        tenant_id=tenant.id,
        actor_user_id=actor.id,
        action="dispatch",
        entity_type="trip",
        entity_id="self-test-trip",
        before={"status": "requested"},
        after={"status": "dispatched"},
    )
    # record_audit deliberately does not commit (see its docstring) — the
    # caller (here, this test, standing in for another domain's own mutation)
    # is responsible for committing its own transaction.
    await session.commit()
    assert entry.id is not None
    assert entry.at is not None

    headers = await auth_headers(client, session, role="admin", tenant_id=tenant.id)
    resp = await client.get("/v1/audit-log?entity_type=trip&entity_id=self-test-trip", headers=headers)

    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] == 1
    item = body["items"][0]
    assert item["id"] == entry.id
    assert item["action"] == "dispatch"
    assert item["actor_user_id"] == actor.id
    assert item["before_json"] == {"status": "requested"}
    assert item["after_json"] == {"status": "dispatched"}


# --- hash chain: verify -----------------------------------------------------


async def test_verify_reports_valid_golden_chain(client, session):
    """Insert 3 rows for the same tenant, then confirm GET /verify walks the
    whole chain and reports it intact."""
    headers = await auth_headers(client, session, role="admin")

    for i in range(3):
        resp = await client.post(
            "/v1/audit-log",
            json={"action": "create", "entity_type": "trip", "entity_id": f"chain-trip-{i}"},
            headers=headers,
        )
        assert resp.status_code == 201, resp.text

    resp = await client.get("/v1/audit-log/verify", headers=headers)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body == {"valid": True, "broken_at_id": None, "checked": 3}


async def test_verify_requires_admin_or_owner_role(client, session):
    headers = await auth_headers(client, session, role="dispatcher")

    resp = await client.get("/v1/audit-log/verify", headers=headers)
    assert resp.status_code == 403


async def test_verify_detects_tampered_row(client, session):
    """Hand-modify a row's `action` directly via the DB session (bypassing
    both this router and app.services.audit_log entirely, the way a rogue DB
    admin or a compromised process might) and confirm /verify now reports the
    chain invalid at exactly that row's id."""
    headers = await auth_headers(client, session, role="admin")

    created_ids = []
    for i in range(3):
        resp = await client.post(
            "/v1/audit-log",
            json={"action": "create", "entity_type": "trip", "entity_id": f"tamper-trip-{i}"},
            headers=headers,
        )
        assert resp.status_code == 201, resp.text
        created_ids.append(resp.json()["id"])

    # Sanity check: untampered chain is valid before we touch anything.
    pre = await client.get("/v1/audit-log/verify", headers=headers)
    assert pre.json()["valid"] is True

    tampered_id = created_ids[1]  # the middle row
    result = await session.execute(select(AuditLog).where(AuditLog.id == tampered_id))
    row = result.scalar_one()
    row.action = "tampered"
    await session.commit()

    resp = await client.get("/v1/audit-log/verify", headers=headers)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["valid"] is False
    assert body["broken_at_id"] == tampered_id
    assert body["checked"] == 2  # stops at the 2nd row walked (oldest-first)


async def test_verify_is_scoped_per_tenant(client, session):
    """The hash chain is per-tenant — a second tenant's untouched chain must
    still verify as valid even after another tenant's chain is tampered."""
    tenant_a = Tenant(name="Chain Tenant A")
    tenant_b = Tenant(name="Chain Tenant B")
    session.add_all([tenant_a, tenant_b])
    await session.commit()
    await session.refresh(tenant_a)
    await session.refresh(tenant_b)

    headers_a = await auth_headers(client, session, role="admin", tenant_id=tenant_a.id)
    headers_b = await auth_headers(client, session, role="admin", tenant_id=tenant_b.id)

    resp_a = await client.post(
        "/v1/audit-log",
        json={"action": "create", "entity_type": "trip", "entity_id": "a-trip"},
        headers=headers_a,
    )
    assert resp_a.status_code == 201
    await client.post(
        "/v1/audit-log",
        json={"action": "create", "entity_type": "trip", "entity_id": "b-trip"},
        headers=headers_b,
    )

    tampered_id = resp_a.json()["id"]
    result = await session.execute(select(AuditLog).where(AuditLog.id == tampered_id))
    row = result.scalar_one()
    row.action = "tampered"
    await session.commit()

    verify_a = await client.get("/v1/audit-log/verify", headers=headers_a)
    assert verify_a.json()["valid"] is False

    verify_b = await client.get("/v1/audit-log/verify", headers=headers_b)
    assert verify_b.json() == {"valid": True, "broken_at_id": None, "checked": 1}
