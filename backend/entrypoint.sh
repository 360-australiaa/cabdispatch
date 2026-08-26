#!/bin/sh
# Runs on every container start (see Dockerfile ENTRYPOINT). Applies pending
# Alembic migrations before the API starts serving -- so a `docker compose up`
# / redeploy always leaves the schema in sync with the image's code, without
# a separate manual migration step. Safe to run on every boot: Alembic no-ops
# when already at head.
set -e

echo "[entrypoint] running alembic migrations..."
alembic upgrade head

echo "[entrypoint] starting uvicorn..."
exec uvicorn app.main:app --host 0.0.0.0 --port 8001