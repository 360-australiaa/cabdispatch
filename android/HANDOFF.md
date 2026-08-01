# Android meter — finish-it checklist (read this first)

You (Claude Code, running via the JetBrains plugin inside Android Studio) have **no memory of
the session that generated this code**. This file is written to be fully self-contained so you
can pick the work up cold. Read it fully before touching code.

## What this is

Cab Dispatch is an NSW taxi-meter SaaS: FastAPI backend + React fleet dashboard + this Android
meter app (Kotlin/Compose, offline-first). Full product spec: `../docs/TCT-METER-01-spec.md`
(sections B5/B6/B7 are the ones that matter for this module — screens, fare engine, offline
behaviour). Repo: https://github.com/360-australiaa/cabdispatch

**Honest current status:** every file in this module has been written and *read carefully* by a
prior agent pass, including a reconciliation pass that fixed one real bug (S3 not persisting
trip ticks to Room — see `ui/screens/hired/HiredViewModel.kt`'s doc comment if you want the
history). But **it has never been compiled**. The environment that wrote it had no Android SDK.
This machine (yours) is the first place this code will ever hit a real compiler. Expect and
budget time for real compile errors — signature mismatches between sibling files that were
written in parallel without ever type-checking against each other are the most likely failure
mode, not conceptual bugs.

## Step 0 — get it compiling (do this before anything else)

1. Open `android/` (this folder) as the project root in Android Studio, let Gradle sync.
2. `./gradlew assembleDebug` (or Build → Make Project). Fix real compile errors as they surface —
   check `AppContainer.kt` first if you see "unresolved reference" errors, it's the manual
   service-locator wiring everything else depends on.
3. `./gradlew testDebugUnitTest` — two plain-JUnit test files exist and should just run:
   `domain/fare/FareEngineTest.kt` and a sync-outbox test. **Do not weaken or delete an
   assertion to make a test pass** — if `FareEngineTest` disagrees with the backend, the backend
   (`../backend/app/services/fare_engine.py` + `../backend/tests/test_fare_engine_golden.py`) is
   the source of truth; fix the Kotlin port, not the test.
4. Only once it compiles and unit tests pass, move to a device/emulator.

## Step 1 — cross-check the fare engine against the backend (safety-critical, do this explicitly)

There are **two** fare-related classes in this module and that's deliberate, not a bug — don't
merge them without understanding why first:

- `domain/fare/FareEngine.kt` — the line-for-line port of the backend's `fare_engine.py`, proven
  against the same golden vectors as `backend/tests/test_fare_engine_golden.py`. This is what
  must be correct for money.
- `domain/FareEngine.kt` — a live, tick-by-tick UI-facing engine that drives the S3 running-fare
  display in real time. It persists `movingSeconds`/`waitingSeconds` to Room as it goes; S4
  (`CloseAndPayViewModel`) reconstructs the final trip from those persisted counters through
  `domain/fare/TripFareReconstruction.kt`, which is what actually calls the golden-vector-proven
  engine for the number that gets shown/charged/synced.

Read `domain/fare/TripFareReconstruction.kt` and confirm for yourself that the number the
passenger is charged always comes from the proven engine, never from the live UI engine directly.
If you find a code path where it doesn't, that's a real bug — fix it before anything else on this
list.

## Step 2 — known gaps, in priority order

### High priority (correctness / would-block-shipping)

- **`security/TariffSignatureVerifier.kt`** — has a `*** PLACEHOLDER KEY — REPLACE BEFORE
  SHIPPING ***` comment. Real Ed25519/RSA verify logic is implemented, but the public key is a
  placeholder constant. Needs a real keypair from whoever owns backend tariff-signing before this
  can be trusted. Fine to leave as-is for dev/testing; flag loudly if asked to "ship" this.
- **`ui/screens/settings/SettingsViewModel.kt`** — `ADMIN_PIN_PLACEHOLDER = "913572"` is a
  hardcoded factory-reset PIN, explicitly marked `*** NOT A REAL SECURITY CONTROL ***`. Needs a
  real backend-verified admin check before shipping.
- **`domain/DriverAuthRepository.kt`** — the meter's "Driver ID" field is currently mapped
  straight to the backend's `email`, and "PIN" to `password` — i.e. it's reusing the staff
  email/password login, not a real driver-PIN system. Decide: keep this as the permanent design,
  or build a dedicated driver-PIN backend endpoint (`POST /v1/auth/driver-login` taking a short
  driver code + PIN, separate from staff login) and wire this class to it. If you build the
  backend endpoint, the FastAPI conventions to follow are in `../backend/app/api/v1/auth.py` and
  `../backend/app/core/security.py` — tenant-scoped, same JWT shape, same `require_role` pattern
  every other domain router uses.

