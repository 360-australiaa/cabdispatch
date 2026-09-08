"""Guards on the alembic chain itself.

Why this file exists: until Wave 2 the suite built its schema with
`Base.metadata.create_all()` and never executed a single migration, so a fully
green run said nothing at all about whether a fresh database could actually be
built. That gap cost this project twice —

  * the production-only NOT NULL crash documented at the top of
    `app/services/fleet.py`, invisible to pytest for exactly this reason;
  * `alembic upgrade head` being broken on SQLite outright (a bare
    `op.create_foreign_key` at revision 7060b390bade, which SQLite cannot ALTER
    into an existing table), found by hand during Wave 1 — with 883 tests
    passing the whole time.

`tests/conftest.py` now runs `alembic upgrade head` for the session fixture, so
the chain applying is exercised by every test run. The three checks below cover
what that alone still cannot catch: a forked graph, and model/migration drift.

These run against their OWN temporary database, not the session one, so they
measure the migration chain in isolation and cannot be contaminated by rows or
schema the rest of the suite created.
"""
from __future__ import annotations

import uuid
from pathlib import Path

import pytest
from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
from alembic.script import ScriptDirectory
from sqlalchemy import create_engine

from alembic import command
from app.core.database import Base
from tests.conftest import alembic_config

# Importing app.models registers every domain table on Base.metadata. Without
# it the drift comparison below would happily pass against an empty metadata.
import app.models  # noqa: F401  isort:skip


def test_alembic_has_exactly_one_head():
    """A forked migration graph is not a style problem — `alembic upgrade head`
    refuses to run at all with multiple heads, so a fork bricks every fresh
    deploy and every new dev machine until someone writes a merge revision.

    Wave 1 produced exactly such a fork within hours, when two agents branched
    migrations off the same parent; it was caught by hand. This automates that.
    """
    heads = ScriptDirectory.from_config(alembic_config()).get_heads()
    assert len(heads) == 1, (
        f"alembic has {len(heads)} heads: {heads}. Two revisions were branched off the "
        "same parent. Write a merge revision (`alembic merge -m '...' <rev> <rev>`) "
        "before this lands — `alembic upgrade head` cannot run until you do."
    )


@pytest.fixture(scope="module")
def migrated_sqlite_url(tmp_path_factory) -> str:
    """A throwaway SQLite file built purely by `alembic upgrade head`.

    Deliberately a *sync* fixture: `alembic/env.py` drives its async engine with
    `asyncio.run()`, which raises if a loop is already running, so this must not
    be executed from inside one.
    """
    db_file: Path = tmp_path_factory.mktemp("alembic") / f"migrated_{uuid.uuid4().hex}.db"
    async_url = f"sqlite+aiosqlite:///{db_file.as_posix()}"

    # `alembic/env.py` unconditionally does
    #     config.set_main_option("sqlalchemy.url", settings.DATABASE_URL)
    # so setting the URL on the Config here would simply be overwritten. The
    # settings singleton is already constructed by the time any test runs, so
    # the only way to redirect this one upgrade at a throwaway file is to point
    # the singleton at it for the duration — and put it back afterwards, since
    # the app engine bound to the session database outlives this fixture.
    from app.core.config import settings

    previous = settings.DATABASE_URL
    settings.DATABASE_URL = async_url
    try:
        command.upgrade(alembic_config(), "head")
        yield f"sqlite:///{db_file.as_posix()}"
    finally:
        settings.DATABASE_URL = previous


def test_migration_chain_applies_to_an_empty_database(migrated_sqlite_url):
    """The plainest possible statement of the bug Wave 1 found by hand: a fresh
    database can be built from nothing by running the migrations."""
    engine = create_engine(migrated_sqlite_url)
    try:
        with engine.connect() as conn:
            applied = MigrationContext.configure(conn).get_current_heads()
    finally:
        engine.dispose()

    expected = ScriptDirectory.from_config(alembic_config()).get_heads()
    assert applied == tuple(expected), (
        f"after `upgrade head` the database reports {applied}, expected {tuple(expected)}"
    )


def test_migrated_schema_matches_the_orm_models(migrated_sqlite_url):
    """The migrated schema and `Base.metadata` must describe the same database.

    This is the check that catches "someone added a column to the model and
    forgot to write the migration" — a whole class of bug that is invisible
    locally (where `create_all` builds the schema straight from the models) and
    fatal in production (where only the migrations ever run).

    A non-empty diff means one of two things, and both are yours to fix, not to
    silence: either the model changed without a migration (write the migration),
    or a migration wrote something the model does not describe (fix whichever
    is wrong). Do not add exclusions here to make a red run green.
    """
    engine = create_engine(migrated_sqlite_url)
    try:
        with engine.connect() as conn:
            diff = compare_metadata(MigrationContext.configure(conn), Base.metadata)
    finally:
        engine.dispose()

    assert diff == [], (
        "the migrated schema has drifted from the ORM models. alembic reports:\n  "
        + "\n  ".join(repr(item) for item in diff)
        + "\n\nRun `alembic revision --autogenerate` to see the migration that would "
        "close this, then write it deliberately."
    )
