"""devices: somewhere for a locate answer to land, and an acknowledged-at stamp

Revision ID: c7a4e2b8f13d
Revises: b3e71d4f0a29
Create Date: 2026-09-08 00:00:00.000000

Adds `devices.last_locate_lat/lng/accuracy_m/at` and `devices.command_acked_at`.

Remote locate has never worked, and could not have. `locate_requested` was set
by an admin and read back by the tablet, but the flag had NO CLEAR PATH -- no
route, no service call, nothing in the heartbeat ever set it back to false -- so
the dashboard showed "Pending" forever whether or not the device had answered.
Reported from the field as "when I try to reboot or locate it's showing pending,
but nothing is working". That is exactly what it did.

Worse, the answer had nowhere to go. The tablet replied by publishing a VEHICLE
position (`POST /v1/fleet/positions`), which needs a real vehicle UUID from a
live driver session -- so a parked, logged-off tablet, which is the entire point
of remote locate, could never answer at all, and a tablet holding a stale UUID
(a vehicle since deleted by a fleet wipe) got a 404 that surfaced to the driver
as "Location request failed to send - HTTP 404 not found".

So the answer now lands on the DEVICE row, reported by the device against its
own credential, and reporting is what clears the flag. A location fix belongs to
the tablet, not to whichever car it happens to be bolted into today.

`command_acked_at` is the same idea for the reboot flag: an admin who queues a
command needs to see that something on the other end acted on it, rather than a
badge that stays "Pending" for the life of the row.

Purely additive and safe on a live database: five nullable columns, no backfill.
An existing device has never reported a location, which is exactly what NULL
says here.
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "c7a4e2b8f13d"
down_revision = "b3e71d4f0a29"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("devices", sa.Column("last_locate_lat", sa.Float(), nullable=True))
    op.add_column("devices", sa.Column("last_locate_lng", sa.Float(), nullable=True))
    op.add_column("devices", sa.Column("last_locate_accuracy_m", sa.Float(), nullable=True))
    op.add_column("devices", sa.Column("last_locate_at", sa.DateTime(timezone=True), nullable=True))
    op.add_column("devices", sa.Column("command_acked_at", sa.DateTime(timezone=True), nullable=True))


def downgrade() -> None:
    op.drop_column("devices", "command_acked_at")
    op.drop_column("devices", "last_locate_at")
    op.drop_column("devices", "last_locate_accuracy_m")
    op.drop_column("devices", "last_locate_lng")
    op.drop_column("devices", "last_locate_lat")
