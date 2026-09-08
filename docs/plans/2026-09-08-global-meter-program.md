# Cab Dispatch — Global Meter Program (2026-09-08)

**What this is:** the execution plan for taking Cab Dispatch from a single-tenant NSW field-test MVP to a globally deployable product — commissioning-gated tablets, an accurate meter, a driver home screen worth looking at, and an ops dashboard that is safe to hand to a stranger. It is written to be executed **autonomously by a fleet of Sonnet agents in parallel git worktrees**, orchestrated by a lead session, with no human in the loop except at the gates marked **OWNER**.

**What it is built on:** four independent audits committed alongside it. Every claim in this plan carries a `file:line` in one of them. Agents MUST read the audit for their surface before touching code:

| Audit | Path |
|---|---|
| Android architecture, fare accuracy, offline, MDM, hardware, security | `docs/audits/2026-09-08-android-architecture-audit.md` |
| Android driver-facing UI (home screen, meter, motion, a11y) | `docs/audits/2026-09-08-android-driver-ui-audit.md` |
| Backend API, tenancy, device contract, data integrity, security, ops | `docs/audits/2026-09-08-backend-audit.md` |
| React ops dashboard | `docs/audits/2026-09-08-dashboard-audit.md` |

Prior context an agent may need: `PROJECT_HANDOFF.md` (whole-platform status), `android/HANDOFF.md` §"SYSTEM REFERENCE" (lines 147–288: the live server, Figma→Android status, API readiness), `docs/TCT-DRIVER-APP-01.md` (design intent + tokens), `docs/TCT-METER-01-spec.md` (NSW regulation and fare rules).

---

## ⚠ Do today, before any agent starts (OWNER + lead session)

A late addendum to the backend audit found a complete unauthenticated-to-authenticated path, verified in the built artifact:

1. The seeded demo driver's code and PIN are `const val`s in `ui/screens/login/LoginVehicleBindViewModel.kt:39-40`, documented there as *"this tenant's real seeded driver code, verified live"*. They are **present as strings in `cabdispatch-meter-0.6.2.apk`** (`classes11.dex`) — the `BuildConfig.DEBUG` gate on the button does not strip the constant, and the APK was not built through the minified `release` variant.
2. The same APK embeds the API base URL `http://72.61.107.107:8001` — production, **plaintext HTTP**.
3. `POST /v1/auth/driver-login` has **no rate limiting**, the PIN is 6 numeric digits, and the driver-code lookup at `api/v1/auth.py:142` is **global, not tenant-scoped**.
4. Three ~145 MB APKs sit in the repo root **untracked but not gitignored** — one `git add -A` commits them, and the credential, to permanent history.

**Actions, in order:**
- **OWNER, now:** on the dashboard, change the demo driver's PIN or delete the driver (Fleet ▸ Drivers). Treat the old credential as disclosed.
- **Lead, before Phase 0:** add `*.apk` to `.gitignore`; delete `DEMO_DRIVER_ID`/`DEMO_DRIVER_PIN` and `quickLoginDemoDriver`; if the convenience is wanted, it lives under `android/app/src/debug/` so it cannot compile into any other variant. Make the `release` variant **fail the build** while `API_BASE_URL` is still the `example.com` placeholder (`build.gradle.kts:109-111`).
- **B4 (rate limiting) is promoted to blocking**, and its driver-login limit must be per-`driver_code` as well as per-IP. `driver-login` must also resolve the driver **within a tenant** — add `tenant_code`/slug to the request or scope the lookup by the device's paired tenant.
- **B3's HTTPS item is promoted from "deploy doc" to a prerequisite** — decision #1 in §5 is no longer optional.

## 0. The state of the product, in one screen

**What works and is genuinely good.** The pure fare engine (`android/.../domain/fare/FareEngine.kt`, `backend/app/services/fare_engine.py`) is `BigDecimal`/`Decimal` throughout, golden-vector tested across both sides, and handles NSW rounding, maxi, PSL, tolls and negotiated fares correctly. Toll detection is careful. Ed25519 tariff signing is real. The audit log is a real hash chain. Offline trip sync is idempotent. The dashboard has 22 real, wired modules with zero mock data. The commissioning gate (registration → permissions → offline maps → kiosk → tariff → heartbeat) works end to end from a clean install — verified on the test tablet today.

**What is broken, by severity.**

*Money is wrong or at risk.*
- The meter tick assumes exactly one second per iteration; under load it **under-bills** (Android F1).
- Distance is time-integrated speed, never GPS haversine; **no GPS-staleness cut-off**, so a tunnel keeps billing at the last speed (F2, F3).
- The fare loop dies on Back, on Doze, and on process death — **no foreground service** (F4).
- The live dial's total omits the maxi multiplier and round-down; **a maxi trip under-reads by 50% of the metered base** on screen (F8).
- Manual + auto tolls can double-charge the same crossing (T1).
- A retried `/tick` **double-counts distance** server-side (backend §4).
- One bad item in `POST /v1/trips/sync` **discards every good trip in the batch** — a whole offline shift (backend §4).
- Card payment, printer, SMS and email receipts are **all mocks wired into the only build path**; the driver sees "Payment received" for money that never moved (Android §5).

*Security.*
- **A working driver login is compiled into the distributed APK, which points at a plaintext-HTTP production server with no rate limiting** (see the block above).
- Offline login accepts the cached PIN on a **server rejection** — a suspended driver logs in forever (X1).
- `POST /v1/auth/logout` is a no-op; refresh tokens are never rotated; four WebSocket handlers accept **refresh and `mfa_pending` tokens** (backend §5).
- No rate limiting anywhere, including the 6-digit PIN login and admin-PIN verify.
- Production boots happily with the **committed tariff-signing private key**, defeating signature verification entirely (backend §5).
- Tokens, device secret and PIN hash in plaintext `SharedPreferences`; PIN hash unsalted (X2, X3).
- "Wipe all fleet data" is a live route and a header button on a production page.

*Data loss.*
- A shift started offline is **synthesised and never persisted** (S3).
- A permanently-rejected outbox row blocks the queue forever (S1).
- No background jobs at all: duress never auto-escalates, fatigue never fires without a ticking trip, position history for a silent vehicle is never pruned.

*The driver experience.*
- Home screen header is 128dp of an 800dp canvas (16%) before any content. Live Dispatch is a fixed 300dp box that says "No live offers" for most of every shift. Three of four "My Account" tiles are below an invisible fold. The nav rail has HISTORY and TRIPS **swapped**, METER unselectable, and 6 of 14 items hidden. Twenty distinct font sizes on one screen, a 9sp floor, and every icon unlabelled for TalkBack. ~3,500 lines of dead dashboard variants sit next to the live one.

*Global readiness.*
- NSW is hardcoded in ~30 Android files, ~25 backend sites and ~40 dashboard sites: `Australia/Sydney`, `/11` GST, 22:00–06:00 night, Fri/Sat peak, a 2026–27 holiday frozenset duplicated across two codebases, AUD, "Point to Point", the toll registry, `urban|country|exempt`. Rate cards are per-tenant; **rules are code**.
- No self-serve tenant creation: `POST /v1/platform/tenants` makes a bare row; an operator must impersonate it to add an owner and a tariff.
- Dashboard: no i18n, no timezone handling, Karachi as the default map centre with user-visible "for field testing" copy.

