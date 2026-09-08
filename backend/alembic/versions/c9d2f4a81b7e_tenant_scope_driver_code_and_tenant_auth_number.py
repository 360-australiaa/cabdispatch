"""tenant-scope driver_code uniqueness; add tenants.authorization_number

Revision ID: c9d2f4a81b7e
Revises: c5b8e2a71f30
Create Date: 2026-09-08 00:00:00.000000

Two independent, small changes bundled into one revision because both are
part of X2 (tenant self-serve onboarding) and both are pure schema/constraint
changes with no application-code coupling between them.

1. `users.driver_code` uniqueness, platform-wide -> per-tenant.

   Before this revision, `driver_code` carried a bare platform-wide unique
   index (`ix_users_driver_code`, created `unique=True` in
   d613d986df6e_user_driver_code.py). That is a tenancy bug: two different
   taxi networks on this platform could not both hand a driver the code
   "101" — one 6-character credential space shared by every operator, not
   one per operator. `POST /v1/auth/driver-login` already resolves a driver
   by `(tenant_slug, driver_code)` (see app/api/v1/auth.py::driver_login,
   landed ahead of this migration as part of closing the backend-audit §5
   ADDENDUM finding), so the DB constraint was already stricter than what
   the application actually needs or enforces at the query layer.

   DATA SAFETY: narrowing a unique constraint from "unique across the whole
   table" to "unique within (tenant_id, driver_code)" can NEVER conflict on
   existing data — every set of rows that was unique platform-wide is
   trivially unique per-tenant too (a subset of a set with no duplicates has
   no duplicates). No backfill, no row rewrite, no possibility of this
   migration failing on live data. This is a pure widen-the-allowed-set
   change: rows that were previously refused (a second tenant re-using a
   code the first tenant already had) will now be accepted; no existing row
   changes value or is at any risk of collision.

   The old unique index is dropped and replaced by:
     - a new PLAIN (non-unique) index on `driver_code` alone, so an
       operator support lookup by code without a known tenant is still
       indexed (SQLite drops the old index's ordering guarantee for free
       when replaced 1:1 rather than merely un-uniqued, so we drop + recreate
       rather than trying to "downgrade" it in place).
     - a new composite UNIQUE constraint on `(tenant_id, driver_code)` —
       `uq_users_tenant_driver_code`, matching `app/models/user.py::User`'s
       `__table_args__`.

   `driver_code IS NULL` rows (every non-driver user, and any driver created
   before this column existed) are unaffected either way: SQL unique
   constraints/indexes treat NULL as distinct from every other NULL, exactly
   as the old index already did.

2. `tenants.authorization_number` — nullable, additive.

   A7 established that a tenant's operator-authorisation number (e.g. NSW's
   "TSP-448041") is a per-TENANT value whose *label* is jurisdiction-specific
   (see android's domain/TenantBranding.kt, which documents exactly this
   split and is the reason this column exists — the splash screen currently
   compiles "TSP-448041" in as a literal because there was nowhere server-
   side to source it from). This is the TENANT half only: no jurisdiction
   label/column is added here — X1 owns that half of the same seam.

   Nullable, no server_default, no backfill: every existing tenant simply
   has no authorisation number recorded yet until an owner sets one (there
   is no way to derive it from existing data — it is not derivable from
   `abn`/`tsp_number`, which mean distinct regulatory things — see
   app/models/tenant.py's own comment on why `slug` wasn't overloaded onto
   those columns either).

Both use `batch_alter_table` per repo convention (SQLite cannot ALTER a table
to add/drop a constrained column or index in place — see
app/services/fleet.py:1-42).
"""
from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "c9d2f4a81b7e"
down_revision = "c5b8e2a71f30"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("users") as batch:
        batch.drop_index("ix_users_driver_code")
        batch.create_index("ix_users_driver_code", ["driver_code"], unique=False)
        batch.create_unique_constraint(
            "uq_users_tenant_driver_code", ["tenant_id", "driver_code"]
        )

    with op.batch_alter_table("tenants") as batch:
        batch.add_column(sa.Column("authorization_number", sa.String(length=50), nullable=True))


def downgrade() -> None:
    with op.batch_alter_table("tenants") as batch:
        batch.drop_column("authorization_number")

    with op.batch_alter_table("users") as batch:
        batch.drop_constraint("uq_users_tenant_driver_code", type_="unique")
        batch.drop_index("ix_users_driver_code")
        batch.create_index("ix_users_driver_code", ["driver_code"], unique=True)
