"""vouchers and corporate accounts (real backing ledger for blueprint 5.2.5's
voucher/account Trip.payment_method values)

Revision ID: e7a1c9f2b4d6
Revises: 4994e8b24a3d
Create Date: 2026-09-03 00:00:00.000000

Replaces app.services.payments.redeem_voucher / validate_account_reference's
old non-empty-string-only stub validation with real backing tables -- see
app/models/vouchers.py's module docstring.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'e7a1c9f2b4d6'
down_revision: Union[str, Sequence[str], None] = '4994e8b24a3d'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'vouchers',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('tenant_id', sa.String(length=36), nullable=False),
        sa.Column('code', sa.String(length=50), nullable=False),
        sa.Column('value_aud', sa.Numeric(precision=10, scale=2), nullable=False),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('redeemed_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('redeemed_by_trip_id', sa.String(length=36), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.ForeignKeyConstraint(['tenant_id'], ['tenants.id'], ),
        sa.ForeignKeyConstraint(['redeemed_by_trip_id'], ['trips.id'], ),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('tenant_id', 'code', name='uq_vouchers_tenant_code'),
    )
    op.create_index(op.f('ix_vouchers_tenant_id'), 'vouchers', ['tenant_id'], unique=False)
    op.create_index(op.f('ix_vouchers_code'), 'vouchers', ['code'], unique=False)

    op.create_table(
        'corporate_accounts',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('tenant_id', sa.String(length=36), nullable=False),
        sa.Column('reference', sa.String(length=100), nullable=False),
        sa.Column('company_name', sa.String(length=255), nullable=False),
        sa.Column('active', sa.Boolean(), server_default=sa.true(), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.ForeignKeyConstraint(['tenant_id'], ['tenants.id'], ),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('tenant_id', 'reference', name='uq_corporate_accounts_tenant_reference'),
    )
    op.create_index(op.f('ix_corporate_accounts_tenant_id'), 'corporate_accounts', ['tenant_id'], unique=False)
    op.create_index(op.f('ix_corporate_accounts_reference'), 'corporate_accounts', ['reference'], unique=False)


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f('ix_corporate_accounts_reference'), table_name='corporate_accounts')
    op.drop_index(op.f('ix_corporate_accounts_tenant_id'), table_name='corporate_accounts')
    op.drop_table('corporate_accounts')

    op.drop_index(op.f('ix_vouchers_code'), table_name='vouchers')
    op.drop_index(op.f('ix_vouchers_tenant_id'), table_name='vouchers')
    op.drop_table('vouchers')
