"""trips dispute flagging and new payment methods

Revision ID: a7f2b8c4d1e6
Revises: db95ace20751
Create Date: 2026-08-03 14:00:00.000000

Blueprint 5.2.5 "Dispute" button / 6.1.3 schema + "Account"/"Voucher"/
"Split Fare" payment methods, all on `trips`:

- `flagged_for_review` (bool, default False, indexed) + `review_notes`
  (nullable text) — see app.services.trips.flag_trip_for_review /
  app.api.v1.trips.flag_trip (PATCH /v1/trips/{id}/flag).
- `voucher_code` / `account_reference` (nullable free text, v1 scope — no
  real voucher/promo-code or corporate-account table exists yet) — see
  app.services.payments.redeem_voucher / validate_account_reference.
- `split_payments` (nullable JSON, same portable-JSON-column pattern as the
  pre-existing `trips.auto_tolls_applied`) — a list of {"method", "amount"}
  sub-payments, only populated when payment_method == "split_fare"; see
  app.services.trips.close_trip / SplitPaymentMismatchError.

All new columns are nullable (or have a server_default for the boolean), no
backfill needed — every existing trip simply has no dispute flag / new
payment-method data yet.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'a7f2b8c4d1e6'
down_revision: Union[str, Sequence[str], None] = 'db95ace20751'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('trips', sa.Column('flagged_for_review', sa.Boolean(), server_default=sa.text('0'), nullable=False))
    op.add_column('trips', sa.Column('review_notes', sa.Text(), nullable=True))
    op.add_column('trips', sa.Column('voucher_code', sa.String(length=50), nullable=True))
    op.add_column('trips', sa.Column('account_reference', sa.String(length=100), nullable=True))
    op.add_column('trips', sa.Column('split_payments', sa.JSON(), nullable=True))
    op.create_index(op.f('ix_trips_flagged_for_review'), 'trips', ['flagged_for_review'], unique=False)


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f('ix_trips_flagged_for_review'), table_name='trips')
    op.drop_column('trips', 'split_payments')
    op.drop_column('trips', 'account_reference')
    op.drop_column('trips', 'voucher_code')
    op.drop_column('trips', 'review_notes')
    op.drop_column('trips', 'flagged_for_review')
