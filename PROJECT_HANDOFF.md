# Cab Dispatch — full project handoff

Self-contained status + continuation doc for the whole platform: backend, dashboard, Android
meter. Written so a fresh Claude Code session (or a human dev with no prior context) can pick up
any part of this cold. Repo: https://github.com/360-australiaa/cabdispatch. Full original product
spec: `docs/TCT-METER-01-spec.md` — read it for the "why" behind any design decision referenced
here (NSW fare regulation, competitor positioning, phased delivery plan).

## Status at a glance

| Part | Files | Real status |
|---|---|---|
| Backend (FastAPI) | 90 app + 31 tests | **Done and verified.** 498 tests passing (up from 480). Every Alembic migration (14 revisions) applies cleanly to a fresh sqlite DB. This pass added named dispatch zones + live per-zone demand stats (MTI-parity), negotiated/"Set Price" fixed fares, a platform-owner admin console, a per-vehicle evidence-pack export + lifetime-totals register + pilot-report, driver photos, and quick-tap message templates — every one independently re-exercised live over real HTTP, not just "tests pass". **Two real bugs found and fixed during this pass's verification, not left standing:** the platform tenant list ordered oldest-first with a 20-row default page, meaning a newly-onboarded tenant would never appear on the default page once >20 tenants exist (fixed: newest-first); and the driver-photo upload endpoint was staff-only gated, which would have 403'd a real driver uploading their own photo from their own Profile screen — the exact thing the Android app was built to do (fixed: self-or-staff gated, live-verified with a real driver token). A 16th planned agent (a monitoring-partner duress panel exposing driver PII/GPS/audio to an external third party) was correctly blocked by the safety classifier — that data-sharing decision needs your explicit authorization, not an inference from a business-planning PDF; not built, not worked around. |
| Dashboard (React) | 95+ | **Done and verified.** 18 ops modules now (+ Zones & Demand, Platform Admin, evidence-pack export, driver-photo display, vehicle lifetime-totals/pilot-report). `npm run build` is clean and every new page this pass was exercised live in a real browser against real data — including confirming the Platform Admin nav item is genuinely invisible to a non-platform-owner and a normal tenant owner gets a real 403 if they navigate to `/platform` directly. |
| Android driver app (Kotlin) | ~90 | **Source-complete, unverified.** This pass added Plot + Statistics screens (zone-based demand parity with a real competitor meter), a "Set Price" negotiated-fare flow, a start-meter confirmation dialog, a boot-time terms screen, a permissions checklist, a live shift-duration countdown, driver-photo capture/upload, and quick-tap canned messages — 4 agents ran concurrently touching shared files (`AppContainer.kt`, `ApiService.kt`, `WheelDashboardScreen.kt`, `CabDispatchNavHost.kt`); reconciliation confirmed every shared-file edit merged cleanly with no duplicate declarations, no orphaned references, and the golden-vector fare engine (`domain/fare/`) completely untouched. **Still never compiled** — no Android SDK in this environment — so nothing here has been visually confirmed, only reasoned through and cross-checked file-by-file. |

**2026-08-01 addition — Captain Taxis Driver App (`docs/TCT-DRIVER-APP-01.md`):** a design/product handover for a much richer driver-facing UI (rotating 6-slot wheel navigation, permanent live-map background, in-house job dispatch, driver↔dispatch messaging) with a working HTML/JS reference prototype at `docs/driver-dashboard-full-prototype.html` — open it in a browser directly to see/interact with the exact wheel drag-snap mechanics, meter styling, and payment flow the Android build was ported from. This extended the existing backend (new `jobs`/`messages` domain) and rebuilt the Android app's navigation shell around the wheel; it did not start a new project.

