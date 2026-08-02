"""User model — platform staff, tenant admins/dispatchers, and drivers.

Note: tenant_id is nullable (unlike the standard TenantScopedMixin pattern) because
tenant "0" ("TCT") platform-owner staff are not scoped to any single tenant — they
need cross-tenant access (see require_role/get_current_tenant_id in
app.core.security). All other roles must have a non-null tenant_id in practice;
this is enforced at the application layer, not the schema layer.
"""
from __future__ import annotations

import uuid

from sqlalchemy import ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TimestampMixin

# Roles: owner, admin, dispatcher, driver
ROLE_OWNER = "owner"
ROLE_ADMIN = "admin"
ROLE_DISPATCHER = "dispatcher"
ROLE_DRIVER = "driver"


class User(Base, TimestampMixin):
    __tablename__ = "users"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    # Nullable: tenant "0"/"TCT" owner-role platform staff are not tied to a tenant.
    tenant_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("tenants.id"), nullable=True, index=True
    )
    role: Mapped[str] = mapped_column(String(20), nullable=False, default=ROLE_DRIVER)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    email: Mapped[str] = mapped_column(String(255), nullable=False, unique=True, index=True)
    phone: Mapped[str | None] = mapped_column(String(30), nullable=True)
    driver_licence_no: Mapped[str | None] = mapped_column(String(50), nullable=True)
    wat_endorsed: Mapped[bool] = mapped_column(default=False, nullable=False)
    pin_hash: Mapped[str | None] = mapped_column(String(255), nullable=True)
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="active")
    # MFA (blueprint 12.2): opt-in TOTP, not forced — mfa_enabled defaults to
    # False so every existing seeded/test account keeps logging in with just
    # email+password. mfa_secret is populated by POST /v1/auth/mfa/setup and
    # only takes effect (mfa_enabled=True) once confirmed via
    # POST /v1/auth/mfa/verify. A setup that's never verified leaves a secret
    # sitting here unused, which is fine — it's re-generated on the next setup
    # call and never activates login until verified.
    mfa_secret: Mapped[str | None] = mapped_column(String(64), nullable=True)
    mfa_enabled: Mapped[bool] = mapped_column(default=False, nullable=False)
