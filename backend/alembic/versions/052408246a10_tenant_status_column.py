"""tenant status column

Revision ID: 052408246a10
Revises: 2acd19d3155f
Create Date: 2026-09-02 23:51:34.076238

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '052408246a10'
down_revision: Union[str, Sequence[str], None] = '2acd19d3155f'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # server_default so every pre-existing tenant row backfills to "active"
    # with no separate data migration - see app.models.tenant.Tenant.status's
    # doc comment.
    op.add_column(
        "tenants",
        sa.Column("status", sa.String(length=20), nullable=False, server_default="active"),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column("tenants", "status")