*Engineering hygiene.*
- This branch is **180 commits ahead of `main`**. 82 git worktrees and 78 `worktree-agent-*` branches are lying around. **No CI.** No Android lint. Dashboard has **zero tests** and its "lint" is `tsc --noEmit`. Backend tests never run alembic (SQLite `create_all` only) — the one class of bug that has bitten production twice.
- Kotlin 1.9.24 / AGP 8.5.2 / Compose compiler 1.5.14 — a year behind.

---

## 1. Operating rules for every agent

These are not suggestions. An agent that cannot comply must stop and report.

**Environment**
1. Work in your own git worktree on a branch named per your workstream (§4). Base on `main` **after Phase 0 has merged**. Never base on another workstream's branch.
2. **Never** run `adb`, install an APK, or touch the test tablet. On-device verification is the lead session's job and is listed under **OWNER** gates.
3. **Never** call the production server `72.61.107.107` with a write. Never run `seed.py`, `wipe-test-data`, or any migration against it. Backend work runs against local SQLite (`uv run python scripts/init_db.py`), and Postgres only where a workstream explicitly provisions one.
4. **Never** enter, mint, print, or commit credentials. If a task needs a login, stop and report. Demo credentials in `scripts/seed.py` are to be *removed from docs*, not reused.
5. **Never** deploy. The owner deploys.

**Code**
6. Match the surrounding code: comment density (this repo explains *why* at length — keep doing that), naming, and idiom. Kotlin: no Hilt, `AppContainer` service locator stays. Compose: Material 3 only. Python: async SQLAlchemy 2, Pydantic v2, ruff-clean. TS: no `any`, kit components over raw Tailwind.
7. **Golden vectors are law.** `android/.../test/.../fare/FareEngineTest.kt` and `backend/tests/test_fare_engine_golden.py` expected values MUST NOT change. If your change alters a golden output, you have changed the fare — stop and report.
8. **Alembic stays at exactly one head.** Run `alembic heads` before you push. Migrations use `batch_alter_table` for SQLite per repo convention and set `server_default` on timestamp columns (see `services/fleet.py:1-42` for why).
9. **Calm motion** (Android): nothing animates continuously while the vehicle is parked. Every per-frame delta is a function of speed or a one-shot reaction to a state change. No full-layer `RenderEffect`. Read `ui/theme/Hud.kt:437-455` before adding any animation.
10. **Tenant scoping**: every new query on a `TenantScopedMixin` model filters on `tenant_id`. No exceptions.
11. **Honesty over polish**: never fake a success, a count, or a state. A mock must say it is a mock. An empty state must say why it is empty.

**Process**
12. Every workstream ends with: all three test suites green for the surfaces you touched (`./gradlew :app:testDebugUnitTest`, `uv run pytest`, `npm run lint && npm run test && npm run build`), a rebase onto current `main`, and a PR whose description lists **what you verified and how**. Unverified claims are not accepted.
13. If you discover a conflict with another workstream's file ownership, **stop and report** to the lead. Do not resolve it yourself.
14. Commit trailer: `Co-Authored-By: <your model name> <noreply@anthropic.com>`.
15. Update the relevant handoff doc (`PROJECT_HANDOFF.md`, `android/HANDOFF.md`) in the same PR when you close a gap it lists — a stale handoff is worse than none.

---

## 2. Definition of done for the whole program

The program is complete when the owner can do all of the following without touching a terminal:

1. **Create a new tenant** from the Platform console, receive an owner invite, log in as that owner, and see a working dashboard with a default tariff — in a jurisdiction other than NSW, with its own timezone and currency.
2. **Commission a factory-fresh tablet** against that tenant: install APK → checklist → pairing code from the dashboard → driver login. The driver never sees the checklist again.
3. **Drive a real trip** on the test tablet through a GPS blackout (tunnel or simulator) and have the fare match the server recompute within the 1% flag threshold; kill the app mid-fare and have the fare survive; press Back mid-fare and have the fare survive.
4. **Watch it on the dashboard**: the vehicle appears within 5 s of shift start, the trail draws, Locate/Restart round-trip, and a duress event auto-escalates without anyone touching it.
5. **Go offline for a shift**: start the shift offline, close three trips, poison one with a bad voucher, reconnect — two trips sync, one is reported failed with a reason, nothing is lost.
6. **Hand the dashboard to a driver-role user** and find every write action hidden or refused.
7. **Look at the driver home screen** and not say "it looks like shit."

Plus three engineering gates: CI green on every PR (Android unit + lint, backend pytest incl. alembic, dashboard vitest + eslint + build); `main` is the deployed branch; zero `worktree-agent-*` branches.

---

## 3. Phasing and dependency graph

```
PHASE 0  (serial, one agent, ~1 day)        ── must merge before anything else
   P0.1 merge branch → main, prune worktrees
   P0.2 CI + lint gates on all three surfaces
   P0.3 delete dead Android UI (~3,500 lines)
   P0.4 split the three Android god-files
   P0.5 dashboard: vitest + eslint scaffolding (empty but wired)
         │
WAVE 1  (parallel, 9 agents)                ── correctness & security, P0
   A1 fare-engine blockers      B1 trips integrity      D1 dashboard tests+lint
   A2 auth/sync blockers        B2 auth+WS lifecycle    D2 role gating
   A5 hardware honesty          B3 secrets+deploy       D3 remove test tooling
                                B4 rate limiting
         │  (A1 and B1 must both merge before Wave-2 A3 starts — the meter
         │   screen is rebuilt on top of the fixed engine)
WAVE 2  (parallel, 8 agents)                ── product & platform, P1
   A3 home-screen redesign      B5 device lifecycle     D4 design system
   A4 meter-screen polish       B6 lazy background work D5 shared utils + surgery
   A7 commissioning polish      B7 ops hardening
                                B8 test parity (alembic/PG)
         │
WAVE 3  (parallel but COORDINATED, 5 agents) ── global readiness, P1/P2
   X1 jurisdiction seam  = B9 (backend FareRegion) + A6 (Android JurisdictionConfig)
                            — ONE agent owns both halves, golden tests stay identical
   X2 tenant self-serve  = B10 (backend) + D7 (platform page)
   D6 dashboard i18n + tenancy (depends on B9's Tenant.timezone/currency)
   A8 Android accessibility pass
         │
WAVE 4  (parallel, as many as useful)       ── feature completion, P2
   D8 dispatch geocoder   D9 duress desk   D10 security settings
   D11 reporting/export   D12 server-side pagination (+ backend params)
   B11 dead-surface reconciliation + openapi regen
   A9 toolchain upgrade (Kotlin 2 / Compose BOM / KSP)
         │
OWNER GATES  (the lead session, on the real tablet and real dashboard)
   G1 after Wave 1: fare survives Back/Doze/kill; tunnel test; offline shift
   G2 after Wave 2: home screen review; commissioning from factory reset
   G3 after Wave 3: second-jurisdiction tenant end-to-end
   G4 after Wave 4: full DoD §2 walkthrough
```

**Worktree hygiene for the orchestrator:** one worktree per workstream, named `wt/<id>`; delete on merge. Never more than ~9 concurrent agents — the file-ownership tables below were drawn so that agents in the same wave do not touch the same files, but the three god-files are only safe *after* P0.4 splits them.

---

## 4. Workstreams

Each workstream lists: **Branch**, **Owns** (files you may edit), **Do not touch**, **Tasks** (with audit references), **Acceptance**, **Verify**.

---

### PHASE 0 — Preconditions (serial; one agent, `phase0/foundation`)

**Owns:** everything, serially. Nothing else runs until this merges.

