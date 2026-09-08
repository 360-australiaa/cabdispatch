"""driver engagement: wallet_transactions, trip_ratings, announcements, incentives

Revision ID: b2c4d6e8f0a1
Revises: f1a2b3c4d5e6
Create Date: 2026-09-03 00:00:00.000000

Real backing tables for the four driver-tablet dashboard tiles (Wallet
Balance, Driver Rating, Announcements, Incentive Progress) -- see
app/models/driver_engagement.py's module docstring. No stored balance /
average / progress columns anywhere: all three are derived on read.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'b2c4d6e8f0a1'
down_revision: Union[str, Sequence[str], None] = 'f1a2b3c4d5e6'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'wallet_transactions',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('tenant_id', sa.String(length=36), nullable=False),
        sa.Column('driver_id', sa.String(length=36), nullable=False),
        sa.Column('amount_aud', sa.Numeric(precision=10, scale=2), nullable=False),
        sa.Column('kind', sa.String(length=20), nullable=False),
        sa.Column('reference', sa.String(length=100), nullable=True),
        sa.Column('note', sa.Text(), nullable=True),
        sa.Column('created_by_user_id', sa.String(length=36), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.ForeignKeyConstraint(['tenant_id'], ['tenants.id'], ),
        sa.ForeignKeyConstraint(['driver_id'], ['users.id'], ),
        sa.ForeignKeyConstraint(['created_by_user_id'], ['users.id'], ),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index(op.f('ix_wallet_transactions_tenant_id'), 'wallet_transactions', ['tenant_id'], unique=False)
    op.create_index(op.f('ix_wallet_transactions_driver_id'), 'wallet_transactions', ['driver_id'], unique=False)

    op.create_table(
        'trip_ratings',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('tenant_id', sa.String(length=36), nullable=False),
        sa.Column('trip_id', sa.String(length=36), nullable=False),
        sa.Column('driver_id', sa.String(length=36), nullable=False),
        sa.Column('stars', sa.Integer(), nullable=False),
        sa.Column('comment', sa.Text(), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.CheckConstraint('stars >= 1 AND stars <= 5', name='ck_trip_ratings_stars_range'),
        sa.ForeignKeyConstraint(['tenant_id'], ['tenants.id'], ),
        sa.ForeignKeyConstraint(['trip_id'], ['trips.id'], ),
        sa.ForeignKeyConstraint(['driver_id'], ['users.id'], ),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('trip_id', name='uq_trip_ratings_trip_id'),
    )
    op.create_index(op.f('ix_trip_ratings_tenant_id'), 'trip_ratings', ['tenant_id'], unique=False)
    op.create_index(op.f('ix_trip_ratings_driver_id'), 'trip_ratings', ['driver_id'], unique=False)

    op.create_table(
        'announcements',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('tenant_id', sa.String(length=36), nullable=False),
        sa.Column('title', sa.String(length=200), nullable=False),
        sa.Column('body', sa.Text(), nullable=False),
        sa.Column('kind', sa.String(length=20), nullable=False),
        sa.Column('starts_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('ends_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('active', sa.Boolean(), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.ForeignKeyConstraint(['tenant_id'], ['tenants.id'], ),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index(op.f('ix_announcements_tenant_id'), 'announcements', ['tenant_id'], unique=False)
    op.create_index(op.f('ix_announcements_starts_at'), 'announcements', ['starts_at'], unique=False)

    op.create_table(
        'incentives',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('tenant_id', sa.String(length=36), nullable=False),
        sa.Column('title', sa.String(length=200), nullable=False),
        sa.Column('description', sa.Text(), nullable=True),
        sa.Column('target_trips', sa.Integer(), nullable=False),
        sa.Column('reward_aud', sa.Numeric(precision=10, scale=2), nullable=False),
        sa.Column('starts_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('ends_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('active', sa.Boolean(), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.CheckConstraint('target_trips > 0', name='ck_incentives_target_trips_positive'),
        sa.ForeignKeyConstraint(['tenant_id'], ['tenants.id'], ),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index(op.f('ix_incentives_tenant_id'), 'incentives', ['tenant_id'], unique=False)
    op.create_index(op.f('ix_incentives_starts_at'), 'incentives', ['starts_at'], unique=False)


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f('ix_incentives_starts_at'), table_name='incentives')
    op.drop_index(op.f('ix_incentives_tenant_id'), table_name='incentives')
    op.drop_table('incentives')

    op.drop_index(op.f('ix_announcements_starts_at'), table_name='announcements')
    op.drop_index(op.f('ix_announcements_tenant_id'), table_name='announcements')
    op.drop_table('announcements')

    op.drop_index(op.f('ix_trip_ratings_driver_id'), table_name='trip_ratings')
    op.drop_index(op.f('ix_trip_ratings_tenant_id'), table_name='trip_ratings')
    op.drop_table('trip_ratings')

    op.drop_index(op.f('ix_wallet_transactions_driver_id'), table_name='wallet_transactions')
    op.drop_index(op.f('ix_wallet_transactions_tenant_id'), table_name='wallet_transactions')
    op.drop_table('wallet_transactions')
