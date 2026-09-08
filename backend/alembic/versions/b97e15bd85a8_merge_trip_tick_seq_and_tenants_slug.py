"""merge trip tick_seq and tenants slug

Two Wave-1 workstreams branched a migration off the same parent
(c7a4e2b8f13d) in parallel worktrees and neither could see the other:

  a3f7d21c9b48  trips.last_tick_seq + shifts.client_uuid   (B1, trip integrity)
  d5f0a91c73b2  tenants.slug                                (B4, rate limiting)

They touch disjoint tables, so this is a pure graph join with no schema
work of its own -- the empty upgrade/downgrade below is correct, not a
stub. Same shape as the existing 95db941b68cd and 2acd19d3155f merges in
this history.

Revision ID: b97e15bd85a8
Revises: a3f7d21c9b48, d5f0a91c73b2
Create Date: 2026-09-08 10:05:29.567687

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'b97e15bd85a8'
down_revision: Union[str, Sequence[str], None] = ('a3f7d21c9b48', 'd5f0a91c73b2')
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""


def downgrade() -> None:
    """Downgrade schema."""
