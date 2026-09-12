# Android Meter — Where We Stand, GPS Blackout, Sensors, and the Optimisation Plan (2026-09-12)

**Who this is for:** a Sonnet 5 agent (or several, in parallel git worktrees) executing the Android workstreams below without a human in the loop except at the gates marked **OWNER**. Read §0 and §1 before touching anything. Every claim in §1 was verified today against the live code and a fresh build; every task in §3 carries the file it lives in.

**Companion documents (read the one for your workstream, not all of them):**

| Document | What it is |
|---|---|
| `docs/audits/2026-09-08-android-architecture-audit.md` | The original F/S/X/T finding IDs this plan reuses |
| `docs/audits/2026-09-11-full-stack-audit.md` §"Finding-by-finding status", §"New findings" (N1–N6), §"GPS-blackout / hardware survey" | What was fixed between 09-08 and 09-11 |
| `docs/plans/2026-09-11-gps-blackout-hardware-fallback-and-program-plan.md` | Part A (OBD-II hardware) is **superseded by the owner's 2026-09-12 decision — not being built**; Part B's remediation waves still apply |
| `docs/plans/2026-09-08-global-meter-program.md` §1 | Operating rules for agents — they still apply verbatim |
| `docs/plans/2026-09-12-nsw-compliance-and-market-readiness-master-plan.md` P1.4, P2.4, P3.4, P4.4 | The compliance framing (STOPPED state, blackout segment model, hardware decision) |

---

## 0. Baseline facts — read before you clone anything

