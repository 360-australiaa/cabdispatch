import asyncio
from logging.config import fileConfig

from sqlalchemy import pool
from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import async_engine_from_config

import app.models  # noqa: F401 — imports register every model on Base.metadata
from alembic import context

# Make sure the app package (and every domain model) is importable and that
# Base.metadata is fully populated before autogenerate/upgrade runs.
from app.core.config import settings
from app.core.database import Base

# this is the Alembic Config object, which provides
# access to the values within the .ini file in use.
config = context.config

# DATABASE_URL always comes from app settings (env var / .env), never from
# alembic.ini, so dev (sqlite) and prod (postgres) both work unmodified.
config.set_main_option("sqlalchemy.url", settings.DATABASE_URL)

# Interpret the config file for Python logging.
#
# disable_existing_loggers=False (2026-09-16 fix; stock Alembic template default is True):
# fileConfig()'s own default silently sets `.disabled = True` on every PRE-EXISTING logger not
# named in alembic.ini's own `[loggers]` section (`root`, `sqlalchemy`, `alembic` only) --
# permanently, for the life of the process, with nothing to ever re-enable them. Harmless for a
# real `alembic upgrade head` CLI invocation (a fresh process, nothing else logging yet), but
# `command.upgrade()` runs this same `env.py` in-process from the test suite's own migration
# tests (`tests/test_migrations.py`) and from `app.services.trips`' own runtime migration guard --
# any test that runs AFTER something has already created "app.request"/"app.core.errors"/etc. as
# real logger objects (i.e., after the app's own middleware/routes have been imported) silently
# and permanently disabled them for the rest of the session. Real bug found live, 2026-09-16:
# tests/test_request_logging.py's two caplog assertions failed with "no app.request record" only
# in full-suite order, never in isolation -- `Logger.disabled=True` bypasses `caplog.at_level`'s own
# level-setting entirely, a different mechanism from the level/propagation caplog actually controls.
if config.config_file_name is not None:
    fileConfig(config.config_file_name, disable_existing_loggers=False)

target_metadata = Base.metadata


def run_migrations_offline() -> None:
    """Run migrations in 'offline' mode — emits SQL to stdout, no DB connection."""
    url = config.get_main_option("sqlalchemy.url")
    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )

    with context.begin_transaction():
        context.run_migrations()


def do_run_migrations(connection: Connection) -> None:
    context.configure(connection=connection, target_metadata=target_metadata)

    with context.begin_transaction():
        context.run_migrations()


async def run_async_migrations() -> None:
    """Async engine (aiosqlite / asyncpg) driven migration run, executed via
    `connection.run_sync` since Alembic's migration machinery itself is sync."""
    connectable = async_engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )

    async with connectable.connect() as connection:
        await connection.run_sync(do_run_migrations)

    await connectable.dispose()


def run_migrations_online() -> None:
    asyncio.run(run_async_migrations())


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
