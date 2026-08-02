"""cabcharge/ttss subsidy fields

Revision ID: a1c3f9d02b7e
Revises: 7b7a31a35ebd
Create Date: 2026-08-02 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'a1c3f9d02b7e'
down_revision: Union[str, Sequence[str], None] = '7b7a31a35ebd'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # POST /v1/payments/ttss/claim: subsidy_amount (TTSS contribution, 50% of
    # fare capped at $60.00 per blueprint 11.1) and passenger_paid_amount
    # (amount - subsidy_amount). NULL for every other payment method.
    op.add_column('payments', sa.Column('subsidy_amount', sa.Numeric(10, 2), nullable=True))
    op.add_column('payments', sa.Column('passenger_paid_amount', sa.Numeric(10, 2), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('payments', 'passenger_paid_amount')
    op.drop_column('payments', 'subsidy_amount')
