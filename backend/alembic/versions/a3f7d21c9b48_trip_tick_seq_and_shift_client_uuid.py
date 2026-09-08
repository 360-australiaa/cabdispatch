"""trip tick_seq and shift client_uuid

Revision ID: a3f7d21c9b48
Revises: c7a4e2b8f13d
Create Date: 2026-09-08 00:00:00.000000

Wave-1 B1 (trip integrity). Two nullable columns, no backfill:

`trips.last_tick_seq` — the server-side half of `PATCH /v1/trips/{id}/tick`
idempotency. A tick batch was never idempotent: `apply_tick` walks forward
from `trips.last_ts` and ACCUMULATES haversine distance, so a client retry
after a mobile timeout re-billed the same kilometres (waiting time was
already safe — elapsed clamps to 0 on backwards time). The meter now sends a
monotonic `tick_seq` per trip and this column records the highest applied;
anything at or below it is answered as a 200 no-op. NULL means no sequenced
tick has ever been applied to that trip — an existing row, or a meter build
that predates the field — and those fall back to the timestamp defence
(points at or before `last_ts` are dropped), which needs no client
cooperation at all.

`shifts.client_uuid` + `uq_shifts_tenant_client_uuid` — the device-generated
idempotency key for `POST /v1/shifts/start`, mirroring
`trips.client_uuid`/`uq_trips_tenant_client_uuid` exactly. This is what makes
an offline shift start real: the meter previously fabricated a synthetic
shift id locally that was never persisted, so trips closed under it
referenced a shift that did not exist. NULL for every existing row and for
any shift opened from the dashboard; NULLs are distinct under a unique
constraint in both SQLite and Postgres, so any number of them coexist.

`batch_alter_table` per this repo's SQLite convention — SQLite cannot add a
table-level constraint to an existing table in place, so the constraint has
to go on through a table rebuild.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'a3f7d21c9b48'
down_revision: Union[str, Sequence[str], None] = 'c7a4e2b8f13d'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('trips', sa.Column('last_tick_seq', sa.Integer(), nullable=True))

    with op.batch_alter_table('shifts') as batch_op:
        batch_op.add_column(sa.Column('client_uuid', sa.String(36), nullable=True))
        batch_op.create_index('ix_shifts_client_uuid', ['client_uuid'])
        batch_op.create_unique_constraint(
            'uq_shifts_tenant_client_uuid', ['tenant_id', 'client_uuid']
        )


def downgrade() -> None:
    """Downgrade schema."""
    with op.batch_alter_table('shifts') as batch_op:
        batch_op.drop_constraint('uq_shifts_tenant_client_uuid', type_='unique')
        batch_op.drop_index('ix_shifts_client_uuid')
        batch_op.drop_column('client_uuid')

    op.drop_column('trips', 'last_tick_seq')
