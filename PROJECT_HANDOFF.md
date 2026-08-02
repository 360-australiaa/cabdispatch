# Cab Dispatch — full project handoff

Self-contained status + continuation doc for the whole platform: backend, dashboard, Android
meter. Written so a fresh Claude Code session (or a human dev with no prior context) can pick up
any part of this cold. Repo: https://github.com/360-australiaa/cabdispatch. Full original product
spec: `docs/TCT-METER-01-spec.md` — read it for the "why" behind any design decision referenced
here (NSW fare regulation, competitor positioning, phased delivery plan).

## Status at a glance

| Part | Files | Real status |
|---|---|---|
| Backend (FastAPI) | 82 app + 23 tests | **Done and verified.** 300 tests passing (up from 232 — new `test_auth_mfa.py`, `test_geofences.py`, `test_reports.py`), incl. the golden fare-vector compliance suite, exercised live end-to-end over real HTTP — meter flow, dispatch flow, and (this pass) a two-step MFA login round-trip and a toll-zone geofence CRUD round-trip. |
| Dashboard (React) | 95 | **Done and verified.** 13 ops modules now (see below for the 5 that shipped this pass). `npm run build` is clean (`tsc -b && vite build`, zero errors) and `npm run dev -- --port 5180` boots and serves 200 — checked directly, not assumed. |
| Android driver app (Kotlin) | ~70 | **Source-complete, unverified.** Wheel-nav dashboard redesign (TCT-DRIVER-APP-01) on top of the original meter screens, including job offers, messaging, and a restyled meter/payment flow. Latest pass: fare-meter LED digits switched red → glowing white and enlarged for back-seat legibility, and the wheel got a lock-in pulse + gold glow on selection settle (previously just a size/color crossfade). **Still never compiled** — no Android SDK in the environment that built any of this — so none of the Android-side changes across any pass have been visually confirmed, only reasoned through against the existing code. |

**2026-08-01 addition — Captain Taxis Driver App (`docs/TCT-DRIVER-APP-01.md`):** a design/product handover for a much richer driver-facing UI (rotating 6-slot wheel navigation, permanent live-map background, in-house job dispatch, driver↔dispatch messaging) with a working HTML/JS reference prototype at `docs/driver-dashboard-full-prototype.html` — open it in a browser directly to see/interact with the exact wheel drag-snap mechanics, meter styling, and payment flow the Android build was ported from. This extended the existing backend (new `jobs`/`messages` domain) and rebuilt the Android app's navigation shell around the wheel; it did not start a new project.

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
(trigger/escalate/cancel/close + a live WS GPS stream), billing, compliance-document vault, an
append-only audit log, and (new) jobs/dispatch + messaging. Every query is tenant-scoped via
`get_current_tenant_id` (`app/core/security.py`) — that's the entire row-level isolation
mechanism, there's no DB-level RLS.

**Jobs/dispatch (`app/api/v1/jobs.py`) — verified live, not just unit-tested:** `POST /v1/jobs`
broadcasts a 20s `JobOffer` to every driver who is simultaneously toggled available
(`POST /v1/jobs/availability`), on an open shift, and not mid-trip; `WS /v1/jobs/live` pushes the
offer to that driver in real time. First `.../accept` wins — I confirmed by hand that accepting
immediately (not just on TTL expiry) flips every sibling offer for the same job to `expired` and
sets the job's `accepted_by_driver_id`. **v1 scope, deliberately cut down:** no proximity/ETA
ranking — every available driver gets the same broadcast regardless of distance, flagged as
future work, not an oversight.

**Messages (`app/api/v1/messages.py`) — also verified live:** one thread per driver
(`thread_id == driver_id`), `sender_type` `dispatch`/`driver`, `WS /v1/messages/live?driver_id=`
for real-time delivery. Simple by design — no multi-party threads, no attachments.

**2026-08-02 addition — MFA, geofences, reports (`app/api/v1/auth.py`, `geofences.py`,
`reports.py`) — unit-tested, not yet browser-verified end-to-end from a real authenticator app:**

