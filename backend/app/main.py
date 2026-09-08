"""FastAPI application entrypoint.

Router wiring is the integration step's responsibility: each of the 14
domain slices below was built independently and is included here. auth is
foundation glue (not one of the domains) added at integration time so
seeded users can obtain a bearer token at all — see
app/api/v1/auth.py's module docstring. users was added post-integration to
close a real CRUD gap: no domain slice owned "create a driver via the API".

Two domains (payments, tariffs) each export two routers under distinct path
prefixes — both are included per their own module docstrings. live_ops owns
no table and exports one router with several literal, non-`/v1/<domain>`-
prefixed paths (`/v1/vehicles`, `/v1/drivers`, `/v1/fleet/positions`,
`WS /v1/fleet/live`) that were verified not to collide with the sibling
fleet domain's own `/v1/fleet/vehicles` / `/v1/fleet/devices` routes (see
app/api/v1/live_ops.py's module docstring).

jobs (`/v1/jobs`, `WS /v1/jobs/live`) and messages (`/v1/messages`,
`WS /v1/messages/live`) were added in this integration pass. Both were built
independently against the same conventions as the other 12 domains and both
were verified at integration time to use path prefixes (`jobs`, `messages`)
that do not collide with any existing domain's routes, literal or
prefixed — including live_ops's literal paths above.

reports (`/v1/reports`) is a pure reporting/export layer added on top of the
existing `trips` table (NSW PtP compliance export, revenue dashboard,
GST/BAS-prep summary) — it owns no table of its own and its `reports_router`
path prefix does not collide with any existing domain's routes.

fatigue_alerts (`/v1/fatigue-alerts`, blueprint 12.3) was added in the
MDM-lite/fatigue-monitoring pass on top of this already-integrated tree. It
owns the `FatigueAlert` table (list/acknowledge only via this router) but
alerts themselves are raised as a side effect of `PATCH /v1/trips/{id}/tick`
in the existing `trips` router — see `app.services.fatigue` and
`app/api/v1/trips.py`'s `tick_trip` docstring. That same pass also extended
the `fleet` domain's `Device` model/router with `locate_requested` /
`reboot_requested` MDM-lite command flags (no new router or path prefix).

tenants (`/v1/tenants`) is new in this pass: owner-only
`POST /v1/tenants/{id}/admin-pin` to set the tenant's server-verified admin
PIN (see `app.models.tenant.Tenant.admin_pin_hash`, `app.services.tenant`),
replacing the Android app's hardcoded ADMIN_PIN_PLACEHOLDER factory-reset
check. This pass also extended the `fleet` domain's device router (no new
router of its own) with `POST /v1/fleet/devices/{id}/verify-admin-pin` — the
device-facing check endpoint a device calls to validate a PIN without ever
seeing the hash.

zones (`/v1/zones`) is new in this pass: named dispatch zones with a
driver-facing short code (e.g. "17"), "plot into a zone" (stored as
plotted_zone_id/plotted_at on the existing `shifts` table -- see
app.models.shift.Shift's DEVIATION note), and GET /v1/zones/stats -- a live
per-zone demand snapshot matching a screen on a real competitor taxi meter
(MTI). Owns one new table (`zones`); reads shifts/live_ops/jobs/trips
read-only for the stats aggregation (see app.services.zones).

vouchers (/v1/vouchers) and corporate-accounts (/v1/corporate-accounts) are
new in this pass: real backing ledgers for the trips domain's "voucher"/
"account" Trip.payment_method values, replacing the earlier non-empty-
string-only stub validation in app.services.payments.redeem_voucher /
validate_account_reference (see app/models/vouchers.py). Each owns one new
table and its own CRUD router (list/get open to any authenticated tenant
user, create/update/delete owner/admin-only); neither collides with any
existing domain's path prefix.

platform (/v1/platform) is new in this pass: a platform-owner-only admin
console - GET/POST /v1/platform/tenants (list every tenant / onboard a
new one), GET /v1/platform/tenants/{id}/summary (per-tenant health
rollup), GET /v1/platform/health (platform-wide aggregate). Gated to
role == "owner" AND tenant_id == PLATFORM_TENANT_ID specifically (see
app.api.v1.platform.require_platform_owner) - stricter than the plain
owner-role gate every other domain router uses. Closes the gap where the
platform tenant could already act cross-tenant via get_current_tenant_id
tenant_id override (see app.core.security) but had no dedicated
management surface. Path prefix platform does not collide with any
existing domain routes.

driver engagement (me / wallet / ratings / announcements / incentives) is
new in this pass: the real backing for the four driver-tablet dashboard
tiles (Wallet Balance, Driver Rating, Announcements, Incentive Progress).
`/v1/me/*` is the driver-facing read surface (scoped to the caller's own
user id, never a query param); `/v1/wallet`, `/v1/ratings`,
`/v1/announcements`, `/v1/incentives` are the operator CRUD surfaces
(owner/admin writes, same gate as vouchers). ratings additionally owns one
literal `/v1/trips/{id}/rating` path (Close & Pay's post-close rating hook)
verified not to collide with any route in the trips router itself -- same
"literal path owned by a sibling router" precedent as live_ops. See
app/models/driver_engagement.py for the "derived, never stored" rule.

app_releases (/v1/app-releases + /v1/platform/app-releases) is new in this
pass: real OTA self-update publishing for the Android meter app. Two
routers under distinct prefixes -- same convention as payments/tariffs
above. `POST /v1/platform/app-releases` (platform-owner only, same gate as
the rest of `/v1/platform/...`) uploads a new APK; `GET
/v1/app-releases/latest` and `GET /v1/app-releases/{id}/download` are open
to any authenticated tenant/device user (not platform-owner-gated -- these
are reads, and every tenant's devices run the same one app build). See
app/models/app_release.py for why this table is platform-wide, not
tenant-scoped. HONEST CAVEAT this pass does not paper over: these tablets
are Knox Manage device-owner-enrolled, and Knox Manage's current policy
blocks installs from unknown sources (see
docs/KNOX_LOCKDOWN_RUNBOOK.md/docs/OTA_UPDATE_ROLLOUT.md) -- a Knox Manage
policy exception is still required per-fleet before this works end-to-end,
and every install still needs one Android system confirmation tap (this
app is not Device Owner, so it cannot install silently).
"""
import logging
import os
import re
import sys

