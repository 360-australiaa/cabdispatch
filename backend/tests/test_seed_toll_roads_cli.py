"""Tests for `scripts/seed_toll_roads.py`'s command-line behaviour — the
error handling around the seeding, not the seeding itself (that lives in
`test_seed_toll_roads.py`, which is async and hits the real test database).

Kept in its own module deliberately: `test_seed_toll_roads.py` sets a
module-level `pytestmark = pytest.mark.asyncio`, and these are plain sync
tests that drive `run_cli()` with a stubbed seeder — inheriting an asyncio
mark they do not want would only produce noise.

Why these exist at all: the guard under test only ever runs during a deploy,
which is the worst possible moment to discover it does not work.
"""
from __future__ import annotations

import pytest
from sqlalchemy.exc import ProgrammingError

from scripts import seed_toll_roads as seed_module


def _programming_error(detail: str) -> ProgrammingError:
    """A ProgrammingError shaped like the one asyncpg really raises — the guard
    matches on the message text, so a hand-rolled exception with the right
    class but the wrong message would test nothing."""
    return ProgrammingError("SELECT toll_roads.charging_policy FROM toll_roads", {}, Exception(detail))


def test_run_cli_explains_a_pre_migration_schema_mismatch(monkeypatch, capsys):
    """The real failure seen on deploy: `docker compose up -d` returns before
    the backend entrypoint has finished `alembic upgrade head`, so a seed run
    started immediately after it queries a column the migration has not added
    yet, and fails with a hundred lines of SQLAlchemy traceback that read like
    the deployment broke."""

    async def _missing_column():
        raise _programming_error("column toll_roads.charging_policy does not exist")

    monkeypatch.setattr(seed_module, "seed_toll_roads", _missing_column)

    with pytest.raises(ProgrammingError):
        seed_module.run_cli()

    stderr = capsys.readouterr().err
    # An operator needs exactly three things from this message: that nothing
    # was written, what to wait for, and the command to re-run.
    assert "Nothing was" in stderr
    assert "healthy" in stderr
    assert "scripts/seed_toll_roads.py" in stderr


def test_run_cli_still_fails_loudly_rather_than_swallowing_the_error(monkeypatch, capsys):
    """The hint must never turn a failure into a success. A migration that
    genuinely has not been applied has to keep exiting non-zero, or a deploy
    script would sail straight past it."""

    async def _missing_table():
        raise _programming_error('relation "toll_points" does not exist')

    monkeypatch.setattr(seed_module, "seed_toll_roads", _missing_table)

    with pytest.raises(ProgrammingError):
        seed_module.run_cli()

    assert "Nothing was" in capsys.readouterr().err


def test_run_cli_does_not_attach_the_hint_to_an_unrelated_database_error(monkeypatch, capsys):
    """The guard narrows a confusing failure; it must not mislabel a different
    one. An unrelated ProgrammingError propagates without 'just wait and
    retry' advice that would send someone down the wrong path."""

    async def _unrelated():
        raise _programming_error("syntax error at or near")

    monkeypatch.setattr(seed_module, "seed_toll_roads", _unrelated)

    with pytest.raises(ProgrammingError):
        seed_module.run_cli()

    assert "Nothing was" not in capsys.readouterr().err
