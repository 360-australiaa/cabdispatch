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
"""
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.v1.announcements import router as announcements_router
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
from app.api.v1.trips import router as trips_router
from app.api.v1.users import router as users_router
from app.api.v1.vouchers import router as vouchers_router
from app.api.v1.wallet import router as wallet_router
from app.api.v1.zones import router as zones_router
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
app.include_router(geofences_router)
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