from fastapi import FastAPI, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.api.v1.announcements import router as announcements_router
from app.api.v1.app_releases import platform_router as app_releases_platform_router
from app.api.v1.app_releases import router as app_releases_router
from app.api.v1.audit_log import router as audit_log_router
from app.api.v1.auth import router as auth_router
from app.api.v1.billing import router as billing_router
from app.api.v1.compliance import router as compliance_router
from app.api.v1.corporate_accounts import router as corporate_accounts_router
from app.api.v1.duress import router as duress_router
from app.api.v1.duress_device import router as duress_device_router
from app.api.v1.fatigue_alerts import router as fatigue_alerts_router
from app.api.v1.fleet import router as fleet_router
from app.api.v1.geofences import router as geofences_router
from app.api.v1.incentives import router as incentives_router
from app.api.v1.jobs import router as jobs_router
from app.api.v1.live_ops import router as live_ops_router
from app.api.v1.me import router as me_router
from app.api.v1.messages import router as messages_router
from app.api.v1.payments import router as payments_router
from app.api.v1.payments import webhook_router as payments_webhook_router
from app.api.v1.platform import router as platform_router
from app.api.v1.psl_ledger import router as psl_ledger_router
from app.api.v1.ratings import router as ratings_router
from app.api.v1.reports import router as reports_router
from app.api.v1.shifts import router as shifts_router
from app.api.v1.tariffs import fares_order_router
from app.api.v1.tariffs import router as tariffs_router
from app.api.v1.tenants import router as tenants_router
from app.api.v1.toll_roads import router as toll_roads_router
from app.api.v1.trips import router as trips_router
from app.api.v1.users import router as users_router
from app.api.v1.vouchers import router as vouchers_router
from app.api.v1.wallet import router as wallet_router
from app.api.v1.zones import router as zones_router
from app.core.config import settings
from app.core.errors import ExceptionEnvelopeMiddleware, register_exception_handlers
from app.core.health import health_report
from app.core.logging import RequestIdMiddleware, configure_logging
from app.core.ratelimit import RateLimitExceeded, limiter
from app.core.ratelimit import backend as ratelimit_backend

