"""driver photo storage and device version history

Revision ID: 32b7662e9362
Revises: 30d61efb3583
Create Date: 2026-08-10 13:50:00.000000

Two independent additions bundled into one migration (see task brief):

1. `users.photo_url` -- nullable relative on-disk path, same convention as
   `compliance_documents.file_path`. Closes a real gap: a monitoring partner
   receiving a duress alarm needs to see the driver's photo to verify
   identity, and today there is nowhere for one to even be stored. See
   POST/GET /v1/users/{id}/photo in app/api/v1/users.py.

2. `device_version_history` -- new append-only table (device_id, app_version,
   recorded_at, tenant_id) feeding the per-vehicle compliance evidence pack
   (GET /v1/fleet/vehicles/{id}/evidence-pack, app.services.evidence_pack)
   with a real firmware/app-version timeline instead of just the current
   snapshot on `devices.app_version`. Populated by
   app.services.fleet.record_heartbeat whenever a heartbeat's app_version
   differs from what is currently stored.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '32b7662e9362'
down_revision: Union[str, Sequence[str], None] = '30d61efb3583'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('users', sa.Column('photo_url', sa.String(length=500), nullable=True))

    op.create_table(
        'device_version_history',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('tenant_id', sa.String(length=36), nullable=False),
        sa.Column('device_id', sa.String(length=36), nullable=False),
        sa.Column('app_version', sa.String(length=30), nullable=False),
        sa.Column('recorded_at', sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(['device_id'], ['devices.id']),
        sa.ForeignKeyConstraint(['tenant_id'], ['tenants.id']),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index(
        op.f('ix_device_version_history_tenant_id'), 'device_version_history', ['tenant_id'], unique=False
    )
    op.create_index(
        op.f('ix_device_version_history_device_id'), 'device_version_history', ['device_id'], unique=False
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f('ix_device_version_history_device_id'), table_name='device_version_history')
    op.drop_index(op.f('ix_device_version_history_tenant_id'), table_name='device_version_history')
    op.drop_table('device_version_history')

    op.drop_column('users', 'photo_url')
