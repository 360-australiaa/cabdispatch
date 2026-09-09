"""fatigue_alerts: unique per-shift dedup for bounded alert kinds

Revision ID: c774793b6619
Revises: b3f7a0c1d4e2
Create Date: 2026-09-09 00:00:00.000000

PRODUCTION INCIDENT FIX (request_id cd2486bb63374e4384e20cdde6dc3558):
`GET /v1/shifts` was 500ing because `app.services.fatigue.
_shift_duration_alert_exists`'s `scalar_one_or_none()` found MORE THAN ONE
existing `shift_duration_exceeded` row for the same shift and raised
`MultipleResultsFound`. Nothing in the schema stopped that: two lazy checks
racing on the same shift (the position heartbeat and a shift-list read, or
two heartbeats close together — see `app.services.lazy_maintenance` and
`app.services.fatigue`'s module docstring) could each find "no alert yet"
and both insert one, since the dedup was a plain check-then-insert with no
DB-level guard behind it.

Same root cause and same fix shape as `97da879e0540`
(duress_devices per-tenant device_code uniqueness), which closed the
identical "a missing uniqueness constraint lets a duplicate row exist, and
then a `scalar_one_or_none()` lookup 500s the instant anything reads it"
bug in a different domain.

Only `shift_duration_exceeded` and `no_break_taken` are deduped "does one
exist for this shift, ever" — a bounded-lifetime dedup key that only makes
sense for those two kinds (see `app.services.fatigue`'s module docstring).
`speed_exceeded` deliberately raises one row per over-threshold telemetry
point and MUST NOT be constrained by this index. Hence a PARTIAL unique
index (`WHERE kind IN (...)`), not a plain table-wide `UniqueConstraint`
like `97da879e0540` used for duress_devices — partial indexes are supported
directly on both engines this project targets (Postgres always; SQLite
>= 3.8.0) via plain `CREATE [UNIQUE] INDEX ... WHERE ...`, so this needs no
`batch_alter_table` copy-table dance — it only adds an index, it does not
alter the table.

DATA CLEANUP, BEFORE THIS INDEX CAN BE CREATED: the very duplicate rows this
migration exists to prevent already exist in production (that is the
confirmed cause of the outage), and a UNIQUE index cannot be created over
data that already violates it. `upgrade()` below therefore de-duplicates
FIRST, in Python (portable across both engines, and exercised by every test
run since the test suite applies this migration too) — keeping the
earliest row (by `triggered_at`, tied-broken by `id`) per
`(tenant_id, shift_id, kind)` among the two bounded kinds, deleting the
rest — and only then creates the index. This migration is therefore safe to
run directly against the live database; no separate manual cleanup step is
required.

For reference (e.g. to sanity-check row counts before running this against
production), the equivalent de-dup as a single statement against Postgres:

    DELETE FROM fatigue_alerts fa
    USING (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY tenant_id, shift_id, kind
                   ORDER BY triggered_at ASC, id ASC
               ) AS rn
        FROM fatigue_alerts
        WHERE kind IN ('shift_duration_exceeded', 'no_break_taken')
    ) ranked
    WHERE fa.id = ranked.id
      AND ranked.rn > 1;
"""
from __future__ import annotations

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = 'c774793b6619'
down_revision: Union[str, Sequence[str], None] = 'b3f7a0c1d4e2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

# The two bounded-per-shift dedup kinds this index covers — see the module
# docstring above for why `speed_exceeded` is deliberately excluded.
_BOUNDED_KINDS = ('shift_duration_exceeded', 'no_break_taken')

_INDEX_NAME = 'uq_fatigue_alerts_tenant_shift_kind_bounded'
_WHERE_SQL = "kind IN ('shift_duration_exceeded', 'no_break_taken')"


def upgrade() -> None:
    """Upgrade schema."""
    conn = op.get_bind()

    fatigue_alerts = sa.table(
        'fatigue_alerts',
        sa.column('id'),
        sa.column('tenant_id'),
        sa.column('shift_id'),
        sa.column('kind'),
        sa.column('triggered_at'),
    )

    # --- de-duplicate existing rows first (see module docstring) -----------
    existing = conn.execute(
        sa.select(
            fatigue_alerts.c.id,
            fatigue_alerts.c.tenant_id,
            fatigue_alerts.c.shift_id,
            fatigue_alerts.c.kind,
        )
        .where(fatigue_alerts.c.kind.in_(_BOUNDED_KINDS))
        .order_by(fatigue_alerts.c.triggered_at.asc(), fatigue_alerts.c.id.asc())
    ).fetchall()

    seen_keys: set[tuple[str, str, str]] = set()
    duplicate_ids: list[str] = []
    for row in existing:
        key = (row.tenant_id, row.shift_id, row.kind)
        if key in seen_keys:
            duplicate_ids.append(row.id)
        else:
            seen_keys.add(key)

    if duplicate_ids:
        conn.execute(fatigue_alerts.delete().where(fatigue_alerts.c.id.in_(duplicate_ids)))

    # --- now safe to add the partial unique index ---------------------------
    op.create_index(
        _INDEX_NAME,
        'fatigue_alerts',
        ['tenant_id', 'shift_id', 'kind'],
        unique=True,
        postgresql_where=sa.text(_WHERE_SQL),
        sqlite_where=sa.text(_WHERE_SQL),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(_INDEX_NAME, table_name='fatigue_alerts')