- **MFA (blueprint 12.2):** TOTP-based two-factor, opt-in per user. `POST /v1/auth/mfa/setup`
  issues a pending secret + `otpauth://` URI, `POST /v1/auth/mfa/verify` confirms a 6-digit code
  and flips `mfa_enabled=true`, `POST /v1/auth/mfa/disable` requires re-entering the password.
  `POST /v1/auth/login` now returns `{mfa_required: true, mfa_token}` instead of tokens for
  `mfa_enabled` accounts — that short-lived `mfa_token` is single-use (its `jti` is burned on
  success) and only exchangeable via `POST /v1/auth/mfa/login` + a valid TOTP code. Accounts
  without MFA enabled get the exact same one-call `TokenResponse` as before — this is additive,
  not a breaking change to the login contract.
- **Geofences (`app/services/geofence.py`):** circular `toll`/`region` zones (center lat/lng +
  radius in meters), full CRUD via `/v1/geofences`. Toll crossings are auto-detected server-side
  from trip GPS ticks (`PATCH /v1/trips/{id}/tick`) — the dashboard's Toll Zones panel only
  manages the zone definitions, not the detection math.
- **Reports (`app/services/reports.py`):** `GET /v1/reports/revenue` (grouped by day/week/month/
  driver/vehicle/tariff/payment_method), `GET /v1/reports/gst-summary` (monthly GST breakdown,
  carries an explicit `disclaimer` string — not a substitute for real accounting advice), and
  `GET /v1/reports/nsw-ptp-export` (CSV, NSW Point to Point Transport Commissioner format). All
  three are read-only aggregations over `trips`/`payments` — closed trips only, no new tables.

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
- **MDM remote-device commands (kiosk-lock, force-update, locate, reboot — `app/models/fleet.py`
  `Device.kiosk_locked`/`force_update_pending`/`locate_requested`/`reboot_requested`) are a
  command *queue*, not a command *executor*.** The dashboard's new Devices panel can flip these
  flags via `POST /v1/fleet/devices/{id}/{kiosk-lock,force-update,locate,reboot}` and they persist
  and round-trip correctly — but nothing anywhere (backend or Android) actually acts on them yet.
  Confirmed by reading the Android source: `MainActivity.kt` explicitly says kiosk/lock-task mode
  is out of scope, and `reboot_requested` already carried an honesty note in the model (see
  `app/models/fleet.py`) that a real reboot needs the on-device app enrolled as Android Device
  Owner via zero-touch provisioning, which nothing here does. Treat every button in the Devices
  panel as "queues a request the fleet can see," not "remotely controls the device."

## Dashboard — what's real, what's mocked

13 modules now (Live Map, Dispatch, Duress Desk, Trips, Shifts & Reconciliation, Tariff Studio
[now with a Toll Zones tab], PSL Centre, Fleet & Drivers [now with a Devices tab], Compliance
Vault [now with a Reports tab], Billing, White-label Settings, **Security**) — all built against
the real API, no mock data layer in the dashboard itself, `react-query` hooks call the live
backend directly.

**2026-08-02 addition — 5 sibling agents' work reconciled in one pass (Reports/Compliance-export,
MFA + two-step login, geofence zone manager, MDM remote-command buttons, and a real Mapbox GL JS
Live Map), all built in parallel without compiling against each other or against the
newly-refreshed backend contract.** Read every file each agent touched (router, sidebar nav,
`lib/auth.tsx`, and every new page/hook) looking for naming/route/nav collisions — found **none**:
`router.tsx`, `Sidebar.tsx`, and `App.tsx` each already had a single coherent version covering
every new route, so no merge was actually needed, just verification. `npm install && npm run
build` succeeded with zero TypeScript errors on the first attempt, and `npm run dev -- --port
5180` boots and serves 200. Specifically checked the one change with real regression risk — the
new two-step MFA login flow — against `src/lib/auth.tsx`: `login()` returns a discriminated
`LoginResult` (`{mfaRequired: false}` vs `{mfaRequired: true, mfaToken}`), and for any account
without MFA enabled the backend still returns tokens directly from the first `POST
/v1/auth/login` call, so `LoginPage` never even renders the second step — non-MFA sign-in is
byte-for-byte the original single-step flow, just wrapped in a branch.

