"""toll_gantries: real chain sequence_position / cumulative_distance_km

Revision ID: acd836fc4fe7
Revises: c774793b6619
Create Date: 2026-09-09 00:00:00.000000

Adds two nullable columns to `toll_gantries` — `sequence_position` (int) and
`cumulative_distance_km` (numeric(8,3)) — so `TollGantry` can carry a REAL
position/distance along its own road, sourced from the official NSW toll
CartoDB source's `tollpoints_data` table (a genuine ordered point-chain with
real point-to-point distances) rather than the chord-between-any-two-gantries
APPROXIMATION `app.services.tolls._distance_to_road_corridor_m` has always
had to use, because `app/data/nsw_toll_gantries.csv` itself carries no path
order at all. See `app.models.toll.TollGantry`'s own docstring for the exact
semantics, and `app.services.tolls`'s module docstring for how the new
columns are consumed (a road with no real chain data — every column NULL —
keeps using the old chord approximation unchanged).

PURE SCHEMA CHANGE, no data backfill here: unlike `c774793b6619` (which had
to de-duplicate pre-existing rows before its new constraint could even be
created), nothing about adding two nullable columns requires touching
existing rows, and the actual VALUES for the roads whose real chain data
resolves cleanly are sourced facts, not something this migration can derive
on its own — they are populated by `scripts/seed_toll_roads.py` (the
existing, tested, idempotent loader for every other real fact this table
carries), from its own `_REAL_ROAD_CHAIN_WAYPOINTS` table. Every row a
pre-existing database already has simply gets these two new columns as NULL
until the next `python scripts/seed_toll_roads.py` run populates the roads
that qualify — exactly the same "add the column, backfill via the loader"
shape `c5f5e2009707` (driver_availability_last_position) already used for a
similar real-data column.
"""
from __future__ import annotations

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = 'acd836fc4fe7'
down_revision: Union[str, Sequence[str], None] = 'c774793b6619'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('toll_gantries', sa.Column('sequence_position', sa.Integer(), nullable=True))
    op.add_column(
        'toll_gantries',
        sa.Column('cumulative_distance_km', sa.Numeric(8, 3), nullable=True),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('toll_gantries', 'cumulative_distance_km')
    op.drop_column('toll_gantries', 'sequence_position')
