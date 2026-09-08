"""D10 (security settings): a durable record of each login, backing the
dashboard's "sessions" list and "sign out everywhere" action.

Why this exists at all: `app.core.security._RevocationStore` only ever knows
about a *jti* someone explicitly told it to revoke — there was never a way to
enumerate "every session this user currently has open" to show them a list,
or to revoke a session by an id a human can click on rather than by pasting a
JWT. This table is that index: one row per login, updated in place across
refresh-token rotation (the row's `current_access_jti`/`current_refresh_jti`
change, its `id` does not), so "sign out everywhere" can walk every
non-revoked row for a user and push each pair's jtis into the SAME
revocation store every other logout/refresh path already uses — no second
revocation mechanism, just a second way to reach it.

Deliberately NOT a `TenantScopedMixin` row: `tenant_id` is nullable to match
`User.tenant_id` (platform-owner staff have none), and every query against
this table is scoped by `user_id == current_user.id` regardless — a user can
only ever list/revoke their OWN sessions (see app/api/v1/auth.py), so a
missing tenant filter here is not a cross-tenant leak the way it would be on
a fleet/trips/etc. row.
"""
from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TimestampMixin


class UserSession(Base, TimestampMixin):
    __tablename__ = "user_sessions"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False, index=True)
    tenant_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("tenants.id"), nullable=True, index=True
    )

    # The live jti pair for this session as of right now. Rotated in place by
    # POST /v1/auth/refresh (see that handler) rather than creating a new row
    # per refresh — a session is one continuous login, not one row per token.
    current_access_jti: Mapped[str] = mapped_column(String(36), nullable=False)
    current_refresh_jti: Mapped[str] = mapped_column(String(36), nullable=False)

    user_agent: Mapped[str | None] = mapped_column(String(255), nullable=True)
    ip_address: Mapped[str | None] = mapped_column(String(64), nullable=True)

    last_seen_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    # NULL == still live. Set (once) by /sessions/{id}/revoke, /sessions/revoke-all,
    # or /logout (this session's own row only) — never un-set.
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


__all__ = ["UserSession"]
