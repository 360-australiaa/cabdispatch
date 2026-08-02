"""geofences (toll/region auto-detection, blueprint 5.2.4/7.2.5)

NOTE: branches from '7b7a31a35ebd' — at least one other concurrent migration
(possibly more) also branches from that same revision. Whoever next runs
`alembic upgrade head` will hit multiple heads and need to `alembic merge`
them; this migration deliberately touches only the `geofences` table and the
new `trips.auto_tolls_applied` column so that merge stays mechanical.

Revision ID: c3f5a08d2e91
Revises: 7b7a31a35ebd
Create Date: 2026-08-02 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'c3f5a08d2e91'
down_revision: Union[str, Sequence[str], None] = '7b7a31a35ebd'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table('geofences',
    sa.Column('id', sa.String(length=36), nullable=False),
    sa.Column('tenant_id', sa.String(length=36), nullable=True),
    sa.Column('name', sa.String(length=255), nullable=False),
    sa.Column('kind', sa.String(length=10), nullable=False),
    sa.Column('center_lat', sa.Float(), nullable=False),
    sa.Column('center_lng', sa.Float(), nullable=False),
    sa.Column('radius_m', sa.Float(), nullable=False),
    sa.Column('toll_amount', sa.Numeric(precision=10, scale=2), nullable=True),
    sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
    sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
    sa.ForeignKeyConstraint(['tenant_id'], ['tenants.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_geofences_kind'), 'geofences', ['kind'], unique=False)
    op.create_index(op.f('ix_geofences_tenant_id'), 'geofences', ['tenant_id'], unique=False)

    # Tracks which toll-kind geofence ids have already had their toll_amount
    # folded into trips.tolls, so PATCH .../tick never double-charges a
    # vehicle lingering in the same zone (see app.services.trips.apply_tick).
    op.add_column('trips', sa.Column('auto_tolls_applied', sa.JSON(), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('trips', 'auto_tolls_applied')

    op.drop_index(op.f('ix_geofences_tenant_id'), table_name='geofences')
    op.drop_index(op.f('ix_geofences_kind'), table_name='geofences')
    op.drop_table('geofences')