# FIRST, before anything logs. Until this call existed there was no logging
# configuration in this project at all (backend audit §6): modules called
# getLogger and the app inherited whatever uvicorn installed, which meant no
# request correlation and no machine-parseable output. See app/core/logging.py.
configure_logging()

logger = logging.getLogger(__name__)


# --- single-worker enforcement (backend audit §6 gap 9) ----------------------
# docker-compose.yml carries a long comment explaining that this app MUST run
# with exactly one worker, and entrypoint.sh spells `--workers 1` out. Both are
# documentation: nothing *checks*. That is not enough for this failure, because
# raising the worker count breaks things **silently** -- no error, just a live
# map that stops updating for half the users and a revoked token that still
# works. This turns the comment into an assertion.
#
# There is no portable way to ask "how many workers am I one of", so we detect
# the three ways it can actually be set in this deployment: uvicorn's
# `--workers/-w` argv flag, uvicorn/gunicorn's `WEB_CONCURRENCY` env var, and
# gunicorn's `GUNICORN_CMD_ARGS`. A false negative is possible (an exotic
# launcher); a false positive is not, which is the right way round for
# something that refuses to boot in production.
def _detected_worker_count() -> tuple[int, str] | None:
    argv = " ".join(sys.argv)
    match = re.search(r"(?:--workers|-w)[=\s]+(\d+)", argv)
    if match:
        return int(match.group(1)), "the --workers command line flag"

    for env_var in ("WEB_CONCURRENCY", "GUNICORN_CMD_ARGS"):
        raw = os.environ.get(env_var, "")
        if not raw:
            continue
        env_match = re.search(r"(?:--workers|-w)[=\s]+(\d+)", raw) if env_var != "WEB_CONCURRENCY" else re.fullmatch(r"\s*(\d+)\s*", raw)
        if env_match:
            return int(env_match.group(1)), f"${env_var}"
    return None


def assert_single_worker() -> None:
    detected = _detected_worker_count()
    if detected is None or detected[0] <= 1:
        return
    count, source = detected
    message = (
        f"MULTI-WORKER DEPLOY DETECTED ({count} workers, from {source}). This backend is "
        "correct only at exactly one worker. The three in-process broadcasters "
        "(app/services/live_ops.py's position broadcaster, JobOfferBroadcaster, "
        "message_broadcaster) hold their subscriber sets in this process's memory, so a "
        "WebSocket client connected to one worker never sees an event published on "
        "another -- the live map, job offers and messages silently stop for a share of "
        "users. app/core/security.py's JWT revocation store has the same problem when "
        "Redis is unreachable: a token revoked on one worker stays valid on every other. "
        "Fix it by scaling horizontally behind Caddy with a shared Redis pub/sub backend "
        "for the broadcasters (see docs/followups/2026-09-08-redis-pubsub-broadcasters.md), "
        "not by raising the worker count."
    )
    if settings.ENV == "production":
        # Refusing to boot is the honest response: the alternative is serving
        # traffic that is wrong in a way nobody will notice for weeks.
        raise RuntimeError(message)
    logger.critical(message)


assert_single_worker()

app = FastAPI(title="Cab Dispatch API", version="0.1.0")

# --- rate limiting (backend audit §5 "Rate limiting": there was none, anywhere) --
# `limiter` is attached to app.state because that is where slowapi's decorators
# look it up from the Request. The exception handler turns a tripped decorator
# limit into a 429 with Retry-After rather than an unhandled exception. The
# per-route limits live as decorators next to the routes they protect
# (app/api/v1/auth.py, app/api/v1/fleet.py) so a reader of a route sees its
# limit; the numbers themselves are constants in app/core/ratelimit.py.
app.state.limiter = limiter


@app.exception_handler(RateLimitExceeded)
async def _rate_limit_handler(request: Request, exc: RateLimitExceeded) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_429_TOO_MANY_REQUESTS,
        content={"detail": f"Rate limit exceeded: {exc.detail}"},
        headers={"Retry-After": "60"},
    )


