"""merge shift break-tracking (main) and NSW 2026 compliance (this branch) heads

Revision ID: 2acd19d3155f
Revises: 0db130c8824f, 8fcf315286e1
Create Date: 2026-09-02 22:55:05.141355

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '2acd19d3155f'
down_revision: Union[str, Sequence[str], None] = ('0db130c8824f', '8fcf315286e1')
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    pass


def downgrade() -> None:
    """Downgrade schema."""
    pass
