"""toll pricing correction: per-point toll model + charging policy + network cap + provenance

Revision ID: 7060b390bade
Revises: a9c1f4e7d2b8
Create Date: 2026-09-07 00:00:00.000000

2026-09-07 NSW toll pricing correction pass -- schema side. See
`app/data/nsw_toll_roads.json`'s `notes` and `scripts/seed_toll_roads.py`'s
module docstring for the full list of real pricing/modelling defects this
pass fixed (Westlink M7's derived-vs-published $/km rate; WestConnex's
`not_captured` placeholder prices; Military Road E-Ramp modelled as its own
road instead of a Lane Cove Tunnel toll point; the old 'M4M5_ROZELLE' road
conflating two different Linkt-named segments). This migration only adds the
SCHEMA those fixes needed -- every column is nullable/defaulted so it is
purely additive:

1. `toll_roads.charging_policy` (NOT NULL, defaulted "once_per_road" so
   every existing row keeps its current behaviour) and
   `toll_roads.network_group` (nullable) -- see
   `app.models.toll.TOLL_CHARGING_POLICIES` / `TollRoad.network_group`.

2. New provenance/formula columns on `toll_road_price_revisions`:
   `rate_per_km_class_a/b`, `flagfall_class_a/b`, `network_cap_class_a/b`,
   `source_url`, `retrieved_at` -- all nullable (a pre-existing revision has
   none of these captured, which is honest, not a data loss).

3. Two new tables, `toll_points` and `toll_point_price_revisions` -- the
   same static-identity/dated-revision split `toll_roads`/
   `toll_road_price_revisions` already uses, one level down, for a road
   whose `pricing_model == "per_point"` (M2, CCT, LCT) -- see
   `app.models.toll.TollPoint` / `TollPointPriceRevision`.

4. `toll_gantries.toll_point_id` (nullable FK to `toll_points.id`) -- links
   a physical gantry to its named toll point on a per_point road; NULL for
   every gantry of every other road.

No data migration here -- the actual price corrections/new toll-point rows
are seeded by `scripts/seed_toll_roads.py` from the corrected
`app/data/nsw_toll_roads.json` / `nsw_toll_gantries.csv`, run after this
migration (same "schema in Alembic, reference data in the idempotent seed
script" split the previous toll-registry migration already used). The two
superseded `TollRoad` rows ("MILITARY_E_RAMP", "M4M5_ROZELLE") are retired
by that same script (`_retire_superseded_roads`), not by a data migration
here -- they were never real in-force prices a passenger could have paid
against (see that script's module docstring, point 3-4), so there is no
fare-evidence reason to preserve them, and retiring them via the idempotent
seed script (rather than one-off SQL here) keeps the "how do I get from a
stale registry to a correct one" story in one place.
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = '7060b390bade'
down_revision: Union[str, Sequence[str], None] = 'a9c1f4e7d2b8'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column(
        'toll_roads',
        sa.Column('charging_policy', sa.String(length=32), nullable=False, server_default='once_per_road'),
    )
    op.add_column('toll_roads', sa.Column('network_group', sa.String(length=32), nullable=True))
    op.create_index(op.f('ix_toll_roads_network_group'), 'toll_roads', ['network_group'], unique=False)

    op.add_column('toll_road_price_revisions', sa.Column('rate_per_km_class_a', sa.Numeric(precision=10, scale=4), nullable=True))
    op.add_column('toll_road_price_revisions', sa.Column('rate_per_km_class_b', sa.Numeric(precision=10, scale=4), nullable=True))
    op.add_column('toll_road_price_revisions', sa.Column('flagfall_class_a', sa.Numeric(precision=10, scale=2), nullable=True))
    op.add_column('toll_road_price_revisions', sa.Column('flagfall_class_b', sa.Numeric(precision=10, scale=2), nullable=True))
    op.add_column('toll_road_price_revisions', sa.Column('network_cap_class_a', sa.Numeric(precision=10, scale=2), nullable=True))
    op.add_column('toll_road_price_revisions', sa.Column('network_cap_class_b', sa.Numeric(precision=10, scale=2), nullable=True))
    op.add_column('toll_road_price_revisions', sa.Column('source_url', sa.String(length=500), nullable=True))
    op.add_column('toll_road_price_revisions', sa.Column('retrieved_at', sa.Date(), nullable=True))

    op.create_table(
        'toll_points',
        sa.Column('id', sa.String(length=64), nullable=False),
        sa.Column('toll_road_id', sa.String(length=32), nullable=False),
        sa.Column('name', sa.String(length=255), nullable=False),
        sa.Column('description', sa.String(length=1000), nullable=True),
        sa.Column('source_note', sa.String(length=2000), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.ForeignKeyConstraint(['toll_road_id'], ['toll_roads.id'], ),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index(op.f('ix_toll_points_toll_road_id'), 'toll_points', ['toll_road_id'], unique=False)

    op.create_table(
        'toll_point_price_revisions',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('toll_point_id', sa.String(length=64), nullable=False),
        sa.Column('price_class_a', sa.Numeric(precision=10, scale=2), nullable=True),
        sa.Column('price_class_b', sa.Numeric(precision=10, scale=2), nullable=True),
        sa.Column('currency', sa.String(length=3), nullable=False),
        sa.Column('gst_included', sa.Boolean(), nullable=False),
        sa.Column('effective_date', sa.Date(), nullable=False),
        sa.Column('indexation', sa.String(length=32), nullable=False),
        sa.Column('confidence', sa.String(length=32), nullable=False),
        sa.Column('verify_note', sa.String(length=2000), nullable=True),
        sa.Column('source_url', sa.String(length=500), nullable=True),
        sa.Column('retrieved_at', sa.Date(), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.ForeignKeyConstraint(['toll_point_id'], ['toll_points.id'], ),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index(
        op.f('ix_toll_point_price_revisions_toll_point_id'), 'toll_point_price_revisions', ['toll_point_id'], unique=False
    )
    op.create_index(
        op.f('ix_toll_point_price_revisions_effective_date'), 'toll_point_price_revisions', ['effective_date'], unique=False
    )

    op.add_column('toll_gantries', sa.Column('toll_point_id', sa.String(length=64), nullable=True))
    op.create_index(op.f('ix_toll_gantries_toll_point_id'), 'toll_gantries', ['toll_point_id'], unique=False)
    op.create_foreign_key(
        op.f('fk_toll_gantries_toll_point_id_toll_points'), 'toll_gantries', 'toll_points', ['toll_point_id'], ['id']
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_constraint(op.f('fk_toll_gantries_toll_point_id_toll_points'), 'toll_gantries', type_='foreignkey')
    op.drop_index(op.f('ix_toll_gantries_toll_point_id'), table_name='toll_gantries')
    op.drop_column('toll_gantries', 'toll_point_id')

    op.drop_index(op.f('ix_toll_point_price_revisions_effective_date'), table_name='toll_point_price_revisions')
    op.drop_index(op.f('ix_toll_point_price_revisions_toll_point_id'), table_name='toll_point_price_revisions')
    op.drop_table('toll_point_price_revisions')

    op.drop_index(op.f('ix_toll_points_toll_road_id'), table_name='toll_points')
    op.drop_table('toll_points')

    op.drop_column('toll_road_price_revisions', 'retrieved_at')
    op.drop_column('toll_road_price_revisions', 'source_url')
    op.drop_column('toll_road_price_revisions', 'network_cap_class_b')
    op.drop_column('toll_road_price_revisions', 'network_cap_class_a')
    op.drop_column('toll_road_price_revisions', 'flagfall_class_b')
    op.drop_column('toll_road_price_revisions', 'flagfall_class_a')
    op.drop_column('toll_road_price_revisions', 'rate_per_km_class_b')
    op.drop_column('toll_road_price_revisions', 'rate_per_km_class_a')

    op.drop_index(op.f('ix_toll_roads_network_group'), table_name='toll_roads')
    op.drop_column('toll_roads', 'network_group')
    op.drop_column('toll_roads', 'charging_policy')
