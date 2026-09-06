"""Tests for the users domain (staff + driver CRUD) — added post-integration
to close the gap where no domain slice owned "create a driver via the API"."""
from __future__ import annotations

import uuid
from datetime import UTC, datetime
from decimal import Decimal

import pytest

from app.core import security
from app.models.audit_log import AuditLog
from app.models.compliance import ComplianceDocument
from app.models.driver_engagement import TripRating, WalletTransaction
from app.models.psl_ledger import PSLLedgerEntry, PSLTopUp
from app.models.tariffs import Tariff, TariffChangeLog
from app.models.tenant import Tenant
from app.models.trips import TRIP_STATUS_CLOSED, Trip
from app.models.user import User
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


def _unique_email(prefix: str = "driver") -> str:
    # Email is globally unique across tenants (see app/models/user.py), and
    # this suite shares one DB across every test file in the session, so a
    # fixed literal like "dup@example.com" can collide with an unrelated
    # sibling domain's own test fixture (this happened against
    # test_psl_ledger.py) — always mint a fresh email instead.
    return f"{prefix}-{uuid.uuid4()}@example.com"


async def _create_driver(client, headers, **overrides):
    payload = {
        "name": overrides.pop("name", "Dev Driver"),
        "email": overrides.pop("email", _unique_email()),
        "password": overrides.pop("password", "Driver-Pass1!"),
        "role": overrides.pop("role", "driver"),
        **overrides,
    }
    return await client.post("/v1/users", json=payload, headers=headers)


# --- helpers for the delete-dependent-records tests below --------------------
# Direct ORM inserts (same convention as tests/test_psl_ledger.py's
# _make_tenant/_make_driver and tests/test_driver_engagement.py's
# _tenant/_user/_trip) rather than round-tripping through every sibling
# domain's own create endpoint — the only thing under test here is whether
# app.services.user.assert_user_deletable notices the row, not whether that
# domain's own create flow works (already covered by that domain's own test
# file).


