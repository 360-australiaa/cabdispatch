"""fleet delete fk safety and wallet transaction poster set null

Revision ID: 6ce5e71ba25d
Revises: 13b4dd5b2c1c
Create Date: 2026-09-07 03:23:56.905478

Fixes a real production bug, reproduced live on prod: `DELETE
/v1/fleet/vehicles/{id}` and `DELETE /v1/fleet/devices/{id}` failed 100% of
the time against postgres (the real production database -- see
docker-compose.yml) for any vehicle/device that had ever sent a heartbeat or
position update, i.e. practically every real one. Root cause: postgres
enforces every FK by default; sqlite (dev/test's DATABASE_URL, see
app/core/config.py) silently does NOT unless a connection explicitly turns
PRAGMA foreign_keys=ON (see app.core.database, same pass) -- so the whole
test suite passed against a DB that could never exercise the constraint that
was rejecting these deletes in production. See app.services.fleet's and
app.models.fleet's module docstrings for the full narrative and the
delete-safety design rationale encoded below (CASCADE for derived/ephemeral
operational telemetry, SET NULL for a dangling back-reference that isn't the
row's real subject); app.services.user.assert_user_deletable documents the
mirror-image decision for users (BLOCK, not cascade, when the dependent row
is audit/financial evidence).

Column/constraint changes (all six existing FKs, none of which were ever
explicitly named -- see the naming-convention note below):

  * device_version_history.device_id      -> ON DELETE CASCADE
  * vehicle_position_history.vehicle_id   -> ON DELETE CASCADE
  * device_pairing_codes.vehicle_id       -> ON DELETE CASCADE
  * device_pairing_codes.used_by_device_id -> ON DELETE SET NULL
  * wallet_transactions.created_by_user_id -> ON DELETE SET NULL (found
    auditing every other NOT-NULL-vs-nullable FK to users.id per the same
    task brief -- see app.models.driver_engagement.WalletTransaction's own
    comment: this is the STAFF poster, not the ledger line's subject
    driver_id, which stays a blocking dependent on purpose)
  * tariff_extras.tariff_id               -> ON DELETE CASCADE (found by
    turning `PRAGMA foreign_keys=ON` on for sqlite, below, and running the
    existing test suite against it, per this same pass's own instructions:
    a genuinely separate real production bug in the identical class, NOT
    part of the original vehicle/device report -- see
    app.models.tariffs.Extra's own comment)
  * tariff_change_log.tariff_id           -> ON DELETE CASCADE (same
    discovery: `write_change_log` unconditionally appends a row on every
    tariff create, so DELETE /v1/tariffs/{id} failed 100% of the time in
    production for every tariff that ever existed -- see
    app.models.tariffs.TariffChangeLog's own comment for why this is
    CASCADE while the sibling actor_user_id FK on the very same table
    stays a blocking NO ACTION on purpose)

NAMING CONVENTION NOTE: this project's declarative `Base` registers no
`naming_convention` (see app.core.database), so every one of these FK
constraints was created UNNAMED (`sa.ForeignKeyConstraint(['col'], [...])`,
no `name=`, in the initial-schema / device-version-history /
vehicle-position-history / driver-engagement migrations). The two backends
disagree about what an unnamed FK's real on-disk name actually is:

  * sqlite has no persisted constraint name at all -- `PRAGMA
    foreign_key_list` always reports it as anonymous, and altering it needs
    `batch_alter_table`'s copy-and-move strategy (SQLite has no ALTER-based
    ADD/DROP CONSTRAINT support at all, same reason as this project's
    existing 30d61efb3583/db95ace20751/97da879e0540 batch migrations) --
    which can only *address* the anonymous constraint it just reflected by
    first giving it a deterministic synthetic name via an explicit
    `naming_convention` passed into the batch context.
  * postgres auto-names an unnamed single-column FK constraint
    `<table>_<column>_fkey` -- its own long-standing default, and a
    genuinely different name from sqlite's synthetic one above.

Hence the dialect branch below -- not stylistic, the two branches really do
target two different on-disk constraint names. Only the sqlite branch could
be exercised in this sandbox (no postgres instance available); the postgres
branch follows postgres's documented default single-column-FK naming
exactly but was NOT verified against a real postgres server -- flagged
honestly rather than claimed otherwise.
"""
from typing import Sequence, Union

