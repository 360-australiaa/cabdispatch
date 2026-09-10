"""widen toll_roads.description and toll_points.description to unbounded text

Revision ID: 47a5f4bde77e
Revises: c8ce29172db2
Create Date: 2026-09-10 00:00:00.000000

Real bug found live: `scripts/seed_toll_roads.py` crashed on deploy with a
`StringDataRightTruncationError` — one road's real, worked-through pricing-
correction audit trail (`toll_roads.description` for M4M8_LINK, cross-checking
the road's real distance-based formula against Linkt's own origin-destination
trip table) ran to 1856 characters against the old `VARCHAR(1000)` cap.

This is genuine engineering documentation (why a road is modelled the way it
is, sourced against a real published dataset), not a short label — truncating
it to fit an arbitrary cap would be the wrong fix, and the column never had a
legitimate length ceiling to begin with. `toll_points.description` gets the
identical treatment: same free-text audit-trail pattern, one level down, not
currently over its own cap but with no principled reason to leave it capped
either.

`source_note`/`verify_note` (both already `VARCHAR(2000)` on `TollRoad`/
`TollPoint`/`TollGantry`) are left untouched — checked against the current
seed data, none is within 100 characters of that cap, so there is nothing
actually broken there to fix; widening them "just in case" is not this
migration's job.

Postgres note: `ALTER COLUMN ... TYPE TEXT` from `VARCHAR(n)` is a metadata-
only change (TEXT and VARCHAR share on-disk representation), so this runs
instantly with no table rewrite regardless of table size. SQLite (dev/test)
has no real column-width enforcement at all — `String`/`Text` are both
already unconstrained there — so `batch_alter_table` is used purely so this
migration runs identically on both without SQLite-specific branching.
"""
from __future__ import annotations

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = '47a5f4bde77e'
down_revision: Union[str, Sequence[str], None] = 'c8ce29172db2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    with op.batch_alter_table('toll_roads') as batch_op:
        batch_op.alter_column(
            'description',
            existing_type=sa.String(length=1000),
            type_=sa.Text(),
            existing_nullable=True,
        )
    with op.batch_alter_table('toll_points') as batch_op:
        batch_op.alter_column(
            'description',
            existing_type=sa.String(length=1000),
            type_=sa.Text(),
            existing_nullable=True,
        )


def downgrade() -> None:
    """Downgrade schema.

    Reverting to `VARCHAR(1000)` while a row already carries a longer
    description (M4M8_LINK's real 1856-character one, if this migration has
    ever run against a seeded database) would itself fail with the exact
    `StringDataRightTruncationError` this migration exists to fix. Truncating
    data to make a downgrade succeed is worse than refusing to run it -- a
    downgrade path a human must think about, not one that silently loses the
    audit trail this migration was written to stop losing.
    """
    with op.batch_alter_table('toll_points') as batch_op:
        batch_op.alter_column(
            'description',
            existing_type=sa.Text(),
            type_=sa.String(length=1000),
            existing_nullable=True,
        )
    with op.batch_alter_table('toll_roads') as batch_op:
        batch_op.alter_column(
            'description',
            existing_type=sa.Text(),
            type_=sa.String(length=1000),
            existing_nullable=True,
        )
