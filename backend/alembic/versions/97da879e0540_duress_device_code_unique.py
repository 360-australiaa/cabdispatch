"""duress_devices: per-tenant unique device_code

Revision ID: 97da879e0540
Revises: 270b880a78d9
Create Date: 2026-08-26 00:00:00.000000

Closes a real bug found during live end-to-end verification: with no
uniqueness constraint, two devices could be provisioned with the same
device_code under one tenant, and app.services.duress_device.authenticate_device's
scalar_one_or_none() lookup would then raise MultipleResultsFound (a 500)
the moment either device tried to authenticate. Same per-tenant uniqueness
precedent as devices.uq_devices_tenant_android_id (fleet domain).

SQLite has no ALTER-based ADD CONSTRAINT support, hence batch_alter_table
(copy-and-move strategy) -- same convention already used by this project's
30d61efb3583 (zones_and_shift_plotting) and db95ace20751
(compliance_expiry_tracking) migrations for the identical reason.
"""
from typing import Sequence, Union

from alembic import op


# revision identifiers, used by Alembic.
revision: str = '97da879e0540'
down_revision: Union[str, Sequence[str], None] = '270b880a78d9'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    with op.batch_alter_table('duress_devices') as batch_op:
        batch_op.create_unique_constraint(
            'uq_duress_devices_tenant_device_code', ['tenant_id', 'device_code']
        )


def downgrade() -> None:
    """Downgrade schema."""
    with op.batch_alter_table('duress_devices') as batch_op:
        batch_op.drop_constraint('uq_duress_devices_tenant_device_code', type_='unique')