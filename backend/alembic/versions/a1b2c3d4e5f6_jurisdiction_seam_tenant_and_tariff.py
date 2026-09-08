"""jurisdiction seam (X1): tenant timezone/currency/jurisdiction/gst_divisor
+ tariff night-window/peak overrides

Revision ID: a1b2c3d4e5f6
Revises: c5b8e2a71f30
Create Date: 2026-09-08 00:00:00.000000

Backend half of the X1 workstream in
`docs/plans/2026-09-08-global-meter-program.md` (Wave 3) — see
`app.services.regions.FareRegion` for the seam this feeds.

`tenants` gains four columns, all with a server_default equal to today's
only real value (NSW/AUD/Australia-Sydney/11), so every existing tenant row
becomes explicitly NSW rather than implicitly NSW — nothing about any
existing tenant's fare behaviour changes:
  - `timezone` (Australia/Sydney)
  - `currency` (AUD)
  - `jurisdiction` (NSW) — `app.services.regions.get_region`'s lookup key
  - `gst_divisor` (11, nullable — a jurisdiction with no GST-equivalent levy
    has none)

`tariffs` gains three NULLABLE per-tariff override columns
(`night_start_hour`, `night_end_hour`, `peak_weekdays`) — NULL on every
existing row, meaning "use the tenant's region default", so no existing
tariff's classification changes either.

batch_alter_table throughout per repo convention (SQLite cannot ALTER a
table to add a constrained/defaulted column in place — see
app/services/fleet.py:1-42).
"""
from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "a1b2c3d4e5f6"
down_revision = "c5b8e2a71f30"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("tenants") as batch:
        batch.add_column(
            sa.Column(
                "timezone", sa.String(length=64), nullable=False, server_default="Australia/Sydney"
            )
        )
        batch.add_column(sa.Column("currency", sa.String(length=3), nullable=False, server_default="AUD"))
        batch.add_column(
            sa.Column("jurisdiction", sa.String(length=20), nullable=False, server_default="NSW")
        )
        batch.add_column(
            sa.Column("gst_divisor", sa.Numeric(10, 4), nullable=True, server_default="11")
        )

    with op.batch_alter_table("tariffs") as batch:
        batch.add_column(sa.Column("night_start_hour", sa.Integer(), nullable=True))
        batch.add_column(sa.Column("night_end_hour", sa.Integer(), nullable=True))
        batch.add_column(sa.Column("peak_weekdays", sa.String(length=20), nullable=True))


def downgrade() -> None:
    with op.batch_alter_table("tariffs") as batch:
        batch.drop_column("peak_weekdays")
        batch.drop_column("night_end_hour")
        batch.drop_column("night_start_hour")

    with op.batch_alter_table("tenants") as batch:
        batch.drop_column("gst_divisor")
        batch.drop_column("jurisdiction")
        batch.drop_column("currency")
        batch.drop_column("timezone")
