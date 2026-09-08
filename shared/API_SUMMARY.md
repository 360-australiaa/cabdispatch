# Cab Dispatch API — Quick Orientation

Full machine-readable spec: `shared/openapi.json` — regenerated from the live app
(`app.main:app`'s own `.openapi()`, not hand-edited) on 2026-09-08 (Wave 4, B11). It currently
lists **178 route+method entries**. This file is a faster human/agent orientation — read it
first; fall back to the OpenAPI JSON for exact request/response schemas. **Regenerate, don't
hand-edit, both files** when routes change — see the snippet in `docs/DASHBOARD_WEB_OVERVIEW.md`'s
closing note for how.

This file was substantially out of date before this pass — the router map below had drifted well
behind the live app (whole domains like `platform`, `duress-devices`, `toll-roads`, `wallet`,
`ratings`, `zones`, `incentives`, `announcements`, `corporate-accounts`, and the `/v1/me/**`
convenience routes were missing entirely). It has been rebuilt directly from the regenerated
`openapi.json`'s path list rather than carried forward — treat any claim below you can't verify
against that file as suspect and prefer the JSON.

## Base URL

- Local dev: `http://localhost:8001` (run with `uv run uvicorn app.main:app --port 8001` from
  `backend/`)
- All application routes are versioned under `/v1/...`. `/health` is unversioned.

## Authentication

Bearer JWT. Every domain route (except `POST /v1/stripe/webhook`) requires
`Authorization: Bearer <access_token>` and resolves the caller's tenant via
`app.core.security.get_current_tenant_id` — this is the sole multi-tenancy isolation mechanism in
the system; every query is filtered by it server-side.

### `POST /v1/auth/login`

Request:
```json
{ "email": "admin@cabdispatch.test", "password": "ChangeMe123!" }
```

Response `200`:
```json
{
  "access_token": "<jwt, 30 min TTL>",
  "refresh_token": "<jwt, 14 day TTL>",
  "token_type": "bearer",
  "user": {
    "id": "...", "tenant_id": "...", "role": "owner",
    "name": "Platform Admin", "email": "admin@cabdispatch.test", "status": "active"
  }
}
```

Other auth endpoints: `POST /v1/auth/refresh` (`{"refresh_token": "..."}` → new token pair),
`GET /v1/auth/me` (current user), `POST /v1/auth/logout` (204, client should discard tokens).

### `POST /v1/auth/driver-login`

The real driver-facing counterpart to `POST /v1/auth/login` — Driver ID + PIN instead of
email + password, for meter/kiosk devices. Replaces the placeholder driverId→email / pin→password
mapping on the Android side.

Request:
```json
{ "driver_code": "HQMGA", "pin": "ChangeMe123!" }
```

Response shape is identical to `POST /v1/auth/login` (`TokenResponse` or, if the driver has MFA
enabled, `MfaRequiredResponse` — same two-step `POST /v1/auth/mfa/login` exchange). `driver_code` is
globally unique (like `email`, not tenant-scoped) and only ever set on `role == "driver"` users —
see `POST /v1/users` below.

### Admin MFA (TOTP)

`POST /v1/auth/login` returns `TokenResponse` as before for users without MFA enabled. For a user
with MFA enabled it instead returns `MfaRequiredResponse` (`{"mfa_required": true, "mfa_token": "..."}`)
— exchange that short-lived `mfa_token` plus a 6-digit TOTP code via `POST /v1/auth/mfa/login` to get
the real `TokenResponse`. Existing no-MFA logins are unaffected (still one call, same response shape).

- `POST /v1/auth/mfa/setup` — authenticated; returns a TOTP secret + `otpauth://` QR URI, MFA not yet
  enforced until verified.
- `POST /v1/auth/mfa/verify` — authenticated; body `{"code": "123456"}`, confirms the setup TOTP code
  and flips MFA on for the account.
- `POST /v1/auth/mfa/disable` — authenticated; body `{"code": "123456"}`, turns MFA back off.

### Seeded demo accounts (via `scripts/seed.py`)

| Email | Password | Role | Tenant | Notes |
|---|---|---|---|---|
| `admin@cabdispatch.test` | `ChangeMe123!` | owner | TCT (platform, id `00000000-0000-0000-0000-000000000000`) | Cross-tenant: pass `?tenant_id=<id>` on any request to act as that tenant |
| `owner@lillycabs.test` | `ChangeMe123!` | owner | Lilly Cabs (demo operator) | Locked to its own tenant |
| `driver@lillycabs.test` | `ChangeMe123!` | driver | Lilly Cabs (demo operator) | Also logs in via `POST /v1/auth/driver-login` using its seeded `driver_code` (printed by `scripts/seed.py`'s final summary — value is random per fresh DB, not hardcoded here) |

## Roles

`owner` > `admin` > `dispatcher` > `driver` (informal hierarchy; each endpoint documents its own
`require_role(...)` set — there is no implicit inheritance in code, routes list every role that may
call them).

## Router map

Rebuilt directly from `shared/openapi.json`'s path list (2026-09-08). Every route below except
`POST /v1/stripe/webhook` and a few explicitly-marked device/unauthenticated ones requires a
bearer token and is tenant-scoped via `get_current_tenant_id`.

| Prefix | Domain | Key endpoints |
|---|---|---|
| `/v1/auth` | Auth | `POST /login`, `POST /driver-login` (Driver ID + PIN, meter/kiosk-facing), `POST /refresh`, `POST /logout`, `GET /me`, `POST /mfa/setup`, `POST /mfa/verify`, `POST /mfa/disable`, `POST /mfa/login` |
| `/v1/users` | Users (staff + driver onboarding/CRUD) | `GET,POST /`, `GET,PATCH,DELETE /{id}`, `POST,GET /{id}/photo` — `POST /` auto-generates a `driver_code` for `role="driver"` when none supplied; `driver_code` is required to use `POST /v1/auth/driver-login` |
| `/v1/tenants` | Tenants | `GET,PATCH /me`, `POST /{tenant_id}/admin-pin` (owner-only; sets the tenant's server-verified admin PIN, hash never returned) |
| `/v1/platform` | Platform console (cross-tenant, platform-owner only) | `GET,POST /tenants`, `PATCH /tenants/{id}`, `GET /tenants/{id}/summary`, `GET /tenants/{id}/billing`, `GET /billing/summary`, `GET /health`, `GET,POST /app-releases`, `PATCH /app-releases/{id}`, `POST /invites/accept` — self-serve tenant creation (`POST /tenants`) lands here |
| `/v1/fleet` | Fleet (vehicles + devices + positions) | `GET,POST /vehicles`, `GET,PATCH,DELETE /vehicles/{id}`, `POST /vehicles/{id}/pairing-code`, `GET /vehicles/{id}/{lifetime-totals,shift-history,pilot-report,evidence-pack}`, `GET,POST /devices`, `GET /devices/me`, `POST /devices/register`, `GET,PATCH,DELETE /devices/{id}`, `POST /devices/{id}/{heartbeat,command-ack,kiosk-lock,force-update,locate,locate-response,reboot,rotate-secret,verify-admin-pin}`, `GET,POST /positions`, `GET /positions/{vehicle_id}`, `GET /compliance-expiry`, `POST /wipe-test-data/force` (destructive test-only route, see note below) |
| `/v1/devices` | Device-secret auth (no bearer token) | `POST /auth` (device pairing-secret → session), `POST /{device_id}/heartbeat` — a device-secret-authenticated sibling to some of `/v1/fleet/devices/**`, not the same auth mechanism |
| `/v1/geofences` | Geofences (toll/region zones + auto-detection) | `GET,POST /`, `GET,PATCH,DELETE /{id}` — tenant zones plus platform-wide reference geofences; toll crossings auto-detected from trip GPS ticks |
| `/v1/toll-roads` | Toll road registry (per-road/per-gantry pricing) | `GET /`, `GET /{road_id}`, `GET /gantries`, `POST /{road_id}/price-revisions` |
| `/v1/tariffs` | Tariffs | `GET,POST /`, `GET,PATCH,DELETE /{id}`, `GET /active?region=` (Ed25519-signed), `GET /signing-public-key` (unauthenticated), `GET /presets`, `POST /from-preset`, `GET /suggest`, `GET,POST /{id}/extras`, `GET,PATCH,DELETE /{id}/extras/{extra_id}`, `GET /{id}/change-log`, `GET /{id}/change-log/{log_id}` |
| `/v1/fares-order` | Fares Order reference rates | `GET /current?region=urban\|country` — platform-wide regulated reference rates |
| `/v1/trips` | Trips | `POST /`, `GET /`, `GET,PATCH,DELETE /{id}`, `PATCH /{id}/tick`, `PATCH /{id}/flag`, `POST /{id}/close`, `POST /sync` (offline bulk replay), `POST /{id}/rating`, `POST /{id}/receipt/{email,sms}`, `GET /{id}/gps-trace`, `GET /earnings/today` |
| `/v1/shifts` | Shifts | `GET,POST /`, `GET,PATCH,DELETE /{id}`, `POST /start`, `POST /{id}/end`, `POST /{id}/break/{start,end}`, `GET /{id}/report`, `GET /{id}/report.{csv,pdf}` |
| `/v1/payments` | Payments — **deprecated/unwired mock, not called by Android or the dashboard's write paths.** See `docs/PAYMENTS_INTEGRATION.md`. | `GET /`, `GET,PATCH /{id}` (PATCH refuses to fabricate `succeeded`/`failed` on Stripe-rail methods — real settlement is webhook-only), `POST /tap-to-pay/intent`, `POST /link`, `POST /cash`, `POST /manual`, `POST /cabcharge/authorize`, `POST /ttss/claim` — every non-cash creation flow returns `mock: true` in every environment this app has ever run in (no real Stripe/CabCharge/TTSS credentials configured anywhere in this repo) |
| `/v1/stripe` | Payments webhook | `POST /webhook` — no auth, no tenant scoping (Stripe calls this directly); dev mode accepts an unsigned payload when no `STRIPE_WEBHOOK_SECRET` is set — must not ship that way to production |
| `/v1/psl` | PSL Ledger | `GET,POST /ledger`, `GET,PATCH,DELETE /ledger/{id}`, `POST /topup`, `GET /topups`, `GET /report?period=YYYY-MM` |
| `/v1/duress` | Duress (driver-triggered event lifecycle) | `GET,POST /`, `GET,PATCH,DELETE /{event_id}`, `POST /trigger`, `POST /{event_id}/{cancel,escalate,close,call}`, `POST /{event_id}/gps`, `POST,GET /{event_id}/audio`, `POST /{event_id}/snapshot`, `GET /{event_id}/snapshot/latest`, `GET /{event_id}/snapshot/{id}`, `GET /{event_id}/snapshots`, `POST /device/alarm`, `POST /device/{event_id}/{audio,gps}`, `POST /twilio/status` |
| `/v1/duress-devices` | Duress hardware device registry (separate from `/v1/fleet/devices`) | `GET,POST /`, `GET,PATCH,DELETE /{device_id}`, `POST /{device_id}/rotate-secret` |
| `/v1/vehicles`, `/v1/drivers` | Live Ops (read-only rollups) | `GET /vehicles`, `GET /vehicles/{id}`, `GET /vehicles/{id}/position-history`, `GET /drivers`, `GET /drivers/{id}` |
| `/v1/billing` | Billing | `GET,POST /subscriptions`, `GET,PATCH,DELETE /subscriptions/{id}` (delete = cancel), `GET /invoices`, `POST /connect/onboard` |
| `/v1/compliance` | Compliance Vault | `GET,POST /documents` (multipart upload), `GET,PATCH,DELETE /documents/{id}`, `GET /documents/{id}/download`, `GET /vehicles/{id}/dossier`, `GET /vehicles/{id}/dossier.pdf` |
| `/v1/reports` | Reports (NSW PtP compliance export / revenue / GST-BAS-prep) | `GET /nsw-ptp-export?from=&to=&format=json\|csv`, `GET /revenue?from=&to=`, `GET /gst-summary?from=&to=` — pure read layer, owns no table of its own. Still `nsw-ptp-export` in the live spec as of this doc — the jurisdiction-seam plan (X1) proposed renaming this to `regulator-export` with a 301 from the old path; that rename has not landed |
| `/v1/audit-log` | Audit Log (tamper-evident hash chain) | `GET /`, `GET /verify` — **read-only.** There is no `POST` (removed 2026-09-08, Wave 4/B11 — see `app/api/v1/audit_log.py`'s module docstring). Rows can only be written server-side via `app.services.audit_log.record_audit()`; `GET /verify` walks the per-tenant `hash`/`previous_hash` chain and reports the first broken link, if any |
| `/v1/fatigue-alerts` | Fatigue Alerts (driving-hours monitoring) | `GET /`, `GET /{id}`, `POST /{id}/acknowledge` — alerts are raised as a side effect of `PATCH /v1/trips/{id}/tick` |
| `/v1/jobs` | Jobs (dispatch/job-offer broadcast+accept) | `POST /`, `GET /`, `GET /{id}`, `DELETE /{id}`, `GET /{id}/offers`, `POST /{id}/offers/{offer_id}/{accept,decline}`, `POST /availability`, `WS /live` |
| `/v1/messages` | Messages (dispatch↔driver threads) | `POST /`, `GET /`, `GET /templates`, `POST /templates/{code}`, `POST /{id}/read`, `WS /live?driver_id=` |
| `/v1/vouchers`, `/v1/corporate-accounts` | Vouchers / corporate accounts | Standard `GET,POST /`, `GET,PATCH,DELETE /{id}` CRUD on each |
| `/v1/zones` | Zones | `GET,POST /`, `GET /stats`, `GET,PUT,DELETE /{zone_id}`, `POST /{zone_id}/plot`, `POST /unplot` |
| `/v1/announcements`, `/v1/incentives` | Driver engagement (fleet-side authoring) | Standard `GET,POST /`, `GET,PATCH,DELETE /{id}` CRUD on each |
| `/v1/wallet` | Driver wallet | `GET /drivers/{driver_id}`, `GET,POST /transactions` |
| `/v1/ratings` | Driver ratings | `GET /`, `GET /summary` |
| `/v1/me` | Driver-facing convenience reads (the authenticated caller's own data) | `GET /announcements`, `GET /incentives`, `GET /rating`, `GET /wallet` |
| `/v1/app-releases` | Android app release distribution | `GET /latest`, `GET /{release_id}/download` |
| `/health`, `/health/live` | Health checks (unversioned) | `GET /health` (DB + alembic-head + Redis), `GET /health/live` (liveness only) |

Full per-field request/response shapes: `shared/openapi.json`. If this table and the JSON
disagree, the JSON is right — it comes straight from `app.openapi()`.

## Notes for downstream agents (dashboard, Android)

- All money fields are `Decimal`/string-serialized in JSON (2-4 dp depending on field) — do not
  parse as float.
- Websocket auth: browsers can't set custom headers on the handshake, so both `WS /v1/fleet/live`
  and `WS /v1/duress/{id}/live` accept the access token as `?token=` query param instead of a
  header. Same pattern for `WS /v1/jobs/live` and `WS /v1/messages/live?driver_id=`.
- Jobs: `POST /v1/jobs` fans out a 20s `JobOffer` per currently-available driver (available toggle
  AND open shift AND not mid-trip); first `.../accept` wins and expires every sibling offer for
  that job. `WS /v1/jobs/live` pushes `job_offer` events to the offer's own driver only.
- Messages: one thread per driver (`thread_id == driver_id`); a `driver`-role sender always posts
  as themselves, everyone else must supply `driver_id` in the body. `WS /v1/messages/live` requires
  `?driver_id=`; a `driver`-role caller may only subscribe to their own thread.
- `PLATFORM_TENANT_ID` (`00000000-0000-0000-0000-000000000000`) is the only tenant_id whose
  `owner`-role token may cross-tenant via `?tenant_id=<id>` on any request; every other
  role/tenant is hard-locked server-side to its own token's tenant_id.
- Tariff signing: `GET /v1/tariffs/active`'s `signature` field is Ed25519 over a fixed-order
  canonical JSON payload (see `app.services.tariff_signing`'s module docstring for the exact wire
  format — field order, decimal-string quantization, compact separators). One platform-wide keypair,
  not per-tenant. Android's `security/TariffSignatureVerifier.kt` now has a real
  `Ed25519TariffSignatureVerifier` wired into `AppContainer`/`TariffCache` (the file's other class,
  an RSA verifier, is kept only as a smaller-jump reference implementation, per that file's own
  doc comment — it is not what's actually used). Corrects this file's earlier claim that Android
  only implemented RSA — verify against the file directly before repeating either version.
- Admin PIN: per-tenant, not per-user. An owner sets/overwrites it via
  `POST /v1/tenants/{id}/admin-pin`; the hash is never returned to any caller. A device checks a PIN
  via `POST /v1/fleet/devices/{id}/verify-admin-pin`, which returns `{valid, configured}` — always
  check `configured` explicitly (`configured=false` is "never set up", distinct from
  `valid=false` = "set, but wrong").
