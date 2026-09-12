"""device gps blackout segments, reconciliation, and stopped_s

Revision ID: b1a2c3d4e5f7
Revises: ace7a9a79687
Create Date: 2026-09-13 00:00:00.000000

Part of the GPS-blackout program's backend counterpart (B-W1,
docs/plans/2026-09-12-android-meter-optimisation-and-gps-blackout-plan.md).
Three new columns on `trips`:

* `device_gps_blackout_segments` (nullable JSON, same portable-JSON-column
  pattern as `trips.gps_blackout_events` itself) — the DEVICE's own account
  of the GPS blackouts it experienced this trip (wire mirror of Android's
  `GpsBlackoutSegmentDto` list via `TripSyncItem.gps_blackout_segments`),
  stored side by side with — and never merged into — the pre-existing
  `gps_blackout_events` column, which is this SERVER's own independent
  recompute from the raw `gps_trace`. See `app.models.trips.Trip.
  device_gps_blackout_segments`'s own doc comment for the full reasoning.

* `blackout_reconciliation` (nullable JSON) — the fraud/bug-detection flags
  raised by comparing the two accounts above
  (`app.services.trips.reconcile_gps_blackout_segments`). Audit-trail only:
  never itself changes any money/distance/time column on the trip.

* `stopped_s` (`Integer`, `NOT NULL`, default `0`) — STOPPED-state wiring
  (G4): the server's own independently-computed total seconds this trip
  spent in the meter's driver-initiated "stopped" state (distinct from a GPS
  blackout), in the same spirit as the pre-existing `moving_s`/`waiting_s`
  columns. Defaults every existing trip to 0 — no historical trip has ever
  had a STOPPED interval, this column being exactly what starts recording
  one — via `server_default=sa.text('0')`, the same "backfill to the
  baseline every existing row already implicitly had" convention
  `2bc9163321d2`'s own doc comment documents for `passenger_count` etc.

Nullable JSON columns, `NOT NULL` integer with a server default: no data
backfill needed beyond what the column defaults themselves provide.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'b1a2c3d4e5f7'
down_revision: Union[str, Sequence[str], None] = 'ace7a9a79687'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('trips', sa.Column('device_gps_blackout_segments', sa.JSON(), nullable=True))
    op.add_column('trips', sa.Column('blackout_reconciliation', sa.JSON(), nullable=True))
    op.add_column(
        'trips',
        sa.Column('stopped_s', sa.Integer(), server_default=sa.text('0'), nullable=False),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('trips', 'stopped_s')
    op.drop_column('trips', 'blackout_reconciliation')
    op.drop_column('trips', 'device_gps_blackout_segments')
