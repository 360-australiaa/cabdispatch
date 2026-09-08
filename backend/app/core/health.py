"""The real health check behind `GET /health`.

What was wrong
--------------
`GET /health` used to be `return {"status": "ok", "env": ...}` -- it touched
nothing (backend audit §6 "Health endpoint"). That is worse than having no
health endpoint at all, because `backend/Dockerfile`'s `HEALTHCHECK` curls it
and `docker compose up --wait` gates the whole deploy on the result. A backend
whose Postgres is dead reported `healthy` and the deploy went green. A health
check that cannot fail is not a health check.

What it checks now, and why each one earns its place
----------------------------------------------------
* **database** -- `SELECT 1` on the app's own engine. The single most likely
  dependency failure, and the one the old endpoint most conspicuously ignored.
* **migrations** -- the `alembic_version` row in the database must equal the
  migration head of the code in this image. This is a specific, likely and
  currently-invisible failure: `entrypoint.sh` runs `alembic upgrade head` on
  boot, but if that step is skipped, fails, or the image is rolled back to
  older code against a newer database, the container serves traffic against a
  schema its ORM does not match. That failure looks like scattered 500s on
  unrelated routes, not like "the database is down" -- exactly the shape of the
  production-only bug documented at the top of `app/services/fleet.py`.
* **redis** -- `PING`. Redis is genuinely optional for correctness in this app
  (both `app.core.security`'s revocation store and `app.core.ratelimit` fall
  back to per-process memory and say so), so a Redis outage is reported as
  `degraded` and does NOT fail the check outside production. In production it
  IS fatal, because there the fallbacks are silently wrong: a revoked token
  stays valid and every rate limit is multiplied by the process count. Being
  honest about that difference is the point -- the check reports what it found
  either way, it just decides differently whether to fail the deploy.

Why `/health/live` is separate
------------------------------
Docker restarts a container whose `HEALTHCHECK` fails. If the *liveness* probe
did dependency checks, a slow Postgres would restart a perfectly healthy API
process, which drops every live WebSocket and fixes nothing -- a restart loop
driven by someone else's outage. So `/health/live` answers from process memory
only (the container is alive), and `/health` is the readiness/dependency check
that `up --wait` and monitoring should gate on.
"""
from __future__ import annotations

import asyncio
import logging
from pathlib import Path

from sqlalchemy import text

from app.core.config import settings
from app.core.database import engine

logger = logging.getLogger(__name__)

# Each dependency gets its own budget. Kept short deliberately: a health probe
# that hangs for 30s is itself an outage, because Docker's HEALTHCHECK timeout
# (5s, see the Dockerfile) fires first and reports `unhealthy` with no detail.
CHECK_TIMEOUT_SECONDS = 3.0

ALEMBIC_INI = Path(__file__).resolve().parents[2] / "alembic.ini"


def _code_head() -> str | None:
    """The migration head of the code in this image, or None if the migration
    directory cannot be read at all. Multiple heads is itself a failure -- the
    repo convention (program plan §1 rule 8) is exactly one."""
    from alembic.config import Config
    from alembic.script import ScriptDirectory

    cfg = Config(str(ALEMBIC_INI))
    cfg.set_main_option("script_location", str(ALEMBIC_INI.parent / "alembic"))
    heads = ScriptDirectory.from_config(cfg).get_heads()
    if len(heads) != 1:
        raise RuntimeError(f"alembic has {len(heads)} heads: {heads}")
    return heads[0]


async def check_database() -> dict:
    """`SELECT 1`. Fails the check on any exception or on timeout."""
    try:
        async with engine.connect() as conn:
            await asyncio.wait_for(conn.execute(text("SELECT 1")), CHECK_TIMEOUT_SECONDS)
        return {"status": "ok"}
    except Exception as exc:
        # Any failure here means "not healthy" -- there is no exception class
        # worth exempting from that.
        logger.exception("Health check: database unreachable")
        return {"status": "error", "detail": f"{type(exc).__name__}: {exc}"}


async def check_migrations() -> dict:
    """Database `alembic_version` == this image's single migration head."""
    try:
        head = await asyncio.to_thread(_code_head)
    except Exception as exc:
        logger.exception("Health check: cannot resolve code migration head")
        return {"status": "error", "detail": f"cannot resolve code head: {exc}"}

    try:
        async with engine.connect() as conn:
            result = await asyncio.wait_for(
                conn.execute(text("SELECT version_num FROM alembic_version")),
                CHECK_TIMEOUT_SECONDS,
            )
            rows = [row[0] for row in result.fetchall()]
    except Exception as exc:
        # No `alembic_version` table is a real, distinct failure: the schema was
        # built by something other than the migration chain (`create_all`), so
        # nothing guarantees it matches production's.
        logger.exception("Health check: cannot read alembic_version")
        return {"status": "error", "code_head": head, "detail": f"cannot read alembic_version: {exc}"}

    if rows == [head]:
        return {"status": "ok", "head": head}
    return {
        "status": "error",
        "code_head": head,
        "db_head": rows[0] if len(rows) == 1 else rows,
        "detail": "database schema version does not match this image's alembic head",
    }


async def check_redis() -> dict:
    """`PING`. See the module docstring for why this is only fatal in production."""
    if not settings.REDIS_URL:
        return {"status": "skipped", "detail": "REDIS_URL not configured"}
    client = None
    try:
        import redis.asyncio as redis_asyncio

        client = redis_asyncio.from_url(
            settings.REDIS_URL,
            socket_connect_timeout=CHECK_TIMEOUT_SECONDS,
            socket_timeout=CHECK_TIMEOUT_SECONDS,
        )
        await asyncio.wait_for(client.ping(), CHECK_TIMEOUT_SECONDS)
        return {"status": "ok"}
    except Exception as exc:
        return {"status": "error", "detail": f"{type(exc).__name__}: {exc}"}
    finally:
        if client is not None:
            try:
                await client.aclose()
            except Exception:  # noqa: S110 -- teardown must never fail a probe
                pass


async def health_report() -> tuple[dict, bool]:
    """Runs every check concurrently. Returns (body, healthy).

    Concurrent rather than sequential so the worst case is one timeout, not
    three -- three 3s timeouts in series would exceed Docker's 5s probe timeout
    and report `unhealthy` with no body at all.
    """
    database, migrations, redis_result = await asyncio.gather(
        check_database(), check_migrations(), check_redis()
    )

    redis_required = settings.ENV == "production"
    redis_ok = redis_result["status"] in ("ok", "skipped")
    healthy = (
        database["status"] == "ok"
        and migrations["status"] == "ok"
        and (redis_ok or not redis_required)
    )

    if not redis_ok and not redis_required:
        # Reported, but not fatal: outside production the in-memory fallbacks
        # in security/ratelimit are the documented, tested path.
        redis_result = dict(redis_result, status="degraded", required=False)

    body = {
        "status": "ok" if healthy else "error",
        "env": settings.ENV,
        "checks": {"database": database, "migrations": migrations, "redis": redis_result},
    }
    return body, healthy
