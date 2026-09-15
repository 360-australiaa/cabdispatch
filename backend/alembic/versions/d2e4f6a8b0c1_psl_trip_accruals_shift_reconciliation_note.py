"""psl trip accruals, shift reconciliation note, session jti index

Revision ID: d2e4f6a8b0c1
Revises: b1a2c3d4e5f7
Create Date: 2026-09-15 00:00:00.000000

Backend slice of the admin-panel deep-improvement plan
(docs/plans/2026-09-14-admin-panel-deep-improvement-plan.md, items 1.2, 1.5
and 4 "PSL Centre").

* `psl_trip_accruals` (new table) -- one row per CLOSED trip whose PSL levy
  has been accrued into the driver's `psl_ledger` row for that period. This
  is the per-trip idempotency key the ledger never had: before it, nothing
  wrote to `psl_ledger` on trip close at all (140 production trips each
  carrying the $1.32 levy, 0 ledger rows), and any naive "add on close"
  would double-count on a replayed sync. `trip_id` is UNIQUE, so a trip can
  be accrued exactly once no matter how many times its close/sync is
  replayed. No FK on `trip_id`/`driver_id`, matching the trips domain's own
  unconstrained-cross-domain-ref precedent (see `app.models.trips`), and so
  a force-wiped driver (app.services.fleet_wipe) never trips over this
  table. See `app.services.psl_ledger.accrue_trip_psl` / `rebuild_ledger`.

* `shifts.reconciliation_note` (nullable Text) -- set by
  `POST /v1/trips/{id}/fare-correction` when a trip on an already-reconciled
  shift has its fare of record changed: the shift's stored aggregates are
  recomputed, `reconciled` is flipped back to False, and this note says why
  ("fare corrected on <date>; re-reconcile"). NULL for every existing shift:
  nothing has ever asked one to be re-reconciled.

* index on `user_sessions.current_access_jti` -- the 24h idle-expiry check
  in `app.core.security.get_current_user` now looks a session row up by its
  live access jti on every authenticated request; that column had no index.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'd2e4f6a8b0c1'
down_revision: Union[str, Sequence[str], None] = 'b1a2c3d4e5f7'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'psl_trip_accruals',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('tenant_id', sa.String(length=36), nullable=False),
        sa.Column('trip_id', sa.String(length=36), nullable=False),
        sa.Column('driver_id', sa.String(length=36), nullable=False),
        sa.Column('period', sa.String(length=7), nullable=False),
        sa.Column('amount', sa.Numeric(precision=10, scale=2), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.ForeignKeyConstraint(['tenant_id'], ['tenants.id'], ),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('trip_id', name='uq_psl_trip_accruals_trip_id'),
    )
    op.create_index(op.f('ix_psl_trip_accruals_driver_id'), 'psl_trip_accruals', ['driver_id'], unique=False)
    op.create_index(op.f('ix_psl_trip_accruals_period'), 'psl_trip_accruals', ['period'], unique=False)
    op.create_index(op.f('ix_psl_trip_accruals_tenant_id'), 'psl_trip_accruals', ['tenant_id'], unique=False)

    op.add_column('shifts', sa.Column('reconciliation_note', sa.Text(), nullable=True))

    op.create_index(
        op.f('ix_user_sessions_current_access_jti'), 'user_sessions', ['current_access_jti'], unique=False
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f('ix_user_sessions_current_access_jti'), table_name='user_sessions')
    op.drop_column('shifts', 'reconciliation_note')
    op.drop_index(op.f('ix_psl_trip_accruals_tenant_id'), table_name='psl_trip_accruals')
    op.drop_index(op.f('ix_psl_trip_accruals_period'), table_name='psl_trip_accruals')
    op.drop_index(op.f('ix_psl_trip_accruals_driver_id'), table_name='psl_trip_accruals')
    op.drop_table('psl_trip_accruals')