from alembic import op

# revision identifiers, used by Alembic.
revision: str = '6ce5e71ba25d'
down_revision: Union[str, Sequence[str], None] = '13b4dd5b2c1c'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

# Only used for sqlite's batch-mode copy-and-move -- gives Alembic a
# deterministic name to address each newly-reflected (otherwise nameless)
# constraint by, within the same batch context. Postgres already has its own
# real, different auto-generated name (see module docstring) and does not
# use this convention at all.
_SQLITE_BATCH_NAMING_CONVENTION = {
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
}


def upgrade() -> None:
    """Upgrade schema."""
    bind = op.get_bind()
    if bind.dialect.name == "sqlite":
        with op.batch_alter_table(
            "device_pairing_codes",
            naming_convention=_SQLITE_BATCH_NAMING_CONVENTION,
            recreate="always",
        ) as batch_op:
            batch_op.drop_constraint(
                batch_op.f("fk_device_pairing_codes_vehicle_id_vehicles"), type_="foreignkey"
            )
            batch_op.drop_constraint(
                batch_op.f("fk_device_pairing_codes_used_by_device_id_devices"), type_="foreignkey"
            )
            batch_op.create_foreign_key(
                batch_op.f("fk_device_pairing_codes_vehicle_id_vehicles"),
                "vehicles", ["vehicle_id"], ["id"], ondelete="CASCADE",
            )
            batch_op.create_foreign_key(
                batch_op.f("fk_device_pairing_codes_used_by_device_id_devices"),
                "devices", ["used_by_device_id"], ["id"], ondelete="SET NULL",
            )

        with op.batch_alter_table(
            "device_version_history",
            naming_convention=_SQLITE_BATCH_NAMING_CONVENTION,
            recreate="always",
        ) as batch_op:
            batch_op.drop_constraint(
                batch_op.f("fk_device_version_history_device_id_devices"), type_="foreignkey"
            )
            batch_op.create_foreign_key(
                batch_op.f("fk_device_version_history_device_id_devices"),
                "devices", ["device_id"], ["id"], ondelete="CASCADE",
            )

        with op.batch_alter_table(
            "vehicle_position_history",
            naming_convention=_SQLITE_BATCH_NAMING_CONVENTION,
            recreate="always",
        ) as batch_op:
            batch_op.drop_constraint(
                batch_op.f("fk_vehicle_position_history_vehicle_id_vehicles"), type_="foreignkey"
            )
            batch_op.create_foreign_key(
                batch_op.f("fk_vehicle_position_history_vehicle_id_vehicles"),
                "vehicles", ["vehicle_id"], ["id"], ondelete="CASCADE",
            )

        with op.batch_alter_table(
            "wallet_transactions",
            naming_convention=_SQLITE_BATCH_NAMING_CONVENTION,
            recreate="always",
        ) as batch_op:
            batch_op.drop_constraint(
                batch_op.f("fk_wallet_transactions_created_by_user_id_users"), type_="foreignkey"
            )
            batch_op.create_foreign_key(
                batch_op.f("fk_wallet_transactions_created_by_user_id_users"),
                "users", ["created_by_user_id"], ["id"], ondelete="SET NULL",
            )

        with op.batch_alter_table(
            "tariff_extras",
            naming_convention=_SQLITE_BATCH_NAMING_CONVENTION,
            recreate="always",
        ) as batch_op:
            batch_op.drop_constraint(
                batch_op.f("fk_tariff_extras_tariff_id_tariffs"), type_="foreignkey"
            )
            batch_op.create_foreign_key(
                batch_op.f("fk_tariff_extras_tariff_id_tariffs"),
                "tariffs", ["tariff_id"], ["id"], ondelete="CASCADE",
            )

        with op.batch_alter_table(
            "tariff_change_log",
            naming_convention=_SQLITE_BATCH_NAMING_CONVENTION,
            recreate="always",
        ) as batch_op:
            batch_op.drop_constraint(
                batch_op.f("fk_tariff_change_log_tariff_id_tariffs"), type_="foreignkey"
            )
            batch_op.create_foreign_key(
                batch_op.f("fk_tariff_change_log_tariff_id_tariffs"),
                "tariffs", ["tariff_id"], ["id"], ondelete="CASCADE",
            )
    else:
        # Postgres supports ALTER TABLE ... DROP/ADD CONSTRAINT natively --
        # no table rebuild needed. Names are postgres's own default
        # single-column-FK auto-names (see module docstring) -- NOT verified
        # against a real postgres server in this sandbox.
        op.drop_constraint(
            "device_pairing_codes_vehicle_id_fkey", "device_pairing_codes", type_="foreignkey"
        )
        op.create_foreign_key(
            "device_pairing_codes_vehicle_id_fkey",
            "device_pairing_codes", "vehicles", ["vehicle_id"], ["id"], ondelete="CASCADE",
        )
        op.drop_constraint(
            "device_pairing_codes_used_by_device_id_fkey", "device_pairing_codes", type_="foreignkey"
        )
        op.create_foreign_key(
            "device_pairing_codes_used_by_device_id_fkey",
            "device_pairing_codes", "devices", ["used_by_device_id"], ["id"], ondelete="SET NULL",
        )
        op.drop_constraint(
            "device_version_history_device_id_fkey", "device_version_history", type_="foreignkey"
        )
        op.create_foreign_key(
            "device_version_history_device_id_fkey",
            "device_version_history", "devices", ["device_id"], ["id"], ondelete="CASCADE",
        )
        op.drop_constraint(
            "vehicle_position_history_vehicle_id_fkey", "vehicle_position_history", type_="foreignkey"
        )
        op.create_foreign_key(
            "vehicle_position_history_vehicle_id_fkey",
            "vehicle_position_history", "vehicles", ["vehicle_id"], ["id"], ondelete="CASCADE",
        )
        op.drop_constraint(
            "wallet_transactions_created_by_user_id_fkey", "wallet_transactions", type_="foreignkey"
        )
        op.create_foreign_key(
            "wallet_transactions_created_by_user_id_fkey",
            "wallet_transactions", "users", ["created_by_user_id"], ["id"], ondelete="SET NULL",
        )
        op.drop_constraint(
            "tariff_extras_tariff_id_fkey", "tariff_extras", type_="foreignkey"
        )
        op.create_foreign_key(
            "tariff_extras_tariff_id_fkey",
            "tariff_extras", "tariffs", ["tariff_id"], ["id"], ondelete="CASCADE",
        )
        op.drop_constraint(
            "tariff_change_log_tariff_id_fkey", "tariff_change_log", type_="foreignkey"
        )
        op.create_foreign_key(
            "tariff_change_log_tariff_id_fkey",
            "tariff_change_log", "tariffs", ["tariff_id"], ["id"], ondelete="CASCADE",
        )


