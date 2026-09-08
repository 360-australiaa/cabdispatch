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
# --workers 1, SPELLED OUT AND DELIBERATE. It was previously implicit (uvicorn
# defaults to one worker with no flag), which meant the only thing standing
# between this deploy and a silently-broken multi-worker one was a comment in
# docker-compose.yml. Above one worker, three things break with no error at
# all: the in-process broadcasters in app/services/live_ops.py,
# JobOfferBroadcaster and message_broadcaster keep their WebSocket subscriber
# sets in one process's memory (so a client on worker A never sees an event
# published on worker B -- the live map, job offers and messages just stop for
# a share of users), and app/core/security.py's JWT revocation store falls back
# to a per-process dict when Redis is unreachable (so a revoked token keeps
# working on every other worker).
#
# app/main.py's assert_single_worker() now backs this up at import: it refuses
# to boot when ENV=production and >1 worker is detected, and logs CRITICAL
# otherwise. Raising this number is therefore not a tuning knob -- do the Redis
# pub/sub swap first: docs/followups/2026-09-08-redis-pubsub-broadcasters.md.
exec uvicorn app.main:app --host 0.0.0.0 --port 8001 --workers 1