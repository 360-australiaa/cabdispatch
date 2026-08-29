"""Tests for app.services.user_invites (plan Part 4, Phase 1, WP-10) --
create_invite/consume_invite directly at the service layer, independent of
the HTTP endpoints tested in tests/test_auth.py."""
from __future__ import annotations

import uuid
from datetime import timedelta

import pytest
from sqlalchemy import select

from app.core import security
from app.models.user import User
from app.models.user_invite import (
    INVITE_PURPOSE_INVITE,
    INVITE_PURPOSE_PASSWORD_RESET,
    UserInvite,
)
from app.services.user_invites import (
    InvalidInviteTokenError,
    consume_invite,
    create_invite,
)

pytestmark = pytest.mark.asyncio


async def _make_pending_user(session, *, tenant_id: str) -> User:
    """A user created via the invite flow: pin_hash=None until accepted --
    see app.models.user_invite's module docstring."""
    user = User(
        tenant_id=tenant_id,
        role="dispatcher",
        name="Invited Person",
        email=f"{uuid.uuid4()}@example.com",
        pin_hash=None,
        status="active",
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)
    return user


async def _make_tenant(session):
    from app.models import Tenant

    tenant = Tenant(name=f"Invite Test Tenant {uuid.uuid4()}", plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    return tenant


async def test_create_invite_returns_raw_token_not_stored(session):
    tenant = await _make_tenant(session)
    user = await _make_pending_user(session, tenant_id=tenant.id)

    invite, raw_token, link = await create_invite(session, user=user, purpose=INVITE_PURPOSE_INVITE)

    assert raw_token
    assert len(raw_token) > 20
    assert raw_token in link
    # The raw token is never persisted -- only its hash is.
    assert invite.token_hash != raw_token
    result = await session.execute(select(UserInvite).where(UserInvite.id == invite.id))
    stored = result.scalar_one()
    assert stored.token_hash != raw_token
    assert stored.used_at is None
    assert stored.purpose == INVITE_PURPOSE_INVITE


async def test_consume_invite_sets_password_and_clears_must_change(session):
    tenant = await _make_tenant(session)
    user = await _make_pending_user(session, tenant_id=tenant.id)
    user.must_change_password = True
    session.add(user)
    await session.commit()

    _invite, raw_token, _link = await create_invite(session, user=user, purpose=INVITE_PURPOSE_INVITE)

    updated = await consume_invite(session, token=raw_token, new_password="NewPassw0rd!")

    assert updated.id == user.id
    assert updated.pin_hash is not None
    assert security.verify_password("NewPassw0rd!", updated.pin_hash)
    assert updated.must_change_password is False
    assert updated.password_changed_at is not None


async def test_consume_invite_token_is_single_use(session):
    tenant = await _make_tenant(session)
    user = await _make_pending_user(session, tenant_id=tenant.id)
    _invite, raw_token, _link = await create_invite(session, user=user, purpose=INVITE_PURPOSE_INVITE)

    await consume_invite(session, token=raw_token, new_password="FirstPassw0rd!")

    with pytest.raises(InvalidInviteTokenError):
        await consume_invite(session, token=raw_token, new_password="SecondPassw0rd!")


async def test_consume_invite_rejects_expired_token(session):
    tenant = await _make_tenant(session)
    user = await _make_pending_user(session, tenant_id=tenant.id)

    _invite, raw_token, _link = await create_invite(
        session, user=user, purpose=INVITE_PURPOSE_PASSWORD_RESET, ttl=timedelta(seconds=-1)
    )

    with pytest.raises(InvalidInviteTokenError):
        await consume_invite(session, token=raw_token, new_password="NewPassw0rd!")


async def test_consume_invite_rejects_unknown_token(session):
    with pytest.raises(InvalidInviteTokenError):
        await consume_invite(session, token="not-a-real-token", new_password="NewPassw0rd!")


async def test_consume_invite_rejects_wrong_purpose(session):
    tenant = await _make_tenant(session)
    user = await _make_pending_user(session, tenant_id=tenant.id)
    _invite, raw_token, _link = await create_invite(
        session, user=user, purpose=INVITE_PURPOSE_PASSWORD_RESET
    )

    with pytest.raises(InvalidInviteTokenError):
        await consume_invite(
            session,
            token=raw_token,
            new_password="NewPassw0rd!",
            expected_purpose=INVITE_PURPOSE_INVITE,
        )