- **New: Compliance Vault → Reports tab** (`src/pages/compliance/NswPtpExportCard.tsx`,
  `RevenueSection.tsx`, `src/hooks/useReports.ts`). One-click NSW PtP CSV export (date-range
  picker → streams `GET /v1/reports/nsw-ptp-export` as a file download) plus a Revenue & GST
  view (recharts bar charts by day/payment-method, a GST-by-month table) — no new charting
  dependency, reuses recharts (already shipped for `src/pages/duress/GpsTracePanel.tsx`).
- **New: Settings → Security** (`src/pages/settings/security/`). Self-service TOTP MFA
  enable/disable — QR-friendly `otpauth://` URI + manual-entry secret, 6-digit verify step,
  password re-entry to disable. Calls `refreshUser()` after every state change so the sidebar/
  login flow immediately reflects the account's current `mfa_enabled`.
- **New: Tariff Studio → Toll Zones tab** (`src/pages/tariffs/TollZone*.tsx`,
  `src/hooks/useGeofences.ts`). CRUD over circular `kind: "toll"` geofences — click-to-place
  center on an embedded map (falls back to plain lat/lng number inputs without a Mapbox token),
  live radius-circle preview, toll amount in AUD.
- **New: Fleet & Drivers → Devices tab** (`src/pages/fleet/DevicesPanel.tsx`). Device CRUD plus
  the four MDM command buttons (kiosk-lock, force-update, locate, reboot) — **see the backend
  section above for why these only queue a request, they don't actually control the device yet.**
  Also added a fatigue-alerts warning banner at the top of the whole page (driving-hours
  violations raised server-side, acknowledge-only here).
- **Live Map now renders a real Mapbox GL JS map** (`src/pages/live-map/FleetMapCanvas.tsx`) —
  dark style, Sydney-centered or fit to the fleet's bounding box, custom marker elements with a
  pulsing red ring + click-through on active duress vehicles. Gated on `VITE_MAPBOX_TOKEN` being
  set (`.env.example`); **falls back to the original plain-SVG canvas plot when it's unset**, so
  the page still works with zero maps config. The Toll Zone picker above reuses the exact same
  token/fallback pattern for its click-to-place-center map.
- **`src/components/PagePlaceholder.tsx` is dead code** — every route was wired to a real page by
  the time integration finished; this file is unused and safe to delete, just wasn't cleaned up.
- Anywhere the dashboard shows a "Simulated" badge (Billing page), that's it honestly surfacing
  the backend's Stripe mock-fallback, not a dashboard bug.

**The two Mapbox integrations use the same public `pk.*` token but reach genuinely different
endpoints, for genuinely different reasons — worth understanding together:**

| | Dashboard Live Map + Toll Zone picker | Android driver-app dashboard background |
|---|---|---|
| What it calls | Mapbox GL JS (interactive vector tiles, npm `mapbox-gl`) | Mapbox Static Images API (`data/remote/MapboxStaticImage.kt`) |
| Result | Real pan/zoom/rotate map, live markers | A single rendered PNG for a given center/zoom — real imagery, zero gestures |
| Why the difference | Browser JS SDK only needs the public `pk.*` token | The Android **Maps SDK** (the interactive one) resolves its Gradle dependency from Mapbox's *private* Maven repo, which requires a separate secret `sk.*` "Downloads:Read" token wired into `settings.gradle.kts` — a `pk.*` token is rejected there outright, so the dependency fails to resolve before any app code runs. The Static Images API has no such gate — it's a plain authenticated REST GET, same trust level as any other backend call. |
| Fallback when unset | Plain-SVG lat/lng canvas plot | Fixed Sydney CBD center (`SydneyCbdFallback`) — moot anyway since Android has no real GPS position wired in yet (see Android section) |

Don't re-attempt the Android interactive SDK without first getting a real `sk.*` downloads token
from whoever owns the Mapbox account — that's the actual blocker, not a code gap.

