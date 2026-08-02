"""audit log hash chain

Revision ID: a1c4e9f2b6d3
Revises: 7b7a31a35ebd
Create Date: 2026-08-02 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'a1c4e9f2b6d3'
down_revision: Union[str, Sequence[str], None] = '7b7a31a35ebd'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

# 64 zero-chars — the fixed genesis `previous_hash` for the first row in a
# tenant's chain (see app.services.audit_log.GENESIS_HASH). Used here only as
# the server_default backfill value for any pre-existing rows (there are none
# in this dev environment); app.services.audit_log.record_audit always sets
# both columns explicitly for every row it writes going forward, exactly like
# the existing `at` column's server_default.
_GENESIS = '0' * 64


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column(
        'audit_log',
        sa.Column('hash', sa.String(length=64), nullable=False, server_default=_GENESIS),
    )
    op.add_column(
        'audit_log',
        sa.Column('previous_hash', sa.String(length=64), nullable=False, server_default=_GENESIS),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('audit_log', 'previous_hash')
    op.drop_column('audit_log', 'hash')
