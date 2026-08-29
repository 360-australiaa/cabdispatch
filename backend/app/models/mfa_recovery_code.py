"""MfaRecoveryCode model -- one-time backup codes for TOTP MFA (plan Part 4
Phase 1 WP-16, docs/ARCHITECTURE_TENANCY_FLEET_COMPLIANCE.md I-5).

Mirrors app.models.user_invite own token-hash-only-at-rest pattern: the
plaintext code is returned to the caller exactly once, in the
POST /v1/auth/mfa/verify response, and never persisted or logged -- only its
hash (see app.services.mfa_recovery_codes._hash_code) is stored here.

NOT YET MIGRATED: per this work package instructions, no alembic revision was
generated for this table. It exists on Base.metadata (see
app/models/__init__.py) so it is created automatically in tests and any
fresh Base.metadata.create_all environment, but a real deployment needs the
phase integrator agent to generate and apply the actual migration before
this feature is usable against Postgres.
"""
from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TimestampMixin


class MfaRecoveryCode(Base, TimestampMixin):
    __tablename__ = "mfa_recovery_codes"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False, index=True)
    code_hash: Mapped[str] = mapped_column(String(64), nullable=False, unique=True, index=True)
    used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


__all__ = ["MfaRecoveryCode"]
