"""tariff cleaning fee cap

Revision ID: 8fcf315286e1
Revises: 2bc9163321d2
Create Date: 2026-09-02 20:03:33.495280

Part of the NSW Point to Point Transport (Fares) Order 2026 compliance pass.
Every existing tariff backfills to the Order's own cap ($124.14) — the same
value app.services.fare_engine.Tariff.cleaning_fee_cap defaults to, so no
existing tariff's effective cleaning-fee ceiling silently changes.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '8fcf315286e1'
down_revision: Union[str, Sequence[str], None] = '2bc9163321d2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column(
        'tariffs',
        sa.Column(
            'cleaning_fee_cap',
            sa.Numeric(precision=10, scale=4),
            server_default=sa.text('124.14'),
            nullable=False,
        ),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('tariffs', 'cleaning_fee_cap')
