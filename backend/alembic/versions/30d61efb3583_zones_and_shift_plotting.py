"""zones and shift plotting

Revision ID: 30d61efb3583
Revises: c44854bb476b
Create Date: 2026-08-10 00:00:00.000000

Named dispatch zones + live demand statistics (matching a screen on a real
competitor taxi meter, MTI): a new `zones` table (see app.models.zones.Zone
-- circular geofence-style zones with a driver-facing short `number` code,
scoped by tenant_id, unique per (tenant_id, number)), plus two new nullable
columns on the existing `shifts` table -- `plotted_zone_id` /
`plotted_at` -- for "plot into a zone" (a driver marking themselves as
actively waiting in a specific zone, distinct from just being on shift; see
app.models.shift.Shift's DEVIATION note for why this lives on shifts rather
than a new table).

NOTE: this revision was hand-written (not `alembic revision --autogenerate`)
because the local dev.db's `alembic_version` table was already out of sync
with its actual schema (pre-existing environment state, unrelated to this
change) -- autogenerate against it failed with "Target database is not up to
date". Written by hand from app.models.zones.Zone and the shift-model diff
instead, following the exact column/index/constraint shapes those models
declare.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '30d61efb3583'
down_revision: Union[str, Sequence[str], None] = 'c44854bb476b'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'zones',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('tenant_id', sa.String(length=36), nullable=False),
        sa.Column('name', sa.String(length=255), nullable=False),
        sa.Column('number', sa.String(length=10), nullable=False),
        sa.Column('center_lat', sa.Float(), nullable=False),
        sa.Column('center_lng', sa.Float(), nullable=False),
        sa.Column('radius_m', sa.Float(), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('CURRENT_TIMESTAMP'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('CURRENT_TIMESTAMP'), nullable=False),
        sa.ForeignKeyConstraint(['tenant_id'], ['tenants.id']),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('tenant_id', 'number', name='uq_zones_tenant_number'),
    )
    op.create_index(op.f('ix_zones_tenant_id'), 'zones', ['tenant_id'], unique=False)
    op.create_index(op.f('ix_zones_number'), 'zones', ['number'], unique=False)

    with op.batch_alter_table('shifts') as batch_op:
        batch_op.add_column(sa.Column('plotted_zone_id', sa.String(length=36), nullable=True))
        batch_op.add_column(sa.Column('plotted_at', sa.DateTime(timezone=True), nullable=True))
        batch_op.create_index(batch_op.f('ix_shifts_plotted_zone_id'), ['plotted_zone_id'], unique=False)


def downgrade() -> None:
    """Downgrade schema."""
    with op.batch_alter_table('shifts') as batch_op:
        batch_op.drop_index(batch_op.f('ix_shifts_plotted_zone_id'))
        batch_op.drop_column('plotted_at')
        batch_op.drop_column('plotted_zone_id')

    op.drop_index(op.f('ix_zones_number'), table_name='zones')
    op.drop_index(op.f('ix_zones_tenant_id'), table_name='zones')
    op.drop_table('zones')
