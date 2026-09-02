"""vehicle make model

Revision ID: 4994e8b24a3d
Revises: 052408246a10
Create Date: 2026-09-03 01:08:30.150943

Adds nullable `make`/`model` columns to `vehicles` -- purely additive, no
backfill needed since both are optional identity fields (null means
"unknown", same convention as Vehicle.vin).
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '4994e8b24a3d'
down_revision: Union[str, Sequence[str], None] = '052408246a10'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('vehicles', sa.Column('make', sa.String(length=60), nullable=True))
    op.add_column('vehicles', sa.Column('model', sa.String(length=60), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('vehicles', 'model')
    op.drop_column('vehicles', 'make')