**P0.1 Merge and prune**
- Merge `android/battery-network-heartbeat-and-map-fixes` into `main` (fast-forward; it is 180 commits ahead and contains this plan). Tag `v0.6.2-audit-baseline`.
- `git worktree prune`; delete all 78 `worktree-agent-*` branches and their worktrees; delete merged feature branches. Keep `dashboard/dark-mode-invisible-text` if unmerged and useful — inspect, then decide.
- Move `cabdispatch-meter-*.apk` files out of the repo root into `android/dist/` (gitignored) and add the pattern to `.gitignore`.

**P0.2 CI**
- `.github/workflows/ci.yml` with three jobs: `android` (`./gradlew :app:testDebugUnitTest :app:lintDebug`), `backend` (`uv sync && uv run ruff check && uv run pytest`), `dashboard` (`npm ci && npm run lint && npm run test && npm run build`). Required on PRs to `main`.
- Android: add `detekt` with the default config + a `.editorconfig`; wire `detekt` into the CI job. Baseline current violations rather than fixing them all.
- Backend: `ruff` already configured — make CI fail on it.

**P0.3 Delete dead Android UI** — per UI audit §0 and architecture audit §1.4
- Delete `ui/screens/dashboard/WheelDashboardScreen.kt`, `HomeDashboardV2.kt`, `DockScreenChromeV2.kt`, `ui/wheel/WheelState.kt`, `WheelGeometry.kt`, `WheelGesture.kt`, `ui/deck/DeckChrome.kt`, `ui/theme/WheelColorsV2.kt`, and the `WheelColors` object from `ui/theme/Theme.kt:48-81`.
- Migrate the one live `DeckKeypad` call site (`DeckHomeScreen.kt:2538`) to `CaptainKeypad` (`CaptainWidgets.kt:496-515`) and delete `ui/deck/DeckWidgets.kt`.
- Delete the `PLOT_ZONE` and `ZONE_STATISTICS` routes (`CabDispatchNavHost.kt:270-275`) and `PlotZoneScreen.kt`/`ZoneStatisticsScreen.kt` — their content lives in `ZonesPaneContent` tabs. Confirm with grep that nothing else references them.
- Fix every KDoc that referenced the deleted files (`CabDispatchNavHost.kt:68,107,180`, `DuressOverlays.kt:48,196`, `MapboxOfflineRegion.kt:26`).

**P0.4 Split the god-files** — mechanical moves only, zero behaviour change
- `ui/screens/dashboard/DeckHomeScreen.kt` (2,704) → `DeckHomeScreen.kt` (shell + pane switch), `CaptainHeader.kt`, `CaptainNavRail.kt`, `MeterCard.kt`, `LiveDispatchCard.kt`, `ShiftStatsBar.kt`, `StatusMapPanel.kt`, `HomeDialogs.kt`. Same package.
- `ui/screens/hired/HiredScreen.kt` (2,594) → `HiredScreen.kt`, `MeterDial.kt`, `FareBreakdownCard.kt`, `ControlsDrawer.kt`, `NavigatorPane.kt`, `MeterBanners.kt`.
- `data/remote/ApiService.kt` (2,067) → keep the interface in `ApiService.kt`; move DTOs into `data/remote/dto/{Auth,Fleet,Trips,Tariffs,Shifts,Duress,Jobs,Messages,Zones,Engagement}Dtos.kt` (precedent: `DriverEngagementDtos.kt`).
- `dashboard/src/pages/live-map/FleetMapCanvas.tsx` (1,548) → `FleetMapCanvas.tsx` (component), `mapInit.ts`, `markers.ts`, `trails.ts`, `PlainCanvasMap.tsx`.
- `dashboard/src/pages/fleet/api.ts` (804) → `api/vehicles.ts`, `api/drivers.ts`, `api/devices.ts`, `api/index.ts` re-export.

**P0.5 Dashboard test scaffolding** — install `vitest`, `@testing-library/react`, `jsdom`, `eslint` + `typescript-eslint` + `eslint-plugin-react-hooks` + `eslint-plugin-jsx-a11y`; add `test` block to `vite.config.ts`; `npm run lint` = `eslint . && tsc --noEmit`; `npm run test` = `vitest run`. Baseline existing ESLint violations with `eslint-disable` **removed** and rules set to `warn` where the count is large; one smoke test so the runner is proven.

**Acceptance:** `main` == this branch + P0 changes; CI green; `git branch | grep -c worktree-agent` == 0; all three builds pass; the Android app still installs and reaches the login screen (lead verifies on tablet — **OWNER G0**).

---

### WAVE 1 — Correctness and security (parallel)

#### A1 · Fare-engine blockers — `wave1/a1-fare-engine`
**Audit:** Android architecture §2.1, §2.2 (F1, F2, F3, F4, F6, F7, F8, F9, F11, F12, T1, T2).
**Owns:** `domain/FareEngine.kt`, `domain/TripModels.kt`, `domain/fare/TripFareReconstruction.kt`, `domain/location/RealLocationProvider.kt`, `domain/location/GeoMath.kt`, `data/local/dao/TripDao.kt`, `data/local/entity/TripEntity.kt` (+ migration 10→11), new `domain/MeterForegroundService.kt`, `AndroidManifest.xml` (service declaration only), `data/AppContainer.kt` (fare-engine hoist only), `ui/screens/hired/HiredViewModel.kt`, `ui/screens/closepay/CloseAndPayViewModel.kt` (F11 fallback only), tests.
**Do not touch:** `domain/fare/FareEngine.kt` (the pure engine — golden vectors), any `ui/screens/hired/*` composable (A4 owns), `ui/screens/dashboard/*` (A3 owns).

Tasks:
1. **F4 — foreground service.** Hoist `FareEngineImpl` out of `HiredViewModel.viewModelScope` into a process-lifetime holder in `AppContainer`, driven by a `MeterForegroundService` (`foregroundServiceType="location"`, persistent notification "Fare running · $X.XX"). Start at `openTrip`, stop at `closeTrip`. `HiredViewModel` becomes a thin observer. On process restart with an `OPEN` Room trip, rebuild the engine from `reconstructFareState` and resume ticking — the dial must show the correct running total, not reset. Remove the `BackHandler {}` swallow in `HiredScreen.kt:281` once Back is safe (coordinate: A4 will do the UI side; you make it safe).
2. **F1 — monotonic tick.** Replace `elapsedSeconds = 1` and `speed/3600` with a measured `System.nanoTime()` delta. Clamp `dt` to `[0, 5s]` so a resumed process does not bill a 10-minute gap as motion.
3. **F3 — GPS staleness.** `LocationFix` gains `receivedAtNanos`. In `tick()`, if the newest fix is older than `MAX_FIX_AGE_MS = 5_000`, do not accrue distance; accrue waiting time only if the last *known* speed was below threshold; publish `FareState.gpsLost = true`. Expose it on `FareState` so A4 can show "GPS LOST — waiting time only".
4. **F2 — haversine distance.** When a new accepted fix arrived since the last tick, `distanceDeltaKm = GeoMath.distanceKm(prev, cur)`; otherwise fall back to `speed × dt`. Both paths capped at `speed × dt × 1.5` to reject jumps.
5. **F6 — jitter.** Reject fixes with `accuracyM > 50`; clamp speed to 0 when `speed < 1.4 m/s`; collapse the three haversine implementations onto `GeoMath` (keep `TollDetector.tollHaversineM` byte-identical — it must match the backend).
6. **F8 — one truth for the total.** `FareEngineImpl.tick()` publishes `calcEngine.close(cs, …).grandTotal` as `FareState.total`; delete the parallel `FareBreakdown`/`FareState.total` naive sum. The live dial must equal the bill on a maxi trip.
7. **F9** — persist `accruedDistanceCharge`/`accruedWaitingCharge` as decimal strings on `TripEntity` (migration 10→11); `TripFareReconstruction` reads them.
8. **F11** — Close & Pay falls back to `URBAN_TARIFF`/`COUNTRY_TARIFF` with a visible "default rates" notice instead of refusing payment.
9. **F12** — `TripDao` day buckets use `NSW_FARE_ZONE` (A6 will make it configurable later; use the constant now).
10. **T1** — map `TollPreset.id` ↔ registry `roadId`; a road charged one way is not charged the other. **T2** — `tollRegistryCache.refresh()` on reconnect and in `SyncWorker`.
11. **F7** — one `DEFAULT_SPEED_THRESHOLD_KMH`.

