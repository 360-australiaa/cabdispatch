# Cab Dispatch API — Quick Orientation

Full machine-readable spec: `shared/openapi.json` (dumped from the live app). This file is a
faster human/agent orientation — read it first; fall back to the OpenAPI JSON for exact
request/response schemas.

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

## Roles

`owner` > `admin` > `dispatcher` > `driver` (informal hierarchy; each endpoint documents its own
`require_role(...)` set — there is no implicit inheritance in code, routes list every role that may
call them).

## Router map

| Prefix | Domain | Key endpoints |
|---|---|---|
| `/v1/auth` | Auth (foundation glue, not a domain slice) | `POST /login`, `POST /refresh`, `POST /logout`, `GET /me` |
| `/v1/fleet` | Fleet | `GET,POST /vehicles`, `GET,PATCH,DELETE /vehicles/{id}`, `POST /vehicles/{id}/pairing-code`, `GET,POST /devices`, `POST /devices/register`, `POST /devices/{id}/heartbeat`, `POST /devices/{id}/kiosk-lock`, `POST /devices/{id}/force-update`, `POST /devices/{id}/locate`, `POST /devices/{id}/reboot` (MDM-lite) |
| `/v1/geofences` | Geofences (toll/region zones + auto-detection) | `GET,POST /`, `GET,PATCH,DELETE /{id}` — tenant zones plus platform-wide (tenant_id IS NULL) reference geofences seeded by `scripts/seed.py`; toll crossings are auto-detected from trip GPS ticks (see `app.services.geofence`, `PATCH /v1/trips/{id}/tick`) |
| `/v1/tariffs` | Tariffs | `GET,POST /`, `GET /active?region=`, `GET,PATCH,DELETE /{id}`, `GET,POST /{id}/extras`, `GET /{id}/change-log` |
| `/v1/fares-order` | Tariffs (Fares Order reference) | `GET /current?region=urban\|country` — platform-wide (tenant_id IS NULL) regulated reference rates |
| `/v1/trips` | Trips | `POST /`, `GET /`, `GET,PATCH,DELETE /{id}`, `PATCH /{id}/tick` (telemetry batch; also raises fatigue alerts and auto-detects geofence tolls as a side effect), `POST /{id}/close` (fare finalize), `POST /sync` (offline bulk replay, idempotent on `client_uuid`), `POST /{id}/receipt/email`, `POST /{id}/receipt/sms` (real PDF/email/SMS receipts) |
| `/v1/shifts` | Shifts | `POST /start`, `POST /{id}/end`, `GET /{id}/report`, standard CRUD |
| `/v1/payments` | Payments | `GET /`, `GET,PATCH /{id}`, `POST /tap-to-pay/intent`, `POST /link`, `POST /cash`, `POST /manual`, `POST /cabcharge/authorize` (real-or-mock CabCharge docket), `POST /ttss/claim` (Taxi Transport Subsidy Scheme claim) |
| `/v1/stripe` | Payments (webhook) | `POST /webhook` — no auth, no tenant scoping (Stripe calls this directly) |
| `/v1/psl` | PSL Ledger | `GET,POST /ledger`, `GET,PATCH,DELETE /ledger/{id}`, `POST /topup`, `GET /topups`, `GET /report?period=YYYY-MM` |
| `/v1/duress` | Duress | `POST /trigger`, `POST /{id}/cancel`, `POST /{id}/escalate`, `POST /{id}/close`, `POST /{id}/gps`, `WS /{id}/live`, standard CRUD |
| `/v1/vehicles`, `/v1/drivers` | Live Ops (read-only rollups) | `GET /vehicles`, `GET /vehicles/{id}`, `GET /drivers`, `GET /drivers/{id}` |
| `/v1/fleet/positions`, `/v1/fleet/live` | Live Ops (position pub/sub) | `POST /v1/fleet/positions`, `GET /v1/fleet/positions`, `GET /v1/fleet/positions/{vehicle_id}`, `WS /v1/fleet/live` |
| `/v1/billing` | Billing | `GET,POST /subscriptions`, `GET,PATCH,DELETE /subscriptions/{id}` (delete = cancel), `GET /invoices`, `POST /connect/onboard` |
| `/v1/compliance` | Compliance Vault | `GET,POST /documents` (multipart upload), `GET,PATCH,DELETE /documents/{id}`, `GET /documents/{id}/download`, `GET /vehicles/{id}/dossier` |
| `/v1/reports` | Reports (NSW PtP compliance / revenue / GST-BAS-prep) | `GET /nsw-ptp-export?from=&to=&format=json\|csv`, `GET /revenue?from=&to=`, `GET /gst-summary?from=&to=` — pure read layer over `trips`/`payments`, owns no table of its own |
| `/v1/audit-log` | Audit Log (tamper-evident hash chain) | `POST /`, `GET /` — append-only, no update/delete; `GET /verify` walks the per-tenant `hash`/`previous_hash` chain and reports the first broken link, if any |
| `/v1/fatigue-alerts` | Fatigue Alerts (MDM-lite / driving-hours monitoring) | `GET /`, `GET /{id}`, `POST /{id}/acknowledge` — list/acknowledge only; alerts themselves are raised as a side effect of `PATCH /v1/trips/{id}/tick` (see `app.services.fatigue`) |
| `/v1/jobs` | Jobs (dispatch/job-offer broadcast+accept) | `POST /`, `GET /`, `GET /{id}`, `DELETE /{id}` (cancel, admin/dispatcher only), `GET /{id}/offers`, `POST /{id}/offers/{offer_id}/accept`, `POST /{id}/offers/{offer_id}/decline`, `POST /availability` (driver self-toggle), `WS /live` |
| `/v1/messages` | Messages (dispatch<->driver threads) | `POST /`, `GET /?driver_id=`, `POST /{id}/read`, `WS /live?driver_id=` |

Full per-field request/response shapes: `shared/openapi.json`.

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