**The code is on `phase0/merge-to-main`, checked out at `D:\cabdispatch-phase0`.** The checkout at `D:\cabdispatch` (branch `android/battery-network-heartbeat-and-map-fixes`) is **145 commits behind** the trunk and contains only two commits the trunk lacks (`9a3a50a`, `5c332f4` — toll data backup, pairing transport hardening, a `gradle.properties` tweak). Every fare-engine, foreground-service, GPS-blackout and Kotlin-2.0 change lives only on the trunk. **Base every workstream branch on `phase0/merge-to-main`.** Never `git checkout` inside `D:\cabdispatch` (it has stranded the owner's session before).

Measured today, both checkouts, `./gradlew :app:testDebugUnitTest --offline` and `:app:lintDebug --offline`:

| | `D:\cabdispatch` (stale branch) | `D:\cabdispatch-phase0` (trunk) |
|---|---|---|
| Kotlin / AGP / Compose | 1.9.24 / 8.5.2 / kapt | **2.0.21 / 8.7.3 / KSP** |
| Unit tests | 258, **1 failing** (`FareEngineTest.testAF_negativeAddOnsAreDefensivelyClampedToZero`, BigDecimal scale `0` vs `0.00`) | **436, 0 failing** |
| Kotlin compiler warnings | 0 | 0 |
| Android lint | not configured | **3 errors + 8 warnings unbaselined → `lintDebug` FAILS**; 48 more entries hidden by `app/lint-baseline.xml` (2 errors, 20 warnings, 19 of them `GradleDependency`) |
| Gradle heap | `-Xmx512m`, 1 worker — three `hs_err_pid*.log` JVM out-of-memory crashes in `android/` | `-Xmx1280m`, 2 workers |
| Foreground service, monotonic tick, GPS staleness, haversine distance, jitter floor | **none** | **all present** |

**Hygiene counts on the trunk (`app/src/main/java`, 217 files):** 7 `!!`, 37 `TODO/FIXME/TEMPORARY`, 7 catch-all `Exception/Throwable`, 4 `@Suppress`, 15 `LaunchedEffect(Unit)`, 17 `lateinit`, 0 `GlobalScope`, 0 `runBlocking`, 0 `Thread.sleep`. Largest files: `CloseAndPayScreen.kt` 1,617, `SettingsScreen.kt` 1,404, `Hud.kt` 1,389, `domain/FareEngine.kt` 1,297, `MeterBackdropMap.kt` 1,083, `AppContainer.kt` 960.

**Machine constraint:** the build box has 16 GB RAM with Android Studio resident. Keep `org.gradle.workers.max=2`, never run two Gradle daemons at once, run every Gradle task with `--offline` unless a dependency actually changed.

---

## 1. Where we stand — the audit

### 1.1 The fare engine and GPS blackout: what is genuinely fixed

All of the 2026-09-08 audit's fare-accuracy blockers are closed on the trunk, and the fixes are the right shape. Verified in `domain/FareEngine.kt` (`FareEngineImpl.tick`, ~lines 916–1030) and `domain/location/RealLocationProvider.kt`:

| ID | Fix | Where |
|---|---|---|
| F1 | Tick bills the **measured** monotonic delta (`nanoTimeSource`), clamped to `MAX_TICK_SECONDS = 5.0`; the remainder of a longer gap is dropped, never carried | `FareEngine.kt:1236-1256` constants |
| F2 | Distance is `GeoMath.distanceKm(prevFix, fix)` when a new fix arrived, `speed × dt` only otherwise; both capped at `speed × dt × 1.5` | tick, "F2" block |
| F3 | `LocationFix.receivedAtNanos`; a fix older than `MAX_FIX_AGE_MS = 5_000` ⇒ `gpsLost = true`: **no distance accrues**, waiting accrues **only if the vehicle was already below threshold when the sky closed**; `FareState.gpsLost` is published and rendered as a pill on the dial (A4 shipped) | tick, "F3" block; `ui/screens/hired/MeterDial.kt` |
| F4 | `MeterForegroundService` (`foregroundServiceType="location"`, `START_STICKY`, live-fare notification) + process-scoped `MeterController`; `restoreOpenTripIfAny()` rebuilds an OPEN Room trip after process death via `reconstructFareState` | `domain/MeterForegroundService.kt:214-290`, manifest lines 65-66, 118-122 |
| F6 | `MAX_ACCEPTABLE_ACCURACY_M = 50f`, `STATIONARY_SPEED_MS = 1.4f` clamp | `RealLocationProvider.kt:294-306` |
| F8 | `FareState.runningTotal` is `calcEngine.close().grandTotal` every tick — dial equals bill, maxi included | tick, "F8" block |
| Known-corridor catch-up | On reacquisition after a blackout that began **while moving**, `knownCorridorDistanceKm` walks the toll registry's own gantry chain between entry and exit fix (250 m portal radius) and bills that real road distance once through the ordinary distance path. No match ⇒ nothing billed. Owner's 2026-09-09 ruling. | `domain/fare/KnownCorridor.kt`, `KnownCorridorTest.kt` |
| Server mirror | Backend recompute applies the same corridor rule (`b2e3aa5`) and persists `Trip.gps_blackout_events` as an audit trail shown on the dashboard Fare tab (`2531719`) | `backend/app/services/tolls.py`, `trips.py`; `dashboard/.../FareTab.tsx` |
| STOPPED | `TripStatus.STOPPED` exists on Android; `MeterController.pause()/resume()` cancels the tick job; Hired screen renders the paused state | `FareEngine.kt:729-741`, `HiredViewModel.kt:254-255` |

**So the direct answer to "is GPS blackout fixed?": the money-losing bug (a tunnel billing at the frozen last speed, or billing waiting time at 80 km/h) is fixed and unit-tested (`MeterAccuracyTest`, `KnownCorridorTest`, `LocationFilteringTest`). The conservative rule is now: unknown motion ⇒ bill nothing; mapped tunnel ⇒ bill the real road; stationary at loss ⇒ bill waiting.** What is *not* done is everything that makes that rule provable in the field and complete on the wire — §1.2.

### 1.2 GPS blackout: what is still open

| # | Gap | Evidence | Consequence |
|---|---|---|---|
| G1 | **The GPS simulator cannot produce a blackout.** `GpsSimulator`/`SimulatedRoute` have speed profiles but no "emit no fixes for N seconds" segment. | `domain/location/SimulatedRoute.kt` — only a route *through* the Eastern Distributor at line 394, fixes never stop | The tunnel behaviour has never been exercised end-to-end on the tablet; **OWNER G1** from the 09-08 plan ("run the simulator's tunnel profile") is impossible today |
| G2 | **`RealLocationProvider` still freezes** `speedKmh`/`locationFix` on the last accepted fix; staleness is decided only by the fare engine. `GpsQuality` has no `STALE` tier, so the dashboard/Settings GPS dot reads GOOD inside a tunnel. | `RealLocationProvider.kt:177-193`; `domain/GpsQuality.kt` | `LivePositionHeartbeat`, `DuressController`, `TollDetector`, `MeterNavViewModel` all consume a frozen fix as if live — the live map shows the cab parked at the tunnel mouth, duress relays a stale position |
| G3 | **Android never tells the server a blackout happened.** `TripSyncItemDto` carries no blackout segments; the backend infers gaps from `gps_trace` timestamps. | `data/remote/*` and `data/local/entity/*` contain no "blackout" symbol | Device and server can disagree on *where* a gap started (device declares at 5 s of age; server sees only trace timestamps); the corridor decision is made twice from different inputs |
| G4 | **STOPPED does not exist on the wire or the server.** A break parked in an underground car park is "hired, GPS lost, stationary" ⇒ bills waiting time. | master plan P1.4; 09-11 plan "Do this alongside" #2 | Wrong money in exactly the blackout scenario; multi-hire pauses likewise |
| G5 | **The GPS simulator admin-PIN gate is off.** `SIMULATOR_REQUIRES_ADMIN_PIN = false`. | `ui/screens/settings/SettingsViewModel.kt:131` | Any driver can fabricate a fare with fake GPS; the mirror-image of the blackout risk |
| G6 | **Restore-after-process-death mid-blackout is untested.** `restoreOpenTripIfAny` rebuilds accrued figures but `blackoutEntryFix`/`blackoutEntryWasMoving` are in-memory only. | `FareEngine.kt:479-491`, `:541-542`, `:663-664` | Kill the app inside a tunnel ⇒ corridor catch-up is lost for that trip (under-bill, not over-bill — safe direction, but a gap) |
| G7 | **No inertial speed tier.** Nothing in the app reads `SensorManager`; blackouts outside a mapped toll corridor, and speed changes *inside* one, bill nothing. | full-stack audit "Hardware/sensor sweep — zero real hits" | Unmapped structures (car parks, canyons, the Eastern Distributor's non-toll sections) and a queue inside a tunnel are wrong money |
| G8 | **The `gpsTraceJson` blob is rewritten in full every tick** (decode → append → re-encode the whole trip trace on every persisted tick). | `data/repository/TripRepository.kt:240-242` | O(n²) work over a long trip; a 2-hour hire at 1 Hz rewrites a ~7,000-point JSON string 7,000 times — measurable jank and battery on the tablet, and a Room write amplification problem |

### 1.3 The gyroscope / sensor question — owner's decision (2026-09-12)

**Decision: the soft meter keeps billing through a GPS blackout from a speed estimated on the tablet's own inertial sensors, road-constrained, the same way Google Maps and Mapbox Navigation keep a car moving through a tunnel. No OBD-II, no external hardware, now or later.** This supersedes the 09-11 plan's Part A (OBD-II Tier 2) and the "sensor estimation rejected" note in `KnownCorridor.kt`'s doc; W2 updates that comment.

What the tablet actually has (read off the SM-T575 today with `dumpsys sensorservice`): a TDK **ICM-42605** 6-axis IMU (accelerometer + gyroscope), an AK09916C magnetometer, and Samsung's fused **rotation vector**, **game rotation vector**, **gravity** and **linear acceleration** virtual sensors. That is the same sensor class phones use for tunnel dead reckoning.

How Google/Mapbox do it, and what we copy:

1. **They never integrate raw acceleration into free-space position.** A phone IMU integrated twice drifts by hundreds of metres in a minute. What works is *road-constrained* dead reckoning: the vehicle is on a known road, so heading comes from the road geometry (the gyro only decides *which way at a fork*), and only **one** scalar has to be estimated — the vehicle's speed along the road.
2. **Speed is propagated, not measured.** Start from the last good GPS speed at the moment fixes stop; update it with the forward component of linear acceleration (the tablet is rigid in its mount, so the vehicle's forward axis is a fixed direction in tablet frame, learned while GPS is good); reset it to zero whenever the IMU says the vehicle is stationary (zero-velocity update — the drift killer); bound it to what a car can physically do.
3. **Reconcile at reacquisition.** When GPS returns, the true road distance between the entry and exit fixes is known (corridor chain, route polyline, or at minimum the straight-line chord). The estimate is corrected to it, and the correction is recorded. The estimate is what the passenger watches during the tunnel; the reconciled figure is what the receipt bills.

That is the design in **W2** (estimator, calibration, billing integration) and **W3** (road-geometry constraint sources). The `SpeedSource` seam (`domain/FareEngine.kt:101`, implemented by `RealLocationProvider` and `SwitchableSpeedSource`) is where the inertial source plugs in; `FareEngineImpl.tick`'s existing `gpsLost` branch is where it bills.

Two honest limits, so the plan is built around them rather than surprised by them:

- **Speed error grows with time between stops.** An accelerometer bias of 0.05 m/s² uncorrected for 60 s is an 11 km/h speed error. Zero-velocity updates at every queue stop, the forward-axis calibration, and the speed bounds keep it inside a few km/h on real tunnel runs; the reconciliation at exit caps the billed figure at the road distance regardless. W2 ships in **shadow mode first** (estimating while GPS is live and logging the residual) so the field evidence exists before the estimate bills — **OWNER G3** flips billing on from that evidence.
- **The tablet must be in its mount.** A hand-held tablet has no stable forward axis; calibration quality is measured continuously and, when it is not good enough, the meter falls back to today's rule (corridor if matched, otherwise nothing) and says so on the segment.

On "the Google location library": the app already uses Google's Fused Location Provider (`play-services-location:21.3.0`, `Priority.PRIORITY_HIGH_ACCURACY`, 1 Hz, `RealLocationProvider.kt:158`). The fused provider does **not** dead-reckon through tunnels on Android — it stops delivering fixes, which is what F3 detects. Google Maps' tunnel motion is done inside the Maps app, not exposed as an API. Mapbox's equivalent is the Navigation SDK's dead-reckoning (a library, not hardware); W2 task 1 time-boxes a comparison but the recommendation is our own estimator, because billing needs an auditable speed with a recorded confidence, not a black-box position.

### 1.4 Everything else still open on Android (from the 09-11 audit, re-verified today)

| ID | Item | Where | Severity |
|---|---|---|---|
| N1 | Simulator PIN gate off (= G5) | `SettingsViewModel.kt:131` | major |
| N2 | Dead `PlotZoneScreen.kt` (327 lines) + `ZoneStatisticsScreen.kt` (227) | `ui/screens/zones/` | major (dead code) |
| N3 | Offline synthetic `ShiftDto.tenantId = ""` | `domain/ShiftRepository.kt` | minor |
| N5 | `LivePositionHeartbeat` flat 5 s, no screen-off/parked backoff — 17,280 requests/day/tablet | `LivePositionHeartbeat.kt:272` | major (battery + network) |
| N6 | `RATE_PASSENGER` `popUpTo(IDLE)` not `popUpTo(0)` | `ui/navigation/CabDispatchNavHost.kt:202-204` | minor |
| T5 | Per-road toll audit trail (`autoTolledRoadsJson`) never synced | `TripEntity.kt:190-192` | minor, needs product decision |
| — | `TariffSignatureVerifier` / `TariffCanonicalPayload` / `TariffCache` have **zero tests**; `TariffSignatureVerifier.kt:121` TODO unresolved | `security/` | blocker-adjacent |
| — | `RoomMigrationTest` is JVM-only; no instrumented migration test | `test/.../RoomMigrationTest.kt` | major |
| — | Lint: `ProduceStateDoesNotAssignValue` (`AvailableTripsFormat.kt:49`), `StateFlowValueCalledInComposition` ×2 (`CloseAndPayScreen.kt:1424`, `ShiftStartScreen.kt:59`), plus 2 errors and 20 warnings parked in `lint-baseline.xml` | see §0 | **build-breaking** |
| — | No baseline profile, no `profileinstaller`, no StrictMode in debug, no LeakCanary; release variant never built through R8 on a field tablet | `app/build.gradle.kts` | perf/quality |
| — | `MainActivity.kt` forces `LocalDensity` globally to a 1280×800 canvas | architecture audit §6.4 | minor |
| — | Pre-shift inspection checklist still placeholder items | `LoginVehicleBindViewModel.kt` | compliance (master plan P4.4) — **OWNER** content |
| X5 | Field tablets still run debug builds over cleartext to the production IP; TLS is a backend/deploy item | `res/xml/network_security_config.xml` | major, outside this plan except the client flag |

---

## 2. Definition of done for this plan

The plan is complete when all of the following are true on `phase0/merge-to-main`:

1. `./gradlew :app:testDebugUnitTest :app:lintDebug --offline` is green with **`lint-baseline.xml` deleted** and `warningsAsErrors = true`, and Kotlin `allWarningsAsErrors = true`.
2. The simulator has a **tunnel profile** and the meter, run on it on the test tablet, shows GPS LOST, accrues no distance during the gap, bills the corridor once on exit, and matches the server recompute within the 1 % flag threshold — **OWNER G1**.
3. Killing the app from Recents mid-tunnel and relaunching resumes the fare *and* the pending corridor catch-up.
4. A blackout that overlaps a STOPPED interval bills nothing for the stopped minutes, on device and on the server, and the dashboard shows both segments.
5. The live map, Settings and the dashboard status dot say **STALE** inside a tunnel instead of GOOD.
6. `LivePositionHeartbeat` traffic on a parked, screen-off tablet drops by ≥ 80 % with no change while hired or under duress.
7. A 2-hour simulated hire shows no per-tick jank ≥ 16 ms attributable to trace persistence (Perfetto trace attached to the PR).
8. Through a real Sydney tunnel run with the tablet in its mount, the meter keeps moving on the inertial estimate, the receipt bills the reconciled road distance, and the segment (`resolution: INERTIAL`, estimate, reference, correction, confidence) is on the device and the dashboard. Shadow-mode evidence from ≥ 3 drives shows a median |estimated − GPS| speed residual ≤ 3 km/h — **OWNER G3** flips billing on.
9. Zero `!!` in `main/`, zero catch-all `Exception` without a logged reason, zero `TODO` older than this plan without a linked task.
10. CI (GitHub Actions) runs unit + lint on every PR to the trunk.

---

## 3. Workstreams

Branch naming: `wave6/<id>-<slug>` off `phase0/merge-to-main`. One agent per workstream, one worktree per agent. File ownership is exclusive — if you need a file another workstream owns, **stop and report**, do not edit it. Each task list is ordered; do them in order. Every workstream ends with the checklist in §4.

Dependency graph:

```
W0 (serial, first) ──┬── W1 ──┬── W2 inertial estimator (needs W1's segment model + simulator profile)
                     │        └── W3 road-constraint sources (feeds W2; can start in parallel with W2)
                     ├── W4 (independent of W1; touches heartbeat + trace storage)
                     ├── W5 (independent; Compose/perf)
                     ├── W6 (independent; tests/CI)  ── W7 (cleanup, after W5/W6 to avoid churn)
                     └── W8 (release, last)
```

### W0 · Green baseline — `wave6/w0-green-baseline` (serial, ~half a day, do first)

**Owns:** `app/build.gradle.kts` (lint/kotlinOptions blocks only), `app/lint-baseline.xml`, `gradle.properties`, the three lint-error files below, `.github/workflows/android.yml` (new), `android/README.md` build section.

1. Fix the three unbaselined lint errors, properly, not by suppression:
   - `ui/wheel/content/AvailableTripsFormat.kt:49` — `produceState` must assign `value` inside the producer; restructure the countdown as `produceState(initialValue = …) { while (isActive) { value = secondsUntil(expiresAtIso); delay(1_000) } }`.
   - `ui/screens/closepay/CloseAndPayScreen.kt:1424` and `ui/screens/shiftstart/ShiftStartScreen.kt:59` — replace `stateFlow.value` in composition with `collectAsStateWithLifecycle()` (add `androidx.lifecycle:lifecycle-runtime-compose:2.8.4`).
2. Open `lint-baseline.xml`, fix every entry, delete the file. The 19 `GradleDependency` entries: bump only to versions that compile with Kotlin 2.0.21/AGP 8.7.3 and Compose BOM 2024.09+; `Lifecycle` ×6 are the Mapbox MapView lifecycle calls — remove the manual `onStart/onStop` calls as the lint text instructs; `HardwareIds` ×2 — confirm `Settings.Secure.ANDROID_ID` is what pairing intends and suppress **with a justification comment**, that is the one permitted suppression; `KaptUsageInsteadOfKsp` — find the remaining kapt reference and delete it; `UnusedResources` — delete `dummy_driver_photo.xml` and the other resource; `ObsoleteSdkInt` ×3 in `MeterForegroundService.kt:112,157,184` — remove the guards (minSdk is 29).
3. `lint { abortOnError = true; warningsAsErrors = true; checkDependencies = true }` and `kotlinOptions { allWarningsAsErrors = true }`. Fix what that surfaces.
4. `gradle.properties`: keep `-Xmx1280m`, add `-XX:MaxMetaspaceSize=512m`, `org.gradle.caching=true`, `kotlin.incremental=true`. Delete the `hs_err_pid*.log` / `replay_pid*.log` files from `android/` and add both patterns to `.gitignore`.
5. Fix the stale-branch test failure so the two branches can be reconciled: `FareEngineTest.kt:831` expects `BigDecimal.ZERO` but the clamp returns `0.00`; the **test** is wrong (money is scale-2 by convention across the engine) — assert with `compareTo == 0` or `BigDecimal("0.00")`. This test does not fail on the trunk; apply the same assertion style there so the two never diverge again.
6. Add `.github/workflows/android.yml`: JDK 17, Gradle cache, `./gradlew :app:testDebugUnitTest :app:lintDebug`, upload `build/reports` on failure. Runs on PRs to `phase0/merge-to-main` and `main`.

**Acceptance:** both Gradle tasks green with no baseline file; CI green on the PR; README "Build" section says exactly which two commands gate a merge.

### W1 · GPS blackout — make it provable and complete — `wave6/w1-blackout-complete`

**Owns:** `domain/FareEngine.kt` (blackout/STOPPED sections of `FareEngineImpl`, `LocationFix`), `domain/GpsQuality.kt`, `domain/location/RealLocationProvider.kt`, `domain/location/GpsSimulator.kt`, `domain/location/SimulatedRoute.kt`, `domain/MeterForegroundService.kt` (`MeterController` restore only), `domain/TripModels.kt` (blackout/STOPPED fields only), `data/local/entity/TripEntity.kt` + `TripDao.kt` (+ migration to the next version — check `AppDatabase.kt` for the current number first), `data/repository/TripRepository.kt` (segment persistence only), `data/remote/dto/TripsDtos.kt` (sync fields only), `ui/screens/settings/SettingsViewModel.kt:131` (the one constant), `ui/screens/settings/GpsSimulatorPanel.kt`, tests. **Backend counterpart is B-W1 (below); agree field names before you start.**
**Do not touch:** `domain/fare/FareEngine.kt` (pure engine — golden vectors), `domain/fare/KnownCorridor.kt` (finished), any Hired composable except the simulator panel.

Tasks:

1. **Simulator tunnel profile (G1).** Add a `Blackout(seconds)` segment kind to `SpeedProfile`/`SimulatedRoute` during which `GpsSimulator` emits **no fixes at all** (not zero-speed fixes — the whole point is fix *absence*). Add two canned routes to `GpsSimulatorPanel`: "Cross City Tunnel" (real portal coordinates from the cached toll registry, 60 km/h → 95 s blackout → 60 km/h) and "Car park" (30 km/h → stop → 120 s blackout while stationary → drive off). Unit test in `SimulatedRouteTest`: no fix has a timestamp inside the blackout window.
2. **Staleness at the source (G2).** `RealLocationProvider` gains a `staleAfterMs = MAX_FIX_AGE_MS` watchdog: when no fix has been accepted for that long it sets `speedKmh = 0.0` and publishes a new `SpeedSource.fixState: StateFlow<FixState>` where `FixState = Live | Stale(sinceNanos) | None`. The fare engine keeps its own `receivedAtNanos` check (belt and braces — both must agree in a test). `GpsQuality` gains `STALE`; `GpsQualityClassifier.classify` takes `fixAgeMs` and returns `STALE` above the threshold; `isOk(STALE) = false`. Consumers that read `locationFix.value` directly — `LivePositionHeartbeat`, `DuressController`, `MeterNavViewModel`, `HiredViewModel.nextTracePoint`, `TollDetector` via `detectTolls` — must skip or mark a stale fix: heartbeat sends `gps_stale: true` with the last position; the trace point is **not** recorded (the server infers the gap from the timestamps, and that must stay true); toll detection is skipped. Test: `LocationFilteringTest` gets "fixes stop → speed reads 0 within 5 s → quality STALE".
3. **Blackout segments as first-class records (G3).** New Room table `TripBlackoutSegmentEntity(clientUuid, tripClientUuid, startedAtIso, endedAtIso?, entryLat, entryLng, exitLat?, exitLng?, entryWasMoving, resolution: NONE|CORRIDOR|INERTIAL|UNCALIBRATED|STOPPED, billedDistanceKm, corridorRoadId?)`. `FareEngineImpl` emits a `BlackoutEvent.Started/Ended(resolution, km)` on a `SharedFlow`; `MeterController` persists it through `TripRepository`. `TripSyncItemDto` gains `gps_blackout_segments: List<…>` mirroring the entity. Test in `TripRepositoryOfflineTest`: open → blackout → close → the outbox row carries one segment with `resolution = CORRIDOR`.
4. **Restore mid-blackout (G6).** On `restoreOpenTripIfAny`, if the newest segment has `endedAtIso == null`, re-seed `blackoutEntryFix`/`blackoutEntryWasMoving` from it so the corridor catch-up still fires on reacquisition. Test in `MeterAccuracyTest`: enter blackout → kill (new `FareEngineImpl` from Room) → reacquire at the far portal → corridor distance billed exactly once.
5. **STOPPED on the wire (G4).** `TripEntity` + tick DTO gain `stopped_s` and a `state: hired|stopped` on each telemetry point; `pause()` persists the transition; a blackout that begins or continues while STOPPED records `resolution = STOPPED` and bills nothing. Test: "stationary at portal, driver presses STOPPED, 120 s blackout, RESUME, drive off" ⇒ `waitingSeconds` unchanged during the stop. Agree `stopped_s` / `state` names with B-W1.
6. **Flip `SIMULATOR_REQUIRES_ADMIN_PIN = true` (G5/N1).** Update the constant's doc, the Settings row copy, and `docs/PROJECT_HANDOFF.md`. Debug builds keep the simulator reachable *behind the PIN*; the PIN screen already exists (`AdminPinGateScreen`).
7. Add `FareState.blackout: BlackoutStatus?` (`sinceSeconds`, `entryWasMoving`) so the dial pill can say "GPS LOST 0:47 · waiting only" vs "GPS LOST 0:47 · no charge". The UI change itself is W5's; you publish the state.

**Acceptance:** all new tests green; golden vectors unchanged; **OWNER G1** on the tablet with the two new simulator routes: (a) Cross City — distance readout dims, GPS LOST pill, nothing accrues for 95 s, on exit the total jumps by exactly the corridor's registry distance × the rate, the dashboard Fare tab shows one CORRIDOR segment; (b) Car park — waiting accrues while stopped, nothing after STOPPED is pressed, nothing on drive-off until fixes return.

### B-W1 · Backend counterpart — `wave6/b-w1-blackout-wire` (Python, runs in parallel with W1)

**Owns:** `backend/app/schemas/trips.py`, `models/trips.py` (+ one alembic migration), `services/trips.py` (`recompute_from_trace` blackout/stopped handling), `services/tolls.py` (corridor: prefer device-reported segment endpoints over inferred ones when present, verify they agree within 250 m), `dashboard/src/pages/trips/tabs/FareTab.tsx` (segment table gains `resolution` and STOPPED rows), tests.
Rules: `alembic heads` == 1; Postgres-backed tests; `ge=0` on every new money/seconds field; tenant-scope every query.

### W2 · Inertial dead-reckoning speed source (the soft meter through a blackout) — `wave6/w2-inertial-speed` (after W1 merges)

**Owns:** new `domain/location/inertial/` package (`ImuSampler.kt`, `VehicleFrameCalibrator.kt`, `InertialSpeedEstimator.kt`, `InertialSpeedSource.kt`, `ImuTraceRecorder.kt`, `BlackoutReconciler.kt`), `domain/FareEngine.kt` (only the `gpsLost` branch of `FareEngineImpl.tick` and the new reconcile hook), `domain/fare/FareEngine.kt` (**one additive method only**, `reconcileBlackoutDistance` — see task 6; golden vectors must not move), `domain/fare/KnownCorridor.kt` (doc comment only: the "sensor estimation rejected" paragraph is replaced by a pointer to this workstream), `data/AppContainer.kt` (one wiring block), `domain/DeviceReadiness.kt` (one advisory row), `ui/screens/settings/InertialDiagnosticsPanel.kt` (new) + one Settings row, tests. **No new runtime dependency** — `android.hardware.SensorManager` only. **No new permission** (motion sensors need none; do not add `HIGH_SAMPLING_RATE_SENSORS`, 50 Hz is under the 200 Hz cap).

Principle (§1.3): one scalar is estimated — speed along the road. Heading comes from W3's road geometry when available and from the gyro otherwise. Position is only ever advanced *along a road*.

Tasks, in order:

1. **Time-boxed comparison, 1 day, no code merged.** Read Mapbox Navigation SDK v3's dead-reckoning docs and confirm on this device how `FusedLocationProviderClient` behaves in a tunnel (record one Harbour Tunnel run with the existing app: fixes should stop rather than degrade). Write a half-page note in the PR: why an in-house estimator (auditable speed + confidence per tick, no per-MAU cost, no black box in the fare path) over the Navigation SDK. If the note concludes the opposite, **stop and report** before building.
2. **Sensor plumbing.** `ImuSampler` registers `TYPE_LINEAR_ACCELERATION`, `TYPE_GRAVITY`, `TYPE_GYROSCOPE`, `TYPE_ROTATION_VECTOR` at `SENSOR_DELAY_GAME` (~50 Hz) **only while a hiring is open** (start on `MeterController.openTrip`, stop on `closeTrip`) — zero sensor cost otherwise. Samples carry the sensor's own `event.timestamp` (monotonic ns), never wall clock. Processing runs on a dedicated `HandlerThread`, never the main thread; results publish as a `StateFlow` at 10 Hz.
3. **`VehicleFrameCalibrator`.** Learns, while GPS is live, the vehicle's forward unit vector in tablet frame: gravity gives "down"; the horizontal plane is orthogonal to it; during GPS-observed speed changes (|Δv/Δt| > 0.5 m/s² sustained ≥ 1 s) the horizontal linear-acceleration vector's principal direction, signed by whether GPS speed rose or fell, is the forward axis. Requires ≥ 8 such events with angular spread < 15° for `quality = GOOD`; persists the frame in `SecurePrefs` per device; invalidates it if the rotation vector shows the tablet's orientation relative to gravity has changed by > 10° for > 5 s (tablet taken out of the mount). Also estimates the gyro z-bias and accelerometer forward-axis bias during stationary windows (task 4). Unit tests on synthetic samples: a known frame is recovered within 3°; a rotated tablet invalidates.
4. **`InertialSpeedEstimator`.** State: `vEst` (m/s), `headingDeg`, `sigmaV` (error budget), `zuptCount`. Per sample: `vEst += (aForward − biasForward) × dt`; `heading += (gyroZ − biasZ) × dt` (used only when W3 provides no road path). Rules, all unit-tested with recorded traces:
   - **Zero-velocity update (ZUPT):** variance of |linear acceleration| over 1.5 s < 0.05 m/s² **and** |gyro| < 0.02 rad/s ⇒ `vEst = 0`, `sigmaV = 0`, re-estimate biases from the window. This is the drift killer; a taxi in a tunnel queue triggers it at every stop.
   - **Bounds:** `0 ≤ vEst ≤ min(vGpsAtLoss + 30 km/h, 110 km/h)`; `|aForward| ≤ 4 m/s²` (anything larger is mount vibration — clamp it).
   - **Error budget:** `sigmaV += 0.05 m/s² × dt` between ZUPTs; `confidence = HIGH` while `sigmaV < 1 m/s`, `MEDIUM` < 3 m/s, `LOW` above. `LOW` for more than 120 s ⇒ estimator declares `UNRELIABLE` and the engine falls back to the W1 rule for the rest of that blackout.
   - **Seed at blackout start:** `vEst = last accepted GPS speed`, `heading = last GPS bearing`.
   - **Shadow mode while GPS is live:** the estimator always runs during a hiring, re-seeded from each accepted fix, and logs the residual `vEst − vGps` per second into a ring buffer. This is the evidence for G3 and the input to bias estimation; it costs nothing extra.
5. **`InertialSpeedSource : SpeedSource`.** Publishes `speedKmh = vEst × 3.6` and a synthetic `LocationFix` positioned by W3's road constraint (or, without one, advanced along `heading` from the entry fix — display only, never itself billed as distance) with its own `receivedAtNanos` and `fixState = Estimated(confidence)`. `SwitchableSpeedSource`-style composition in `AppContainer`: GPS when live; inertial when `gpsLost` and calibration `GOOD` and confidence ≥ `MEDIUM`; otherwise nothing.
6. **Billing integration.** In `FareEngineImpl.tick`, inside `if (gpsLost)`: when the inertial source is usable, `billedSpeedKmh = vEst`, accrual mode by the tariff threshold exactly as for GPS (`vEst < 26` ⇒ waiting, else distance at `vEst × dt`), through the same `calcEngine.tick` call — one billing path. The open blackout segment records `resolution = INERTIAL`, accumulates `estimatedKm`, and stores `confidence` and `zuptCount`. The frozen `wasStationaryWhenLost` rule remains only for the fallback (`UNRELIABLE`/uncalibrated). `FareState.blackout` carries `estimatedSpeedKmh` and `confidence` for the dial.
   **Reconcile at reacquisition** (`BlackoutReconciler`): reference distance = W3's road-path distance between the entry and exit fixes if any source matched, else the straight-line chord. `billed = referenceKm` when a road path matched (the road is the truth); otherwise `billed = clamp(estimatedKm, chordKm, chordKm × 1.5)`. The delta `billed − estimated` is applied once through `CalcFareEngine.reconcileBlackoutDistance(cs, deltaKm)` — a new **additive** method on the pure engine that adjusts `cumulativeDistanceKm` and `accruedDistanceCharge` by the signed delta at the band rate in force, may be negative, and is covered by new vectors that leave every existing golden vector untouched. The segment records `referenceKm`, `correctionKm`, `referenceSource`. The known-corridor catch-up is **not** additionally applied to an INERTIAL segment (one resolution per segment).
   Tests in `MeterAccuracyTest` with recorded IMU traces: (a) 60 km/h in → 95 s → 60 km/h out, corridor matched ⇒ billed = corridor km, estimate within 10 %; (b) same with a 40 s queue stop mid-tunnel ⇒ ZUPT fires, waiting accrues for the stop, distance for the rest, billed = corridor km; (c) car park, no road path ⇒ billed clamped to chord bounds; (d) uncalibrated ⇒ identical to W1 behaviour; (e) tablet removed from mount mid-blackout ⇒ estimator invalidates, falls back, segment says `UNCALIBRATED`.
7. **`ImuTraceRecorder` + replay harness.** Debug-only Diagnostics button records synchronised IMU samples + GPS fixes to a file on the tablet for a drive; a `test/resources/imu/` fixture set (≥ 3 real Sydney drives, at least one Harbour Tunnel and one Cross City, recorded by the **OWNER** with the existing app) is replayed by the tests above, `TripTraceReplayFidelityTest`-style. Until the owner supplies real traces, synthetic traces generated from the simulator's speed profile plus modelled noise/bias are used, clearly labelled synthetic.
8. **Diagnostics + readiness.** Settings ▸ Diagnostics ▸ "Motion sensors": calibration quality, forward-axis angle, live `vEst` vs GPS speed, residual median/p95 for this shift, ZUPT count, and a "Record IMU trace" button (debug). Readiness row "Motion sensors calibrated" — advisory, since calibration completes during the first drive.
9. **Flags.** `BuildConfig.INERTIAL_BILLING_ENABLED` default **false** (shadow mode only) until **OWNER G3**; `INERTIAL_SHADOW_ENABLED` default true. Flipping the billing flag is a one-line PR that cites the residual evidence from task 8.
10. **Wire.** Segment fields go to the server via W1's `gps_blackout_segments`; B-W1 treats an INERTIAL segment as device-authoritative when `chord ≤ billed ≤ 1.5 × chord` (and `≤ corridor km × 1.1` when a corridor matched) and flags it for review otherwise. The dashboard Fare tab shows estimate, reference, correction and confidence.

**Acceptance:** all tests above green; shadow-mode residuals on the tablet over ≥ 3 real drives with ≥ 5 stops each: median |residual| ≤ 3 km/h, p95 ≤ 8 km/h, every stop produces a ZUPT within 2 s; **OWNER G3** then enables billing and drives one real tunnel: the dial keeps moving, the receipt equals the corridor distance × rate, the dashboard shows the INERTIAL segment with its correction. Golden vectors unchanged.

### W3 · Road-geometry constraint sources — `wave6/w3-road-constraint` (parallel with W2; W2 consumes its interface)

**Owns:** new `domain/location/roadpath/` package (`RoadPathSource.kt` interface, `CorridorRoadPath.kt`, `NavRouteRoadPath.kt`, `StyleRoadPath.kt`, `CompositeRoadPath.kt`), `domain/fare/KnownCorridor.kt` (extract the greedy gantry-chain walk into a reusable `gantryChainPath(...)`; behaviour byte-identical, `KnownCorridorTest` unchanged), `ui/screens/hired/MeterNavViewModel.kt` (expose the active route's `points`), `ui/screens/hired/MeterBackdropMap.kt` (one `querySourceFeatures` helper), tests.

The estimator needs, at blackout entry, a polyline the vehicle is on, and at exit, the road distance between entry and exit fixes. Three sources, tried in this order by `CompositeRoadPath`:

1. **Active navigation route.** When the driver is navigating to a drop-off, `MapboxDirections` already returned the full-fidelity `geometries=polyline` route (`MapboxDirections.kt:43-107`). Project the entry fix onto it (must be within 40 m), advance along it by W2's `∫vEst dt`, and at exit measure the along-route distance to the projected exit fix. Highest quality: it is the actual road ahead.
2. **Toll-registry corridor chain.** The existing `knownCorridorDistanceKm` walk, refactored so the chain itself (not just its length) is available as a polyline for live positioning during the gap. Same 250 m portal radius, same shortest-candidate rule.
3. **Offline map road geometry.** The meter's Mapbox style (`BACKDROP_MAP_STYLE_URI`, `MeterBackdropMap.kt:693`) renders roads from vector tiles the offline-region download already caches. At blackout entry, `querySourceFeatures` on the style's road source within a 300 m box of the entry fix returns road `LineString`s; pick the one whose bearing at the nearest vertex matches the last GPS bearing within 20°, follow connected segments (matching `name`/`class`) up to 12 km, and hand that polyline to W2. This covers tunnels and structures that are neither tolled nor on a nav route. Spike first (1 day): confirm the custom style exposes a road source-layer with tunnel geometry offline; if it does not, add the standard `mapbox-streets-v8` `road` layer to the style (owner action in Mapbox Studio — **stop and report** with the exact change) rather than downloading extra data.
4. **`RoadPathSource` contract:** `fun pathAt(entryFix): RoadPath?`; `RoadPath.advance(metres): LatLng`; `RoadPath.distanceTo(exitFix): Double?` (null if the exit fix is not within 60 m of the path — then the source is treated as unmatched and W2 falls back to the chord bounds). Every source is pure over already-cached data: **no network call in this path, ever.**

**Acceptance:** unit tests per source with fixture polylines (Cross City from the registry chain; a Directions fixture; a synthetic style feature set); `CompositeRoadPath` precedence tests; `KnownCorridorTest` byte-identical; W2's tests pass against the real sources.

### W4 · Battery, network and storage efficiency — `wave6/w4-battery-network`

**Owns:** `domain/LivePositionHeartbeat.kt`, `domain/DeviceCommandHeartbeat.kt`, `domain/DeviceTelemetry.kt`, `domain/location/RealLocationProvider.kt` (request priority/interval only — coordinate with W1, which owns the staleness watchdog; land after W1), `data/repository/TripRepository.kt` + `TripDao.kt` + a new `TripTracePointEntity`/`TripTracePointDao` (+ migration), `data/remote/RealtimeSocket.kt` and its consumers' reconnect loops, `sync/SyncWorker.kt` constraints, tests.

1. **Adaptive heartbeat (N5, master plan P4.4).** `LivePositionHeartbeat` interval becomes a function of state: hired or duress ⇒ 5 s; on shift, moving (speed > 5 km/h) ⇒ 10 s; on shift, stationary ⇒ 30 s; screen off and stationary ⇒ 120 s; **immediately** on a material change (shift start, trip open/close, duress, ≥ 100 m moved since last publish). Duress keeps its own 5 s loop untouched (`DuressController`). Read screen state via `PowerManager.isInteractive` + a `SCREEN_ON/OFF` receiver. Test with a fake clock: the four cadences and the immediate-publish triggers.
2. **Location request priority.** Not on shift ⇒ stop requesting location entirely (today the 1 Hz high-accuracy request runs for the process lifetime once permission is granted). On shift, not hired ⇒ `PRIORITY_BALANCED_POWER_ACCURACY` at 5 s. Hired or duress ⇒ `PRIORITY_HIGH_ACCURACY` at 1 s (unchanged). Drive it from `SessionHolder.session` + `MeterController` state.
3. **Trace storage (G8).** Replace the `gpsTraceJson` rewrite with an append-only `trip_trace_points` table (indexed by `tripClientUuid, seq`), written in batches every 5 s or 10 points, whichever first. `gpsTraceJson` is materialised once at close for the sync payload (keep the DTO shape), then the points are deleted after a confirmed sync. Migration copies existing blobs into rows. Test: 7,200 ticks persist in O(n) writes; `observeTrace` still works.
4. **Device heartbeat.** `DeviceCommandHeartbeat` 60 s stays, but coalesce `battery`/`network` reads (already shared) and skip the POST when nothing changed for 5 minutes and the screen is off; the server treats absence < 10 min as online (confirm with backend; if not, keep 60 s and document).
5. **Socket.** One reconnect policy: exponential backoff 1 s → 60 s with jitter, reset on success, paused while `ConnectivityManager` reports no network, resumed on `onAvailable`. Today there are 32 `reconnect` sites — collapse them onto one helper in `data/remote/`.
6. **WorkManager.** `SyncWorker` periodic: `CONNECTED` + `BATTERY_NOT_LOW`; one-time on reconnect stays unconstrained.
7. Add a debug-only `BatteryStatsPanel` under Settings ▸ Diagnostics: location requests/min, heartbeats/min, Room writes/min, socket reconnects — the numbers the acceptance test reads.

**Acceptance:** Battery Historian or `dumpsys batterystats` before/after on the tablet, parked and screen-off for 30 min: location wakeups and network requests both ≥ 80 % lower. No change to hired-state cadence. **OWNER G4**.

### W5 · Compose performance and UI correctness — `wave6/w5-compose-perf`

**Owns:** everything under `ui/` (except `ui/screens/settings/GpsSimulatorPanel.kt`, `InertialDiagnosticsPanel.kt` and the one `SettingsViewModel` constant), `MainActivity.kt`, `app/build.gradle.kts` (compose compiler options, `profileinstaller`, `baselineprofile` module), new `:baselineprofile` module.

1. **Blackout UI (from W1's state).** Dial pill: "GPS LOST m:ss · waiting only" / "· no charge" / "· estimated" (W2, with the confidence tier) — persistent, calm (no animation while parked, rule 9 of the program plan). Distance readout dims. On restore: "FARE RESUMED" transient. Status strip and Settings GPS dot render `GpsQuality.STALE` as amber "STALE".
2. **Recomposition hygiene.** Every ViewModel `StateFlow` read via `collectAsStateWithLifecycle`. Audit the 15 `LaunchedEffect(Unit)` sites — each must either key on the real dependency or carry a comment saying why `Unit` is correct. Add a Compose **stability configuration file** (`compose_compiler_config.conf`) listing `java.math.BigDecimal`, `java.time.*`, and the domain models that are immutable but cross module boundaries; enable `-P plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination` in a `composeMetrics` Gradle property and attach the report to the PR — target: zero unstable parameters on `MeterDial`, `HiredScreen`, `DeckHomeScreen` composables that recompose per tick.
3. **Per-tick work.** The meter recomposes at 1 Hz for the whole hiring. Profile with Layout Inspector recomposition counts: only the dial figures, the pill and the map puck may recompose per tick; the breakdown card, controls drawer and navigator pane must not. Use `derivedStateOf` for formatted strings; hoist `remember`ed formatters.
4. **Density override.** Remove the global `LocalDensity` override in `MainActivity.kt:119-121`; replace with a `WindowSizeClass`-driven layout that treats 1280×800 as the design canvas without lying to the system about density (font scale and system bars must behave).
5. **Startup and jank.** Add `androidx.profileinstaller` and a `:baselineprofile` module generating a profile for cold start → login → hired. Enable R8 full mode (`android.enableR8.fullMode=true`) and `isShrinkResources = true` on release; build a release APK with the placeholder-URL guard satisfied via `local.properties` and **install it on the tablet** — the release variant has never run in the field.
6. **Debug-only guards.** `StrictMode` (disk/network on main thread ⇒ log, not crash) in `CabDispatchApp` debug builds; LeakCanary `debugImplementation`. Fix what they find.
7. **Accessibility.** `contentDescription` on every icon on the meter and home screens; text floor 12 sp; `Role.Button` on in-dial buttons (A4 items 5–6 from the 09-08 plan, still open).

**Acceptance:** compose metrics report attached; Perfetto trace of a 10-minute simulated hire shows no frame > 16 ms from the meter's own composables; release APK runs on the tablet; TalkBack can operate the meter; previews for RUNNING / STOPPED / GPS LOST (three variants) / MAXI at 1280×800. **OWNER G2** visual check.

### W6 · Tests and CI depth — `wave6/w6-tests-ci`

**Owns:** `app/src/test/**`, `app/src/androidTest/**`, `security/` (tests + the one TODO), `.github/workflows/android.yml` (extend W0's), `app/build.gradle.kts` (test dependencies only).

1. **Tariff signing tests (blocker-adjacent).** `TariffCanonicalPayloadTest`: byte-for-byte canonical layout against a fixture generated by the backend's `scripts/` signer (commit the fixture and the command that produced it). `TariffSignatureVerifierTest`: valid, tampered-field, wrong-key, expired-key, key-not-yet-cached. `TariffCacheTest`: refresh, signature-failure keeps the last good tariff, offline-empty-cache fallback. Resolve `TariffSignatureVerifier.kt:121` or convert it to a tracked issue with the reason.
2. **Instrumented Room migration test** (`androidTest`) from every shipped schema version to current, run on API 29 and 34 emulators in CI (`reactivecircus/android-emulator-runner`). Keep the JVM `RoomMigrationTest` for speed.
3. **Clock injection.** Every `Instant.now()`/`LocalDate.now()` in fare-commencement, time-class and holiday logic takes an injected `Clock`; pin the tests to explicit Sydney instants including the DST boundaries (master plan P0.2).
4. **Tunnel golden vectors.** Add the three blackout scenarios (moving/corridor, stationary/waiting, STOPPED/nothing) as fixed-input vectors in `MeterAccuracyTest` with the expected cents, and mirror them in `backend/tests/test_trips.py` so device and server are asserted equal on the same inputs.
5. **Screenshot tests** for the meter states (Paparazzi or Roborazzi; pick one, justify in the PR) — W5 provides the previews.
6. CI: unit + lint + instrumented migration; cache Gradle; fail on any `TODO(` without a `#ticket` suffix (a one-line grep step).

**Acceptance:** coverage report attached; `security/` has ≥ 90 % line coverage; CI green.

### W7 · Cleanup and debt burn-down — `wave6/w7-cleanup` (after W5 and W6 merge)

**Owns:** whatever the tasks name; nothing under `domain/FareEngine.kt` or `domain/fare/`.

1. Delete `ui/screens/zones/PlotZoneScreen.kt` and `ZoneStatisticsScreen.kt` (N2) and their ViewModels if unreferenced; `RATE_PASSENGER` → `popUpTo(0)` (N6); `tenantId = ""` in `OutboxBackedShiftRepository` → carry the paired tenant id from `DevicePairingStore` (N3).
2. Remove the 7 `!!` (each becomes an early return with a logged reason or a typed error); narrow the 7 catch-all `Exception` sites to the exceptions actually thrown, or log the cause at `Log.w` with a tag — never a silent swallow.
3. Triage the 37 `TODO/FIXME/TEMPORARY`: fix, convert to a tracked issue in the trailer, or delete. Zero remain without a `#ticket`.
4. `lateinit` (17): each either becomes a constructor parameter or `by lazy`; any that must stay gets a comment naming the initialiser.
5. Split `CloseAndPayScreen.kt` (1,617) into `PaymentMethodSheet.kt`, `ReceiptOptions.kt`, `FareSummaryCard.kt`; `SettingsScreen.kt` (1,404) into one file per sub-screen; `AppContainer.kt` (960) into per-domain wiring files (`AppContainer` stays the single service locator — no Hilt).
6. Collapse the three connectivity checks (`ConnectivitySyncTrigger`, `SettingsViewModel.pollNetwork`, `OfflineSyncViewModel.pollNetwork`) onto one `NetworkStatus` flow; collapse the remaining haversines onto `GeoMath` (keep `TollDetector.tollHaversineM` byte-identical — it must match the backend).
7. T5/N4: make the product decision explicit — sync `auto_tolled_roads` to the server as evidence (recommended: yes, it is dispute evidence). If yes, add the DTO field (coordinate with B-W1); if no, replace the comment in `TripEntity.kt:190-192` with the decision and date.

**Acceptance:** hygiene counts from §0 all at zero except documented `@Suppress`; no file over 1,000 lines outside `domain/FareEngine.kt` and `Hud.kt`.

### W8 · Release readiness — `wave6/w8-release` (last)

**Owns:** `app/build.gradle.kts` (version/signing), `res/xml/network_security_config.xml`, `docs/PROJECT_HANDOFF.md`, `android/HANDOFF.md`, `android/README.md`.

1. `versionCode 12`, `versionName 0.7.0`; changelog in `HANDOFF.md` listing W0–W7 by finding ID.
2. Release signing config from `local.properties` keys (`RELEASE_STORE_FILE`, …), never committed; document the owner-only step.
3. `network_security_config.xml`: cleartext only for `10.0.2.2`/`localhost`, plus the one `ALLOW_CLEARTEXT_HOST` build flag the owner sets until TLS is live (X5). Release build must fail if the flag is non-empty.
4. Root/unlock check with an explicit, assessed device profile allowlist (master plan P2.4) — advisory in debug, blocking in release.
5. Screenshot suppression (`FLAG_SECURE`) on Close & Pay, Profile and the duress overlays.
6. OWASP MASVS-L1 self-check table in `HANDOFF.md` with a file/line per control.

**Acceptance:** a signed release APK from a clean clone with documented `local.properties` keys installs and reaches a hired fare on the tablet — **OWNER G5**.

---

## 4. Every workstream ends with

1. `./gradlew :app:testDebugUnitTest :app:lintDebug --offline` green in your worktree.
2. Golden vectors unchanged (`FareEngineTest`, `backend/tests/test_fare_engine_golden.py`). If a value moved, you changed the fare — stop and report.
3. Rebase onto current `phase0/merge-to-main`; resolve nothing that belongs to another workstream — report it.
4. PR description: **what you verified and how** (commands, device, trace files). Unverified claims are not accepted.
5. Update `android/HANDOFF.md` in the same PR for every gap you closed; cite the finding ID.
6. Commit trailer: `Co-Authored-By: <your model name> <noreply@anthropic.com>`.
7. Never run `adb`, install an APK, touch the production server, mint credentials, or deploy. Those are the **OWNER** gates: G1 (W1 tunnel run), G2 (W5 visual), G3 (W2 shadow-mode evidence, then the tunnel drive with billing on), G4 (W4 battery), G5 (W8 release).

## 5. Explicitly not in this plan

- Kalman-filtering GPS — collect W2's shadow-mode residual data first; revisit after one field week.
- **OBD-II, Bluetooth adapters, wired VSS, external taximeters — any hardware at all.** Owner's decision 2026-09-12: the meter is a soft meter and stays one.
- Free-space (unconstrained) inertial position integration — the estimator only ever advances along a road and only ever bills a speed; see W2.
- Device Owner / Knox kiosk — fleet-enrolment decision, not code.
- Map-matching / snap-to-road for the fare **while GPS is live** — W3's road paths are used only inside a blackout; snapping a live GPS fare to the wrong road is worse than the current haversine. Revisit after W2's field data.
- Master plan P3.4's hardware decision trial — closed by the owner's decision; P3.4 should be rewritten to a software-accuracy evidence programme (W2 task 8 residuals, tunnel route set, pilot variance review).

## 6. Owner decisions needed (answer in the PR thread or `HANDOFF.md`)

1. G3 thresholds for enabling inertial billing (proposed: median residual ≤ 3 km/h, p95 ≤ 8 km/h over ≥ 3 drives) — confirm or change, and who records the IMU traces (W2 task 7 needs real drives).
2. T5: sync per-road toll evidence to the server? (recommended yes)
3. Pre-shift inspection checklist content — the nine items and which are blocking (compliance, not engineering).
4. Heartbeat cadence table in W4 §1 — confirm the four numbers or supply the fleet's own.
5. When is TLS live on the backend, so X5's cleartext flag can be removed?
