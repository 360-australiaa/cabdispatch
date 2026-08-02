"""merge heads

Revision ID: 95db941b68cd
Revises: a1c3f9d02b7e, a1c4e9f2b6d3, c3f5a08d2e91
Create Date: 2026-08-02 15:48:09.629305

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '95db941b68cd'
down_revision: Union[str, Sequence[str], None] = ('a1c3f9d02b7e', 'a1c4e9f2b6d3', 'c3f5a08d2e91')
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    pass


def downgrade() -> None:
    """Downgrade schema."""
    pass