**Acceptance:** new unit tests for each of F1/F2/F3/F6/F8/T1 in `domain/`; a `TripRepository` test (currently zero coverage) covering open→tick→kill→restore→close; golden vectors unchanged; `FareEngineImplRunningDisplayTest` extended to assert dial == bill for maxi. Manual: **OWNER G1** — on the tablet, start a fare on the simulator, press Back, press Home, kill the app from Recents, come back — the fare is still running and the total is continuous. Run the simulator's tunnel profile (add one: 60 km/h → 0 fixes for 90 s → 60 km/h) and confirm no distance accrues during the gap.

#### A2 · Auth, session and sync blockers — `wave1/a2-auth-sync`
**Audit:** Android architecture §3 (S1, S2, S3, S5, 401 handling), §7 (X1–X6), §1.2.
**Owns:** `domain/DriverAuthRepository.kt`, `domain/TokenStore.kt`, `domain/DevicePairingStore.kt`, `domain/ShiftRepository.kt`, `sync/OutboxDrainer.kt`, `sync/SyncWorker.kt`, `sync/ConnectivitySyncTrigger.kt`, `data/local/dao/SyncOutboxDao.kt`, `data/local/entity/SyncOutboxEntity.kt` (+ migration, coordinate version number with A1: A1 takes 11, you take 12), `data/AppContainer.kt` (auth/network sections only), `ui/screens/login/LoginVehicleBindViewModel.kt` (quick-login removal only), `res/xml/network_security_config.xml`, `build.gradle.kts` (security-crypto dependency), tests.
**Do not touch:** anything under `ui/screens/dashboard/`, `ui/screens/hired/`, `domain/FareEngine.kt`.

Tasks:
1. **X1** — offline fallback only on `IOException`/timeout. An `HttpException` 401/403 **clears** the cached hash for that driver and fails. Test both branches.
2. **X2** — PBKDF2-HMAC-SHA256, per-device random salt, 120k iterations, for the offline PIN cache. Migrate existing entries lazily (re-hash on next successful online login).
3. **X3** — `EncryptedSharedPreferences` for `TokenStore`, `DevicePairingStore`, and the auth cache. One-time migration from the plain files, then delete them.
4. **X4** — delete `seedOfflineDemoDriver` and the QUICK LOGIN button. Debug builds ship to the field.
5. **X6** — logging interceptor `Level.HEADERS` max, `redactHeader("Authorization")`, `redactHeader("X-Device-Secret")`.
6. **X5** — `network_security_config.xml`: cleartext only for `10.0.2.2` and `localhost`. This will break the current HTTP-only production server — **coordinate with B3**, which makes HTTPS mandatory. Land behind a build flag `ALLOW_CLEARTEXT_HOST` defaulting to empty; the owner sets it until TLS is live.
7. **401 give-up** — clear both tokens and the session when the refresh call itself 401s; route to login.
8. **`synchronized(this)`** → private lock object.
9. **S3** — shift start goes through the outbox (`entityType = SHIFT`, local `clientUuid`); trips closed under an unsynced shift carry the client shift id; `OutboxDrainer` syncs shifts before trips and rewrites `shift_id`. Requires backend `POST /v1/shifts/start` to accept a `client_uuid` and be idempotent on it — **B1 owns that; agree the field name `client_uuid` up front.**
10. **S1** — `attempts` cap (5) + `nextAttemptAt` exponential backoff on the outbox; exhausted rows surface in `OfflineSyncScreen` with a "retry" action. **S2** — malformed rows move to a dead-letter state.
11. **S5** — `tariffCache.refresh()` on reconnect and in `SyncWorker`; also at login.
12. Gate the GPS simulator entry point behind `AdminPinGateScreen`.

**Acceptance:** tests for X1 both branches, X2 round-trip, S1 backoff, S3 shift-then-trip ordering in `OutboxDrainerTest`; `OfflineSyncScreen` shows exhausted rows. **OWNER G1**: log in, disable the driver on the dashboard, kill Wi-Fi, relaunch — login must fail.

#### A5 · Hardware honesty — `wave1/a5-hardware-honesty`
**Audit:** Android architecture §5.
**Owns:** `hardware/**`, `ui/screens/closepay/CloseAndPayScreen.kt` and `CloseAndPayViewModel.kt` (payment/receipt sections only), `data/remote/dto/TripsDtos.kt` (receipt DTOs), `data/AppContainer.kt` (gateway wiring lines only).
**Do not touch:** fare math in `CloseAndPayViewModel` (A1 owns F11).

Tasks:
1. Wire `EmailReceiptGateway` and `SmsReceiptGateway` to the real routes that already exist: `POST /v1/trips/{id}/receipt/email` and `/receipt/sms` (`trips.py:746,781`). Delete the mocks.
2. `MockCardPaymentGateway` and `MockReceiptPrinterGateway`: in non-debug builds throw `NotImplementedError`; in debug builds render every result with a persistent **"SIMULATED — no money moved"** banner on the receipt and in the Close & Pay confirmation. The receipt text itself carries "TEST RECEIPT". Nothing mock may look real.
3. Close & Pay: "TAP TO PAY" and "PRINT" buttons are hidden unless the corresponding gateway reports `isReal`. CASH, PAYMENT LINK (once real), CABCHARGE/TTSS entry remain.
4. Resolve the `paymentMethod` enum mismatch TODOs (`CloseAndPayViewModel.kt:62,133`) against the backend `Trip.payment_method` values — read `backend/app/models/trips.py`.

**Acceptance:** a debug build shows the SIMULATED banner on every mock path; a release build compiles with the mocks unreachable. Unit test on the gateway gating.

#### B1 · Trip integrity — `wave1/b1-trips-integrity`
**Audit:** backend §4, WS-1.
**Owns:** `api/v1/trips.py`, `schemas/trips.py`, `services/trips.py`, `api/v1/shifts.py` + `services/shift.py` (idempotent start only), `models/shifts.py` (+ migration), `tests/test_trips.py`, `tests/test_shifts.py`.

Tasks:
1. `POST /v1/trips/sync` per-item savepoints; a failed item returns `TripSyncResultItem(status="failed", reason=…)`; good items commit. Test: 5 items, item 3 has a bad voucher → 4 succeed.
2. `/tick` idempotency: `TripTickRequest.tick_seq: int`; server stores `trip.last_tick_seq`; points with `ts <= trip.last_ts` are ignored; a replayed request is a 200 no-op. Test: replay the same batch twice → distance unchanged.
3. Ownership on trip writes: driver may only create/tick/close their own trips (`driver_id == user.id`); staff roles unrestricted. Mirror `flag_trip`.
4. Online close runs the same variance check as sync when the client supplies `device_total`; `max_fare_check_passed` is no longer unconditionally `True`.
5. `POST /v1/shifts/start` accepts optional `client_uuid`, unique per tenant, idempotent (409-safe like trips). This is A2's dependency.
6. `_recompute_trip_aggregates` filters `status == "closed"`; `split_fare` attributed by its components.

