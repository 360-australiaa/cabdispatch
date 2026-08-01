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
"""
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.v1.audit_log import router as audit_log_router
from app.api.v1.auth import router as auth_router
from app.api.v1.billing import router as billing_router
from app.api.v1.compliance import router as compliance_router
from app.api.v1.duress import router as duress_router
from app.api.v1.fleet import router as fleet_router
from app.api.v1.jobs import router as jobs_router
from app.api.v1.live_ops import router as live_ops_router
from app.api.v1.messages import router as messages_router
from app.api.v1.payments import router as payments_router
from app.api.v1.payments import webhook_router as payments_webhook_router
from app.api.v1.psl_ledger import router as psl_ledger_router
from app.api.v1.shifts import router as shifts_router
from app.api.v1.tariffs import fares_order_router
from app.api.v1.tariffs import router as tariffs_router
from app.api.v1.trips import router as trips_router
from app.api.v1.users import router as users_router
from app.core.config import settings

app = FastAPI(title="Cab Dispatch API", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
async def health():
    return {"status": "ok", "env": settings.ENV}


app.include_router(auth_router)
app.include_router(users_router)
app.include_router(fleet_router)
app.include_router(tariffs_router)
app.include_router(fares_order_router)
app.include_router(trips_router)
app.include_router(shifts_router)
app.include_router(payments_router)
app.include_router(payments_webhook_router)
app.include_router(psl_ledger_router)
app.include_router(duress_router)
app.include_router(live_ops_router)
app.include_router(billing_router)
app.include_router(compliance_router)
app.include_router(audit_log_router)
app.include_router(jobs_router)
app.include_router(messages_router)