### Medium priority (real functionality gaps, no hardware needed)

- **GPS is stubbed, not real.** Search `TODO(location` across the module — the fare engine
  currently reads from a stub speed source, not `FusedLocationProviderClient`. This is required
  for the app to be useful at all (S3's whole job is reacting to real speed/position). Implement
  a real location provider, feed it into `domain/FareEngine.kt`'s tick loop.
- **Region is hardcoded to `"urban"`** in `SettingsViewModel.kt` — no real region-polygon
  detection yet (spec calls for geofenced urban/country/exempt regions, see spec B7).
- **Toll preset amounts are illustrative placeholders** (`domain/TripModels.kt`) — replace with
  real fixed toll amounts (M5, Harbour southbound, airport) once you have them.
- **Availability broadcast not wired** — `IdleViewModel.kt`'s "For Hire" toggle doesn't tell the
  backend anything yet. Backend already has `POST /v1/fleet/positions` for this (see
  `../shared/API_SUMMARY.md`).
- **Duress gesture is a no-op** — `HiredViewModel.kt`'s triple-tap handler just logs a warning.
  Backend duress endpoints already exist and work (`POST /v1/duress/trigger` etc.) — wire this up,
  it's a real safety feature, not cosmetic.
- **QR vehicle-pairing scanner is a stub** (`domain/QrScanner.kt`) — manual-entry fallback works,
  but real camera scanning (CameraX + ML Kit) isn't implemented.
- **Pre-shift inspection checklist items are placeholders** (`LoginVehicleBindViewModel.kt`) —
  confirm real checklist content against whatever compliance checklist NSW cl.14 actually
  requires (spec section A1) before this is real.

### Low priority (hardware-dependent — reasonable to leave stubbed until a physical pilot)

`hardware/payments/CardPaymentGateway.kt` (Stripe Terminal Tap-to-Pay), `hardware/printing/
ReceiptPrinterGateway.kt` (BT thermal printer), `hardware/receipt/{Sms,Email}ReceiptGateway.kt` —
all real interfaces with mock/no-op implementations behind them. Don't fake these into "working" —
they genuinely need physical hardware (or a real Stripe key) to implement for real. Leave stubbed
and move on unless you specifically have hardware to test against.

## Step 3 — manual end-to-end test, once it runs

1. Log in (S1) — see the driver-auth gap above; for now use a real backend user's email/password
   as "Driver ID"/"PIN" (create one via the dashboard's Fleet & Drivers page, or
   `POST /v1/users`).
2. Walk S1 → S2 (available toggle) → S3 (start a trip, watch the fare accrue) → S4 (close, check
   the fare breakdown + GST line) → S5 (shift report) → S6 (settings/diagnostics).
3. **Offline test — this is the point of the whole app:** turn on airplane mode, run a full trip
   start-to-close. Confirm it works with zero errors (no network calls should block anything).
   Then re-enable network and confirm `SyncWorker` drains the outbox automatically within a
   couple minutes — check the backend's `GET /v1/trips` (or the dashboard's Trips page) for the
   trip showing up, and confirm running it twice doesn't create a duplicate (idempotency via
   `client_uuid`).

## Backend, for testing against

Needs to run **on this same machine** for the emulator's `10.0.2.2:8001` alias to reach it (a
physical device needs your LAN IP instead — see `app/build.gradle.kts`'s `API_BASE_URL` per
build type).

```
cd backend
uv sync
uv run python scripts/init_db.py
uv run python scripts/seed.py
uv run uvicorn app.main:app --port 8001
```

Seeded logins (both password `ChangeMe123!`): `admin@cabdispatch.test` (platform owner,
cross-tenant), `owner@lillycabs.test` (Lilly Cabs tenant owner). Neither is a `driver` role —
create one via the dashboard or `POST /v1/users` before testing driver login specifically.
Full API reference: `../shared/API_SUMMARY.md` and `../shared/openapi.json`.

## When you fix something

Commit as you go with real messages (what + why, not "fix bug"). Push to `main` unless you're
mid-something risky, in which case a branch + note to the user is safer. Update this file's
"known gaps" section if you close one out or discover a new one — keep it honest for whoever
(human or Claude) reads it next.
