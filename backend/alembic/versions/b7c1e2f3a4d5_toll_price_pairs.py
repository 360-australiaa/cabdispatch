"""toll_price_pairs: Linkt entry-point -> exit-point prices

Revision ID: b7c1e2f3a4d5
Revises: d2e4f6a8b0c1
Create Date: 2026-09-15 00:00:00.000000

Adds `toll_price_pairs` (see `app.models.toll.TollPricePair`): the owner's 2026-09-15 decision to
bill every NSW toll road at Linkt's own entry-point -> exit-point price instead of a per-road
formula. Purely additive: a new table with foreign keys to `toll_gantries`, no data change --
`scripts/seed_toll_roads.py` fills it from `app/data/linkt_nsw_pricing.json`.
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "b7c1e2f3a4d5"
down_revision = "d2e4f6a8b0c1"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "toll_price_pairs",
        sa.Column("id", sa.String(length=300), nullable=False),
        sa.Column("entry_gantry_id", sa.String(length=128), nullable=False),
        sa.Column("exit_gantry_id", sa.String(length=128), nullable=False),
        sa.Column("billing_asset_id", sa.String(length=16), nullable=False),
        sa.Column("billing_name", sa.String(length=128), nullable=False),
        sa.Column("class_a_bands", sa.JSON(), nullable=False),
        sa.Column("class_b_bands", sa.JSON(), nullable=False),
        sa.Column("effective_date", sa.Date(), nullable=False),
        sa.Column("source_url", sa.String(length=500), nullable=True),
        sa.Column("retrieved_at", sa.Date(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("(CURRENT_TIMESTAMP)"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("(CURRENT_TIMESTAMP)"), nullable=False),
        sa.ForeignKeyConstraint(["entry_gantry_id"], ["toll_gantries.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["exit_gantry_id"], ["toll_gantries.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_toll_price_pairs_entry_gantry_id", "toll_price_pairs", ["entry_gantry_id"])
    op.create_index("ix_toll_price_pairs_exit_gantry_id", "toll_price_pairs", ["exit_gantry_id"])


def downgrade() -> None:
    op.drop_index("ix_toll_price_pairs_exit_gantry_id", table_name="toll_price_pairs")
    op.drop_index("ix_toll_price_pairs_entry_gantry_id", table_name="toll_price_pairs")
    op.drop_table("toll_price_pairs")
