"""traffic_cameras and traffic_hazards: live NSW traffic camera/hazard cache

Revision ID: c8ce29172db2
Revises: acd836fc4fe7
Create Date: 2026-09-09 00:00:00.000000

Two new platform-wide reference tables backing `app/api/v1/traffic.py`
(`GET /v1/traffic/cameras`, `GET /v1/traffic/hazards`) — see
`app.models.traffic`'s module docstring for the full rationale and
`app.services.live_traffic` for where the data comes from (the public,
no-auth-required Transport for NSW live-traffic feeds).

Neither table carries a `tenant_id` column, same "no ownership dimension to
model" reasoning `acd836fc4fe7`'s sibling `toll_gantries`/`toll_roads`
already use — every tenant's tablets/dashboards see the exact same live NSW
picture, so there is nothing tenant-specific to scope.

PURE SCHEMA CHANGE, no data backfill: both tables start empty and are
populated lazily by `app.services.live_traffic.ensure_cameras_fresh` /
`ensure_hazards_fresh` the first time either read endpoint is hit — same
"add the table, the existing lazy-refresh mechanism populates it" shape as
every other cache table in this codebase.
"""
from __future__ import annotations

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = 'c8ce29172db2'
down_revision: Union[str, Sequence[str], None] = 'acd836fc4fe7'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'traffic_cameras',
        sa.Column('id', sa.String(length=64), nullable=False),
        sa.Column('name', sa.String(length=255), nullable=False),
        sa.Column('latitude', sa.Float(), nullable=False),
        sa.Column('longitude', sa.Float(), nullable=False),
        sa.Column('direction', sa.String(length=16), nullable=True),
        sa.Column('image_url', sa.String(length=500), nullable=False),
        sa.Column('region', sa.String(length=64), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.PrimaryKeyConstraint('id'),
    )

    op.create_table(
        'traffic_hazards',
        sa.Column('id', sa.String(length=64), nullable=False),
        sa.Column('category', sa.String(length=16), nullable=False),
        sa.Column('latitude', sa.Float(), nullable=False),
        sa.Column('longitude', sa.Float(), nullable=False),
        sa.Column('headline', sa.String(length=1000), nullable=True),
        sa.Column('closure_type', sa.String(length=64), nullable=True),
        sa.Column('direction', sa.String(length=64), nullable=True),
        sa.Column('speed_limit', sa.Integer(), nullable=True),
        sa.Column('expected_delay_minutes', sa.Integer(), nullable=True),
        sa.Column('ended', sa.Boolean(), nullable=False),
        sa.Column('source_last_published', sa.DateTime(timezone=True), nullable=False),
        sa.Column('raw_json', sa.JSON(), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index(
        op.f('ix_traffic_hazards_category'), 'traffic_hazards', ['category'], unique=False
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f('ix_traffic_hazards_category'), table_name='traffic_hazards')
    op.drop_table('traffic_hazards')
    op.drop_table('traffic_cameras')
