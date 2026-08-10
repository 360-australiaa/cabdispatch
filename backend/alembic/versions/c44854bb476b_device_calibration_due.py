"""device calibration due (meter re-verification tracking)

Revision ID: c44854bb476b
Revises: b3e9f7c2a4d8
Create Date: 2026-08-10 00:00:00.000000

Operations-cycle tracking pass, on top of the compliance-expiry pass
(db95ace20751). Adds a single nullable `calibration_due` Date column to
`devices` -- the meter's statutory periodic re-verification due-date (the
taxi-meter equivalent of a cl 14 re-verification requirement), tracked on the
Device (the tablet/kiosk unit the meter app runs on) rather than the Vehicle,
since calibration is a property of the physical meter instrument, not the car
it's currently paired to. Nullable, no server_default needed -- every
existing seeded/test Device simply has no calibration due-date set yet; null
means "unknown", not "expired" (see app.models.fleet.Device's doc comment).

See app.services.compliance_expiry for the expiring-soon/expired detection
logic (calibration_expiring_soon/calibration_expired FatigueAlert kinds,
reusing the existing fatigue_alerts table -- no new table needed) and
GET /v1/fleet/compliance-expiry for the dashboard listing.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'c44854bb476b'
down_revision: Union[str, Sequence[str], None] = 'b3e9f7c2a4d8'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('devices', sa.Column('calibration_due', sa.Date(), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('devices', 'calibration_due')
