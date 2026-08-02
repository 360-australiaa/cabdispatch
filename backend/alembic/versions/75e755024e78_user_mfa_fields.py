"""user mfa fields

Revision ID: 75e755024e78
Revises: 95db941b68cd
Create Date: 2026-08-02 15:50:22.557395

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '75e755024e78'
down_revision: Union[str, Sequence[str], None] = '95db941b68cd'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Blueprint 12.2: opt-in TOTP MFA for admin/staff users. mfa_enabled needs
    # a server_default so this ALTER TABLE doesn't fail against existing
    # seeded rows (NOT NULL with no default) — every pre-existing account
    # lands as mfa_enabled=False, i.e. completely unaffected/opted-out.
    op.add_column('users', sa.Column('mfa_secret', sa.String(length=64), nullable=True))
    op.add_column(
        'users',
        sa.Column('mfa_enabled', sa.Boolean(), nullable=False, server_default=sa.false()),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('users', 'mfa_enabled')
    op.drop_column('users', 'mfa_secret')