def downgrade() -> None:
    """Downgrade schema."""
    bind = op.get_bind()
    if bind.dialect.name == "sqlite":
        with op.batch_alter_table(
            "tariff_change_log",
            naming_convention=_SQLITE_BATCH_NAMING_CONVENTION,
            recreate="always",
        ) as batch_op:
            batch_op.drop_constraint(
                batch_op.f("fk_tariff_change_log_tariff_id_tariffs"), type_="foreignkey"
            )
            batch_op.create_foreign_key(
                batch_op.f("fk_tariff_change_log_tariff_id_tariffs"),
                "tariffs", ["tariff_id"], ["id"],
            )

        with op.batch_alter_table(
            "tariff_extras",
            naming_convention=_SQLITE_BATCH_NAMING_CONVENTION,
            recreate="always",
        ) as batch_op:
            batch_op.drop_constraint(
                batch_op.f("fk_tariff_extras_tariff_id_tariffs"), type_="foreignkey"
            )
            batch_op.create_foreign_key(
                batch_op.f("fk_tariff_extras_tariff_id_tariffs"),
                "tariffs", ["tariff_id"], ["id"],
            )

        with op.batch_alter_table(
            "wallet_transactions",
            naming_convention=_SQLITE_BATCH_NAMING_CONVENTION,
            recreate="always",
        ) as batch_op:
            batch_op.drop_constraint(
                batch_op.f("fk_wallet_transactions_created_by_user_id_users"), type_="foreignkey"
            )
            batch_op.create_foreign_key(
                batch_op.f("fk_wallet_transactions_created_by_user_id_users"),
                "users", ["created_by_user_id"], ["id"],
            )

        with op.batch_alter_table(
            "vehicle_position_history",
            naming_convention=_SQLITE_BATCH_NAMING_CONVENTION,
            recreate="always",
        ) as batch_op:
            batch_op.drop_constraint(
                batch_op.f("fk_vehicle_position_history_vehicle_id_vehicles"), type_="foreignkey"
            )
            batch_op.create_foreign_key(
                batch_op.f("fk_vehicle_position_history_vehicle_id_vehicles"),
                "vehicles", ["vehicle_id"], ["id"],
            )

        with op.batch_alter_table(
            "device_version_history",
            naming_convention=_SQLITE_BATCH_NAMING_CONVENTION,
            recreate="always",
        ) as batch_op:
            batch_op.drop_constraint(
                batch_op.f("fk_device_version_history_device_id_devices"), type_="foreignkey"
            )
            batch_op.create_foreign_key(
                batch_op.f("fk_device_version_history_device_id_devices"),
                "devices", ["device_id"], ["id"],
            )

        with op.batch_alter_table(
            "device_pairing_codes",
            naming_convention=_SQLITE_BATCH_NAMING_CONVENTION,
            recreate="always",
        ) as batch_op:
            batch_op.drop_constraint(
                batch_op.f("fk_device_pairing_codes_used_by_device_id_devices"), type_="foreignkey"
            )
            batch_op.drop_constraint(
                batch_op.f("fk_device_pairing_codes_vehicle_id_vehicles"), type_="foreignkey"
            )
            batch_op.create_foreign_key(
                batch_op.f("fk_device_pairing_codes_used_by_device_id_devices"),
                "devices", ["used_by_device_id"], ["id"],
            )
            batch_op.create_foreign_key(
                batch_op.f("fk_device_pairing_codes_vehicle_id_vehicles"),
                "vehicles", ["vehicle_id"], ["id"],
            )
    else:
        op.drop_constraint(
            "tariff_change_log_tariff_id_fkey", "tariff_change_log", type_="foreignkey"
        )
        op.create_foreign_key(
            "tariff_change_log_tariff_id_fkey",
            "tariff_change_log", "tariffs", ["tariff_id"], ["id"],
        )
        op.drop_constraint(
            "tariff_extras_tariff_id_fkey", "tariff_extras", type_="foreignkey"
        )
        op.create_foreign_key(
            "tariff_extras_tariff_id_fkey",
            "tariff_extras", "tariffs", ["tariff_id"], ["id"],
        )
        op.drop_constraint(
            "wallet_transactions_created_by_user_id_fkey", "wallet_transactions", type_="foreignkey"
        )
        op.create_foreign_key(
            "wallet_transactions_created_by_user_id_fkey",
            "wallet_transactions", "users", ["created_by_user_id"], ["id"],
        )
        op.drop_constraint(
            "vehicle_position_history_vehicle_id_fkey", "vehicle_position_history", type_="foreignkey"
        )
        op.create_foreign_key(
            "vehicle_position_history_vehicle_id_fkey",
            "vehicle_position_history", "vehicles", ["vehicle_id"], ["id"],
        )
        op.drop_constraint(
            "device_version_history_device_id_fkey", "device_version_history", type_="foreignkey"
        )
        op.create_foreign_key(
            "device_version_history_device_id_fkey",
            "device_version_history", "devices", ["device_id"], ["id"],
        )
        op.drop_constraint(
            "device_pairing_codes_used_by_device_id_fkey", "device_pairing_codes", type_="foreignkey"
        )
        op.drop_constraint(
            "device_pairing_codes_vehicle_id_fkey", "device_pairing_codes", type_="foreignkey"
        )
        op.create_foreign_key(
            "device_pairing_codes_used_by_device_id_fkey",
            "device_pairing_codes", "devices", ["used_by_device_id"], ["id"],
        )
        op.create_foreign_key(
            "device_pairing_codes_vehicle_id_fkey",
            "device_pairing_codes", "vehicles", ["vehicle_id"], ["id"],
        )