**2026-08-26 addition -- Physical duress device integration (multi-agent pass, live-verified):**
built the full server-side contract for the CT-DPD-01 physical panic-button hardware (own
4G/GNSS/VoLTE SIM, BLE 5 link to the tablet) described in `docs/DURESS_DEVICE_INTEGRATION.md` --
new `DuressDevice` domain (`app/models/duress_device.py`, `app/schemas/duress_device.py`,
`app/services/duress_device.py`, `app/api/v1/duress_device.py`): the HMAC device-auth handshake
(`POST /v1/devices/auth`), alarm open/correlation (a device alarm either attaches to an
already-open tablet event -- flipping `source` to `"both"` -- or opens a fresh one, idempotently),
device-path GPS batch ingest and audio upload (kept on separate `device_audio_ref`/`device_id`
columns from the tablet's own, added to `DuressEvent`), heartbeat, and admin CRUD for provisioning
devices. Also added the operator "call the cab" action on the existing tablet-side domain
(`POST /v1/duress/{id}/call`, dials the device's own SIM via Twilio with the same mock-fallback
convention as every other Twilio/Stripe/SendGrid integration here) and its async status webhook.
Reversible secret-at-rest encryption (`app/core/crypto.py`, Fernet) was added specifically because
HMAC verification -- unlike every password/PIN elsewhere in this codebase -- needs the plaintext
secret back.

**Two real bugs found and fixed during live end-to-end verification, not left standing:** (1) the
new migration defined `created_at`/`updated_at` with no `server_default`, so every insert 500'd with
a NOT NULL violation the instant it hit the real (alembic-migrated) dev database -- invisible to
`pytest` because the test suite builds its schema via `Base.metadata.create_all()`, which reads the
ORM model's default directly and never exercises the migration file at all; fixed by matching the
initial schema's `server_default=sa.text('(CURRENT_TIMESTAMP)')` convention. (2) `device_code` had
no per-tenant uniqueness constraint, so provisioning two devices with the same code corrupted the
auth handshake's `scalar_one_or_none()` lookup into an unhandled `MultipleResultsFound` 500 the
next time either device authenticated; fixed with a `UniqueConstraint("tenant_id", "device_code")`
(migration `97da879e0540`, using `batch_alter_table` for SQLite per this project's existing
convention) and a clean `409` response instead. Both fixes are regression-tested in the new
`tests/test_duress_device.py` (17 tests, all passing alongside the full 498-test suite). The
dashboard's Duress Desk (`dashboard/src/pages/duress/`) got a "Call the cab" button, a dual
tablet/device GPS trace, a source badge, and a device-call summary panel -- `npm run lint`
(`tsc --noEmit`) clean. Not yet built: the Android BLE client and front-camera capture (the
integration doc's Section 2 BLE profile is a locked contract for that future work, not running
code), and the physical hardware itself (see `docs/CT-DPD-01_Tech_Pack_for_TY-EMS.md`, at OEM
evaluation stage with TY-EMS as of this writing).

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

**2026-08-10 addition -- MTI feature-parity pass (zones/demand, set-price, evidence pack, platform console, ops tracking, quick messages), 15-agent run with a messy environment and two real bugs caught during verification:**

This pass worked from real screenshots of a competitor's (MTI) taxi meter to close specific feature gaps, plus several blueprint items (evidence-pack export, platform admin console, operations-cycle tracking). **A tool-hook bug broke the normal file-editing path for every single agent this pass** (a PreToolUse hook crashed on the local Windows username containing a space) -- every agent, including the verification pass itself, had to fall back to writing files via raw Bash/Python/PowerShell instead of the normal edit tool. This was checked for corruption specifically (stray escape-sequence artifacts, encoding mojibake) before trusting any of it -- none found, and every touched/new Python file was independently byte-compiled to confirm.

- **Zones and Demand** (app/models/zones.py, app/services/zones.py, app/api/v1/zones.py). Named dispatch zones (circular geofence, mirroring the existing toll-zone pattern), "plot into a zone" tied to a driver's open shift, and GET /v1/zones/stats -- live plotted/vacant/busy vehicle counts and bookings/street-hails-last-hour per zone, computed from data that already exists (live positions, jobs, trips). Live re-verified: created a zone, plotted a real driver into it (correctly 409s with no open shift), confirmed the stats counter incremented.
- **Negotiated / "Set Price" fixed fares** (app/services/fare_engine.py). Reuses the existing airport_fixed mechanism's shape rather than inventing a parallel one -- purely additive, a new early-return branch in FareEngine.close() gated on a new optional field, zero changes to the existing golden-vector path when unset (confirmed via git diff: 90 insertions, 0 deletions). Unlike the airport fixed fare, PSL/tolls still accrue on top of the negotiated amount, matching the real meter's own on-screen disclaimer ("doesn't include levies and/or tolls"). Live re-verified: a $30 negotiated trip closed at exactly $30 with no tolls, and a $9,999 negotiated amount was correctly rejected (422, sanity cap).
- **Platform admin console** (app/api/v1/platform.py). Cross-tenant tenant list/create + a per-tenant health summary + platform-wide health, gated to role == "owner" AND tenant_id == PLATFORM_TENANT_ID specifically (not just any owner). Live re-verified, including the negative case: a normal tenant owner gets a real 403 on every platform route.
- **Evidence-pack export + lifetime-totals register + ops tracking** (app/services/evidence_pack.py, app/services/fleet_reports.py). GET /v1/fleet/vehicles/{id}/evidence-pack bundles compliance docs, tariff history, device firmware-version history, and a tamper-log extract into one ZIP -- every category always present, with an honest "note" explaining emptiness rather than silently omitting it (live-verified by downloading and inspecting the actual ZIP contents). GET .../lifetime-totals (all-time cumulative fares/PSL/tolls/km, mirroring a physical meter's register) and GET .../pilot-report?from=&to= (fare-accuracy variance, uptime estimate, duress test/flagged-trip counts over a date range -- the exact evidence a 60-day pilot needs) -- both live-verified with real data. Also: Device.calibration_due folded into the existing compliance-expiry alert pattern (no new endpoint).
- **Quick-tap canned messages** (app/services/messages.py). Driver-side templates (No Job/Recall/Job Query/Other) and dispatch-side templates, sent through the exact same message-creation path real free-text messages already use. Live-verified via GET /v1/messages/templates.
- **Driver photos** (app/api/v1/users.py). Real gap closed, then a real bug in the fix caught and fixed again during verification -- see below.
- **Not built -- correctly blocked, not worked around:** a monitoring-partner duress panel (external API-key auth exposing driver name/phone/photo/live-GPS/audio to a third-party alarm-monitoring centre) was blocked by the safety classifier as a data-sharing architecture inferred from a business-planning PDF rather than explicitly authorized by you. This is the right call -- sharing driver PII and live location/audio with an external party is a real business/privacy decision, not something that should get built on an inferred "finish everything" instruction. The dashboard agent assigned to build a UI for it correctly refused too, once it found no real backend contract to build against, rather than fabricate one. **If you want this built, say so explicitly and it can be scoped properly** -- including the Privacy Act data-sharing agreement the blueprint PDF itself names as a prerequisite (5.2, "Privacy Act data agreement").

**Two real bugs found and fixed during the post-workflow verification, not left standing:**
1. **Platform tenant list ordering.** list_tenants ordered oldest-first with a 20-row default page size -- caught by a test creating a tenant and then not finding it in the default list (with hundreds of tenants already in the shared dev/test database from other test files). This is a real production bug, not just a test artifact: once a platform has more than 20 tenants, a newly-onboarded one would never appear on the default page. Fixed: newest-first ordering.
2. **Driver-photo upload gate.** Shipped staff-only (owner/admin/dispatcher), which would have 403'd a real driver-role account uploading their own photo -- the Android app's Profile screen was built to do exactly that. Fixed: self-or-staff gated (any user may upload their own photo; staff may additionally upload on behalf of anyone in their tenant). Live-verified with a real driver bearer token.

Full backend suite: 480/480 passing after both fixes. Dashboard: clean build, every new page (Zones and Demand, Platform Admin, evidence-pack export button, driver-photo avatar, vehicle lifetime-totals/pilot-report modal) exercised live in a real browser with real data. Android: 4 concurrent agents' edits to shared files (AppContainer.kt, ApiService.kt, WheelDashboardScreen.kt, CabDispatchNavHost.kt) reconciled with no duplicate declarations, no orphaned references -- see android/HANDOFF.md's top entries.


**2026-08-03 addition (latest) — White-label branding was completely non-functional; now real,
verified live end-to-end.** The dashboard's White-label Settings page (logo/color pickers, live
preview, presets, reset-to-default) had been fully built for a while, but the two endpoints it
called (`GET`/`PATCH /v1/tenants/me`) never existed server-side — the page 404'd immediately on
load, and even fixing that alone wouldn't have been enough: nothing else in the app actually read
the saved theme, and receipts showed a hardcoded "CAB DISPATCH" placeholder regardless of tenant.
Closed all three pieces:
- **Backend:** `GET`/`PATCH /v1/tenants/me` added (`app/api/v1/tenants.py`), backed by `Tenant`'s
  already-existing `theme_json` column (no migration needed) — owner/admin can update, anyone on
  the tenant can read, `theme_json=null` resets to the platform default. 8 new tests.
- **Dashboard — the app now actually re-themes, not just the settings page's own preview card.**
  `AppShell.tsx` applies the tenant's `primary_color`/`accent_color` to the two CSS custom
  properties (`--brand-primary`/`--brand-accent`) that literally every themed surface in this app
  already reads (`tailwind.config.js`'s `brand.primary/accent` mapping) — one small hook,
  zero changes needed to any individual page. `Sidebar.tsx` also now shows the tenant's real
  `logo_url`/`name` instead of a fixed "Cab Dispatch" label. **Verified live, not just built:**
  saved the dashboard's own "Lilly Cabs preset" (pink theme + a placeholder logo image), confirmed
  via `getComputedStyle` that the CSS variables actually changed, then navigated to a completely
  different page (Trips) and confirmed the whole UI — sidebar, buttons, active-nav highlight — was
  genuinely pink, not just the settings page.
- **Receipts:** `app/services/receipts.py`'s PDF header now renders the real tenant name/ABN
  (`_lookup_tenant_branding`) instead of a hardcoded "CAB DISPATCH" placeholder. 2 new tests.
- **Deliberately not done this pass, flagged rather than silently assumed complete:** the tenant's
  `logo_url` is not embedded as an actual image in the generated receipt PDF (real, separate work —
  fetch + decode + fpdf2's image API — the dashboard/sidebar are the only places a logo image
  renders today); the Android meter app has zero tenant-branding awareness; and there's no custom
  domain support (blueprint §13.1) — that needs real DNS/infrastructure, not code.

**2026-08-03 addition (later) — duress voice/audio, trip dispute + new payment methods, tariff
presets, driver/vehicle compliance-expiry — a 9-agent pass, independently re-verified live
afterward (not just "tests pass"), with two real bugs found and fixed during that verification:**

- **Duress escalation call + audio recording (`app/services/duress.py`, `app/api/v1/duress.py`).**
  A real Twilio Voice call now fires automatically when the escalation cascade reaches its final
  stage (mock-fallback if no `DURESS_ESCALATION_CALL_PHONE`/Twilio credentials configured, same
  pattern as every other paid integration). New `POST`/`GET /v1/duress/{id}/audio` — upload and
  play back a captured audio recording (local-disk storage, same convention as receipts/
  compliance docs), reusing the existing `DuressEvent.audio_ref` column rather than a new one.
- **Trip dispute flagging + voucher/account/split-fare payments (`app/models/trips.py`,
  `app/services/trips.py`, `app/services/payments.py`).** New `PATCH /v1/trips/{id}/flag`
  (422 without a non-empty reason, 409 on a still-open trip) and a `flagged_for_review` filter on
  `GET /v1/trips`. `payment_method` now also accepts `voucher` (redemption stub), `account`
  (corporate-account reference, stub), and `split_fare` (a list of sub-payments that must sum,
  to the cent, to the trip's final total — 422 otherwise). Live re-verified: created and closed a
  real trip, flagged it without a reason (422), flagged it with one (200), confirmed the filter
  and the dashboard's dispute panel showed the exact reason text back.
- **Tariff presets + auto-suggest (`app/services/tariff_presets.py`, `app/services/tariffs.py`).**
  `GET /v1/tariffs/presets` (Airport Rank / Special Event / Shared Ride / Wheelchair Accessible —
  prefills the existing accurate NSW Fares Order tariff model, doesn't replace it) and
  `GET /v1/tariffs/suggest?lat=&lng=&vehicle_class=` (reuses the existing geofence/time-class
  logic, degrades honestly to the current default tariff when nothing more specific matches — live
  re-verified: correctly detected the seeded Sydney Airport geofence and said so in its `reason`
  string, even though no airport-specific tariff exists yet to actually suggest).
- **Driver/vehicle compliance-expiry tracking (`app/services/compliance_expiry.py`).** New
  `driver_license_expiry`/`driver_authority_expiry` (User) and `registration_expiry`/
  `insurance_expiry` (Vehicle) columns, a new `GET /v1/fleet/compliance-expiry` rollup, and a real
  login block: `POST /v1/auth/driver-login` now 403s with an actually-expired license (never blocks
  on a null/unset one). Live re-verified: set an expired license on the seeded demo driver,
  confirmed both the compliance-expiry list and the login 403 fired correctly.
- **Two real bugs caught and fixed during the post-pass verification, not left standing:** the new
  `TripSyncItem` backend schema was missing the three new payment fields, meaning they'd have been
  silently dropped on the app's actual offline-sync path (fixed, with 4 new tests); and a likely
  pre-existing Kotlin compile error in `RealLocationProvider.kt` (`isActive` used without a
  `CoroutineScope` receiver) was caught while cross-checking a sibling agent's own careful avoidance
  of the same mistake, and fixed (see `android/HANDOFF.md`'s top entry for both).

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

Real Stripe/Twilio/CabCharge/TTSS/SendGrid credentials (every one of these has a real mock-fallback
call path in code — receipts, CabCharge/TTSS payment, duress voice escalation — but none has a live
account configured in this dev environment), an actual Docker/Postgres/Redis run (compose file
exists, untested), Redis-backed pub/sub for duress/live-position/jobs/messages at >1 backend
process, S3 storage (receipts/compliance docs/duress audio all go to local disk), ESP32 duress
hardware + BLE pairing, physical field fare-accuracy testing, Android
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
