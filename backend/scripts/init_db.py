"""Zero-friction dev DB bootstrap: `Base.metadata.create_all` against the async
engine, for anyone who doesn't want to run Alembic locally.

This is NOT a substitute for the Alembic migration chain (alembic/versions/)
in any environment that cares about incremental schema history (staging/
production) — use `uv run alembic upgrade head` there. This script exists
purely so a fresh dev checkout can get a working sqlite dev.db with one
command:

    uv run python scripts/init_db.py
"""
from __future__ import annotations

import asyncio

# Import every model so Base.metadata is fully populated before create_all.
import app.models  # noqa: F401
from app.core.config import settings
from app.core.database import Base, engine


async def init_db() -> None:
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    print(f"Created all tables against {settings.DATABASE_URL}")


if __name__ == "__main__":
    asyncio.run(init_db())
