"""trip passenger count, wheelchair hiring, airport rank requested maxi

Revision ID: 2bc9163321d2
Revises: 0201776e9446
Create Date: 2026-09-02 19:57:33.541550

Part of the NSW Point to Point Transport (Fares) Order 2026 compliance pass
(see app.services.fare_engine's module docstring). Three new columns on
`trips` feeding `FareState.maxi_applied`'s derivation — `trips.maxi` itself
is unchanged in shape (still a plain boolean) but is now resolved
server-side from the vehicle's real `vehicle_class` at trip-creation time
(see app.services.trips.resolve_is_maxi_vehicle) rather than trusted from a
raw client-supplied flag.

All three default to the "not a maxi trip" baseline (passenger_count=1,
both booleans false) so every existing trip backfills to exactly what it
already implicitly was — no real historical trip's fare classification
changes. sa.false() (not sa.text('0')) for the same reason as
a7f2b8c4d1e6's own note: '0' is a SQLite-only boolean literal, Postgres
rejects an integer-typed default on a BOOLEAN column outright.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '2bc9163321d2'
down_revision: Union[str, Sequence[str], None] = '0201776e9446'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column(
        'trips', sa.Column('passenger_count', sa.Integer(), server_default=sa.text('1'), nullable=False)
    )
    op.add_column(
        'trips', sa.Column('wheelchair_hiring', sa.Boolean(), server_default=sa.false(), nullable=False)
    )
    op.add_column(
        'trips',
        sa.Column('airport_rank_requested_maxi', sa.Boolean(), server_default=sa.false(), nullable=False),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('trips', 'airport_rank_requested_maxi')
    op.drop_column('trips', 'wheelchair_hiring')
    op.drop_column('trips', 'passenger_count')
