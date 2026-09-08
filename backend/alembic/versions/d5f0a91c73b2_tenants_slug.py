"""tenants.slug — a public handle so driver-login can be scoped to one tenant

Revision ID: d5f0a91c73b2
Revises: c7a4e2b8f13d
Create Date: 2026-09-08 00:00:00.000000

`POST /v1/auth/driver-login` resolved a driver with
`select(User).where(User.driver_code == ...)` and NO tenant filter (backend
audit §5 ADDENDUM) — a single 6-digit-PIN credential space shared across every
tenant on the platform, and the endpoint had no rate limiting either. Scoping
that lookup needs the client to name a tenant, and the client holds no token at
that point in the flow, so it needs a public, stable, URL-safe handle. `id` is a
UUID (unusable as something a driver's tablet is configured with) and `name` is
free text an owner can rename. Hence a real `slug` column, rather than
overloading `abn`/`tsp_number`, which mean specific regulatory things.

Backfill: derived from `name`, matching `app.models.tenant.slugify_tenant_name`
(lowercase, non-alphanumerics to hyphens, 64 chars). Because two tenants may
share a name — and because a name may reduce to an empty string — the backfill
appends the first 8 characters of the tenant's UUID whenever the base slug would
otherwise be a duplicate or empty. Every existing row therefore ends with a
non-null, unique slug, and the unique index below can be created safely.

The column stays nullable at the DB level: making it NOT NULL would require a
table rebuild under SQLite for no safety gain, since the `before_insert`
listener in app/models/tenant.py already guarantees every new row has one.
Uniqueness — the property the driver-login lookup actually depends on — is
enforced by the index.
"""
from __future__ import annotations

import re
import uuid

import sqlalchemy as sa

from alembic import op

revision = "d5f0a91c73b2"
down_revision = "c7a4e2b8f13d"
branch_labels = None
depends_on = None


def _slugify(name: str) -> str:
    """Kept in sync with app.models.tenant.slugify_tenant_name. Duplicated here
    on purpose: a migration must not import application code, which may have
    moved on several releases by the time this revision is replayed."""
    return re.sub(r"[^a-z0-9]+", "-", (name or "").lower()).strip("-")[:64].strip("-")


def upgrade() -> None:
    # batch_alter_table per repo convention — SQLite cannot ALTER a table to add
    # a constrained column in place (see app/services/fleet.py:1-42).
    with op.batch_alter_table("tenants") as batch:
        batch.add_column(sa.Column("slug", sa.String(length=64), nullable=True))

    conn = op.get_bind()
    rows = conn.execute(sa.text("SELECT id, name FROM tenants")).fetchall()

    seen: set[str] = set()
    for row in rows:
        tenant_id = row[0]
        base = _slugify(row[1] or "") or (tenant_id or str(uuid.uuid4()))[:64]
        slug = base
        if slug in seen:
            suffix = (tenant_id or uuid.uuid4().hex)[:8]
            slug = f"{base[: 64 - len(suffix) - 1]}-{suffix}"
        # Pathological case: same name AND same id prefix. Loop until unique
        # rather than letting the index creation below fail on live data.
        while slug in seen:
            suffix = uuid.uuid4().hex[:8]
            slug = f"{base[: 64 - len(suffix) - 1]}-{suffix}"
        seen.add(slug)
        conn.execute(
            sa.text("UPDATE tenants SET slug = :slug WHERE id = :id"),
            {"slug": slug, "id": tenant_id},
        )

    with op.batch_alter_table("tenants") as batch:
        batch.create_index("ix_tenants_slug", ["slug"], unique=True)


def downgrade() -> None:
    with op.batch_alter_table("tenants") as batch:
        batch.drop_index("ix_tenants_slug")
        batch.drop_column("slug")
