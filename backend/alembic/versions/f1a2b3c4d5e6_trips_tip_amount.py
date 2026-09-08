"""trips tip amount

Revision ID: f1a2b3c4d5e6
Revises: e7a1c9f2b4d6
Create Date: 2026-09-02 00:00:00.000000

Close & Pay "tips" pass: adds a single nullable `tip_amount` column to
`trips`, matching the `Numeric(10, 2)` convention already used by every other
money column on this model (`tolls`/`extras`/`total`/etc). Deliberately kept
separate from `subtotal`/`surcharge`/`total`/`gst_component` — a driver tip is
not part of the NSW-regulated metered fare and must never distort those
figures (see `app.services.fare_engine.FareEngine.close`, which never reads
it, and `app.models.trips.Trip`'s module docstring deviation #6).

Nullable, no backfill needed - every existing trip simply has no tip
recorded (NULL means "no tip").
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'f1a2b3c4d5e6'
down_revision: Union[str, Sequence[str], None] = 'e7a1c9f2b4d6'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('trips', sa.Column('tip_amount', sa.Numeric(10, 2), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('trips', 'tip_amount')
