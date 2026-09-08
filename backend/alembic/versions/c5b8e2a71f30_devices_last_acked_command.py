"""devices: record WHICH command a device acknowledged

Revision ID: c5b8e2a71f30
Revises: b97e15bd85a8
Create Date: 2026-09-08 00:00:00.000000

Adds `devices.last_acked_command`.

`command_acked_at` landed when `restart` was the only acknowledgeable command,
so a bare timestamp was unambiguous. It no longer is: `force_update` and
`kiosk_lock` gained ack paths (backend audit §3 G9 -- before this they had none
at all, so the dashboard read "Pending" for the life of the row, the same bug
`locate` and `reboot` were already fixed for). With three commands sharing one
timestamp, an admin queuing a force-update would see `command_acked_at` carrying
a restart acknowledged an hour earlier and read it as "the update landed". This
column says which one, so the dashboard can tell them apart honestly.

Purely additive and safe on a live database: one nullable column, no backfill.
NULL means "not recorded" -- every device already in the field acquires a value
the next time it acknowledges anything.
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "c5b8e2a71f30"
down_revision = "b97e15bd85a8"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("devices", sa.Column("last_acked_command", sa.String(length=30), nullable=True))


def downgrade() -> None:
    op.drop_column("devices", "last_acked_command")
