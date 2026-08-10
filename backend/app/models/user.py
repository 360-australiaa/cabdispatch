"""User model — platform staff, tenant admins/dispatchers, and drivers.

Note: tenant_id is nullable (unlike the standard TenantScopedMixin pattern) because
tenant "0" ("TCT") platform-owner staff are not scoped to any single tenant — they
need cross-tenant access (see require_role/get_current_tenant_id in
app.core.security). All other roles must have a non-null tenant_id in practice;
this is enforced at the application layer, not the schema layer.
"""
from __future__ import annotations

import uuid
from datetime import date

from sqlalchemy import Date, ForeignKey, String
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
    # Driver PIN-login identifier for POST /v1/auth/driver-login (see
    # app/api/v1/auth.py). Short/memorable so it can be keyed in by hand on a
    # meter/kiosk; only issued to role == "driver" (see
    # app/services/user.py::generate_unique_driver_code). Globally unique —
    # like User.email, not per-tenant — because driver-login has no tenant
    # context to scope a lookup by (see
    # app/services/user.py::assert_driver_code_available for the same
    # reasoning already established for email by assert_email_available).
    driver_code: Mapped[str | None] = mapped_column(String(6), nullable=True, unique=True, index=True)
    # MFA (blueprint 12.2): opt-in TOTP, not forced — mfa_enabled defaults to
    # False so every existing seeded/test account keeps logging in with just
    # email+password. mfa_secret is populated by POST /v1/auth/mfa/setup and
    # only takes effect (mfa_enabled=True) once confirmed via
    # POST /v1/auth/mfa/verify. A setup that's never verified leaves a secret
    # sitting here unused, which is fine — it's re-generated on the next setup
    # call and never activates login until verified.
    mfa_secret: Mapped[str | None] = mapped_column(String(64), nullable=True)
    mfa_enabled: Mapped[bool] = mapped_column(default=False, nullable=False)

    # Driver compliance-expiry tracking (blueprint 7.2.3/10.1). Nullable, no
    # server_default — every existing seeded/test user simply has neither set
    # yet. Null means "unknown", NOT "expired": app.services.compliance_expiry
    # and POST /v1/auth/driver-login both fail OPEN on a null value here,
    # matching this codebase's existing treatment of nullable compliance-ish
    # fields (e.g. driver_licence_no above is optional and never blocks
    # anything). Only a real, in-the-past driver_license_expiry blocks
    # driver-login (see app/api/v1/auth.py::driver_login); a real, in-the-past
    # driver_authority_expiry does not block login in this pass — the task's
    # blueprint reference (5.2.1) calls out license expiry specifically, not
    # authority — it only ever raises a FatigueAlert (see
    # app.services.compliance_expiry).
    driver_license_expiry: Mapped[date | None] = mapped_column(Date, nullable=True)
    driver_authority_expiry: Mapped[date | None] = mapped_column(Date, nullable=True)

    # Driver/staff photo. Nullable -- most existing seeded/test users have
    # none. Stores a relative (to BACKEND_ROOT) on-disk path, same local-disk-
    # upload convention as app.models.compliance.ComplianceDocument.file_path
    # -- see app.services.user's photo-storage helpers, which mirror
    # app.services.compliance's exactly. Populated by
    # POST /v1/users/{id}/photo, served by GET /v1/users/{id}/photo (see
    # app/api/v1/users.py) -- closes a real gap: a monitoring partner
    # receiving a duress alarm needs to see the driver's photo to verify
    # identity.
    photo_url: Mapped[str | None] = mapped_column(String(500), nullable=True)
