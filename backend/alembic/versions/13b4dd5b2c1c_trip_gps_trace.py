"""trip gps trace

Revision ID: 13b4dd5b2c1c
Revises: b4f8e1c9a2d7
Create Date: 2026-09-07 02:07:55.409038

New `trip_gps_traces` table (GPS-trace persistence pass) -- one row per trip
holding the real, raw GPS/speed trace the Android meter uploads on
`POST /v1/trips/sync` (`TripSyncItem.gps_trace`). Until this pass that trace
was used ONCE, transiently, to independently verify a synced trip's
device-reported total (`app.services.trips.recompute_from_trace`) and then
discarded -- the product decision behind this table is to also persist it
durably, so the dashboard can draw the real driven route on a trip's detail
view and so the trace survives as route evidence to defend a disputed fare
(this is a legally fare-regulated taxi meter, same audit-record posture as
the existing compliance "evidence pack" export).

See `app.models.trips.TripGpsTrace`'s own docstring for the full design
rationale this migration encodes:

  * ONE ROW PER TRIP holding the whole ordered point list as JSON, not one
    row per point (unlike the sibling `vehicle_position_history` /
    `device_version_history` many-rows-per-parent tables) -- every real
    consumer (route-map polyline, evidence-pack export) always wants the
    whole trace as one unit, so there is no query-pattern benefit to
    thousands of per-trip point rows here.
  * A SEPARATE TABLE, not a new column on `trips` -- `GET /v1/trips` returns
    PAGES of trips, and an hour-long fare's trace is on the order of 3,600
    points (~100-300KB serialized); embedding that in every row of a list
    response would be a serious performance regression. This table is only
    ever read by the dedicated `GET /v1/trips/{id}/gps-trace` endpoint.
  * `tenant_id` + a UNIQUE (tenant_id, trip_id) constraint enforce the
    one-to-one relationship and keep every read tenant-scoped, matching the
    sole multi-tenancy enforcement mechanism this whole system relies on
    (see `app.core.database.TenantScopedMixin`'s own docstring).
  * NO row is ever written for an empty trace (today's reality: every synced
    trip currently arrives with `gps_trace: []` because of an Android-side
    bug a parallel workstream is fixing) -- see
    `app.services.trips.build_gps_trace_row`, which returns `None` for an
    empty list so the sync router never inserts a junk "recorded but empty"
    row.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '13b4dd5b2c1c'
down_revision: Union[str, Sequence[str], None] = 'b4f8e1c9a2d7'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'trip_gps_traces',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('tenant_id', sa.String(length=36), nullable=False),
        sa.Column('trip_id', sa.String(length=36), nullable=False),
        sa.Column('points', sa.JSON(), nullable=False),
        sa.Column('point_count', sa.Integer(), nullable=False),
        sa.Column('recorded_at', sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(['tenant_id'], ['tenants.id']),
        sa.ForeignKeyConstraint(['trip_id'], ['trips.id']),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('tenant_id', 'trip_id', name='uq_trip_gps_traces_tenant_trip_id'),
    )
    op.create_index(
        op.f('ix_trip_gps_traces_tenant_id'), 'trip_gps_traces', ['tenant_id'], unique=False
    )
    op.create_index(
        op.f('ix_trip_gps_traces_trip_id'), 'trip_gps_traces', ['trip_id'], unique=False
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f('ix_trip_gps_traces_trip_id'), table_name='trip_gps_traces')
    op.drop_index(op.f('ix_trip_gps_traces_tenant_id'), table_name='trip_gps_traces')
    op.drop_table('trip_gps_traces')
