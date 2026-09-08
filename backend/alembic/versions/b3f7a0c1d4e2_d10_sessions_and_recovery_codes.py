"""D10 (security settings): user_sessions + recovery_codes tables

Revision ID: b3f7a0c1d4e2
Revises: e74b5d854ad7
Create Date: 2026-09-08 00:00:00.000000

Two new, additive tables — no existing column altered, so no
`batch_alter_table` is needed here (that convention is for altering an
existing SQLite table in place; a bare `create_table` is portable as-is).

`user_sessions` backs the dashboard's session list + "sign out everywhere"
(see app/models/user_session.py::UserSession). `recovery_codes` backs MFA
recovery codes (see app/models/recovery_code.py::RecoveryCode). Both are
scoped to `users.id`; neither stores anything in plaintext (session rows
hold jtis, not tokens; recovery-code rows hold bcrypt hashes, not codes).
"""
from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "b3f7a0c1d4e2"
down_revision = "e74b5d854ad7"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "user_sessions",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column("user_id", sa.String(length=36), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("tenant_id", sa.String(length=36), sa.ForeignKey("tenants.id"), nullable=True),
        sa.Column("current_access_jti", sa.String(length=36), nullable=False),
        sa.Column("current_refresh_jti", sa.String(length=36), nullable=False),
        sa.Column("user_agent", sa.String(length=255), nullable=True),
        sa.Column("ip_address", sa.String(length=64), nullable=True),
        sa.Column("last_seen_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index("ix_user_sessions_user_id", "user_sessions", ["user_id"])
    op.create_index("ix_user_sessions_tenant_id", "user_sessions", ["tenant_id"])

    op.create_table(
        "recovery_codes",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column("user_id", sa.String(length=36), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("code_hash", sa.String(length=255), nullable=False),
        sa.Column("consumed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index("ix_recovery_codes_user_id", "recovery_codes", ["user_id"])


def downgrade() -> None:
    op.drop_index("ix_recovery_codes_user_id", table_name="recovery_codes")
    op.drop_table("recovery_codes")
    op.drop_index("ix_user_sessions_tenant_id", table_name="user_sessions")
    op.drop_index("ix_user_sessions_user_id", table_name="user_sessions")
    op.drop_table("user_sessions")
