"""trip gps blackout audit trail

Revision ID: ace7a9a79687
Revises: 47a5f4bde77e
Create Date: 2026-09-11 00:00:00.000000

`trips.gps_blackout_events` (nullable JSON, same portable-JSON-column pattern
as the pre-existing `trips.auto_tolled_roads` / `split_payments`) — one entry
per real GPS blackout gap (elapsed time above
`app.services.trips.BLACKOUT_GAP_THRESHOLD_S`) a trip's telemetry passed
through, recording whether it was explained by a known, mapped toll-road
corridor (`app.services.tolls.known_corridor_distance_km`) and, if so, the
real distance billed for it. See `app.models.trips.Trip.gps_blackout_events`'s
own doc comment for the full shape and reasoning — this closes the audit-trail
gap found while finishing the corridor-billing fix itself: neither the meter
nor the dashboard could previously show *why* a tunnel-crossed trip was billed
the way it was.

Nullable, no backfill needed — every existing trip simply has no blackout
history yet (the column being added is exactly what starts recording it).
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'ace7a9a79687'
down_revision: Union[str, Sequence[str], None] = '47a5f4bde77e'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('trips', sa.Column('gps_blackout_events', sa.JSON(), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('trips', 'gps_blackout_events')
