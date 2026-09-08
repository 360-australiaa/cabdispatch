"""tenant_invites — one-time owner-invite tokens for self-serve onboarding

Revision ID: d1e3b5c7a9f0
Revises: c9d2f4a81b7e
Create Date: 2026-09-08 00:00:01.000000

New table only, additive — see app/models/tenant_invite.py::TenantInvite for
the design rationale (only a SHA-256 digest of the raw invite token is ever
stored, never the token itself).
"""
from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "d1e3b5c7a9f0"
down_revision = "c9d2f4a81b7e"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "tenant_invites",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column("tenant_id", sa.String(length=36), sa.ForeignKey("tenants.id"), nullable=False),
        sa.Column("user_id", sa.String(length=36), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("token_hash", sa.String(length=64), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("used_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_tenant_invites_tenant_id", "tenant_invites", ["tenant_id"])
    op.create_index("ix_tenant_invites_user_id", "tenant_invites", ["user_id"])
    op.create_index("ix_tenant_invites_token_hash", "tenant_invites", ["token_hash"], unique=True)


def downgrade() -> None:
    op.drop_index("ix_tenant_invites_token_hash", table_name="tenant_invites")
    op.drop_index("ix_tenant_invites_user_id", table_name="tenant_invites")
    op.drop_index("ix_tenant_invites_tenant_id", table_name="tenant_invites")
    op.drop_table("tenant_invites")
