"""merge jurisdiction seam and tenant-invites heads

Revision ID: e74b5d854ad7
Revises: a1b2c3d4e5f6, d1e3b5c7a9f0
Create Date: 2026-09-08 15:55:41.721124

Pure merge, no schema change. `a1b2c3d4e5f6` (jurisdiction seam: tenant
timezone/currency/jurisdiction/gst_divisor + tariff night-window/peak
overrides) and `d1e3b5c7a9f0` (tenant_invites table) both branched off
`c5b8e2a71f30` and were committed independently — the migration graph had
forked into two heads (`alembic heads` printed two lines), which
`tests/conftest.py` treats as fatal (no fresh test database can be built
from a forked graph) and this repo's own convention (§1 rule 8 of
`docs/plans/2026-09-08-global-meter-program.md`) requires exactly one. This
revision converges them back to one head; it does not touch a single
column.
"""
from __future__ import annotations

revision = "e74b5d854ad7"
down_revision = ("a1b2c3d4e5f6", "d1e3b5c7a9f0")
branch_labels = None
depends_on = None


def upgrade() -> None:
    pass


def downgrade() -> None:
    pass
