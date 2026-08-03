"""tenant admin pin

Revision ID: d4a7b1e39c5f
Revises: 6b8e6598e086
Create Date: 2026-08-03 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'd4a7b1e39c5f'
down_revision: Union[str, Sequence[str], None] = '6b8e6598e086'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Nullable, no server_default needed — every existing tenant simply has
    # no admin PIN configured yet (admin_pin_hash IS NULL), same as a brand
    # new tenant would. See Tenant.admin_pin_hash's doc comment.
    op.add_column('tenants', sa.Column('admin_pin_hash', sa.String(length=255), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('tenants', 'admin_pin_hash')
