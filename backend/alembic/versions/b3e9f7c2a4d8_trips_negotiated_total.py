"""trips negotiated/set-price fixed fare

Revision ID: b3e9f7c2a4d8
Revises: a7f2b8c4d1e6
Create Date: 2026-08-10 00:00:00.000000

Competitor-matching "Set Price" fixed-fare feature: the driver enters a fixed
price before starting the meter; NSW law allows this for pre-arranged/
negotiated fares. Adds a single nullable `negotiated_total` column to `trips`,
reusing the same fare-engine mechanism the pre-existing `airport_fixed` trip
type uses (see app.services.fare_engine.FareState.negotiated_total /
FareEngine.close) - but unlike `airport_fixed`, PSL and tolls still accrue and
add on top of it at close (the competitor's own on-screen disclaimer: "this
price doesn't include levies and/or tolls").

Nullable, no backfill needed - every existing trip simply has no negotiated
total (NULL means "normal metered trip").
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'b3e9f7c2a4d8'
down_revision: Union[str, Sequence[str], None] = 'a7f2b8c4d1e6'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('trips', sa.Column('negotiated_total', sa.Numeric(10, 2), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('trips', 'negotiated_total')
