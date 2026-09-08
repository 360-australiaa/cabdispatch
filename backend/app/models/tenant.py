"""Tenant model — one row per taxi network / operator (TSP) on the platform."""
from __future__ import annotations

import re
import uuid
from decimal import Decimal

from sqlalchemy import JSON, Numeric, String, event
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TimestampMixin

# Lifecycle status for a tenant on the platform, set/read only via the
# platform-owner console (see app.services.platform.update_tenant_status /
# PATCH /v1/platform/tenants/{tenant_id}) — an ordinary tenant owner never
# sets this on themselves. Plain string, same portable-enum convention as
# app.models.billing's PLAN_*/STATUS_* constants, not a DB-level enum.
TENANT_STATUS_ACTIVE = "active"
TENANT_STATUS_TRIAL = "trial"
TENANT_STATUS_SUSPENDED = "suspended"
VALID_TENANT_STATUSES = (TENANT_STATUS_ACTIVE, TENANT_STATUS_TRIAL, TENANT_STATUS_SUSPENDED)


class Tenant(Base, TimestampMixin):
    __tablename__ = "tenants"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    # Short, stable, URL-safe public handle for this tenant — the discriminator a
    # client sends when it needs to name a tenant WITHOUT already holding a
    # tenant-scoped token. Today that is exactly one caller: POST
    # /v1/auth/driver-login, where the driver has no token yet and the lookup was
    # previously global across every tenant on the platform (backend audit §5
    # ADDENDUM) — one 6-digit-PIN space shared by the whole platform.
    #
    # Unique platform-wide, because that is the entire point: a slug must resolve
    # to exactly one tenant. Nullable in the column definition only so the
    # accompanying migration can add it to a live table and backfill it in the
    # same revision; the `before_insert` listener below means no NEW row can be
    # created without one, so application code may treat it as present.
    slug: Mapped[str | None] = mapped_column(String(64), nullable=True, unique=True, index=True)
    abn: Mapped[str | None] = mapped_column(String(20), nullable=True)
    tsp_number: Mapped[str | None] = mapped_column(String(50), nullable=True)
    bsp_number: Mapped[str | None] = mapped_column(String(50), nullable=True)
    theme_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    plan: Mapped[str] = mapped_column(String(50), nullable=False, default="standard")
    # Every pre-existing tenant row (created before this column existed)
    # defaults to "active" via the server_default in the accompanying
    # migration — no backfill needed, and no tenant silently becomes
    # unusable just because this column was added.
    status: Mapped[str] = mapped_column(String(20), nullable=False, server_default=TENANT_STATUS_ACTIVE)
    stripe_acct_id: Mapped[str | None] = mapped_column(String(255), nullable=True)
    # Hashed (bcrypt via app.core.security.hash_password, same scheme as
    # User.pin_hash) admin PIN gating destructive on-device actions (e.g. the
    # Android app's factory-reset flow — see
    # au...SettingsViewModel.ADMIN_PIN_PLACEHOLDER). Nullable: a tenant that
    # hasn't set one yet simply can't use PIN-gated device actions until an
    # owner sets it via POST /v1/tenants/{id}/admin-pin — see
    # app/api/v1/tenants.py and app/services/tenant.py. Never sent to a
    # device; devices call POST /v1/fleet/devices/{id}/verify-admin-pin
    # instead, which checks the PIN server-side and returns a bool.
    admin_pin_hash: Mapped[str | None] = mapped_column(String(255), nullable=True)

    # --- jurisdiction seam (X1, docs/plans/2026-09-08-global-meter-program.md
    # Wave 3) — the tenant-level facts app.services.regions.FareRegion needs
    # to resolve which jurisdiction's fare-time/money rules apply to this
    # tenant. Defaulted server-side so every existing row (created before
    # these columns existed) becomes NSW/AUD/Australia-Sydney/11 exactly —
    # see the accompanying migration; nothing changes for NSW.
    #
    # `jurisdiction` is deliberately a plain string, not a FK to a
    # jurisdictions table: app.services.regions has no such table yet (see
    # that package's module docstring, "What this is not (yet)") — this is
    # the seam a real per-jurisdiction data model would key off, not that
    # model itself.
    timezone: Mapped[str] = mapped_column(
        String(64), nullable=False, server_default="Australia/Sydney"
    )
    currency: Mapped[str] = mapped_column(String(3), nullable=False, server_default="AUD")
    jurisdiction: Mapped[str] = mapped_column(String(20), nullable=False, server_default="NSW")
    # Divisor applied to a GST(-equivalent)-inclusive grand total to report
    # its tax component (see app.services.fare_engine.FareEngine.close).
    # Nullable: a jurisdiction that levies no such tax has none — see
    # app.services.regions.FareRegion.gst_divisor's own doc for why that
    # must render as "no figure" rather than a fabricated 0.
    gst_divisor: Mapped[Decimal | None] = mapped_column(
        Numeric(10, 4), nullable=True, server_default="11"
    )


def slugify_tenant_name(name: str, *, fallback: str | None = None) -> str:
    """Derives a URL-safe slug from a tenant's display name.

    Deliberately conservative and ASCII-only: lowercase, non-alphanumerics
    collapsed to single hyphens, trimmed to 64 characters (the column width).
    A name that reduces to nothing (all punctuation, or a non-Latin script with
    no ASCII residue) falls back to `fallback` -- in practice the tenant's UUID
    -- so this can never return an empty slug, which would be a slug matching
    nothing that then collides with the next such tenant.

    Uniqueness is NOT enforced here; that is the caller's job (the migration
    de-duplicates its backfill, the `before_insert` listener appends a UUID
    fragment). The unique index on the column is the real guarantee.
    """
    slug = re.sub(r"[^a-z0-9]+", "-", (name or "").lower()).strip("-")[:64].strip("-")
    if slug:
        return slug
    return (fallback or str(uuid.uuid4()))[:64]


@event.listens_for(Tenant, "before_insert")
def _ensure_tenant_slug(mapper, connection, target: Tenant) -> None:
    """Guarantees every newly inserted tenant has a slug, without every caller
    (POST /v1/platform/tenants, scripts/seed.py, a dozen test fixtures) having to
    remember to set one -- none of which this workstream owns.

    A short UUID fragment is appended so two tenants both called "City Cabs" do
    not collide on the unique index: the slug stays human-recognisable while the
    suffix keeps it unique. A slug supplied explicitly by the caller is left
    exactly as given.
    """
    if getattr(target, "slug", None):
        return
    base = slugify_tenant_name(target.name, fallback=target.id or str(uuid.uuid4()))
    suffix = uuid.uuid4().hex[:8]
    target.slug = f"{base[: 64 - len(suffix) - 1]}-{suffix}"
