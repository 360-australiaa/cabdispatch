"""trips.toll_confirmed_gantries: corroboration evidence for auto-tolls

Revision ID: d8f2a61c9047
Revises: c41d7be90a52
Create Date: 2026-09-07 00:00:00.000000

Adds `trips.toll_confirmed_gantries` -- road id -> the ids of that road's gantries
a trip actually passed within `app.services.tolls.TOLL_CONFIRM_RADIUS_M` of.

Why it has to be a column and not a local: automatic toll detection is
incremental, one telemetry point at a time, and the two confirmations that
corroborate a road can arrive minutes apart. See the model field's own comment,
and `TOLL_CONFIRM_RADIUS_M` for the field report behind the whole rule -- vehicles
on service roads and on surface streets above tunnel gantries were being charged
for roads they never entered, because a single fix inside the 150m detection
radius was enough to bill them.

Purely additive and safe on a live database: nullable JSON with no backfill. An
existing trip has no recorded confirmations, which is correct -- it was priced
under the old rule and its stored `tolls` is not re-derived by this migration.
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "d8f2a61c9047"
down_revision = "c41d7be90a52"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("trips", sa.Column("toll_confirmed_gantries", sa.JSON(), nullable=True))


def downgrade() -> None:
    op.drop_column("trips", "toll_confirmed_gantries")
