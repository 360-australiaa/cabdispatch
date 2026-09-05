"""trip planned destination

Revision ID: a3b5c7d9e1f2
Revises: b2c4d6e8f0a1
Create Date: 2026-09-05 00:00:00.000000

Live Map route-line pass: adds two nullable `planned_dest_lat`/
`planned_dest_lng` columns to `trips`, matching the plain-float convention
already used by the sibling `start_lat`/`start_lng`/`end_lat`/`end_lng`
columns on this model. Distinct from `end_lat`/`end_lng` -- those are the
REAL final stop, written only once at close_trip(); these are the driver's
INTENDED destination while the trip is still open, set via
PATCH /v1/trips/{id}/tick (see app.models.trips.Trip's module docstring
deviation #7 and app.services.trips.apply_tick).

Nullable, no backfill needed - every existing trip simply has no planned
destination recorded (NULL means "none picked").
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'a3b5c7d9e1f2'
down_revision: Union[str, Sequence[str], None] = 'b2c4d6e8f0a1'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('trips', sa.Column('planned_dest_lat', sa.Float(), nullable=True))
    op.add_column('trips', sa.Column('planned_dest_lng', sa.Float(), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('trips', 'planned_dest_lng')
    op.drop_column('trips', 'planned_dest_lat')
