"""driver availability last position (proximity-nearest-first job matching)

Revision ID: c5f5e2009707
Revises: 97da879e0540
Create Date: 2026-08-27 00:00:00.000000

Closes the "v1 is broadcast-to-everyone, first-accept-wins" gap flagged in
PROJECT_HANDOFF.md by adding the last-known-position columns
`app.services.jobs.create_job_and_broadcast` needs to rank offers
nearest-driver-first instead of fanning out in arbitrary order. Adds three
nullable columns to the existing `driver_availability` table -- `last_lat`,
`last_lng` (Float), `last_position_at` (DateTime, timezone-aware) -- persisted
by `app.services.live_ops.publish_position` alongside its existing broadcast,
whenever a position is published for a vehicle whose currently-assigned
driver already has a `driver_availability` row. All nullable, no
server_default needed -- every existing row simply has no last-known position
yet; NULL means "unknown position", not "at 0,0" (see
`app.models.jobs.DriverAvailability`'s doc comment). No Boolean columns here,
so the Postgres integer-default-on-boolean pitfall (see 6b8e6598e086 /
a7f2b8c4d1e6) does not apply, but Float/DateTime server_defaults are avoided
here anyway since none are needed.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'c5f5e2009707'
down_revision: Union[str, Sequence[str], None] = '97da879e0540'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('driver_availability', sa.Column('last_lat', sa.Float(), nullable=True))
    op.add_column('driver_availability', sa.Column('last_lng', sa.Float(), nullable=True))
    op.add_column(
        'driver_availability', sa.Column('last_position_at', sa.DateTime(timezone=True), nullable=True)
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('driver_availability', 'last_position_at')
    op.drop_column('driver_availability', 'last_lng')
    op.drop_column('driver_availability', 'last_lat')
