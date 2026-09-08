"""app releases (OTA update publishing)

Revision ID: b4f8e1c9a2d7
Revises: 8138c0bdae41
Create Date: 2026-09-06 00:00:00.000000

New `app_releases` table backing the Android meter app's self-update flow
(`GET /v1/app-releases/latest`, `POST /v1/platform/app-releases`, `GET
/v1/app-releases/{id}/download` -- see `app/api/v1/app_releases.py`).

Platform-wide, NOT tenant-scoped -- see `app.models.app_release.AppRelease`'s
own docstring: one Android app codebase serves every tenant, so there is no
tenant_id column here at all (unlike the standard `TenantScopedMixin`
domain tables), not even a nullable one -- every row is a platform release.

`version_code` is unique and is what "latest" is decided by (highest
`version_code` among `is_active=true` rows), not `version_name` or
`created_at`. `apk_path` follows the same server-local relative-path
convention as `User.photo_url` (see `app.services.user.save_user_photo`).
`sha256` is always server-computed at upload time so the Android client can
verify its download before ever building an install Intent.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'b4f8e1c9a2d7'
down_revision: Union[str, Sequence[str], None] = '8138c0bdae41'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'app_releases',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('version_code', sa.Integer(), nullable=False),
        sa.Column('version_name', sa.String(length=30), nullable=False),
        sa.Column('apk_path', sa.String(length=500), nullable=False),
        sa.Column('release_notes', sa.Text(), nullable=True),
        sa.Column('is_active', sa.Boolean(), nullable=False),
        sa.Column('sha256', sa.String(length=64), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('version_code', name='uq_app_releases_version_code'),
    )
    op.create_index(op.f('ix_app_releases_version_code'), 'app_releases', ['version_code'], unique=False)


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f('ix_app_releases_version_code'), table_name='app_releases')
    op.drop_table('app_releases')
