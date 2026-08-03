# Cab Dispatch — full project handoff

Self-contained status + continuation doc for the whole platform: backend, dashboard, Android
meter. Written so a fresh Claude Code session (or a human dev with no prior context) can pick up
any part of this cold. Repo: https://github.com/360-australiaa/cabdispatch. Full original product
spec: `docs/TCT-METER-01-spec.md` — read it for the "why" behind any design decision referenced
here (NSW fare regulation, competitor positioning, phased delivery plan).

## Status at a glance

| Part | Files | Real status |
|---|---|---|
| Backend (FastAPI) | 82 app + 25 tests | **Done and verified.** 325 tests passing (up from 300 — new `test_driver_login.py`, `test_admin_pin.py`, plus tariff-signing coverage in `test_tariffs.py`), incl. the golden fare-vector compliance suite, exercised live end-to-end over real HTTP — driver-PIN login, admin-PIN device verification, and Ed25519 tariff-signature verification were each independently re-verified by hand this pass (not just "tests pass" — a real signed payload was fetched from the DB and tampered with to confirm the signature check actually detects it). |
| Dashboard (React) | 95+ | **Done and verified.** 14 ops modules now (13 + new **Messages** — driver↔dispatch threads, `/messages`). `npm run build` is clean (`tsc -b && vite build`, zero errors) and a real message was sent through the live UI and confirmed persisted via a direct API call this pass. |
| Android driver app (Kotlin) | ~75 | **Source-complete, unverified.** Wheel-nav dashboard redesign (TCT-DRIVER-APP-01) on top of the original meter screens, including job offers, messaging, and a restyled meter/payment flow. Latest pass: real GPS (`FusedLocationProviderClient`) now drives the fare engine, map centering, and region auto-detection — replacing the fixed `StubSpeedSource`/hardcoded `"urban"` region; driver login now hits a real `POST /v1/auth/driver-login` (driver-PIN, not email/password); admin-PIN factory-reset and tariff-signature verification are now real (Ed25519, server-verified), replacing hardcoded placeholders; MDM "locate" now answers with a real position. **Still never compiled** — no Android SDK in the environment that built any of this — so none of the Android-side changes across any pass have been visually confirmed, only reasoned through against the existing code and cross-checked file-by-file against `git diff` this pass (see `android/HANDOFF.md`'s top entry). |

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

**2026-08-03 addition — driver-PIN login, server-verified admin PIN, real Ed25519 tariff-signing —
independently re-verified against the running DB/API, not just unit-tested:**

- **Driver-PIN login (`app/api/v1/auth.py`, `app/services/user.py`).** New `User.driver_code`
  (5-char, ambiguous-character-excluded alphabet — no 0/O/1/I — globally unique, not
  tenant-scoped, since the login endpoint has no tenant context yet at that point) auto-generated
  for `role="driver"` users created without one. `POST /v1/auth/driver-login` takes
  `driver_code`+`pin`, reuses the exact same two-step MFA contract staff login already has. Live
  re-verified this pass: reseeded, got a real `driver_code` (`HQMGA` for the demo driver),
  confirmed the endpoint accepts the right PIN and 401s the wrong one.
- **Server-verified admin PIN (`app/api/v1/tenants.py`, `app/services/tenant.py`).**
  `Tenant.admin_pin_hash` (owner-settable via `POST /v1/tenants/{id}/admin-pin`), checked
  device-side via `POST /v1/fleet/devices/{id}/verify-admin-pin` — replaces the Android app's old
  hardcoded `ADMIN_PIN_PLACEHOLDER`. Live re-verified: created a device, set a real PIN, confirmed
  `{"valid":true,...}` for the right PIN and `{"valid":false,...}` for the wrong one.
- **Real Ed25519 tariff-signing (`app/services/tariff_signing.py`).** `GET
  /v1/tariffs/active`'s `signature` field is a genuine Ed25519 signature over a canonical payload
  (fixed field order, rate fields quantized to 4dp), verifiable via the unauthenticated `GET
  /v1/tariffs/signing-public-key`. Went beyond "field is present" for this one: wrote an
  independent script that fetched the real `Tariff` ORM row and called
  `verify_tariff_signature()` directly — confirmed it returns `True` for the real signature and
  `False` after hand-tampering `flag_fall`, i.e. real tamper-detection, not a decorative field.

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
  `Device.kiosk_locked`/`force_update_pending`/`locate_requested`/`reboot_requested`) are mostly a
  command *queue*, not a command *executor* — with one exception now.** `locate_requested` is
  answered for real as of 2026-08-03: the Android app's existing heartbeat call
  (`SettingsViewModel.kt#loadDeviceStatus`) checks the flag and, if set, publishes the device's
  real GPS fix via `POST /v1/fleet/positions` — but only the next time the driver happens to open
  S6/Settings (no periodic background heartbeat exists yet, so it's not instant). kiosk-lock and
  force-update flags still persist and round-trip correctly but nothing on-device acts on them
  (`MainActivity.kt` explicitly says kiosk/lock-task mode is out of scope). `reboot_requested`
  remains intentionally backend-only — a real reboot needs the on-device app enrolled as Android
  Device Owner via zero-touch provisioning, which nothing here does (see the honesty note in
  `app/models/fleet.py`). Treat kiosk-lock/force-update/reboot buttons as "queues a request the
  fleet can see," and locate as "answers eventually, not instantly."

## Dashboard — what's real, what's mocked

14 modules now (Live Map, Dispatch, **Messages**, Duress Desk, Trips, Shifts & Reconciliation,
Tariff Studio [now with a Toll Zones tab], PSL Centre, Fleet & Drivers [now with a Devices tab],
Compliance Vault [now with a Reports tab], Billing, White-label Settings, Security) — all built
against the real API, no mock data layer in the dashboard itself, `react-query` hooks call the
live backend directly.

**2026-08-03 addition — Messages (`src/pages/messages/`).** Closes the last piece of the
driver↔dispatch loop: pick a driver from the sidebar (shows shift status), see/send messages in
a thread panel with a live WS connection indicator (`useMessagesLive.ts`, mirroring
`useDuressLiveGps.ts`'s token-in-query-param auth pattern). **Verified live, not just built:**
logged in as the tenant owner, opened a real driver's thread, sent a message through the actual
UI, and confirmed via a direct `GET /v1/messages?driver_id=...` call that it persisted
server-side with the correct `sender_type`/`sender_user_id`/body.

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
- **2026-08-03 — closed:** GPS is real now (`FusedLocationProviderClient`-backed
  `RealLocationProvider`, driving the fare engine, map centering, and region auto-detection —
  replacing the old hardcoded `"urban"`/`StubSpeedSource`); driver login hits the real
  `POST /v1/auth/driver-login` (driver-PIN) instead of mapping onto staff email/password; admin
  factory-reset PIN and the tariff-signature public key are both real/server-verified now (Ed25519),
  not hardcoded placeholders; MDM "locate" is answered with a real position (next heartbeat, see
  backend section above). None of this has been compiled/run on-device — see `android/HANDOFF.md`'s
  top entry for the file-by-file cross-check done in place of a real compile.
- Known real gaps still open, priority-ordered: duress-gesture GPS relay and the settings screen's
  GPS status dot still read a separate raw `LocationManager` fix instead of the new real provider
  (a real consolidation candidate, not a correctness bug); driver-initiated street-hail start
  coordinates still hardcoded `0.0, 0.0`; QR vehicle-pairing scanner is still a stub
  (manual-entry fallback works); no periodic background heartbeat (so MDM "locate" only answers
  when Settings happens to be opened); and hardware integrations (Stripe Terminal, BT printer)
  that are real interfaces behind mock implementations — reasonable to leave stubbed until there's
  physical hardware to test against. "Navigate" is a deliberate deep-link to the phone's maps app,
  not custom turn-by-turn — that's a spec decision, not a shortcut.

## Not done anywhere (flagged, not silently dropped)

Real Stripe/Twilio keys, an actual Docker/Postgres/Redis run (compose file exists, untested),
Redis-backed pub/sub for duress/live-position/jobs/messages at >1 backend process, PDF generation,
S3 storage, ESP32 duress hardware + BLE pairing, physical field fare-accuracy testing, Android
build/device verification, App Store/Play Store packaging, CI/CD, any kind of security/pen test
pass, proximity/ETA-ranked job matching (v1 is broadcast-to-everyone, first-accept-wins), a true
7-segment LED font for the meter digits, MFA verified against a real authenticator app in a real
browser (only unit/integration-tested so far), and most MDM commands actually executing on-device
(kiosk-lock/force-update/reboot still queue server-side only — `locate` is the one exception,
answered for real as of 2026-08-03, see the backend section above). This is a local-dev-verified
MVP, not a production deployment.

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
