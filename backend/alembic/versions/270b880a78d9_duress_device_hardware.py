"""duress device hardware (CT-DPD-01) + tablet/device correlation on duress_events

Revision ID: 270b880a78d9
Revises: 32b7662e9362
Create Date: 2026-08-25 00:00:00.000000

New table `duress_devices` -- the physical panic-button hardware (SIM7600G-H
4G/GNSS/VoLTE + ESP32-S3 BLE), factory-provisioned and bound to one vehicle.
See docs/DURESS_DEVICE_INTEGRATION.md for the full system contract this
backs, and app.models.duress_device.DuressDevice for the field-level design
notes (in particular why secret_encrypted is stored reversibly via Fernet
rather than one-way hashed, unlike every password/PIN elsewhere in this
codebase).

Four new nullable/defaulted columns on `duress_events` let a device-triggered
alarm and a tablet-triggered DuressEvent correlate into ONE incident rather
than two separate rows -- see app.models.duress.DuressEvent's own updated
docstring section for the exact semantics of each.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '270b880a78d9'
down_revision: Union[str, Sequence[str], None] = '32b7662e9362'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'duress_devices',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('tenant_id', sa.String(length=36), nullable=False),
        sa.Column('device_code', sa.String(length=64), nullable=False),
        sa.Column('vehicle_id', sa.String(length=36), nullable=True),
        sa.Column('secret_encrypted', sa.String(length=255), nullable=False),
        sa.Column('phone_number', sa.String(length=32), nullable=True),
        sa.Column('battery_pct', sa.Integer(), nullable=True),
        sa.Column('on_battery', sa.Boolean(), nullable=False),
        sa.Column('gnss_fix', sa.Boolean(), nullable=False),
        sa.Column('signal_csq', sa.Integer(), nullable=True),
        sa.Column('firmware_version', sa.String(length=30), nullable=True),
        sa.Column('last_seen_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('active', sa.Boolean(), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.ForeignKeyConstraint(['tenant_id'], ['tenants.id']),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index(
        op.f('ix_duress_devices_tenant_id'), 'duress_devices', ['tenant_id'], unique=False
    )
    op.create_index(
        op.f('ix_duress_devices_device_code'), 'duress_devices', ['device_code'], unique=False
    )
    op.create_index(
        op.f('ix_duress_devices_vehicle_id'), 'duress_devices', ['vehicle_id'], unique=False
    )

    op.add_column('duress_events', sa.Column('device_id', sa.String(length=36), nullable=True))
    op.add_column(
        'duress_events',
        sa.Column('source', sa.String(length=10), nullable=False, server_default='tablet'),
    )
    op.add_column('duress_events', sa.Column('device_audio_ref', sa.String(length=255), nullable=True))
    op.add_column('duress_events', sa.Column('device_call_result_json', sa.JSON(), nullable=True))
    op.create_index(
        op.f('ix_duress_events_device_id'), 'duress_events', ['device_id'], unique=False
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f('ix_duress_events_device_id'), table_name='duress_events')
    op.drop_column('duress_events', 'device_call_result_json')
    op.drop_column('duress_events', 'device_audio_ref')
    op.drop_column('duress_events', 'source')
    op.drop_column('duress_events', 'device_id')

    op.drop_index(op.f('ix_duress_devices_vehicle_id'), table_name='duress_devices')
    op.drop_index(op.f('ix_duress_devices_device_code'), table_name='duress_devices')
    op.drop_index(op.f('ix_duress_devices_tenant_id'), table_name='duress_devices')
    op.drop_table('duress_devices')