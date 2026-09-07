"""nsw toll road registry (real per-road pricing + 141 gantries)

Revision ID: a9c1f4e7d2b8
Revises: 6ce5e71ba25d
Create Date: 2026-09-07 00:00:00.000000

Adds the real NSW toll-road registry (`app.models.toll`: `toll_roads`,
`toll_road_price_revisions`, `toll_gantries`) that replaces the old
`geofences(kind='toll')` flat-circle-with-one-amount model for the 13 real
NSW toll roads (see `app.services.tolls` / `scripts/seed_toll_roads.py` for
why one circle-and-one-amount cannot represent a road tolled by many
gantries, a directional road, a zone_flat/distance/time_of_day pricing
model, or a quarterly price reindexation).

Two things happen here:

1. Schema: three new tables, plus three new `trips` columns
   (`auto_tolled_roads` / `toll_road_progress` / `unpriced_toll_road_ids`) —
   see `app.models.trips.Trip`'s doc comments on each. The pre-existing
   `trips.auto_tolls_applied` column is untouched: it keeps tracking
   tenant-defined AD HOC toll geofences, a genuinely separate, still-live
   mechanism (`app.services.geofence`).

2. Data cleanup: deletes the 9 old platform-wide (`tenant_id IS NULL`)
   `kind='toll'` reference geofences `scripts/seed.py` used to seed for
   these same 13 real roads (matched by their exact seeded names) — leaving
   them in place would double-charge every trip that crosses one of these
   roads, once from the old flat-circle mechanism and again from the new
   per-road registry. The one seeded `kind='region'` row (the Sydney Airport
   precinct) is untouched -- it was never a toll and is unrelated to this
   pass. Matched by name (not blanket-deleted by kind) so a real deployment
   that already created its OWN unrelated ad hoc toll circle keeps it.
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = 'a9c1f4e7d2b8'
down_revision: Union[str, Sequence[str], None] = '6ce5e71ba25d'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

# The exact `name` values scripts/seed.py used for the 9 GLOBAL_GEOFENCES
# toll-kind rows this registry supersedes (see that script's own history) --
# deleted below ONLY when tenant_id IS NULL AND kind='toll', so a coincidence
# match against a real tenant-owned or region-kind row is impossible.
_SUPERSEDED_GLOBAL_TOLL_GEOFENCE_NAMES = (
    "M5 East Motorway — Sydney entry (approx.)",
    "Sydney Harbour Bridge / Tunnel (approx.)",
    "Eastern Distributor — Moore Park toll point (approx.)",
    "Cross City Tunnel — main tunnel, city centre (approx.)",
    "Lane Cove Tunnel (approx.)",
    "M2 Hills Motorway — North Ryde mainline toll point (approx.)",
    "WestConnex M4/M8 (approx., flat-rate approximation of a distance-based toll)",
    "M7 Westlink (approx., capped full-length rate of a distance-based toll)",
    "NorthConnex (approx.)",
)


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'toll_roads',
        sa.Column('id', sa.String(length=32), nullable=False),
        sa.Column('api_code', sa.String(length=16), nullable=True),
        sa.Column('name', sa.String(length=255), nullable=False),
        sa.Column('operator', sa.String(length=255), nullable=True),
        sa.Column('pricing_model', sa.String(length=32), nullable=False),
        sa.Column('directional', sa.String(length=32), nullable=True),
        sa.Column('description', sa.String(length=1000), nullable=True),
        sa.Column('derived_corridor_km', sa.Numeric(precision=8, scale=3), nullable=True),
        sa.Column('source_note', sa.String(length=2000), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index(op.f('ix_toll_roads_pricing_model'), 'toll_roads', ['pricing_model'], unique=False)

    op.create_table(
        'toll_road_price_revisions',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('toll_road_id', sa.String(length=32), nullable=False),
        sa.Column('price_class_a_min', sa.Numeric(precision=10, scale=2), nullable=True),
        sa.Column('price_class_a_max', sa.Numeric(precision=10, scale=2), nullable=True),
        sa.Column('price_class_b_min', sa.Numeric(precision=10, scale=2), nullable=True),
        sa.Column('price_class_b_max', sa.Numeric(precision=10, scale=2), nullable=True),
        sa.Column('cap_class_a', sa.Numeric(precision=10, scale=2), nullable=True),
        sa.Column('cap_class_b', sa.Numeric(precision=10, scale=2), nullable=True),
        sa.Column('time_of_day_rates_class_a', sa.JSON(), nullable=True),
        sa.Column('currency', sa.String(length=3), nullable=False),
        sa.Column('gst_included', sa.Boolean(), nullable=False),
        sa.Column('effective_date', sa.Date(), nullable=False),
        sa.Column('indexation', sa.String(length=32), nullable=False),
        sa.Column('confidence', sa.String(length=32), nullable=False),
        sa.Column('verify_note', sa.String(length=2000), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.ForeignKeyConstraint(['toll_road_id'], ['toll_roads.id'], ),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index(
        op.f('ix_toll_road_price_revisions_toll_road_id'), 'toll_road_price_revisions', ['toll_road_id'], unique=False
    )
    op.create_index(
        op.f('ix_toll_road_price_revisions_effective_date'), 'toll_road_price_revisions', ['effective_date'], unique=False
    )

    op.create_table(
        'toll_gantries',
        sa.Column('id', sa.String(length=128), nullable=False),
        sa.Column('toll_road_id', sa.String(length=32), nullable=False),
        sa.Column('location', sa.String(length=255), nullable=False),
        sa.Column('ramp', sa.String(length=16), nullable=True),
        sa.Column('direction', sa.String(length=16), nullable=True),
        sa.Column('latitude', sa.Float(), nullable=False),
        sa.Column('longitude', sa.Float(), nullable=False),
        sa.Column('source_sheet', sa.String(length=32), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.ForeignKeyConstraint(['toll_road_id'], ['toll_roads.id'], ),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index(op.f('ix_toll_gantries_toll_road_id'), 'toll_gantries', ['toll_road_id'], unique=False)

    op.add_column('trips', sa.Column('auto_tolled_roads', sa.JSON(), nullable=True))
    op.add_column('trips', sa.Column('toll_road_progress', sa.JSON(), nullable=True))
    op.add_column('trips', sa.Column('unpriced_toll_road_ids', sa.JSON(), nullable=True))

    # --- data cleanup: supersede the old flat-circle reference geofences ----
    geofences = sa.table(
        'geofences',
        sa.column('tenant_id', sa.String),
        sa.column('kind', sa.String),
        sa.column('name', sa.String),
    )
    op.execute(
        geofences.delete().where(
            geofences.c.tenant_id.is_(None),
            geofences.c.kind == 'toll',
            geofences.c.name.in_(_SUPERSEDED_GLOBAL_TOLL_GEOFENCE_NAMES),
        )
    )


def downgrade() -> None:
    """Downgrade schema. Note: the deleted legacy reference geofences (see
    upgrade()'s data-cleanup step) are NOT restored -- downgrading schema
    cannot un-delete data that was deliberately superseded; re-run
    `scripts/seed.py`'s prior version (or restore from backup) if that's
    genuinely needed."""
    op.drop_column('trips', 'unpriced_toll_road_ids')
    op.drop_column('trips', 'toll_road_progress')
    op.drop_column('trips', 'auto_tolled_roads')

    op.drop_index(op.f('ix_toll_gantries_toll_road_id'), table_name='toll_gantries')
    op.drop_table('toll_gantries')

    op.drop_index(op.f('ix_toll_road_price_revisions_effective_date'), table_name='toll_road_price_revisions')
    op.drop_index(op.f('ix_toll_road_price_revisions_toll_road_id'), table_name='toll_road_price_revisions')
    op.drop_table('toll_road_price_revisions')

    op.drop_index(op.f('ix_toll_roads_pricing_model'), table_name='toll_roads')
    op.drop_table('toll_roads')
