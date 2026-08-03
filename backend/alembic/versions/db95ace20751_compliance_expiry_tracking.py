"""compliance expiry tracking

Revision ID: db95ace20751
Revises: d613d986df6e
Create Date: 2026-08-03 13:01:01.191611

Driver license/authority + vehicle registration/insurance expiry tracking
(blueprint 7.2.3/7.2.4/10.1). All new columns are nullable, no
server_default needed — every existing seeded/test User/Vehicle simply has no
expiry set yet; null means "unknown", not "expired" (see
app.models.user.User / app.models.fleet.Vehicle doc comments).

`fatigue_alerts.driver_id` is relaxed from NOT NULL to nullable, and a new
`fatigue_alerts.vehicle_id` column is added, so the new
registration/insurance expiry-alert kinds (raised against a vehicle, which
has no driver) can be stored in the same table as the pre-existing
shift_duration/speed alert kinds (see app.models.fatigue_alert's DEVIATION
note and app.services.compliance_expiry).

NOTE: this revision was hand-trimmed after `alembic revision --autogenerate`
picked up unrelated in-flight changes from a sibling agent's parallel work on
the `trips` table (flagged_for_review/review_notes/voucher_code/
account_reference/split_payments) — those belong to that agent's own
migration, not this one, and are intentionally NOT included here.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'db95ace20751'
down_revision: Union[str, Sequence[str], None] = 'd613d986df6e'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # `op.alter_column` alone emits a raw `ALTER TABLE ... ALTER COLUMN`,
    # which SQLite (this repo's dev/test DATABASE_URL) cannot execute at all —
    # it has no such statement. `batch_alter_table` is Alembic's
    # dialect-portable way to do this: on SQLite it transparently recreates
    # the table with the new schema and copies the data across; on Postgres
    # it just emits the plain ALTER TABLE statements. No prior migration in
    # this tree needed this (none had altered an existing NOT NULL column
    # before), so there's no local precedent — this is the first.
    with op.batch_alter_table('fatigue_alerts') as batch_op:
        batch_op.add_column(sa.Column('vehicle_id', sa.String(length=36), nullable=True))
        batch_op.alter_column('driver_id', existing_type=sa.VARCHAR(length=36), nullable=True)
        batch_op.create_index(batch_op.f('ix_fatigue_alerts_vehicle_id'), ['vehicle_id'], unique=False)

    op.add_column('users', sa.Column('driver_license_expiry', sa.Date(), nullable=True))
    op.add_column('users', sa.Column('driver_authority_expiry', sa.Date(), nullable=True))
    op.add_column('vehicles', sa.Column('registration_expiry', sa.Date(), nullable=True))
    op.add_column('vehicles', sa.Column('insurance_expiry', sa.Date(), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('vehicles', 'insurance_expiry')
    op.drop_column('vehicles', 'registration_expiry')
    op.drop_column('users', 'driver_authority_expiry')
    op.drop_column('users', 'driver_license_expiry')

    with op.batch_alter_table('fatigue_alerts') as batch_op:
        batch_op.drop_index(batch_op.f('ix_fatigue_alerts_vehicle_id'))
        batch_op.alter_column('driver_id', existing_type=sa.VARCHAR(length=36), nullable=False)
        batch_op.drop_column('vehicle_id')