async def _tenant(session, name: str = "Delete Dependents Tenant") -> str:
    tenant = Tenant(name=name, plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    return tenant.id


async def _user_with_token(session, *, tenant_id: str, role: str = "driver") -> tuple[str, dict]:
    """Creates a user of `role` in `tenant_id`; returns (user_id, headers)."""
    user = User(
        tenant_id=tenant_id,
        role=role,
        name=f"Test {role}",
        email=_unique_email(role),
        pin_hash=security.hash_password("Test-Passw0rd!"),
        status="active",
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)
    token = security.create_access_token(user_id=user.id, tenant_id=tenant_id, role=role)
    return user.id, {"Authorization": f"Bearer {token}"}


async def _trip(session, *, tenant_id: str, driver_id: str) -> str:
    """Minimal closed Trip row -- only what TripRating's FK needs to exist."""
    trip = Trip(
        tenant_id=tenant_id,
        client_uuid=str(uuid.uuid4()),
        vehicle_id=str(uuid.uuid4()),
        driver_id=driver_id,
        tariff_id=str(uuid.uuid4()),
        type="rank_hail",
        status=TRIP_STATUS_CLOSED,
        start_at=datetime.now(UTC),
        end_at=datetime.now(UTC),
        start_lat=-33.87,
        start_lng=151.21,
    )
    session.add(trip)
    await session.commit()
    await session.refresh(trip)
    return trip.id


async def _tariff(session, *, tenant_id: str) -> str:
    """Minimal Tariff row -- only what TariffChangeLog's FK needs to exist."""
    tariff = Tariff(
        tenant_id=tenant_id,
        name="Delete-Dependents Test Tariff",
        region="urban",
        effective_from=datetime(2025, 1, 1, tzinfo=UTC),
        flag_fall=Decimal("5.00"),
        dist_rate_1=Decimal("2.50"),
        dist_rate_2=Decimal("2.30"),
        night_rate_1=Decimal("3.00"),
        night_rate_2=Decimal("2.70"),
        waiting_rate_per_min=Decimal("1.09"),
    )
    session.add(tariff)
    await session.commit()
    await session.refresh(tariff)
    return tariff.id


async def test_create_and_get_driver(client, session):
    headers = await auth_headers(client, session, role="admin")
    email = _unique_email()

    resp = await _create_driver(client, headers, email=email)
    assert resp.status_code == 201
    body = resp.json()
    assert body["role"] == "driver"
    assert body["email"] == email
    assert "password" not in body
    assert "pin_hash" not in body
    user_id = body["id"]

    resp = await client.get(f"/v1/users/{user_id}", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["id"] == user_id


async def test_create_user_requires_admin_role(client, session):
    headers = await auth_headers(client, session, role="driver")

    resp = await _create_driver(client, headers)
    assert resp.status_code == 403


async def test_duplicate_email_rejected(client, session):
    headers = await auth_headers(client, session, role="admin")
    email = _unique_email("dup")

    resp = await _create_driver(client, headers, email=email)
    assert resp.status_code == 201

    resp = await _create_driver(client, headers, email=email, name="Second Driver")
    assert resp.status_code == 409


async def test_list_users_is_tenant_scoped(client, session):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Tenant A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Tenant B")
    email_a, email_b = _unique_email("a-driver"), _unique_email("b-driver")

    await _create_driver(client, headers_a, email=email_a)
    await _create_driver(client, headers_b, email=email_b)

    resp = await client.get("/v1/users", headers=headers_a, params={"role": "driver"})
    assert resp.status_code == 200
    emails = {u["email"] for u in resp.json()["items"]}
    assert email_a in emails
    assert email_b not in emails


async def test_update_user_and_new_password_can_login(client, session):
    headers = await auth_headers(client, session, role="admin")
    email = _unique_email("reset-me")

    resp = await _create_driver(client, headers, email=email, password="Old-Passw0rd!")
    user_id = resp.json()["id"]

    resp = await client.patch(
        f"/v1/users/{user_id}",
        json={"phone": "0400000000", "password": "New-Passw0rd!"},
        headers=headers,
    )
    assert resp.status_code == 200
    assert resp.json()["phone"] == "0400000000"

    resp = await client.post("/v1/auth/login", json={"email": email, "password": "New-Passw0rd!"})
    assert resp.status_code == 200

    resp = await client.post("/v1/auth/login", json={"email": email, "password": "Old-Passw0rd!"})
    assert resp.status_code == 401


async def test_delete_user(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await _create_driver(client, headers, email=_unique_email("to-delete"))
    user_id = resp.json()["id"]

    resp = await client.delete(f"/v1/users/{user_id}", headers=headers)
    assert resp.status_code == 204

    resp = await client.get(f"/v1/users/{user_id}", headers=headers)
    assert resp.status_code == 404


# --- delete: dependent-records refusal (see app.services.user.assert_user_
# deletable's docstring for the "cascade vs refuse" design) -------------------
#
# Real-production-bug context (see app.services.fleet's module docstring for
# the vehicle/device half of this same pass): postgres enforces every one of
# these NOT NULL foreign keys to users.id, so deleting a user referenced by
# any of them already failed at the database layer before this pass -- these
# tests both prove `assert_user_deletable` turns that into a clean 409, and
# (via tests/conftest.py's now-real `PRAGMA foreign_keys=ON`) that the
# blocking claim is actually true, not just asserted.


async def test_delete_user_blocked_by_compliance_documents(client, session):
    tenant_id = await _tenant(session)
    admin_headers = await auth_headers(client, session, role="admin", tenant_id=tenant_id)
    target_id, _ = await _user_with_token(session, tenant_id=tenant_id, role="admin")

    session.add(
        ComplianceDocument(
            tenant_id=tenant_id,
            vehicle_id=str(uuid.uuid4()),
            doc_type="calibration_record",
            file_path="uploads/fake/fake.pdf",
            original_filename="fake.pdf",
            uploaded_by=target_id,
            uploaded_at=datetime.now(UTC),
        )
    )
    await session.commit()

    resp = await client.delete(f"/v1/users/{target_id}", headers=admin_headers)
    assert resp.status_code == 409, resp.text
    assert "compliance document" in resp.json()["detail"]

    # Refused, not partially applied.
    assert (await client.get(f"/v1/users/{target_id}", headers=admin_headers)).status_code == 200


async def test_delete_user_blocked_by_psl_ledger_and_topups(client, session):
    tenant_id = await _tenant(session)
    admin_headers = await auth_headers(client, session, role="admin", tenant_id=tenant_id)
    driver_id, _ = await _user_with_token(session, tenant_id=tenant_id, role="driver")

    session.add(PSLLedgerEntry(tenant_id=tenant_id, driver_id=driver_id, period="2026-07"))
    session.add(
        PSLTopUp(
            tenant_id=tenant_id,
            driver_id=driver_id,
            period="2026-07",
            amount=Decimal("20.00"),
            stripe_charge_id="ch_fake_123",
        )
    )
    await session.commit()

    resp = await client.delete(f"/v1/users/{driver_id}", headers=admin_headers)
    assert resp.status_code == 409, resp.text
    detail = resp.json()["detail"]
    assert "PSL ledger" in detail
    assert "PSL top-up" in detail


async def test_delete_user_blocked_by_wallet_transactions_as_driver(client, session):
    tenant_id = await _tenant(session)
    admin_headers = await auth_headers(client, session, role="admin", tenant_id=tenant_id)
    driver_id, _ = await _user_with_token(session, tenant_id=tenant_id, role="driver")

    session.add(
        WalletTransaction(tenant_id=tenant_id, driver_id=driver_id, amount_aud=Decimal("50.00"), kind="top_up")
    )
    await session.commit()

    resp = await client.delete(f"/v1/users/{driver_id}", headers=admin_headers)
    assert resp.status_code == 409, resp.text
    assert "wallet transaction" in resp.json()["detail"]


async def test_delete_user_wallet_poster_reference_is_nulled_not_blocking(client, session):
    """Contrast with the previous test: WalletTransaction.driver_id (the
    ledger line's actual subject) blocks; created_by_user_id (the staff
    member who merely posted it) does not -- it cascades to NULL at the DB
    layer instead (ondelete="SET NULL", see that column's own comment)."""
    tenant_id = await _tenant(session)
    admin_headers = await auth_headers(client, session, role="admin", tenant_id=tenant_id)
    poster_id, _ = await _user_with_token(session, tenant_id=tenant_id, role="admin")
    driver_id, _ = await _user_with_token(session, tenant_id=tenant_id, role="driver")

    txn = WalletTransaction(
        tenant_id=tenant_id,
        driver_id=driver_id,
        amount_aud=Decimal("10.00"),
        kind="adjustment",
        created_by_user_id=poster_id,
    )
    session.add(txn)
    await session.commit()
    await session.refresh(txn)

    resp = await client.delete(f"/v1/users/{poster_id}", headers=admin_headers)
    assert resp.status_code == 204, resp.text

    await session.refresh(txn)
    assert txn.created_by_user_id is None
    assert txn.driver_id == driver_id  # the ledger line itself survives, untouched


async def test_delete_user_blocked_by_trip_ratings(client, session):
    tenant_id = await _tenant(session)
    admin_headers = await auth_headers(client, session, role="admin", tenant_id=tenant_id)
    driver_id, _ = await _user_with_token(session, tenant_id=tenant_id, role="driver")
    trip_id = await _trip(session, tenant_id=tenant_id, driver_id=driver_id)

    session.add(TripRating(tenant_id=tenant_id, trip_id=trip_id, driver_id=driver_id, stars=5))
    await session.commit()

    resp = await client.delete(f"/v1/users/{driver_id}", headers=admin_headers)
    assert resp.status_code == 409, resp.text
    assert "trip rating" in resp.json()["detail"]


async def test_delete_user_blocked_by_tariff_change_log(client, session):
    """Fare-regulation evidence (who changed which rate, when) blocks
    deletion even for a staff/admin account, not just drivers -- see
    assert_user_deletable's own comment on this specific check."""
    tenant_id = await _tenant(session)
    admin_headers = await auth_headers(client, session, role="admin", tenant_id=tenant_id)
    actor_id, _ = await _user_with_token(session, tenant_id=tenant_id, role="admin")
    tariff_id = await _tariff(session, tenant_id=tenant_id)

    session.add(
        TariffChangeLog(tariff_id=tariff_id, tenant_id=tenant_id, actor_user_id=actor_id, after_json={"flag_fall": "5.00"})
    )
    await session.commit()

    resp = await client.delete(f"/v1/users/{actor_id}", headers=admin_headers)
    assert resp.status_code == 409, resp.text
    assert "tariff change log" in resp.json()["detail"]


async def test_delete_user_blocked_by_audit_log_as_actor(client, session):
    """Also documents WHY this one blocks instead of cascading to NULL like
    the schema-nullable AuditLog.actor_user_id column might suggest: the
    table is a cryptographic hash chain over its own rows (see
    app.models.audit_log's docstring) -- an ON DELETE SET NULL would mutate
    a row post-write, which is exactly what the tamper-evidence chain exists
    to detect."""
    tenant_id = await _tenant(session)
    admin_headers = await auth_headers(client, session, role="admin", tenant_id=tenant_id)
    actor_id, _ = await _user_with_token(session, tenant_id=tenant_id, role="admin")

    session.add(
        AuditLog(tenant_id=tenant_id, actor_user_id=actor_id, action="update", entity_type="trip", entity_id="t-1")
    )
    await session.commit()

    resp = await client.delete(f"/v1/users/{actor_id}", headers=admin_headers)
    assert resp.status_code == 409, resp.text
    assert "audit log" in resp.json()["detail"]


async def test_delete_user_reports_multiple_blockers_together(client, session):
    tenant_id = await _tenant(session)
    admin_headers = await auth_headers(client, session, role="admin", tenant_id=tenant_id)
    driver_id, _ = await _user_with_token(session, tenant_id=tenant_id, role="driver")

    session.add(PSLLedgerEntry(tenant_id=tenant_id, driver_id=driver_id, period="2026-08"))
    session.add(
        WalletTransaction(tenant_id=tenant_id, driver_id=driver_id, amount_aud=Decimal("15.00"), kind="top_up")
    )
    await session.commit()

    resp = await client.delete(f"/v1/users/{driver_id}", headers=admin_headers)
    assert resp.status_code == 409, resp.text
    detail = resp.json()["detail"]
    assert "PSL ledger" in detail
    assert "wallet transaction" in detail


async def test_get_nonexistent_user_is_404(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await client.get("/v1/users/does-not-exist", headers=headers)
    assert resp.status_code == 404


# --- photo -------------------------------------------------------------------


async def test_upload_and_get_user_photo(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await _create_driver(client, headers)
    user_id = resp.json()["id"]

    resp = await client.post(
        f"/v1/users/{user_id}/photo",
        files={"file": ("driver.jpg", b"selfie-bytes-fakejpeg", "image/jpeg")},
        headers=headers,
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["photo_url"] is not None
    assert body["photo_url"].startswith("uploads/")

    resp = await client.get(f"/v1/users/{user_id}/photo", headers=headers)
    assert resp.status_code == 200
    assert resp.content == b"selfie-bytes-fakejpeg"


async def test_upload_user_photo_requires_staff_role(client, session):
    headers = await auth_headers(client, session, role="admin")
    driver_headers = await auth_headers(client, session, role="driver", tenant_id=None)
    resp = await _create_driver(client, headers)
    user_id = resp.json()["id"]

    resp = await client.post(
        f"/v1/users/{user_id}/photo",
        files={"file": ("driver.jpg", b"data", "image/jpeg")},
        headers=driver_headers,
    )
    assert resp.status_code == 403


async def test_driver_can_upload_their_own_photo(client, session):
    """Self-or-staff gate (fixed during integration - a driver could not
    upload their own photo at all under the original staff-only gate, which
    would have 403'd against the Android app's actual Profile-screen upload
    flow on every real device). A driver uploading their OWN photo (not
    another user's) must succeed even though driver is not a staff role."""
    driver_headers = await auth_headers(client, session, role="driver")
    token = driver_headers["Authorization"].removeprefix("Bearer ")
    driver_id = security.decode_token(token)["sub"]

    resp = await client.post(
        f"/v1/users/{driver_id}/photo",
        files={"file": ("selfie.jpg", b"selfie-bytes-fakejpeg", "image/jpeg")},
        headers=driver_headers,
    )
    assert resp.status_code == 200
    assert resp.json()["photo_url"] is not None

    resp = await client.get(f"/v1/users/{driver_id}/photo", headers=driver_headers)
    assert resp.status_code == 200
    assert resp.content == b"selfie-bytes-fakejpeg"


async def test_upload_user_photo_rejects_empty_file(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await _create_driver(client, headers)
    user_id = resp.json()["id"]

    resp = await client.post(
        f"/v1/users/{user_id}/photo",
        files={"file": ("empty.jpg", b"", "image/jpeg")},
        headers=headers,
    )
    assert resp.status_code == 400


async def test_get_user_photo_404_when_none_uploaded(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await _create_driver(client, headers)
    user_id = resp.json()["id"]

    resp = await client.get(f"/v1/users/{user_id}/photo", headers=headers)
    assert resp.status_code == 404


async def test_user_photo_is_tenant_isolated(client, session):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Photo Tenant A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Photo Tenant B")
    resp = await _create_driver(client, headers_a)
    user_id = resp.json()["id"]

    resp = await client.post(
        f"/v1/users/{user_id}/photo",
        files={"file": ("driver.jpg", b"data", "image/jpeg")},
        headers=headers_a,
    )
    assert resp.status_code == 200

    resp = await client.get(f"/v1/users/{user_id}/photo", headers=headers_b)
    assert resp.status_code == 404


async def test_upload_user_photo_404_for_unknown_user(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await client.post(
        "/v1/users/does-not-exist/photo",
        files={"file": ("driver.jpg", b"data", "image/jpeg")},
        headers=headers,
    )
    assert resp.status_code == 404
