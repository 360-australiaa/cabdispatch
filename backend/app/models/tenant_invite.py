"""One-time owner-invite tokens for tenant self-serve onboarding (X2, 2026-09-08).

`POST /v1/platform/tenants` creates a brand-new tenant AND its owner user in
one transaction, but that owner user is created with NO password
(`User.pin_hash IS NULL` — see `app.services.platform.create_tenant`), which
already makes it unable to log in via `POST /v1/auth/login`. This table is
how that owner sets their OWN secret instead of a developer minting one for
them: `create_tenant` also creates exactly one row here, hands the RAW,
high-entropy token back to the platform-owner caller exactly once (never
persisted, never logged — see that function's own docstring), and
`POST /v1/platform/invites/accept` (public, token-based — the owner has no
bearer token yet) exchanges it for a password the owner chooses themselves.

Only the token's SHA-256 digest is ever stored, same "never store the secret
itself" principle as `Device.secret_hash` (`app/models/fleet.py`) — a stolen
database dump cannot be replayed as a working invite.
"""
from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TimestampMixin


class TenantInvite(Base, TimestampMixin):
    __tablename__ = "tenant_invites"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    tenant_id: Mapped[str] = mapped_column(String(36), ForeignKey("tenants.id"), nullable=False, index=True)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False, index=True)
    # SHA-256 hex digest of the raw token — see module docstring. Unique so a
    # lookup by digest can never resolve to more than one invite.
    token_hash: Mapped[str] = mapped_column(String(64), nullable=False, unique=True, index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    # NULL until POST /v1/platform/invites/accept succeeds. Once set, the
    # token is dead forever — there is no re-use, matching the pairing-code
    # "burn on success" convention in app.services.fleet.register_device.
    used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


__all__ = ["TenantInvite"]
