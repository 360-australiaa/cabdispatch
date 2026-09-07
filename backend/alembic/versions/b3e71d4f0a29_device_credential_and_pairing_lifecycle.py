"""devices: a real device credential, plus paired/revoked lifecycle

Revision ID: b3e71d4f0a29
Revises: d8f2a61c9047
Create Date: 2026-09-08 00:00:00.000000

Adds `devices.device_secret_hash`, `devices.paired_at` and `devices.revoked_at`.

Why a device needs a credential of its own: every fleet route is authenticated
today by a HUMAN access token, so a tablet with nobody logged into it cannot talk
to this backend at all -- which is why a parked or logged-off tablet cannot be
located, kiosk-locked or told to update. The Android client has been written
against an `X-Device-Secret` header since the pairing feature landed
(`ApiService.deviceHeartbeat`, `DevicePairingStore.saveDeviceSecret`), but no
column, schema field or header reader ever existed here, so that setter has
never once been called. This is the missing half.

`device_secret_hash` stores SHA-256 of a secret minted at registration and handed
to the tablet exactly once, never stored or returned in plaintext afterwards --
the same shape `app.services.duress_device` already uses for duress hardware.

`paired_at` answers "has this tablet ever actually enrolled", which `last_seen_at`
cannot: a manually-provisioned device row (POST /v1/fleet/devices) has never
paired, and a paired one that has been switched off for a week still has.

`revoked_at` lets an operator retire a tablet without deleting its history. A
revoked device's heartbeat 404s, and the Android client already turns a heartbeat
404 into a sticky `deviceRejected` -- so revocation needs nothing new on-device.

Purely additive and safe on a live database: three nullable columns, no backfill.
Every tablet already in the field keeps working -- it has no secret, so it keeps
authenticating its heartbeat with the driver's bearer token (the heartbeat route
accepts either) until someone next re-pairs it. NULL `paired_at` on an existing
row honestly means "we did not record when this paired", not "never paired";
nothing keys a decision off it beyond what the dashboard displays.
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "b3e71d4f0a29"
down_revision = "d8f2a61c9047"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("devices", sa.Column("device_secret_hash", sa.String(length=128), nullable=True))
    op.add_column("devices", sa.Column("paired_at", sa.DateTime(timezone=True), nullable=True))
    op.add_column("devices", sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True))


def downgrade() -> None:
    op.drop_column("devices", "revoked_at")
    op.drop_column("devices", "paired_at")
    op.drop_column("devices", "device_secret_hash")