**Acceptance:** all listed tests; existing 759 still pass; single alembic head.

#### B2 · Auth & WebSocket lifecycle — `wave1/b2-auth-ws`
**Audit:** backend §5, WS-2.
**Owns:** `core/security.py`, `api/v1/auth.py`, the WS auth helper blocks in `api/v1/live_ops.py`, `duress.py`, `jobs.py`, `messages.py` (those functions only), `tests/test_auth*.py`, new `tests/test_ws_auth.py`.

Tasks:
1. `authenticate_websocket_token()` in `core/security.py`: `type == access`, not revoked, tenant resolved. All four handlers call it.
2. `POST /v1/auth/logout` revokes the access jti (return it from the dependency) and the refresh jti if presented.
3. `POST /v1/auth/refresh` revokes the presented refresh jti (rotation).
4. Login and refresh reject users of a `Tenant.status == "suspended"`.
5. Tests: each WS route rejects refresh, `mfa_pending`, and revoked tokens; logout then reuse → 401; refresh twice with the same token → 401.

#### B3 · Secrets & deploy hardening — `wave1/b3-secrets-deploy`
**Audit:** backend §5 secrets, §6 deploy.
**Owns:** `core/config.py`, `scripts/seed.py`, `docs/DEPLOY_UBUNTU.md`, `.env.production.example`, `docker-compose.yml` (Caddy/TLS service only), `tests/test_config_production_guard.py`.

Tasks:
1. `assert_production_secrets_safe` rejects the committed defaults for `TARIFF_SIGNING_PRIVATE_KEY` and `SECRET_ENCRYPTION_KEY`. Test.
2. `seed.py` refuses to run when `ENV == production` unless `--i-know-this-is-production`; never prints credentials; generates a random owner password and prints only "set via env `SEED_OWNER_PASSWORD`".
3. Remove the demo driver code/PIN from every doc (`DEPLOY_UBUNTU.md:182`, `ANDROID_STATUS_FOR_BACKEND_AGENT.md`, `android/HANDOFF.md`). Replace with "create a driver on Fleet ▸ Drivers".
4. Deploy doc: Caddy with automatic TLS as a required service (`docker-compose.yml`), HTTP → HTTPS redirect; `--workers 1` pinned with a comment referencing the in-process broadcasters; `DURESS_ESCALATION_CALL_PHONE` documented as required; backups: nightly `pg_dump` + `cabdispatch_uploads` tar to an offsite target, plus a written restore drill; log rotation; `restart: unless-stopped`.
5. Rotation runbook: what rotating each of the three secrets invalidates.

**Acceptance:** test green; a fresh reader of `DEPLOY_UBUNTU.md` ends with an HTTPS server and no known credential. **OWNER**: the owner obtains a domain and runs the new deploy — A2's cleartext lockdown depends on it.

