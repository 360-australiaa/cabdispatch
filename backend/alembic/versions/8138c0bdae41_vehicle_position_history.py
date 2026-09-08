"""vehicle position history

Revision ID: 8138c0bdae41
Revises: a3b5c7d9e1f2
Create Date: 2026-09-05 18:08:59.761444

New append-only `vehicle_position_history` table (dispatcher-replay pass) --
the FIRST durable table the live-ops domain's position pipeline has ever
written to. See `app.models.fleet.VehiclePositionHistory`'s own docstring for
the full rationale: `POST /v1/fleet/positions` has, until this pass, been
purely in-process pub/sub with an in-memory "latest position" cache (see
`app.services.live_ops`'s module docstring) -- nothing survived a process
restart, and nothing let a dispatcher scrub back through a vehicle's last few
hours of real positions. That in-memory cache is UNCHANGED by this migration;
this table is additive, feeding a new `GET
/v1/vehicles/{vehicle_id}/position-history` read path (see
`app.services.live_ops.get_position_history`), not a replacement for
anything.

Modeled directly on the sibling `device_version_history` table (see
`32b7662e9362_driver_photo_and_device_version_history.py`) -- same
append-only shape, own purpose-built `recorded_at` timestamp column, no
`updated_at`. `lat`/`lng`/`status` are not nullable (every publish carries
them); `speed_kmh`/`heading` are nullable, same honest-null convention as
`PositionRead.speed_kmh`/`heading` (not every publish reports them).

Retention (how long a row survives before being lazily pruned on the next
write for that vehicle) is enforced in application code
(`app.services.live_ops.POSITION_HISTORY_RETENTION_HOURS`), not by anything
in this migration -- there is no scheduled/cron cleanup job anywhere in this
backend (see that constant's own doc comment for why the retention window
itself is a technical default, not a decided data-retention policy).
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '8138c0bdae41'
down_revision: Union[str, Sequence[str], None] = 'a3b5c7d9e1f2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'vehicle_position_history',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('tenant_id', sa.String(length=36), nullable=False),
        sa.Column('vehicle_id', sa.String(length=36), nullable=False),
        sa.Column('lat', sa.Float(), nullable=False),
        sa.Column('lng', sa.Float(), nullable=False),
        sa.Column('speed_kmh', sa.Float(), nullable=True),
        sa.Column('heading', sa.Float(), nullable=True),
        sa.Column('status', sa.String(length=20), nullable=False),
        sa.Column('recorded_at', sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(['tenant_id'], ['tenants.id']),
        sa.ForeignKeyConstraint(['vehicle_id'], ['vehicles.id']),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index(
        op.f('ix_vehicle_position_history_tenant_id'), 'vehicle_position_history', ['tenant_id'], unique=False
    )
    op.create_index(
        op.f('ix_vehicle_position_history_vehicle_id'), 'vehicle_position_history', ['vehicle_id'], unique=False
    )
    op.create_index(
        op.f('ix_vehicle_position_history_recorded_at'), 'vehicle_position_history', ['recorded_at'], unique=False
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f('ix_vehicle_position_history_recorded_at'), table_name='vehicle_position_history')
    op.drop_index(op.f('ix_vehicle_position_history_vehicle_id'), table_name='vehicle_position_history')
    op.drop_index(op.f('ix_vehicle_position_history_tenant_id'), table_name='vehicle_position_history')
    op.drop_table('vehicle_position_history')
