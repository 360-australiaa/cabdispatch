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
| `/v1/fleet` | Fleet | `GET,POST /vehicles`, `GET,PATCH,DELETE /vehicles/{id}`, `POST /vehicles/{id}/pairing-code`, `GET,POST /devices`, `POST /devices/register`, `POST /devices/{id}/heartbeat`, `POST /devices/{id}/kiosk-lock`, `POST /devices/{id}/force-update` |
| `/v1/tariffs` | Tariffs | `GET,POST /`, `GET /active?region=`, `GET,PATCH,DELETE /{id}`, `GET,POST /{id}/extras`, `GET /{id}/change-log` |
| `/v1/fares-order` | Tariffs (Fares Order reference) | `GET /current?region=urban\|country` — platform-wide (tenant_id IS NULL) regulated reference rates |
| `/v1/trips` | Trips | `POST /`, `GET /`, `GET,PATCH,DELETE /{id}`, `PATCH /{id}/tick` (telemetry batch), `POST /{id}/close` (fare finalize), `POST /sync` (offline bulk replay, idempotent on `client_uuid`) |
| `/v1/shifts` | Shifts | `POST /start`, `POST /{id}/end`, `GET /{id}/report`, standard CRUD |
| `/v1/payments` | Payments | `GET /`, `GET,PATCH /{id}`, `POST /tap-to-pay/intent`, `POST /link`, `POST /cash`, `POST /manual` |
| `/v1/stripe` | Payments (webhook) | `POST /webhook` — no auth, no tenant scoping (Stripe calls this directly) |
| `/v1/psl` | PSL Ledger | `GET,POST /ledger`, `GET,PATCH,DELETE /ledger/{id}`, `POST /topup`, `GET /topups`, `GET /report?period=YYYY-MM` |
| `/v1/duress` | Duress | `POST /trigger`, `POST /{id}/cancel`, `POST /{id}/escalate`, `POST /{id}/close`, `POST /{id}/gps`, `WS /{id}/live`, standard CRUD |
| `/v1/vehicles`, `/v1/drivers` | Live Ops (read-only rollups) | `GET /vehicles`, `GET /vehicles/{id}`, `GET /drivers`, `GET /drivers/{id}` |
| `/v1/fleet/positions`, `/v1/fleet/live` | Live Ops (position pub/sub) | `POST /v1/fleet/positions`, `GET /v1/fleet/positions`, `GET /v1/fleet/positions/{vehicle_id}`, `WS /v1/fleet/live` |
| `/v1/billing` | Billing | `GET,POST /subscriptions`, `GET,PATCH,DELETE /subscriptions/{id}` (delete = cancel), `GET /invoices`, `POST /connect/onboard` |
| `/v1/compliance` | Compliance Vault | `GET,POST /documents` (multipart upload), `GET,PATCH,DELETE /documents/{id}`, `GET /documents/{id}/download`, `GET /vehicles/{id}/dossier` |
| `/v1/audit-log` | Audit Log | `POST /`, `GET /` — append-only, no update/delete |

Full per-field request/response shapes: `shared/openapi.json`.

## Notes for downstream agents (dashboard, Android)

- All money fields are `Decimal`/string-serialized in JSON (2-4 dp depending on field) — do not
  parse as float.
- Websocket auth: browsers can't set custom headers on the handshake, so both `WS /v1/fleet/live`
  and `WS /v1/duress/{id}/live` accept the access token as `?token=` query param instead of a
  header.
- `PLATFORM_TENANT_ID` (`00000000-0000-0000-0000-000000000000`) is the only tenant_id whose
  `owner`-role token may cross-tenant via `?tenant_id=<id>` on any request; every other
  role/tenant is hard-locked server-side to its own token's tenant_id.
