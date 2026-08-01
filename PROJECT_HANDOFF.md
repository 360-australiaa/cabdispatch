# Cab Dispatch — full project handoff

Self-contained status + continuation doc for the whole platform: backend, dashboard, Android
meter. Written so a fresh Claude Code session (or a human dev with no prior context) can pick up
any part of this cold. Repo: https://github.com/360-australiaa/cabdispatch. Full original product
spec: `docs/TCT-METER-01-spec.md` — read it for the "why" behind any design decision referenced
here (NSW fare regulation, competitor positioning, phased delivery plan).

## Status at a glance

| Part | Files | Real status |
|---|---|---|
| Backend (FastAPI) | 78 | **Done and verified.** 207 tests passing (incl. the golden fare-vector compliance suite), exercised live end-to-end over real HTTP (login → create vehicle/driver → open/tick/close a trip → fare math checked by hand). |
| Dashboard (React) | 77 | **Done and verified.** All 10 ops modules built, wired to the real API, checked live in-browser (login, Trips, Tariff Studio all rendering real backend data). |
| Android meter (Kotlin) | 52 | **Source-complete, unverified.** Every screen/offline-sync/fare-engine file is written and has been read carefully, including one real bug fix (S3 wasn't persisting to Room). **Never compiled** — see `android/HANDOFF.md` for the detailed continuation checklist; this doc only summarizes it. |

Nothing here has been deployed, load-tested, or security-reviewed for production. Treat all three
as a working local-dev-verified MVP, not a production system, until the "Not done anywhere" list
at the bottom is closed out.

## Repo layout

```
Cab Dispatch/
├── backend/     FastAPI, SQLAlchemy async, Alembic, pytest, docker-compose.yml, .env.example
├── dashboard/   Vite + React 18 + TS + Tailwind, TCT theme tokens
├── android/     Kotlin + Jetpack Compose, Gradle project — see android/HANDOFF.md
├── shared/      openapi.json (generated, source of truth for the API contract) + API_SUMMARY.md
└── docs/        TCT-METER-01-spec.md (the original product/build spec)
```

## Quick start — run everything locally

```bash
# Backend (port 8001)
cd backend
uv sync
uv run python scripts/init_db.py     # fresh sqlite dev DB
uv run python scripts/seed.py        # tenant 0 (TCT) + demo tenant "Lilly Cabs" + 2 users
uv run uvicorn app.main:app --port 8001

# Dashboard (port 5174), in a second terminal
cd dashboard
npm install
npm run dev -- --port 5174

# Android — open `android/` as the project root in Android Studio; see android/HANDOFF.md
```

**Seeded logins** (password `ChangeMe123!` for both): `admin@cabdispatch.test` (platform
owner, cross-tenant — `?tenant_id=` query param lets it act as any tenant) and
`owner@lillycabs.test` (Lilly Cabs tenant owner). Neither is a `driver`-role user — create one
via the dashboard's Fleet & Drivers page, or `POST /v1/users`, before testing the Android meter's
driver login (which currently maps "Driver ID" → email, "PIN" → password — see Android section).

No Docker/Postgres/Redis in the dev loop — sqlite + an in-memory fallback for Redis-backed
features (JWT revocation, live-position/duress pub/sub) cover local dev. `docker-compose.yml` is
written for prod parity but has never actually been run (Docker wasn't installed in the
environment that built this).

## Backend — what's real, what's mocked

Full CRUD across tenants, users (staff + drivers), fleet (vehicles/devices), tariffs (+ the
global Fares Order 2025 no.2 reference + effective-dating + an immutable change log), trips (incl.
the offline-sync contract: `POST /v1/trips/sync` is idempotent on a client-generated UUID and
flags >±1% fare variance on server-side recompute), shifts, payments, PSL ledger, duress
(trigger/escalate/cancel/close + a live WS GPS stream), billing, compliance-document vault, and an
append-only audit log. Every query is tenant-scoped via `get_current_tenant_id`
(`app/core/security.py`) — that's the entire row-level isolation mechanism, there's no DB-level
RLS.

**Grounded in the actual code, not guesses** (`grep -rn "TODO\|mock" app` in `backend/`):

- **Stripe (payments, billing, PSL top-up) is mock-fallback everywhere.** Every Stripe call site
  checks for a real key (`app/services/payments.py`, `billing.py`, `psl_ledger.py`) and returns a
  clearly-flagged `{"mock": true, ...}` response if none is configured — the dashboard's Billing
  page even shows a "Simulated" badge on these rows so it's honest in the UI too, not silently
  fake. Drop in a real `STRIPE_SECRET_KEY` in `.env` to make these real.
- **No PDF export anywhere.** Shift reports (`app/services/shift.py`) and the compliance dossier
  (`app/services/compliance.py`) are JSON-only — both have an explicit `TODO(reporting)` for a
  PDF/CSV pass, no PDF library was added.
- **Uploads go to local disk** (`backend/uploads/`), not S3 — matches the sibling `captaindash`
  project's own convention, no S3/minio was available to wire up.
- **CabCharge/TTSS payment is manual docket entry only** (`POST /v1/payments/manual` — docket
  number + notes), no real CabCharge integration.
- **Duress/live-position pub/sub is in-process, not Redis** — works fine single-process, will
  NOT fan out across multiple uvicorn workers/replicas. Fine for dev, needs Redis pub/sub wired
  in before running more than one backend process.

## Dashboard — what's real, what's mocked

All 10 modules (Live Map, Duress Desk, Trips, Shifts & Reconciliation, Tariff Studio, PSL Centre,
Fleet & Drivers, Compliance Vault, Billing, White-label Settings) are built against the real API —
no mock data layer in the dashboard itself, `react-query` hooks call the live backend directly.

- **Live Map has no real map tiles** — renders vehicle positions on a custom canvas component
  (`src/pages/live-map/FleetMapCanvas.tsx`), not Mapbox/Google Maps (no paid maps SDK key was
  available). Functionally correct, visually a plain canvas, not a real street map.
- **`src/components/PagePlaceholder.tsx` is dead code** — every route was wired to a real page by
  the time integration finished; this file is unused and safe to delete, just wasn't cleaned up.
- Anywhere the dashboard shows a "Simulated" badge (Billing page), that's it honestly surfacing
  the backend's Stripe mock-fallback, not a dashboard bug.

## Android — see `android/HANDOFF.md` for the full checklist

Condensed summary (the linked file has file:line-grounded detail — don't duplicate-maintain this
list here, update that file when gaps close):

- **Step 0 there is "get it compiling"** — this code has never touched a real compiler.
- Fare engine is ported line-for-line from the backend and has its own JUnit tests mirroring the
  backend's golden vectors — but there are deliberately *two* `FareEngine` classes (a live
  UI-ticking one and the proven one used for the actual charged amount); the handoff doc explains
  how to verify money always routes through the proven one.
- Known real gaps, priority-ordered: hardcoded admin factory-reset PIN (`913572`, explicitly
  flagged not-a-real-security-control), placeholder tariff-signature public key, the meter's
  "Driver ID" field currently just being the backend `email` (not a real driver-PIN system),
  stubbed GPS/region-detection/duress-gesture/QR-scanner, and hardware integrations (Stripe
  Terminal, BT printer) that are real interfaces behind mock implementations — reasonable to
  leave stubbed until there's physical hardware to test against.

## Not done anywhere (flagged, not silently dropped)

Real Stripe/Twilio/Mapbox keys, an actual Docker/Postgres/Redis run (compose file exists,
untested), Redis-backed pub/sub for duress/live-position at >1 backend process, PDF generation,
S3 storage, ESP32 duress hardware + BLE pairing, physical field fare-accuracy testing, Android
build/device verification, App Store/Play Store packaging, CI/CD, and any kind of security/pen
test pass. This is a local-dev-verified MVP, not a production deployment.

## Keeping this file honest

When a gap in this doc gets closed, update the relevant section here (and in `android/HANDOFF.md`
for Android-specific items) in the same commit as the fix — a stale handoff doc is worse than no
handoff doc, because it actively misleads whoever reads it next.