# Logged at import so a deploy can see, in its very first lines of output,
# whether it is actually sharing limits across workers (Redis) or silently
# multiplying every limit by the worker count (in-memory fallback).
logger.info(
    "Rate limiting %s (backend: %s)",
    "enabled" if limiter.enabled else "DISABLED",
    "redis" if ratelimit_backend.using_redis else "in-memory fallback",
)

# --- middleware stack -------------------------------------------------------
# ORDER MATTERS AND IS INVERTED. Starlette's `add_middleware` *prepends*, and
# the stack is then built by wrapping in reverse -- so the LAST middleware added
# here is the OUTERMOST at request time. Written out, requests flow:
#
#     client -> RequestIdMiddleware -> CORSMiddleware
#            -> ExceptionEnvelopeMiddleware -> routes
#
# and responses come back out the same way. That specific arrangement is the
# whole fix for the "phantom 503" (see app/core/errors.py and the postmortem at
# the top of app/services/fleet.py): the envelope middleware must sit INSIDE
# CORS so that the 500 it produces travels back out through the CORS layer and
# gets `Access-Control-Allow-Origin` stamped on it. A plain
# `@app.exception_handler(Exception)` cannot do this -- FastAPI hands that to
# ServerErrorMiddleware, which is outside every user middleware, which is
# exactly how the header went missing in the first place.
#
# RequestIdMiddleware is outermost so that even a CORS preflight and any error
# CORS itself produces still carry (and log) a request id.
app.add_middleware(ExceptionEnvelopeMiddleware)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
    # Browsers hide every non-safelisted response header from JS unless it is
    # exposed. Without this the dashboard could not read the request id off a
    # failed response, which is the id an operator needs in order to find the
    # matching log line.
    expose_headers=["X-Request-ID"],
)
app.add_middleware(RequestIdMiddleware)

# Fallback only; the middleware above is the load-bearing half. See errors.py.
register_exception_handlers(app)


@app.get("/health")
async def health():
    """Readiness: database `SELECT 1`, alembic head match, Redis ping.

    Returns 503 when a required dependency is down, so `docker compose up
    --wait` and the Dockerfile HEALTHCHECK actually gate on something. This
    used to return `{"status": "ok"}` unconditionally -- see app/core/health.py
    for what each check is for and why Redis is only fatal in production.
    """
    body, healthy = await health_report()
    return JSONResponse(
        status_code=status.HTTP_200_OK if healthy else status.HTTP_503_SERVICE_UNAVAILABLE,
        content=body,
    )


@app.get("/health/live")
async def health_live():
    """Liveness: answers from process memory, touches nothing.

    Deliberately trivial. Docker restarts a container whose HEALTHCHECK fails,
    so if the liveness probe did dependency checks a slow Postgres would
    restart a healthy API process -- dropping every live WebSocket and fixing
    nothing. Point container liveness at this; point readiness/monitoring at
    `/health`.
    """
    return {"status": "alive", "env": settings.ENV}


app.include_router(auth_router)
app.include_router(users_router)
app.include_router(fleet_router)
app.include_router(geofences_router)
app.include_router(toll_roads_router)
app.include_router(tariffs_router)
app.include_router(fares_order_router)
app.include_router(trips_router)
app.include_router(shifts_router)
app.include_router(payments_router)
app.include_router(payments_webhook_router)
app.include_router(psl_ledger_router)
app.include_router(duress_router)
app.include_router(duress_device_router)
app.include_router(live_ops_router)
app.include_router(billing_router)
app.include_router(compliance_router)
app.include_router(audit_log_router)
app.include_router(jobs_router)
app.include_router(messages_router)
app.include_router(reports_router)
app.include_router(fatigue_alerts_router)
app.include_router(tenants_router)
app.include_router(zones_router)
app.include_router(platform_router)
app.include_router(vouchers_router)
app.include_router(corporate_accounts_router)
app.include_router(me_router)
app.include_router(wallet_router)
app.include_router(ratings_router)
app.include_router(announcements_router)
app.include_router(incentives_router)
app.include_router(app_releases_router)
app.include_router(app_releases_platform_router)