#### B4 · Rate limiting — `wave1/b4-ratelimit`
**Audit:** backend §5, WS-4.
**Owns:** `main.py`, new `core/ratelimit.py`, decorator lines in `api/v1/auth.py` and `api/v1/fleet.py` (coordinate with B2/B5: add decorators only, after they merge, or land first — orchestrator's call), `pyproject.toml`, `tests/test_ratelimit.py`.

Tasks: `slowapi` with the existing `REDIS_URL` + in-memory fallback pattern. Limits: login 10/min/IP, driver-login 5/min/IP + 20/hour/driver_code, mfa 10/min, register 5/min/IP, verify-admin-pin 5/min/user with a 15-min lockout after 10 failures. Add a role gate (`owner|admin`) to verify-admin-pin — the tablet's factory-reset flow uses a driver token today; make that endpoint accept `X-Device-Secret` instead (B5 will add the general mechanism; here just the one route).

#### D1 · Dashboard tests + lint — `wave1/d1-tests`
**Audit:** dashboard §6, §7, WS-A. **Depends on P0.5.**
**Owns:** `dashboard/src/**/*.test.tsx`, `dashboard/e2e/**`, `dashboard/src/router.tsx` (error boundary + 404 only), new `dashboard/src/components/ErrorBoundary.tsx`, `NotFound.tsx`, `playwright.config.ts`.

Tasks: unit tests for `lib/apiClient.ts` refresh-on-401 (shared-promise, concurrent 401s, give-up), `lib/auth.tsx` MFA branch, all 9 UI-kit primitives, every `format*` helper (they will be consolidated by D5 — test the behaviour, not the location); error boundary + 404 page; Playwright smoke: login → MFA → live-map → one page per nav group, run against a local backend seeded in CI.

#### D2 · Role gating — `wave1/d2-role-gating`
**Audit:** dashboard §2, WS-B.
**Owns:** new `lib/permissions.ts`, `pages/trips/**`, `pages/dispatch/**`, `pages/messages/**`, `pages/fleet/VehiclesPanel.tsx`, `pages/fleet/DevicesPanel.tsx`, `pages/zones/index.tsx`, and the 16 existing gated call sites (mechanical swap onto `permissions.ts`).

Tasks: one `permissions.ts` (`canManage`, `canWrite`, `canAdminister`, `isPlatformOwner`); gate Trips/Dispatch/Messages/Fleet/Zones; every gated page renders the `WalletPage.tsx:86` access-notice pattern for the wrong role; test each `can*` function.

#### D3 · Remove test tooling from production UI — `wave1/d3-remove-test-tooling`
**Audit:** dashboard WS-C.
**Owns:** `pages/fleet/index.tsx` (wipe section), `pages/fleet/api/*` (wipe hook), `pages/settings/white-label/index.tsx`, `pages/live-map/mapInit.ts` (post-P0.4 location of `DEFAULT_CENTER`), `pages/live-map/PlainCanvasMap.tsx`.

Tasks: "Wipe all fleet data" behind `VITE_ENABLE_TEST_TOOLING=true` **and** platform-owner role, hidden otherwise (the backend route stays until B11 decides); remove the Lilly Cabs preset; `DEFAULT_CENTER` from tenant `theme_json.default_center` with a fallback to the fleet's last-known bounding box, no city hardcoded, no "for field testing" copy.

---

### WAVE 2 — Product and platform (parallel; starts after A1 + B1 + P0 merged)

#### A3 · Driver home-screen redesign — `wave2/a3-home-redesign`
**Audit:** UI audit §1, §2, §3, §7, §8 (P0 and P1 items). This is the owner's headline ask.
**Owns:** everything in `ui/screens/dashboard/` (post-split), `ui/theme/CaptainSpace.kt` (new), `ui/theme/CaptainWidgets.kt`, `ui/theme/CaptainPalette.kt` (dark `textMuted` only), `ui/screens/dashboard/EngagementTiles.kt`, `ui/overlays/ChromeMetrics.kt` (if the inset formula needs it).
**Do not touch:** `ui/screens/hired/**` (A4), `ui/theme/Hud.kt` (shared — propose changes via A4 if needed), any ViewModel's data contract (you may add fields, not change semantics).

Design brief (from the owner, reconciled with the repo's calm-motion rule): *alive when something is happening, perfectly still when it isn't.* "Game-like" means **arrival choreography, depth, and physics on value changes** — not ambient loops. Every item below is specified in the UI audit §8 with line numbers; this is the ordered task list.

1. **Compact header to 72dp** (UI §8 P0 #1–8). Delete the wordmark. Merge VERIFIED into the avatar badge. SOS to 56dp with inline HOLD. Kill the 200dp void spacer.
2. **Live Dispatch collapses when empty** (UI §8 P0). `AnimatedVisibility` expand/shrink; a 56dp `DispatchIdleStrip` ("Listening for jobs — you're available", pulsing dot gated on `isAvailable`, 48dp VIEW ALL) replaces the 300dp box. Error rendered in `danger`, distinct from empty. Reuse `EmptyOfferState` from `AvailableTripsWheelContent.kt:205` for the full pane.
3. **Announcements and Incentives hide when empty and error-free.** Wallet and Rating always visible. Announcement rows become tappable and open a readable dialog.
4. **Fix the rail**: rename HISTORY↔TRIPS to match their content; drop METER when no fare is open, show it glowing when one is; lock caption 9sp → 12sp; rail 104dp, tiles 88×68, 12sp labels; add a scroll affordance (fade at the bottom edge) so the hidden 6 items are discoverable — or better, cut to ≤10 items by moving DRIVER, VOUCHERS, PRICING, MAP under a single "MORE" pane.
5. **`CaptainSpace.kt`**: `Space`, `Radius`, `Type` objects. Migrate every `Text` in the dashboard package to a `Type` style; every padding to `Space`; every corner to `Radius`. 12sp floor. This is mechanical and large — do it last so the layout is settled first.
6. **Layout grid** (UI §8 P1): MeterCard 560dp; dial `BoxWithConstraints`-sized 300–380dp (fixes the 16:9 clip); NIGHT FARE / SET PRICE / VOUCHERS as a row of three 92dp chips under the dial (delete the absolute-positioned collision layout); right column a **2×2 grid** of the account tiles, no scroll; stat bar 120dp; delete the single-child `Row` wrapper.
7. **Night-fare window string**: read it from the tariff (`nightStartHour`/`nightEndHour` once B9 adds them; until then derive from the engine's own constants, not a literal). Fix the documented 8pm/10pm disagreement at `DeckHomeScreen.kt:1167-1175` by using the engine's value.
8. **`EarningsDelta`**: hide when `tripsCount == 0`; show "First trip of the day".
9. **Motion** (UI §8 P1): `StaggerIn` entrance across MeterCard → dispatch strip → account tiles → stat tiles, ≈500ms total then static; `RollingMoneyText` on Earnings; one-shot pop on Trips increment; **delete** the always-on loops: `SosControl` glow → static ring, Earnings sparkline breath, Available-trips card breathe; keep exactly three state-gated loops (status dot while AVAILABLE, DISPATCH badge while offers pending, METER rail halo while a fare runs). `TECH_GRID_ALPHA` 0.05 → 0.08 with a radial vignette toward the meter.
10. **Dark `textMuted`** `#5F6478` → `#8A90A8` in `DarkTokens`.
11. **Touch targets**: every control on the home screen ≥ 48dp (VIEW ALL, VIEW ALL JOBS, TAKE BREAK, refresh, name/rego tap).
12. **Fixed heights → `heightIn(min)`** so 1.3× `fontScale` doesn't clip; `FixedDesignCanvas` scales from `min(widthScale, heightScale)` (`MainActivity.kt:118`) so a 16:9 tablet letterboxes instead of clipping.

**Acceptance:** Compose previews for the home screen at 1280×800 in three states (off duty, available with no offers, available with 2 offers) and at 1280×720; screenshot tests via Paparazzi or Roborazzi for those previews checked into the repo; no `rememberInfiniteTransition` in the dashboard package outside the three sanctioned sites (add a unit test that greps for it); `detekt` clean. **OWNER G2**: the owner looks at it on the tablet.

#### A4 · Meter-screen polish — `wave2/a4-meter-screen`
**Audit:** UI audit §4, §6; architecture F3/F4 UI surface.
**Owns:** `ui/screens/hired/**` (post-split), `ui/theme/Hud.kt`.
**Do not touch:** `ui/screens/dashboard/**`, `domain/**`.

Tasks:
1. Show A1's new `FareState.gpsLost` as a persistent "GPS LOST — waiting time only" pill on the dial and a dimmed distance readout. Show "FARE RESUMED after restart" transiently when A1's restore path fires.
2. Remove the `BackHandler {}` swallow (A1 made Back safe); Back from the meter returns to the dashboard with the fare still running and the METER rail item glowing.
3. **END FARE and PAUSE to 56dp**; dialog close X and destination clear X to 48dp targets; `MapDestinationSearchBar` 48dp.
4. RUNNING glow (`HiredScreen` `1720-1726` pre-split) driven by speed like `GlowingSpeedometer`, still when parked, gone when paused.
5. Text floor 12sp; migrate to `Type` from A3's `CaptainSpace.kt` (coordinate — A3 lands `CaptainSpace.kt` first; you consume it).
6. Contentdescriptions on every icon on the meter screen; `semantics { role = Role.Button }` on the in-dial buttons.

**Acceptance:** previews at 1280×800 for RUNNING / PAUSED / GPS LOST / MAXI; screenshot tests; **OWNER G2** drive on the simulator.

#### A7 · Commissioning polish — `wave2/a7-commissioning`
**Audit:** architecture §4; UI audit §5; today's on-tablet run.
**Owns:** `ui/screens/readiness/**`, `ui/screens/permissions/**`, `ui/screens/terms/**`, `domain/DeviceReadiness.kt`, `domain/DeviceCommandHeartbeat.kt`, `domain/AppUpdateChecker.kt`, `domain/KioskLockController.kt`, tests.

Tasks:
1. `DeviceCommandHeartbeat` reads `DeviceDto.vehicleId` off the heartbeat and updates the session binding when it differs — the self-heal channel that already exists on the wire (backend G5).
2. `BatteryOptimisation` becomes **blocking** once A1's foreground service exists? No — with a foreground service it becomes genuinely advisory. Update its copy to say so. `SignedTariff` check uses the resolved region, not `"urban"` (`DeviceReadinessViewModel.kt:239`).
3. `AppUpdateChecker` authenticates with `X-Device-Secret` when no bearer exists (requires B5's backend change; land the client side behind a capability check on the 401).
4. Kiosk copy: the readiness row and the dashboard's "Kiosk lock" say **"Screen pinned"** and explain it is escapable and does not survive reboot; "Kiosk lock" wording reserved for a future Device Owner build.
5. Pre-shift inspection checklist: replace the placeholder items (`LoginVehicleBindViewModel.kt:44-46`) with the items in `docs/TCT-METER-01-spec.md` §A1 / NSW cl.14 — **OWNER decision** if the spec is ambiguous; do not invent.
6. Splash: remove `TSP-448041` and "NSW Taxi Meter" literals — read tenant name and jurisdiction from the cached tenant record (A6 adds it; use a placeholder field now).

#### B5 · Device lifecycle — `wave2/b5-device-lifecycle`
**Audit:** backend §3 (G1–G10), WS-5.
**Owns:** device section of `api/v1/fleet.py` (lines ~402–820), `services/fleet.py`, `schemas/fleet.py`, `api/v1/app_releases.py` (auth only), `tests/test_fleet.py`, `tests/test_app_releases.py`.

Tasks: revocation bypass closed (G3); `POST /devices/{id}/rotate-secret` (G2); audit-log every re-pair (G1); `GET /v1/fleet/devices/me` + `X-Device-Secret` on `/app-releases/latest` and download (G5, A7 dependency); `command-ack` clears `force_update_pending` and `kiosk_locked` (G9); prune pairing codes on mint (G10); `?rego=` exact match on `GET /fleet/vehicles` (G6); `verify-admin-pin` accepts `X-Device-Secret`.

#### B6 · Lazy background work — `wave2/b6-lazy-jobs`
**Audit:** backend §6 background jobs, §4 shifts, WS-6.
**Owns:** `services/fatigue.py`, `services/compliance_expiry.py`, `services/duress.py` + `api/v1/duress.py` (escalation advance only), `services/live_ops.py` (retention only), `services/jobs.py` (expiry only), `tests/test_fatigue_alerts.py`, `tests/test_compliance_expiry.py`, `tests/test_duress.py`.

Tasks: fatigue + compliance checks on `POST /v1/fleet/positions` and shift reads; duress cascade advances on any read of the event or the open list (the dashboard polls every 5 s, so this is effectively a timer); global position-history prune on any position write, with `POSITION_HISTORY_RETENTION_HOURS` promoted to a tenant setting with a documented default — **OWNER decision** on the number; job-offer expiry on the jobs list read; open shifts closed on driver delete. Plus: serialise audit-chain appends per tenant (advisory lock / `SELECT … FOR UPDATE` on the latest row) so concurrent writes cannot fork the chain.

#### B7 · Ops hardening — `wave2/b7-ops`
**Audit:** backend §6, WS-7.
**Owns:** `main.py`, new `core/logging.py`, `core/errors.py`, `entrypoint.sh`, `docker-compose.yml` (backend service only), `tests/test_health_and_auth_smoke.py`.

Tasks: `/health` = DB `SELECT 1` + alembic head == code head + Redis ping, `/health/live` trivial; exception handlers that keep CORS headers on 500 and return a JSON envelope; structured JSON logging with request-id middleware; pin `--workers 1` in `entrypoint.sh` with the comment, and file the Redis pub/sub swap as a follow-up with the seams listed.

#### B8 · Test parity — `wave2/b8-test-parity`
**Audit:** backend §6 alembic, §7, WS-8.
**Owns:** `tests/conftest.py`, new `tests/test_migrations.py`, `tests/test_tenant_isolation.py`, CI workflow (backend job).

Tasks: session fixture runs `alembic upgrade head` on a temp SQLite file; assert single head; assert `Base.metadata` == migrated schema (compare via `alembic.autogenerate.compare_metadata` — must be empty); optional Postgres job in CI (`services: postgres`) gated on `TEST_DATABASE_URL`; property test over every `TenantScopedMixin` subclass.

#### D4 · Design system — `wave2/d4-design-system`
**Audit:** dashboard §5, WS-D.
**Owns:** `components/ui/**`, `components/layout/**`, `index.css`, `tailwind.config.js`, and the mechanical call-site swaps in every page for `Tabs`/`Pagination`/`Checkbox` (list them in the PR).

Tasks: `Tabs`, `Pagination`, `Checkbox`, `Spinner`, `Skeleton`, `Toast`, `EmptyState`, `ErrorBanner`, `Tooltip`; migrate the 8 tab bars and 11 pagination blocks; fix dark mode (a real toggle writing `data-theme`, `--brand-lavender` and semantic colours defined for dark, delete the dead `dark:` utilities); focus trap + restore in `Modal`; keyboard sort/rows in `Table`; grouped, collapsible, responsive sidebar; convert the 4 raw tables. Tests for each new primitive.

#### D5 · Shared utilities + surgery — `wave2/d5-shared-utils`
**Audit:** dashboard §6, WS-E.
**Owns:** new `lib/format.ts`, `lib/pollIntervals.ts`, `lib/useResetOnChange.ts`, every `pages/*/format.ts` (delete after re-export), `hooks/useBilling.ts` + `useReports.ts` (format helpers only), `router.tsx` (lazy routes), `vite.config.ts` (`manualChunks`), `pages/platform/index.tsx` (split into sections), `pages/billing/index.tsx` (the `useMemo` bug).

---

### WAVE 3 — Global readiness (coordinated)

#### X1 · Jurisdiction seam — `wave3/x1-jurisdiction` — **one agent owns both halves**
**Audit:** backend §2 + WS-9; Android architecture §2.4.
**Owns (backend):** `services/fare_engine.py`, `services/tariffs.py`, `models/tenant.py`, `models/tariffs.py`, `schemas/tenants.py`, `schemas/tariffs.py`, new `services/regions/{__init__,nsw}.py`, one migration, `tests/test_fare_engine_golden.py` (assertions unchanged), new `tests/test_regions.py`.
**Owns (Android):** `domain/fare/FareEngine.kt` (**structure only — no numeric change**), `domain/FareEngine.kt`, `domain/NswPublicHolidays.kt` → `domain/fare/HolidayCalendar.kt`, `domain/location/RegionResolver.kt`, `domain/TripModels.kt` (`TollPresets`), new `domain/JurisdictionConfig.kt`, `sync/TariffCache.kt` (cache the jurisdiction beside the tariff), `data/remote/dto/TariffsDtos.kt`, tests.

Tasks:
1. Backend: `Tenant.timezone` (default `Australia/Sydney`), `Tenant.currency` (`AUD`), `Tenant.jurisdiction` (`NSW`), `Tenant.gst_divisor` (`11`) — migration with defaults so nothing changes. `Tariff` gains `night_start_hour`, `night_end_hour`, `peak_weekdays`, `speed_threshold_kmh` if absent.
2. `FareRegion` protocol: `tz`, `holidays(year)`, `night_window`, `peak_rule`, `rounding`, `gst_divisor`, `maxi_rule`, `region_vocabulary`. `NSWRegion` reproduces today's behaviour exactly. `resolve_time_class_and_peak(dt, region)` — `NSW_FARE_ZONE` module global removed. `GET /v1/tariffs/active` response includes the resolved jurisdiction block so the tablet does not need a second call.
3. Rename `/v1/reports/nsw-ptp-export` → `/v1/reports/regulator-export` with `NSW` as the formatter; keep the old path as a 301 for one release.
4. Toll registry gains `jurisdiction`; queries filter by the tenant's.
5. Android: `JurisdictionConfig` parsed from the tariff response and cached; every literal in architecture §2.4 becomes a field read. `HolidayCalendar` sourced from the server (`GET /v1/tariffs/active` carries the current + next year's dates) with the 2026 NSW set as the offline fallback. `RegionResolver` reads its centre/radius from config.
6. **Golden vectors on both sides must be byte-identical before and after.** Add one new golden set for a synthetic second region (e.g. `VIC-TEST`: `Australia/Melbourne`, night 21:00–05:00, no peak, GST /11) to prove the seam works.

**Acceptance:** both golden suites unchanged and green; `test_regions.py` covers the synthetic region; `FareTimeClassZoneTest` extended for a non-Sydney zone. **OWNER G3**.

#### X2 · Tenant self-serve onboarding — `wave3/x2-tenant-onboarding`
**Audit:** backend §2 tenant onboarding, WS-10; dashboard platform page.
**Owns:** `api/v1/platform.py`, `services/platform.py`, `schemas/platform.py`, `api/v1/tenants.py` (`admin` access), `tests/test_platform.py`; `dashboard/src/pages/platform/**` (post-D5 split), `hooks/usePlatformConsole.ts`, `pages/getting-started/**`, `pages/tariffs/index.tsx` (write gating: tenant owner may create their own tariffs from a preset; platform owner still owns the Fares Order reference).

Tasks: `POST /v1/platform/tenants` creates tenant + owner + default tariff (from the jurisdiction's preset) in one transaction and returns a one-time invite link; the platform page's form validates `plan` as a `<Select>`, ABN/TSP as patterns; Getting Started gains "add a tablet" and "publish a tariff" steps and completes the compliance item honestly; `GET/PATCH /v1/tenants/me` accepts `admin`.

#### D6 · Dashboard i18n + tenancy — `wave3/d6-i18n-tenancy` — **depends on X1's `Tenant` fields**
**Audit:** dashboard §4, WS-F.
**Owns:** `lib/format.ts` (currency/locale/timezone from the tenant), new `lib/i18n/**`, `lib/auth.tsx` (tenant record in context), every page's user-facing strings (mechanical extraction), `pages/psl/**` and `pages/tariffs/NswTollRoadsPanel.tsx` + `compliance/NswPtpExportCard.tsx` (behind a `jurisdiction` capability check), `pages/login/index.tsx` (tenant branding), `duress/DeviceFormModal.tsx` (E.164).

---

### WAVE 4 — Feature completion (parallel, independent)

| ID | Branch | Owns | Summary (see dashboard audit §9 P2 / backend WS-11) |
|---|---|---|---|
| D8 | `wave4/d8-dispatch` | `pages/dispatch/**` | Mapbox geocoder + map picker for pickup/drop-off; fare estimate computed from the active tariff via `/v1/tariffs/suggest`. |
| D9 | `wave4/d9-duress-desk` | `pages/duress/**` | UUIDs → rego/name; `<Select>` inputs; audible + desktop notification on a new open event; list moves to WS. |
| D10 | `wave4/d10-security-settings` | `pages/settings/security/**`, `pages/login/**`, backend `api/v1/auth.py` (password reset + recovery codes) | QR for `otpauth://`; password change; recovery codes; password reset by email (SendGrid mock-fallback pattern); session list + "sign out everywhere" (uses B2's revocation). |
| D11 | `wave4/d11-reporting` | `pages/audit-log/**`, `pages/trips/**` (export), `pages/billing/**` (PDF) | CSV export; field-level diff; `<Select>` filters; invoice PDF. |
| D12 | `wave4/d12-server-pagination` | `hooks/usePSLCentre.ts`, `hooks/useTrips.ts`, `hooks/useBilling.ts`, `pages/driver-engagement/RatingsPage.tsx`, `pages/fleet/api/*`; backend `api/v1/{trips,psl_ledger,ratings,billing}.py` (query params + totals) | Real server-side paging and date ranges; a `GET /v1/ratings/summary` aggregate. |
| D13 | `wave4/d13-engagement` | `pages/driver-engagement/**` | Fleet-wide wallet list; ledger paging; incentive progress; announcement preview. |
| B11 | `wave4/b11-dead-surfaces` | `api/v1/payments.py`, `api/v1/audit_log.py`, `shared/**` | Decide payments (**OWNER**: wire Close & Pay to it when a real Stripe Terminal integration is scheduled; until then mark deprecated); delete `POST /v1/audit-log`; regenerate `openapi.json` + `API_SUMMARY.md`; `DASHBOARD_REDESIGN_2026.md` → `ANDROID_HOME_REDESIGN_2026.md` and a new `docs/DASHBOARD_WEB_OVERVIEW.md`. |
| A8 | `wave4/a8-android-a11y` | `ui/**` (contentDescription / semantics / targets only) | Every icon labelled; every target ≥ 48dp; TalkBack walk of login → dashboard → meter → close & pay. |
| A9 | `wave4/a9-toolchain` | `android/build.gradle.kts`, `app/build.gradle.kts`, `gradle/**` | Kotlin 2.0 + Compose compiler plugin, Compose BOM current, AGP 8.7+, Room via KSP, `androidTest` for Room migrations 8→9→10→11→12 (real Room `MigrationTestHelper`). |

---

## 5. Owner decisions required (do not let an agent guess these)

| # | Decision | Where it blocks | Default if you say nothing |
|---|---|---|---|
| 1 | **Domain + TLS for the API.** Everything security-related assumes HTTPS. | B3, A2 (cleartext lockdown) | Agents leave the cleartext flag on; the risk stays. |
| 2 | **Position-history retention** (hours/days) — it is driver location data. | B6 | 72 h stays, documented as "technical default". |
| 3 | **Pre-shift inspection checklist content** — a regulated record. | A7 | Placeholders stay, marked as such. |
| 4 | **Payments direction**: real Stripe Terminal integration scheduled, or Close & Pay stays cash/link/CabCharge only? | A5, B11 | Mocks made honest; Tap-to-Pay hidden in release builds. |
| 5 | **Historical trips stored with the wrong `time_class`** (pre-timezone-fix). Backfill or leave? | none — data only | Left as-is; flagged in the Trips page. |
| 6 | **Second jurisdiction** to validate the seam against — which one, with what rules? | X1 test fixture | A synthetic `VIC-TEST` region is used. |
| 7 | **Delete the T99001 test vehicle and the three audit-locked test drivers?** They cannot be removed by the wipe. | none | They stay. |
| 8 | **Monitoring-partner duress panel** (external PII sharing) — previously blocked by policy; needs an explicit yes and a data-sharing agreement. | not in this plan | Not built. |

---

## 6. Owner verification script (the lead runs this on the real tablet + dashboard)

**G0 (after Phase 0):** install the CI-built debug APK; the app reaches the login screen; nothing else changed.

**G1 (after Wave 1):**
1. Start a fare on the band-sweep simulator. Press Back → dashboard shows METER glowing, fare still ticking. Press Home, wait 2 min, return → continuous. Swipe-kill from Recents, relaunch → "Fare resumed", total continuous.
2. Run the tunnel profile → distance readout dims, "GPS LOST", no distance accrues, waiting accrues only if the last speed was < 26.
3. Close the fare; compare the receipt total to `GET /v1/trips/{id}` `recomputed_total` — within 1%.
4. On the dashboard, disable the driver; on the tablet, airplane mode, relaunch → login refused.
5. Airplane mode → start shift → close 2 trips → reconnect → both appear on the dashboard with the right shift.
6. Log in as a `driver`-role user on the dashboard → no write buttons anywhere.

**G2 (after Wave 2):** factory-reset the tablet (Settings ▸ Diagnostics ▸ Factory reset, admin PIN) → commissioning checklist → pairing code → login → **look at the home screen**. Header ≤ 72dp; no empty Live Dispatch box; all four account tiles visible; rail labels correct; motion settles in under a second then stops. Trigger a duress from the tablet, do nothing → the dashboard escalates on its own.

**G3 (after Wave 3):** create a tenant in a non-NSW jurisdiction from the Platform console; log in with the invite; commission a tablet against it; run a trip; the night rate applies at that jurisdiction's hours; the dashboard shows that currency and timezone.

**G4:** the full §2 list.

---

## 7. What this plan deliberately does not do

- Rewrite the home screen to the Figma v2 "map-first + dock" paradigm. That is a different navigation model; the owner's ask was to fix *this* screen. Revisit after G2.
- Build the BLE duress-device client or the cabin-camera capture — no hardware is available.
- Add proximity-ranked dispatch — v1 broadcast is documented as deliberate.
- Move to Device Owner / Knox for true kiosk — that is a fleet-enrolment decision, not code.
- Kalman-filter the GPS — A1's jitter rules cover the field cases; a filter is a follow-up once A1's data shows the residual.
