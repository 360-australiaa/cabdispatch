# Cab Dispatch backend -- production image. Multi-stage: uv installs deps into
# a venv in the builder stage, the final stage copies only the venv + app
# source (no uv/build toolchain in the shipped image). Matches this project's
# uv-based dependency management (pyproject.toml + uv.lock) rather than pip.
FROM python:3.12-slim AS builder

# Official uv static binary, per astral-sh's documented Docker pattern --
# avoids installing uv via pip/curl inside the image.
COPY --from=ghcr.io/astral-sh/uv:0.5.11 /uv /uvx /bin/

WORKDIR /app

# Dependency layer first (cache-friendly -- only re-runs uv sync when
# pyproject.toml/uv.lock change, not on every source edit).
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-install-project --no-dev

# Now the application source.
COPY app ./app
COPY alembic ./alembic
COPY alembic.ini ./
COPY scripts ./scripts
RUN uv sync --frozen --no-dev

FROM python:3.12-slim AS runtime

# curl: used by the HEALTHCHECK below to hit GET /health. apt-get clean
# (not a manual cache-directory delete) keeps the layer slim.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && apt-get clean

WORKDIR /app
COPY --from=builder /app /app
ENV PATH="/app/.venv/bin:$PATH"

# uploads/ is where app.services.{duress,compliance,receipts,duress_device}
# write local-disk files (BACKEND_ROOT/uploads/...) -- created here so the
# directory exists even before the named volume (see docker-compose.yml) is
# first mounted over it.
RUN mkdir -p /app/uploads

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 8001

# LIVENESS, not readiness -- /health/live touches nothing and only says "this
# process is up". The dependency check (/health: DB SELECT 1, alembic head
# match, Redis ping) is wired as the *compose* healthcheck instead, so
# `docker compose up --wait` still gates the deploy on real dependencies while
# a slow Postgres can never mark this container unhealthy and drive a restart
# loop that fixes nothing. See app/core/health.py.
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
    CMD curl -fsS http://localhost:8001/health/live || exit 1

ENTRYPOINT ["/entrypoint.sh"]