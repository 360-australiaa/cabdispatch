"""UserInvite model (plan Part 4, Phase 1, WP-10) -- a single table serving
two flows that are structurally identical ("prove ownership of a token, then
set User.pin_hash"), distinguished only by purpose and TTL:

- purpose="invite" -- issued when a user is created (owner/admin/etc.) so
  they set their own first password instead of a platform operator typing
  one in and conveying it out of band (see docs/ARCHITECTURE_TENANCY_FLEET_
  COMPLIANCE.md Part 1.1, "the de-facto onboarding path").
- purpose="password_reset" -- issued by POST /v1/auth/forgot-password.

See app.services.user_invites for the create/consume logic and the
token-storage design note (sha256 of a high-entropy random token, not
bcrypt).

Status note (plan step 2 of this work package): a not-yet-accepted invite
does NOT get a new User.status value. UserStatus (app/schemas/user.py) is a
closed Literal["active", "inactive", "suspended"] with no
pending/invited member, and widening it would ripple into every existing
status check/dashboard filter for a purely cosmetic gain. The existing login
path already makes an un-accepted invited user unable to log in without any
new state: POST /v1/auth/login (app/api/v1/auth.py) reads
"if user is None or not user.pin_hash or not verify_password(...)" --
verify_password is never reached when pin_hash is falsy. A user created via
the invite flow is therefore created with pin_hash=None and status="active";
it simply cannot authenticate by any path until consume_invite sets a real
pin_hash. This is simpler and reuses an existing, already-tested guard
rather than inventing a second one.
"""
from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TimestampMixin

INVITE_PURPOSE_INVITE = "invite"
INVITE_PURPOSE_PASSWORD_RESET = "password_reset"


class UserInvite(Base, TimestampMixin):
    __tablename__ = "user_invites"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))

    # Nullable: mirrors User.tenant_id -- a platform-tenant ("TCT") staff
    # invite has no single tenant to scope to. Not used for row filtering
    # today (lookups are always by token_hash, which is already unique), kept
    # for reporting/audit ("how many invites has tenant X issued").
    tenant_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("tenants.id"), nullable=True, index=True
    )

    # The user this invite is for. Always required -- there is no
    # "invite an email address that has no User row yet" flow here; the
    # caller creates the User row first (pin_hash=None), then the invite.
    user_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("users.id"), nullable=False, index=True
    )

    # sha256 hex digest of the raw token -- see app.services.user_invites
    # module docstring for why this is a plain hash rather than
    # app.core.security.hash_password (bcrypt). Unique: two different raw
    # tokens hashing to the same value is not a real-world concern at
    # sha256's collision resistance, but the constraint also catches a
    # token-generation bug (e.g. accidental reuse of a fixed value in a
    # test) immediately instead of silently corrupting a lookup.
    token_hash: Mapped[str] = mapped_column(String(64), nullable=False, unique=True, index=True)

    # "invite" | "password_reset" -- see INVITE_PURPOSE_* above. Plain string
    # constant, not a DB enum, matching this codebase existing convention for
    # small closed vocabularies (e.g. User.role, User.suitability_status).
    purpose: Mapped[str] = mapped_column(String(20), nullable=False)

    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


__all__ = ["INVITE_PURPOSE_INVITE", "INVITE_PURPOSE_PASSWORD_RESET", "UserInvite"]