**Earlier same-day addition — Dispatch (`/dispatch`):** the previously-missing link between the
backend's jobs domain and the Android app's Available Trips screen — before this, jobs/messages
was backend-only, with no dashboard UI to actually create a job to test the driver-side
accept/decline flow against. Create-job form → `POST /v1/jobs` → broadcasts to every available
driver; job list + detail panel poll every 2-3s while non-terminal (no dispatcher-scoped WS
exists — `WS /v1/jobs/live` is deliberately driver-scoped, see that endpoint's docstring — polling
is the correct simple call here, same as every other non-safety-critical list page). **Verified
live**, not just built: created a job through the actual UI, confirmed both test drivers got
real-time "pending" offers on the backend. Also fixed a real bug found while testing this: backend
`CORS_ORIGINS` only listed port 5174, but `.claude/launch.json`'s own dashboard config runs on
5180 — that combination had never actually worked (that CORS fix is why this pass's `npm run dev
-- --port 5180` boot-check above could just work, no further backend change needed).

## Android — see `android/HANDOFF.md` for the full checklist

Condensed summary (the linked file has file:line-grounded detail — don't duplicate-maintain this
list here, update that file when gaps close):

- **Step 0 there is "get it compiling"** — this code has never touched a real compiler.
- Fare engine is ported line-for-line from the backend and has its own JUnit tests mirroring the
  backend's golden vectors — but there are deliberately *two* `FareEngine` classes (a live
  UI-ticking one and the proven one used for the actual charged amount); the handoff doc explains
  how to verify money always routes through the proven one.
- **New (2026-08-01): the wheel-nav dashboard is now the app's home screen**, replacing the old
  simple available-toggle screen — a driver-facing rotating 6-slot wheel (drag/snap mechanics
  ported exactly from `docs/driver-dashboard-full-prototype.html`), Available Trips (job offers),
  Messages, and restyled Trip/Earnings/Shift content panes. The meter and payment screens got a
  visual restyle (LED-style fare digits) and one real gap-fix (toll quick-add chips now actually
  add to the fare — the reference prototype itself never wired these, Android does).
- Known real gaps, priority-ordered: hardcoded admin factory-reset PIN (`913572`, explicitly
  flagged not-a-real-security-control), placeholder tariff-signature public key, the meter's
  "Driver ID" field currently just being the backend `email` (not a real driver-PIN system),
  stubbed GPS/region-detection/duress-gesture/QR-scanner (GPS being stubbed also means the wheel's
  speed-lock, job-card distances, and the map background's live position are all approximated,
  not real, until that's fixed), and hardware integrations (Stripe Terminal, BT printer) that are
  real interfaces behind mock implementations — reasonable to leave stubbed until there's physical
  hardware to test against. "Navigate" is a deliberate deep-link to the phone's maps app, not
  custom turn-by-turn — that's a spec decision, not a shortcut.

## Not done anywhere (flagged, not silently dropped)

Real Stripe/Twilio keys, an actual Docker/Postgres/Redis run (compose file exists, untested),
Redis-backed pub/sub for duress/live-position/jobs/messages at >1 backend process, PDF generation,
S3 storage, ESP32 duress hardware + BLE pairing, physical field fare-accuracy testing, Android
build/device verification, App Store/Play Store packaging, CI/CD, any kind of security/pen test
pass, proximity/ETA-ranked job matching (v1 is broadcast-to-everyone, first-accept-wins), a true
7-segment LED font for the meter digits, MFA verified against a real authenticator app in a real
browser (only unit/integration-tested so far), and any device actually executing an MDM command
(kiosk-lock/force-update/locate/reboot all queue server-side only — see the backend section
above). This is a local-dev-verified MVP, not a production deployment.

**Map tiles are now real in exactly one place: the dashboard, and only with a Mapbox token
configured.** `.env.example` ships the `VITE_MAPBOX_TOKEN` line blank, but this dev environment's
own gitignored `dashboard/.env` already has a real `pk.*` token in it — so the Live Map and Toll
Zone picker render actual Mapbox tiles here already, not the plain-SVG/manual-lat-lng fallback. A
fresh clone without that `.env` file falls back automatically, no crash — get your own `pk.*`
token at https://account.mapbox.com/access-tokens/ to restore real tiles elsewhere. The Android
app's map background is a genuine map image (Mapbox Static Images API) but never interactive —
see the Mapbox comparison table above for why that's a real infrastructure constraint (missing
`sk.*` downloads token), not unfinished work.

## Keeping this file honest

When a gap in this doc gets closed, update the relevant section here (and in `android/HANDOFF.md`
for Android-specific items) in the same commit as the fix — a stale handoff doc is worse than no
handoff doc, because it actively misleads whoever reads it next.
