# Android Taxi‑Meter App — Architecture & Correctness Audit (2026-09-08)

**Scope:** `android/app/src/main/java/au/com/threesixty/cabdispatch` — 174 Kotlin files, 48,899 LOC (main) + 5,813 LOC (test, 31 files).

All paths below are relative to that root unless absolute. Severity: **blocker** = wrong money / lost data / unusable; **major** = real defect with workaround; **minor** = hygiene.

The five most alarming findings (F1, F3, X1, S3, §5 mocks) were independently re-verified against the source by the reviewing session before this document was committed.

---

## 1. ARCHITECTURE

### 1.1 Layering

| Layer | Files | Notes |
|---|---|---|
| `data/local` | `AppDatabase.kt` (v10, migrations 8→9, 9→10), 6 DAOs, 8 entities | Room. No `fallbackToDestructiveMigration` (correct — financial data). |
| `data/remote` | `ApiService.kt` (**2,067 lines**), 5 Mapbox REST gateways, `RealtimeSocket.kt` | Retrofit + kotlinx.serialization. Mapbox is REST-only (no SDK secret token). |
| `data/repository` | `TripRepository.kt` only | Every other "repository" lives in `domain/`. |
| `domain` | 45 files, mixes pure policy (`DeviceReadiness`, `KioskLockController`, `NswPublicHolidays`) with Android-dependent stores and repositories | Inconsistent: `RemoteBacked*Repository` classes are in `domain/`, not `data/`. |
| `domain/fare` | `FareEngine.kt` (pure), `TollDetector.kt`, `TariffMapper.kt`, `TripFareReconstruction.kt` | Plain-JVM, unit-testable. The good part of the codebase. |
| `sync` | `OutboxDrainer`, `SyncWorker`, `ConnectivitySyncTrigger`, `TariffCache`, `TariffSigningKeyCache`, `TollRegistryCache` | Clean ports/adapters seam in `OutboxDrainer.Ports`. |
| `security` | `TariffCanonicalPayload.kt`, `TariffSignatureVerifier.kt` | Real Ed25519. |
| `hardware` | `payments/`, `printing/`, `receipt/` | **100% mocks** — see §5. |
| `ui` | 90 files | Compose. Contains several dead screens — see §1.4. |

### 1.2 Dependency injection — `data/AppContainer.kt` (661 lines)

Manual service-locator `object`, deliberately not Hilt (`AppContainer.kt:82-108`). `init(context)` called once from `CabDispatchApp.onCreate` (`CabDispatchApp.kt:26`). `lateinit var` for eager fields, `by lazy` for the rest.

**Issues:**

- **`AppContainer.kt:109` — major.** Global mutable `object` singleton referenced directly from ~40 ViewModels *and* from Compose screens. Nothing is injectable, so no ViewModel is unit-testable without an Android `Application`. Only `OutboxDrainer` and the pure fare/policy classes have a seam. *Fix: at minimum, pass `AppContainer` fields into ViewModel factories rather than reading the object statically inside ViewModels.*
- **`AppContainer.kt:143-163` — major.** `accessToken`/`refreshToken` are public `var` on a global object, write-through to `TokenStore` (SharedPreferences, plaintext, no `EncryptedSharedPreferences`). Any code can overwrite the session token. *Fix: make setters internal to an auth component; move `TokenStore` to `EncryptedSharedPreferences`.*
- **`AppContainer.kt:379` — minor.** `plainOkHttpClient = OkHttpClient()` is a second, independent connection pool/thread pool created eagerly for one endpoint. *Fix: `okHttpClient.newBuilder().authenticator(NONE).build()` to share the pool.*
- **`AppContainer.kt:361-370` — major.** `tokenAuthenticator` calls `performBlockingTokenRefresh` inside `synchronized(this)` where `this` is the `AppContainer` object itself. Any other code that ever synchronizes on `AppContainer` will deadlock against a network round-trip. *Fix: use a private `val refreshLock = Any()`.*

### 1.3 Process-lifetime singletons and loops

| Thing | Where started | Interval | Scope |
|---|---|---|---|
| `LivePositionHeartbeat` | `AppContainer.kt:301` | **5 s** (`HEARTBEAT_INTERVAL_MS = 5_000L`, `LivePositionHeartbeat.kt:272`), rebind 60 s | own `SupervisorJob` |
| `DeviceCommandHeartbeat` | `AppContainer.kt:310` | 60 s (`POLL_INTERVAL_MS`, `DeviceCommandHeartbeat.kt` companion) | own `SupervisorJob` |
| `ConnectivitySyncTrigger` | `AppContainer.kt:279` | callback-driven, never unregistered | n/a |
| `SyncWorker` periodic | `AppContainer.kt:276` | 15 min, `NetworkType.CONNECTED` | WorkManager |
| `DuressController` | lazy, `AppContainer.kt:588` | trigger-driven | own `SupervisorJob` |
| `RealLocationProvider` permission poll | lazy via `speedSource`, `AppContainer.kt:521` | 3 s poll + 1 Hz fixes | own `SupervisorJob` |
| `GpsSimulator` | lazy, `AppContainer.kt:539` | — | own `SupervisorJob` |
| `startupScope` (tariff key + toll registry warm-up) | `AppContainer.kt:287,294` | one-shot | own `SupervisorJob` |

- **`AppContainer.kt:301,310` + `LivePositionHeartbeat.kt:272` — major.** Six independent process-lifetime `SupervisorJob` scopes with no shutdown path, plus a 5 s HTTPS heartbeat while on shift. `LivePositionHeartbeat.kt:58-64` documents that 30 s (the blueprint figure) was lowered to 5 s to match a dispatcher poll — that is 17,280 requests/day/tablet. *Fix: keep the 5 s cadence only while the screen is on and a shift is open; back off to 30 s when the screen is off.*
- **`AppContainer.kt:539` — noted, intentional.** `GpsSimulator` is constructed unconditionally, **not** behind `BuildConfig.DEBUG`, because the field tablet runs a debug build against production. Guardrail is `TripEntity.simulated` (`TripRepository.kt:154`). Reasonable, but the Settings entry point is reachable by any driver — **major**. *Fix: put the simulator entry point behind `AdminPinGateScreen`, which already exists (`ui/screens/adminpin/AdminPinGateScreen.kt`) and is only wired to factory reset.*

### 1.4 Navigation graph — `ui/navigation/CabDispatchNavHost.kt` (396 lines)

**Complete route list (21 registered `composable` destinations):**

| # | Constant | Route string | Composable | Line |
|---|---|---|---|---|
| 1 | `SPLASH` | `splash` | `SplashScreen` | 170 |
| 2 | `LOGIN_VEHICLE_BIND` | `login_vehicle_bind` | `LoginVehicleBindScreen` | 173 |
| 3 | `IDLE` | `idle` | **`DeckHomeScreen`** | 176 |
| 4 | `HIRED` | `hired` | **`DeckHomeScreen(startOnMeter = true)`** | 184 |
| 5 | `CLOSE_PAY` | `close_pay` | `CloseAndPayScreen` | 195 |
| 6 | `RATE_PASSENGER` | `rate_passenger` | `RatePassengerScreen` | 212 |
| 7 | `SHIFT_REPORT` | `shift_report` | `ShiftReportScreen` | 221 |
| 8 | `SETTINGS` | `settings` | `SettingsScreen` | 231 |
| 9 | `MESSAGES_THREAD` | `messages_thread` | `MessageThreadScreen` | 245 |
| 10 | `TRIP_DETAIL` | `trip_detail` | `TripDetailScreen` | 248 |
| 11 | `SHIFT_SUBMITTED` | `shift_submitted` | `ShiftSubmittedScreen` | 251 |
| 12 | `AVAILABLE_TRIP_OFFER` | `available_trip_offer` | `AvailableTripOfferScreen` | 254 |
| 13 | `SHIFT_START` | `shift_start` | `ShiftStartScreen` | 257 |
| 14 | `PROFILE` | `profile` | `ProfileScreen` | 260 |
| 15 | `PLOT_ZONE` | `plot_zone` | `PlotZoneScreen` | 270 |
| 16 | `ZONE_STATISTICS` | `zone_statistics` | `ZoneStatisticsScreen` | 273 |
| 17 | `TERMS_DISCLAIMER` | `terms_disclaimer` | `TermsDisclaimerScreen` | 276 |
| 18 | `DEVICE_READINESS` | `device_readiness` | `DeviceReadinessScreen` | 285 |
| 19 | `PERMISSIONS_CHECKLIST` | `permissions_checklist?next={next}` | `PermissionsChecklistScreen` | 297 |
| 20 | `OFFLINE_SYNC` | `offline_sync` | `OfflineSyncScreen` | 314 |
| 21 | `LOG_OFF` | `log_off` | `LogOffScreen` | 317 |

**Actual boot flow, traced:**

```
SPLASH (900 ms dwell, SplashScreen.kt:59,142)
  └─ TermsAcceptance.isAccepted(VERSION_CODE)?  ── no ──► TERMS_DISCLAIMER ──onAccept──┐
  └─ ACCESS_FINE_LOCATION granted? ── no ──► PERMISSIONS_CHECKLIST?next=<dest>          │
                                                                                        │
  postAuthDestination()  (CabDispatchNavHost.kt:339) ◄──────────────────────────────────┘
      midShift = session?.shiftId != null                     (line 352)
      !midShift && !commissioningStore.isCommissioned() ──► DEVICE_READINESS   (line 359)
      !midShift && DeviceReadiness.blockingFailures(...) ──► DEVICE_READINESS  (line 363)
      session != null ──► IDLE   else ──► LOGIN_VEHICLE_BIND                   (line 370)

LOGIN_VEHICLE_BIND  (login → vehicle bind → pre-shift inspection → startShift)
  └─► SHIFT_START (LoginVehicleBindViewModel.kt:94, LoginVehicleBindScreen.kt:101)
        └─► IDLE (ShiftStartScreen.kt:91)

IDLE  = DeckHomeScreen, pane = DASHBOARD
  └─ Start Meter / Set Price ──► navigate(HIRED)   (DeckHomeScreen.kt:337, 374)
HIRED = DeckHomeScreen(startOnMeter=true), pane = METER → HiredScreen
  └─ CLOSE TRIP ──► CLOSE_PAY (HiredScreen.kt:356)
CLOSE_PAY ──onDone──► RATE_PASSENGER (popUpTo CLOSE_PAY inclusive)  (line 205)
RATE_PASSENGER ──► IDLE (popUpTo IDLE inclusive)                    (line 214)

IDLE ─ LOG OFF chip ──► LOG_OFF (DeckHomeScreen.kt:633)
LOG_OFF ──► SHIFT_REPORT (LogOffScreen.kt:46,123)
SHIFT_REPORT ──onDone──► LOGIN_VEHICLE_BIND (popUpTo(0))            (line 224)
```

**Dead / duplicate screens — confirmed by exhaustive grep for external references:**

| File | LOC | Status | Severity |
|---|---|---|---|
| `ui/screens/dashboard/DeckHomeScreen.kt` | 2,704 | **LIVE** — the only dashboard actually rendered (routes `IDLE` and `HIRED`) | — |
| `ui/screens/dashboard/DockScreenChromeV2.kt` | **664** | **DEAD** — zero references anywhere outside itself | major |
| `ui/screens/dashboard/WheelDashboardScreen.kt` | **1,264** | **DEAD** — referenced only from KDoc comments (incl. `CabDispatchNavHost.kt:180` which says so) | major |
| `ui/screens/dashboard/HomeDashboardV2.kt` | **641** | **DEAD (transitively)** — `HomeDashboardV2ChromeOverlay` is called only from `WheelDashboardScreen.kt:213` | major |
| `ui/screens/dashboard/WheelDashboardViewModel.kt` | 337 | **LIVE** — reused by `DeckHomeScreen.kt:285` | — |
| `ui/screens/hired/HiredScreen.kt` | 2,594 | **LIVE** — rendered inside `DeckHomeScreen`'s METER pane (`DeckHomeScreen.kt:581`) | — |

**~2,569 lines of dead dashboard code.** *Fix: delete `DockScreenChromeV2.kt`, `WheelDashboardScreen.kt`, `HomeDashboardV2.kt`. Note `WheelColorsV2.kt` and parts of `ui/wheel/*` become dead with them — verify before deleting.* (The UI audit extends this list to ~3,500 lines: `ui/wheel/Wheel{State,Geometry,Gesture}.kt`, `ui/deck/DeckChrome.kt`, `ui/theme/WheelColorsV2.kt`, `WheelColors` in `Theme.kt:48-81`.)

**Other navigation issues:**

- **`CabDispatchNavHost.kt:184-194` — major.** `IDLE` and `HIRED` render the *same* composable, so they are two separate `NavBackStackEntry`s. `HiredViewModel` is `viewModel()`-scoped to the entry (`HiredScreen.kt:250`). Pressing system Back on `HIRED` destroys the entry → `HiredViewModel.onCleared()` → `viewModelScope` cancelled → `FareEngineImpl`'s tick job dies **while a passenger is in the car**. The Room row stays `OPEN` and no further ticks are persisted. See §2.1.
- **`CabDispatchNavHost.kt:214-218` — minor.** `RATE_PASSENGER`'s `onDone` does `navigate(IDLE) { popUpTo(IDLE) { inclusive = true } }`. If `IDLE` is not on the back stack (e.g. the driver got to `HIRED` directly from `AVAILABLE_TRIP_OFFER`), `popUpTo` is a no-op and the stack grows. *Fix: `popUpTo(0)`.*
- **`CabDispatchNavHost.kt:363` — major.** `postAuthDestination()` reads `deviceCommandHeartbeat.state.value` at splash time, ~900 ms after `AppContainer.init`. `lastPollSucceeded` will still be `null` and `forceUpdatePending` comes from the persisted seed. `updateAvailable = false` is hardcoded (`CabDispatchNavHost.kt:391`), so `UpToDate` can never block from the splash path. Only the gate screen can. *Fix: document or remove `UpToDate` from `blockingFailures` when `updateAvailable` is unknown.*
- **`CabDispatchNavHost.kt:159-168` — minor.** Nav transitions use fully-qualified `androidx.compose.animation.*` inline instead of imports. Cosmetic.

---

## 2. FARE ACCURACY

### 2.1 The meter tick

`domain/FareEngine.kt` → `FareEngineImpl`:

```kotlin
// domain/FareEngine.kt:447-455
private fun startTicking() {
    tickJob?.cancel()
    tickJob = scope.launch {
        while (isActive && _state.value.status == TripStatus.HIRED) {
            delay(1000)
            if (_state.value.status == TripStatus.HIRED) tick()
        }
    }
}
```

```kotlin
// domain/FareEngine.kt:476-477
val dKm = BigDecimal.valueOf(speed / 3600.0) // one tick = one second
calcEngine.tick(cs, speedKmh = speed, distanceDeltaKm = dKm, elapsedSeconds = 1)
```

| ID | file:line | Sev | Issue | Fix |
|---|---|---|---|---|
| **F1** | `domain/FareEngine.kt:449-455` | **blocker** | `delay(1000)` guarantees *at least* 1000 ms. Under GC/CPU load, Doze, or a busy dispatcher, each tick can take 1.1–2 s of wall clock but always bills exactly 1 s of distance and `elapsedSeconds = 1` of waiting. The meter **systematically under-charges** on a loaded device, and the amount is unbounded. | Replace the fixed `1` with a measured monotonic delta: `val nowNs = System.nanoTime(); val dt = (nowNs - lastTickNs)/1e9; lastTickNs = nowNs` and pass `distanceDeltaKm = speed * dt / 3600`, `elapsedSeconds = dt`. |
| **F2** | `domain/FareEngine.kt:476` | **blocker** | Distance is **time-integrated instantaneous speed**, never GPS haversine between fixes. There is no map-matching and no odometer. Combined with F3 this is the single largest fare-accuracy risk. `GeoMath.distanceKm` exists (`domain/location/GeoMath.kt`) and is used elsewhere but not here. | Accrue `distanceDeltaKm` from `GeoMath.distanceKm(prevFix, curFix)` when a new accepted fix arrived this tick, and fall back to `speed × dt` only when no new fix arrived. Spec B6 calls for `haversine(prev, curr) map-matched`. |
| **F3** | `domain/location/RealLocationProvider.kt:177-193` | **blocker** | **No fix-staleness timeout.** `_speedKmh` is set only when a new fix is *accepted*, and reset to `0.0` only when the permission is lost (line 139). In a tunnel or GPS blackout, `speedKmh` freezes at the last value forever. Enter a tunnel at 80 km/h, lose GPS for 4 minutes → the meter bills 5.3 km of distance the vehicle may not have driven. Frozen at 0 → bills waiting time while at 100 km/h. | Add `MAX_FIX_AGE_MS` (~5 s). In `FareEngineImpl.tick`, if `now - locationFix.timestampMillis > MAX_FIX_AGE_MS`, hold the accrual (or fall back to `WAITING` explicitly and surface a "GPS lost" state on the dial), and expose it in `FareState`. |
| **F4** | `domain/FareEngine.kt:449-455` + `CabDispatchNavHost.kt:184` | **blocker** | The tick loop is a plain coroutine in `HiredViewModel.viewModelScope` — **there is no foreground service**. `DeviceReadiness.kt:84-91` explicitly documents this: "Doze can therefore stop a running fare … it is a trip that stops being charged for while the passenger is still in the car." Battery-optimisation exemption is only *advisory* (`DeviceReadiness.kt:324-332`). Back-navigation from `HIRED` kills it outright. The standing TODO at `domain/FareEngine.kt:133-140` names this. | Hoist `FareEngineImpl` to `AppContainer` behind a `ForegroundService` with `foregroundServiceType="location"`, started at `openTrip` and stopped at `closeTrip`. This is the largest single piece of work in this audit. |
| **F5** | `domain/FareEngine.kt:380-387` | minor | Waiting-mode `distanceDelta` is folded into `cumulativeDistanceKm` (correct, matches the backend) but is itself `speed/3600` — at sub-26 km/h that's ≤7 m/tick, so band-boundary drift is tiny. | — |
| **F6** | `domain/location/RealLocationProvider.kt:208-226` | major | GPS-jitter handling is only two checks: 180 km/h jump rejection and an accuracy-degradation drop. **No stationary-drift suppression.** A parked vehicle with poor sky view emits fixes that wander 5–20 m; `location.hasSpeed()` is usually true and reports a small non-zero speed, so the meter accrues phantom distance while stopped. There is no `accuracyM` floor at all. | Add: (a) reject fixes with `accuracyM > 50f`; (b) clamp `speedKmh` to 0 when `location.speed < ~1.4 m/s` (5 km/h) *and* `speedAccuracyMetersPerSecond` is poor; (c) implement the `kalman(fused_location)` the spec actually asks for. |
| **F7** | `domain/FareEngine.kt:474`, `TripModels.kt:93` | minor | The 26 km/h threshold has a hardcoded `?: 26.0` fallback in three places (`FareEngine.kt:358`, `474`, `TripModels.kt:93`). | Single `const val DEFAULT_SPEED_THRESHOLD_KMH`. |

### 2.2 Money math — `domain/fare/FareEngine.kt` (579 lines)

This file is **good**. `BigDecimal` throughout, ported line-for-line from `backend/app/services/fare_engine.py`, golden-vector tested (`test/.../fare/FareEngineTest.kt`, 815 lines).

- Rounding: `roundDownToCent` (line 79) for `fareTotal` per Act s76(5)/(6); `roundHalfUp` (line 68) for surcharge and GST. Line-item reconciliation at lines 531-550 makes the receipt lines sum exactly to the total.
- Maxi 150%: `Tariff.maxiMultiplier = 1.5` (line 138), applied **only** to `flagFall + peak + distance + waiting` (lines 460-464), never to tolls/PSL/extras. Eligibility is a *computed* property `FareState.maxiRateApplied` (line 290-291) that a UI layer cannot set.
- PSL: `pslAmount = 1.32` (line 140), unconditional at close (`CloseAndPayViewModel.kt:307`) and seeded into the live dial at `domain/FareEngine.kt:367`.
- Fixed fares: `AIRPORT_FIXED_FARE_STANDARD = 60.00` / `MAXI = 80.00` (lines 193-194).
- Negotiated ("Set Price"): bills exactly the agreed amount, absorbs card surcharge, adds only cleaning fee (lines 491-499).

**Remaining money issues:**

| ID | file:line | Sev | Issue | Fix |
|---|---|---|---|---|
| **F8** | `data/AppContainer.kt:440-473` | major | **Two `FareEngine` types, deliberately unconverged.** `domain.FareEngine`/`FareEngineImpl` (stateful, live display) and `domain.fare.FareEngine` (pure, billed). `FareEngineImpl.close()` (line 441-445) returns the **UI snapshot**, not `calcEngine.close()`. The billed total is recomputed independently at Close & Pay from `reconstructFareState(TripEntity)`. `FareState.total` (`TripModels.kt`) is a naive sum with no `roundDownToCent` and no maxi multiplier applied — so **a maxi trip's live dial under-reads the actual bill by 50% of the metered base**. | Make `FareEngineImpl.tick()` publish `calcEngine.close(cs, ...)`'s `grandTotal` as the display total instead of maintaining a parallel `FareBreakdown`. |
| **F9** | `domain/fare/TripFareReconstruction.kt:33-61` | major | Reconstruction re-derives the distance charge from `trip.distanceM` (an **Int, metres**; `HiredViewModel.kt:307` rounds to int on every persist). Sub-metre precision is discarded on every tick; `waitingS`/`movingS` are whole seconds. | Persist `accruedDistanceCharge`/`accruedWaitingCharge` as decimal strings on `TripEntity` and read those. |
| **F10** | `domain/fare/FareEngine.kt:429`, `499` | minor | For fixed/negotiated fares, `surcharge` is computed and returned non-zero while never being added. Footgun for any consumer doing `fareTotal + surcharge`. | Rename to `absorbedSurcharge` or add a `surchargeBilled: Boolean`. |
| **F11** | `ui/screens/closepay/CloseAndPayViewModel.kt:284-297` | major | If no cached tariff row exists for `trip.tariffId`, Close & Pay **hard-fails** with "cannot compute the closing fare". No fallback to `URBAN_TARIFF`/`COUNTRY_TARIFF` constants. | Fall back to the constants at `domain/fare/FareEngine.kt:165,179` with a loud on-screen "using default rates" notice rather than blocking payment. |
| **F12** | `data/local/dao/TripDao.kt:43,58` | major | Trip day-boundary aggregation uses `ZoneId.systemDefault()`, while fare classification uses `Australia/Sydney`. | Use `NSW_FARE_ZONE` here too, or the configurable operating zone (see §2.4). |

### 2.3 Tolls — `domain/fare/TollDetector.kt` (739 lines) + `sync/TollRegistryCache.kt`

Genuinely careful work. Double-counting is addressed in three independent places (per-road dedup; `distance`-model roads revise in place at `domain/FareEngine.kt:542-556`; alert de-dup at `569-571`; corroboration gate `TOLL_CONFIRM_RADIUS_M = 60.0`).

| ID | file:line | Sev | Issue | Fix |
|---|---|---|---|---|
| **T1** | `domain/FareEngine.kt:415-419` vs `522-585` | major | **Manual and auto tolls can double-count the same crossing.** `addToll(preset)` adds a `TollPresets` amount unconditionally; `detectTolls` adds an auto amount for the same road independently. No cross-check between `tollsApplied` (presets) and `autoTollsApplied`. | Map `TollPreset.id` → registry `roadId` and suppress the auto charge for any road already manually added (and vice versa). |
| **T2** | `data/AppContainer.kt:294` | major | `tollRegistryCache.refresh()` is called **exactly once**, fire-and-forget at process start. No reconnect trigger and no periodic refresh. | Add `tollRegistryCache.refresh()` to `ConnectivitySyncTrigger.onAvailable` and to the 15-min `SyncWorker`. |
| **T3** | `domain/FareEngine.kt:313, 384` | minor | `tollRegistry` loads async at `startTrip`; a real crossing in the first few seconds of a cold-cache trip is missed. Documented. | — |
| **T4** | `domain/fare/TollDetector.kt:208-220` | **hardcoded NSW** | `shbShtBand()` hardcodes the Sydney Harbour Bridge/Tunnel peak/off-peak/night windows. | See §2.4. |
| **T5** | `data/local/entity/TripEntity.kt` `autoTolledRoadsJson` | minor | The per-road auto-toll audit trail is persisted locally but **never synced**. A toll dispute cannot be adjudicated server-side. | Add `auto_tolled_roads` to `TripSyncItemDto`. |

### 2.4 Hardcoded NSW / Australia assumptions — **complete list**

| file:line | What is hardcoded |
|---|---|
| `domain/fare/FareEngine.kt:105` | `val NSW_FARE_ZONE: ZoneId = ZoneId.of("Australia/Sydney")` — the clock for **all** fare-time and toll-time classification. |
| `domain/fare/FareEngine.kt:107-111` | `TimeClass` enum: night = 10pm–6am; `HOLIDAY` is country-only. |
| `domain/fare/FareEngine.kt:113-116` | `AreaClass { URBAN, COUNTRY }` — the NSW two-region model. |
| `domain/fare/FareEngine.kt:124-147` | `Tariff` schema defaults: `distKmThreshold = 12`, `speedThresholdKmh = 26`, `maxiMultiplier = 1.5`, `multiHirePct = 0.75`, `pslAmount = 1.32`, `surchargePctCap = 5.0`, `cleaningFeeCap = 124.14`. |
| `domain/fare/FareEngine.kt:165-177` | `URBAN_TARIFF` — literal NSW rates. |
| `domain/fare/FareEngine.kt:179-191` | `COUNTRY_TARIFF` — literal NSW country rates. |
| `domain/fare/FareEngine.kt:193-204` | `AIRPORT_FIXED_FARE_STANDARD = 60.00` / `MAXI = 80.00`. |
| `domain/fare/FareEngine.kt:208-221` | `validateAgainstFaresOrder` — NSW regulatory-cap concept. |
| `domain/fare/FareEngine.kt:511` | `gstComponent = grandTotal / 11` — **Australian 10% GST hardcoded**. |
| `domain/FareEngine.kt:333` | `region.equals("urban") → URBAN else COUNTRY`. |
| `domain/FareEngine.kt:627, 631` | `ZonedDateTime.now(NSW_FARE_ZONE)` for time-class and peak. |
| `domain/FareEngine.kt:643-652` | `resolveTimeClassFor` — 10pm–6am night, Sunday+NSW-holiday rule. |
| `domain/FareEngine.kt:662-667` | `resolveIsPeakFor` — Fri/Sat/pre-NSW-holiday 10pm–6am peak. |
| `domain/NswPublicHolidays.kt:29-58` | **57 hardcoded `LocalDate` literals** — 2026 gazetted, 2027 *calculated, unverified* (`TODO(risk flag)` line 23). No data past 2027. |
| `domain/location/RegionResolver.kt:46-47, 50, 69, 83` | `REGION_URBAN/COUNTRY`, `URBAN_RADIUS_KM = 50.0` ("tuned by eye"), `OPERATING_FOOTPRINT_RADIUS_KM = 2000.0`, `SydneyCbdFallback`. |
| `domain/TripModels.kt:20-24` | `TollPresets`: `M5 $4.30`, `Harbour (southbound) $4.19`, `Airport $6.43`. |
| `domain/fare/TollDetector.kt:208-220, 260-262, 42-84` | SHB/SHT bands; `"WESTCONNEX"` `$12.74` cap; 150 m radius derived from NSW 110 km/h limit. |
| `data/remote/ApiService.kt:1093, 1134` | `val currency: String = "AUD"` |
| `ui/screens/readiness/DeviceReadinessViewModel.kt:239` | `tariffCache.getActiveTariff("urban")` — region hardcoded in the readiness check. |
| `ui/screens/splash/SplashScreen.kt:98, 103` | `"The Captain Taxis · NSW Taxi Meter"`, `"TSP-448041"`. |
| `domain/DeviceReadiness.kt:128` | Regulation text (Fares Order cl 2(d)) in the readiness policy. |
| `ui/screens/terms/TermsDisclaimerScreen.kt` | NSW regulation/disclaimer copy. |
| `ui/deck/DeckChrome.kt:80`, `CloseAndPayViewModel.kt:244`, `DeckHomeScreen.kt:1831,1837` | `Locale.ENGLISH` date formats; `h:mm a` 12-hour clock; no i18n. |
| `data/local/dao/TripDao.kt:43,58` and 8 UI formatters | `ZoneId.systemDefault()` — inconsistent with `NSW_FARE_ZONE`. |
| `AndroidManifest.xml`, `MainActivity.kt:76-77` | `sensorLandscape` and `DESIGN_W_DP=1280 / DESIGN_H_DP=800` fixed canvas. |

**Fix strategy:** introduce a `JurisdictionConfig` value type carrying `{ fareZone: ZoneId, currencyCode: String, gstDivisor: BigDecimal?, regions: List<String>, holidayCalendar: Set<LocalDate>, tariffDefaults: Tariff, tollRegistryId: String, regionResolver: (lat,lng)->String }`, supplied from the server per tenant and cached alongside the signed tariff. `domain/fare/FareEngine.kt` and `domain/FareEngine.kt` are the only two files that must change structurally; the rest are literal substitutions. Must land in lockstep with the backend `FareRegion` refactor or the two engines will disagree.

### 2.5 Signature verification failure path

`sync/TariffCache.kt:98-115`. Prefers the cached Ed25519 key, falls back to a network fetch only if never cached, throws `TariffSignatureException` otherwise. A failed verify → **not written to Room** → the previously-verified tariff stays. Correct fail-safe design.

- **`sync/TariffCache.kt:37-42` — minor.** `getActiveTariff`/`observeActiveTariff` do **not** re-verify on read. On a rooted device, direct Room manipulation bypasses verification. *Fix if threat model includes rooted tablets: store the signature alongside `rawJson` and re-verify once per trip.*
- **`sync/TariffCache.kt:128-139` — good.** `validateFaresOrderOrThrow` rejects a correctly-signed tariff whose rates exceed the regulated cap.

---

## 3. OFFLINE & SYNC

### 3.1 What survives process death

| Survives | Mechanism |
|---|---|
| Trips (open/tick/close) | Room `TripEntity`, `data/repository/TripRepository.kt` — every write commits to Room before returning |
| Sync outbox | Room `SyncOutboxEntity`, one row per `(entityType, clientUuid)` |
| Signed tariff + signing key | Room `TariffEntity` / `TariffSigningKeyEntity` |
| Toll registry | Room `TollRoadEntity` / `TollGantryEntity` / `TollPointEntity` |
| Access + refresh token | `TokenStore` SharedPreferences — **plaintext** |
| Driver session | `SessionStore` SharedPreferences, 12 h `ShiftDurationLimit` staleness check |
| Device pairing id + device secret | `DevicePairingStore` SharedPreferences — **device secret in plaintext** |
| Commissioning flag | `CommissioningStore` |
| Offline PIN hash | `driver_auth_cache` SharedPreferences, `SHA-256(driverId:pin)` **unsalted** |

| Lost on process death | Impact |
|---|---|
| **The live `FareEngineImpl` accrual state** | The dial resets; Room counters survive so the *bill* is recoverable, but the meter stops ticking. **Blocker** — see F4. |
| `SessionHolder.pendingTrip` / `TripContext` | Documented exclusion (`SessionStore.kt:41-46`). |
| `SessionHolder.liveTripClientUuid` | `DeckHomeScreen.kt:576-578` redirects an in-progress trip to Close & Pay after a restart. Deliberate but abrupt. |
| **Started shifts that failed to reach the server** | See S3. **Major data loss.** |

### 3.2 Sync mechanism

**WorkManager** — `sync/SyncWorker.kt` is a `CoroutineWorker`: periodic 15 min (`KEEP`, `CONNECTED`, exponential backoff, `SyncWorker.kt:71-80`); one-time on reconnect (`REPLACE`, `ConnectivitySyncTrigger.kt:55`). Drain logic in the pure `OutboxDrainer` (651-line test). Idempotent on `client_uuid`; rows deleted only on confirmed success.

| ID | file:line | Sev | Issue | Fix |
|---|---|---|---|---|
| **S1** | `data/local/dao/SyncOutboxDao.kt:53-54` | major | `getReadyBatch` has **no `attempts` cap and no backoff column**. A permanently-rejected row (422) is retried forever, keeps `SyncWorker` in `Result.retry()`, and — batch size 20, oldest-first — occupies a slot while newer trips queue behind it. | Add `AND attempts < :maxAttempts` and a `nextAttemptAt` column; surface exhausted rows in `OfflineSyncScreen`. |
| **S2** | `sync/OutboxDrainer.kt:44-49` | minor | Malformed `entity_json` is counted and silently skipped forever, inflating "N trips pending sync". | Dead-letter state or delete after logging. |
| **S3** | `domain/ShiftRepository.kt:26-36` + `55-60` | **blocker** | On network failure, `RemoteBackedShiftRepository.startShift` **fabricates a synthetic `ShiftDto` and returns `Result.success`.** Its own doc: *"NOT persisted or retried and will be orphaned if the app process dies before connectivity returns."* Every trip closed under that shift carries a `shift_id` the server has never heard of. The `SHIFT` outbox type is reserved (`SyncOutboxEntity.kt:57`) but never used. | Queue the shift-start in the outbox with `entityType = SHIFT`, mint the `clientUuid` locally, reconcile trip `shift_id`s on drain. |
| **S4** | `data/AppContainer.kt:276-279` | minor | `ConnectivitySyncTrigger` never unregistered; `enqueuePeriodic` on every init. `KEEP` makes it safe. | — |
| **S5** | `ui/screens/dashboard/WheelDashboardViewModel.kt:153` | major | `tariffCache.refresh(r)` has **exactly one call site**, reached only when the dashboard composes. No reconnect-driven or periodic tariff refresh, none at login. | Add `tariffCache.refresh(region)` to `ConnectivitySyncTrigger.onAvailable` and `SyncWorker.doWork()`. |

### 3.3 401 handling

`AppContainer.kt:350-403`. OkHttp `Authenticator`: skips refresh for `/v1/auth/refresh` and any request already retried once; `synchronized` so concurrent 401s queue; refresh token in the body; returns `null` on give-up. Correct.

- **Issue — major.** On give-up, the stale `accessToken`/`refreshToken` are **not cleared** from `TokenStore`. *Fix: clear both and the session when the refresh call itself returns 401.*
- **Issue — major.** The `synchronized(this)` lock object is `AppContainer` — see §1.2.

### 3.4 Server unreachable mid-fare

Correct by construction: `TripRepository` never awaits the network (`TripRepository.kt:27-36`). A full trip in airplane mode works. Card payment cannot be collected offline (`CardPaymentGateway.kt:91-93`); auto-toll works from the Room cache; the fare computes from the cached tariff.

---

## 4. DEVICE / MDM

### 4.1 Commissioning gate

`domain/CommissioningStore.kt` + `domain/DeviceReadiness.kt` (414 lines, pure, 394-line test). Decision at `CabDispatchNavHost.kt:339-371`: never gate an open shift; not commissioned → `DEVICE_READINESS`; `blockingFailures()` → `DEVICE_READINESS`.

**Blocking (2):** `Registered`; `UpToDate` (only when `forceUpdatePending && updateAvailable`). **Advisory (9):** `Location`, `Permissions`, `BatteryOptimisation`, `Kiosk`, `OfflineMaps`, `MapService`, `SignedTariff`, `VehicleClass`, `Heartbeat`.

- **`DeviceReadiness.kt:84-91` — blocker (cross-ref F4).** `BatteryOptimisation` is advisory, and the enum's own doc says Doze "is a trip that stops being charged for while the passenger is still in the car."
- **`ui/screens/readiness/DeviceReadinessViewModel.kt:239` — major.** `getActiveTariff("urban")` — a country-region tablet always reports `SignedTariff` failing.
- **`CabDispatchNavHost.kt:391` — minor.** `updateAvailable = false` hardcoded from the splash path.

### 4.2 Heartbeats

**`DeviceCommandHeartbeat`** (526 lines, `POLL_INTERVAL_MS = 60_000L`): gated on `SessionHolder.deviceId != null`, act-then-delay, `X-Device-Secret` with bearer fallback, handles `kiosk_locked`/`force_update_pending`/`locate`/`restart`, 404 → `deviceRejected`. **Never reads `DeviceDto.vehicleId` off the heartbeat response** even though the backend returns it — the self-heal channel is on the wire and unused.

**`LivePositionHeartbeat`** (290 lines, `HEARTBEAT_INTERVAL_MS = 5_000L`): gated on `session?.shiftId != null`, re-resolves the binding every 60 s on 404 (fixed 2026-09-08). `status` is a fixed placeholder string (`LivePositionHeartbeat.kt:285`).

### 4.3 Kiosk

`domain/KioskLockController.kt` — **screen pinning only**; the app is not Device Owner. Screen pinning is user-escapable and does not survive reboot; re-applied every 60 s poll. Honest limitation, correctly documented, but should not be presented to an operator as "kiosk lock."

### 4.4 OTA — `domain/AppUpdateChecker.kt` (249 lines)

Real: stream APK → SHA-256 verified while streaming → `FileProvider` → OS install dialog. Knox blocks unknown-source installs fleet-wide until allowlisted (`docs/KNOX_LOCKDOWN_RUNBOOK.md` §3.2).

- **`AppUpdateChecker.kt:95-103` — major.** Both endpoints are bearer-only — **no `X-Device-Secret` fallback**. A parked/logged-off tablet cannot check for or fetch an update. *Fix: extend device-secret auth to `/v1/app-releases/latest` and the download.*
- **minor.** No signature-of-APK check beyond the server-supplied SHA-256. *Fix: pin the signing certificate, or Ed25519-sign the release manifest.*

---

## 5. HARDWARE — **all four are mocks**

| Interface | Real impl? | Mock class | Wired at | What it actually does |
|---|---|---|---|---|
| `hardware/payments/CardPaymentGateway.kt:18` | **NO** | `MockCardPaymentGateway` (`:98-147`) | `AppContainer.kt:482` | 1500 ms delay, returns `paymentId="mock_pi_<millis>"`, `cardBrand="visa"`, `last4="4242"`, `approvalCode="MOCK00"`. **No Stripe Terminal dependency in `build.gradle.kts`. No money moves.** |
| `hardware/printing/ReceiptPrinterGateway.kt:15` | **NO** | `MockReceiptPrinterGateway` (`:46-83`) | `AppContainer.kt:483` | Synthetic devices, `printReceipt` = `Log.i`. **No `BLUETOOTH*` permission in the manifest.** |
| `hardware/receipt/SmsReceiptGateway.kt:14` | **NO** | `MockSmsReceiptGateway` (`:24-32`) | `AppContainer.kt:484` | `Log.i` + 600 ms. |
| `hardware/receipt/EmailReceiptGateway.kt:14` | **NO** | `MockEmailReceiptGateway` (`:25-33`) | `AppContainer.kt:485` | `Log.i` + 600 ms. |

**blocker.** Close & Pay's payment confirmation, receipt printing, SMS and email receipts are all fabricated successes. `CardPaymentGateway.kt:95-96` says "DO NOT wire this into a release build path without replacing it" — it *is* wired into the only build path.

*Fix (minimum viable):* make the mocks fail loudly in non-debug builds, and gate the corresponding Close & Pay buttons on `BuildConfig.DEBUG` until real gateways land. Note the backend `POST /v1/trips/{id}/receipt/email` and `/receipt/sms` routes **do** exist (`trips.py:746,781`) — the Android mocks should call them.

**What IS real:** Ed25519 tariff signature verification, GPS (`RealLocationProvider`, FusedLocationProviderClient), QR scanning (ML Kit), duress audio (`MediaRecorder`) and camera (`CameraX`), TTS, tone, kiosk pinning, OTA download+verify, Mapbox REST.

---

## 6. CODE HEALTH

### 6.1 TODO / TEMPORARY — file:line

| file:line | Marker | Content |
|---|---|---|
| `domain/FareEngine.kt:133-140` | TODO | **Fare engine lifetime tied to `HiredViewModel`.** blocker (F4) |
| `domain/NswPublicHolidays.kt:23`, `:44` | TODO(risk flag) | 2027 holiday dates calculated, not gazetted. **major** |
| `domain/ShiftRepository.kt:26-36` | TODO | Shift start should queue in Room + WorkManager. **blocker (S3)** |
| `security/TariffSignatureVerifier.kt:121` | TODO | Reconcile canonical-payload format with the backend. **major** |
| `domain/DriverAuthRepository.kt:95-98` | TODO(security review) | Unsalted `SHA-256(driverId:pin)` offline cache. **major** |
| `domain/DriverAuthRepository.kt:136-143` | **TEMPORARY** | `seedOfflineDemoDriver` — fabricates a demo driver in the offline auth cache. **major** |
| `ui/screens/login/LoginVehicleBindViewModel.kt:134-137` | **TEMPORARY** | Calls `seedOfflineDemoDriver` from the debug quick-login button. **major** |
| `ui/screens/login/LoginVehicleBindViewModel.kt:44-46` | TODO(compliance) | Pre-shift inspection checklist items are placeholders. **major** — regulated safety record |
| `domain/TripModels.kt:15-17` | TODO(fleet-config) | `TollPresets` amounts are "illustrative placeholders". **major** |
| `domain/TripStatsRepository.kt:27` | TODO | Replace with a real Room DAO aggregate. |
| `domain/fare/TripFareReconstruction.kt:28-31` | TODO | Prefer persisted accrual totals (F9). |
| `ui/screens/closepay/CloseAndPayViewModel.kt:62`, `:133` | TODO(backend) | `paymentMethod` enum mismatch (CABCHARGE / docket number). **major** |
| `ui/screens/shiftreport/ShiftReportViewModel.kt:51`, `:66`, `:180` | TODO | Shift entity not written until report time. |
| `ui/screens/settings/SettingsViewModel.kt:231-233`, `:511` | TODO | Kalman/fused provider; device id. |
| `data/AppContainer.kt:440-473` | open | Two `FareEngine` types deliberately unconverged (F8). **major** |

No `FIXME`, `HACK`, or `XXX` markers exist.

### 6.2 Test coverage — `app/src/test`, 31 files, 5,813 LOC

**Well tested:** `FareEngineTest` (815, golden vectors), `TollDetectorTest` (811), `OutboxDrainerTest` (651), `DeviceReadinessTest` (394), `TripTraceReplayFidelityTest` (272), `FareEngineImplRunningDisplayTest` (253), `FareEngineImplAutoTollTest` (193), `FareBreakdownReconciliationTest` (141), `FareTimeClassZoneTest`, `TimeClassResolutionTest`, `SessionStoreTest`, `KioskLockControllerTest`, `SpeedBandTest`, `VehicleBindingTest`, `DevicePairingStatusTest`, `SpeechQueueTest`, `NavProgressTest`, `RatingValidationTest`, Mapbox polyline/geocoding tests.

**Not tested at all:**
- **`TripRepository`** — the entire offline trip lifecycle. **blocker gap.**
- **`TariffCache` / `TariffSigningKeyCache` / `TollRegistryCache`** — including the signature-failure path and the Fares-Order-violation rejection. **blocker gap.**
- **`security/TariffSignatureVerifier` and `TariffCanonicalPayload`** — zero tests on the Ed25519 verify or canonical byte layout. **blocker gap.**
- `DriverAuthRepository` (incl. offline fallback), `AppContainer.tokenAuthenticator`, `DeviceCommandHeartbeat`, `LivePositionHeartbeat`, `AppUpdateChecker`, `RegionResolver`, `ShiftRepository`, `DuressController`, `NswPublicHolidays`.
- Every ViewModel. Zero Compose UI tests. Zero `androidTest` — Room migrations 8→9 and 9→10 are **never exercised**, on financial data with no destructive fallback. **major.**

### 6.3 Giant files (>1,500 lines) and God-classes

| file | LOC | Assessment |
|---|---|---|
| `ui/screens/dashboard/DeckHomeScreen.kt` | **2,704** | God-file: 11 panes, rail, header, footer, map, ~40 private composables. Extract each pane into its own file. |
| `ui/screens/hired/HiredScreen.kt` | **2,594** | God-file. Split into `MeterDial.kt`, `FareBreakdownCard.kt`, `ControlsDrawer.kt`, `NavigatorPane.kt`. |
| `data/remote/ApiService.kt` | **2,067** | One interface + ~120 DTOs. Split DTOs by domain (`DriverEngagementDtos.kt` already sets the precedent). |
| `ui/screens/closepay/CloseAndPayScreen.kt` | 1,434 | Large but cohesive. |
| `ui/screens/settings/SettingsScreen.kt` | 1,345 | Hosts 4 sub-screens. |
| `ui/theme/Hud.kt` | 1,324 | Shared widget library — acceptable. |
| `ui/screens/dashboard/WheelDashboardScreen.kt` | 1,264 | **Dead — delete.** |

**God-classes:** `AppContainer` (661 lines, ~45 singletons, ~40 dependents); `DeviceCommandHeartbeat` (526 lines — polling + kiosk + force-update + locate + restart + telemetry); `ApiService` (~90 endpoints).

### 6.4 Deprecated Compose APIs

Material 3 throughout; **zero** M2 imports, zero deprecated progress-indicator signatures, zero accompanist. The only `@Deprecated` is a required platform override in `domain/SpeechAnnouncer.kt:106`. No action needed.

- **minor.** `kapt` → KSP for Room.
- **minor.** `MainActivity.kt:119-121` overrides `LocalDensity` globally to force a 1280×800 canvas; system-window content and `fontScale` behave unexpectedly.

### 6.5 Duplicated logic

| Duplication | Files | Sev |
|---|---|---|
| Two `FareEngine` types | `domain/FareEngine.kt` vs `domain/fare/FareEngine.kt` (F8) | major |
| Two `FareState` types | `TripModels.kt:76` vs `domain/fare/FareEngine.kt:227` — ~8 fields mirrored by hand per tick (`domain/FareEngine.kt:493-507`) | major |
| Two `FareBreakdown` types | `TripModels.kt:60` vs `domain/fare/FareEngine.kt:294` | major |
| Two `TimeClass` enums | `TripModels.kt:9` vs `domain/fare/FareEngine.kt:107`, bridged at `domain/FareEngine.kt:590-594` | minor |
| Three connectivity checks | `ConnectivitySyncTrigger:82`, `SettingsViewModel.pollNetwork`, `OfflineSyncViewModel.pollNetwork` | minor |
| Three haversines | `GeoMath.distanceKm`, `TollDetector.tollHaversineM:140`, `RealLocationProvider:228-232` | minor |
| Four dashboard chrome implementations | three dead (§1.4) | major |
| **Done right:** `DevicePairingRepository` — single impl for Settings and the readiness gate. Cite as the pattern. | | — |

---

## 7. Security findings

| ID | file:line | Sev | Issue | Fix |
|---|---|---|---|---|
| **X1** | `domain/DriverAuthRepository.kt:69-113` | **blocker** | The offline-PIN fallback triggers on **any** `runCatching` failure — including HTTP 401/403. Comment at line 94: *"Offline (or backend rejected/unreachable/malformed) — fall back to the cached hash."* A driver whose account has been **deactivated, suspended, or authority-revoked** logs in successfully forever on any tablet they once used. Authorisation bypass on a regulated meter. | Fall back **only** on `IOException`/`SocketTimeoutException`. A `HttpException` 401/403 must clear the cache and fail. |
| **X2** | `domain/DriverAuthRepository.kt:163-167` | major | `SHA-256(driverId:pin)` with **no salt, no iterations**. A 6-digit PIN space is exhaustible in milliseconds from the prefs file. | PBKDF2/Argon2 with a per-device salt and ≥100k iterations, or Android Keystore. |
| **X3** | `domain/TokenStore.kt:36`, `DevicePairingStore`, `driver_auth_cache` | major | Bearer token, refresh token, **device secret**, and PIN hash all in plain `SharedPreferences`. | `EncryptedSharedPreferences` for all four. |
| **X4** | `domain/DriverAuthRepository.kt:144-158` + `LoginVehicleBindViewModel.kt:134` | major | `seedOfflineDemoDriver` writes a fabricated user into the same offline cache X1 reads from. `BuildConfig.DEBUG`-gated — but the field tablet runs a debug build against production. | Delete `seedOfflineDemoDriver` and the quick-login button, or gate on a build flavour that is provably not shipped. |
| **X5** | `AndroidManifest.xml` `networkSecurityConfig` + `build.gradle.kts` `API_BASE_URL` | major | Default base URL is cleartext HTTP; `network_security_config.xml` permits cleartext to the production IP. | Restrict cleartext to `10.0.2.2`/localhost; assert HTTPS for any other host. Depends on the server getting TLS. |
| **X6** | `AppContainer.kt:251-257` | minor | `HttpLoggingInterceptor.Level.BODY` when `BuildConfig.DEBUG` — bearer tokens and PINs to logcat, on the field tablet. | `Level.HEADERS` at most, with `redactHeader("Authorization")` and `redactHeader("X-Device-Secret")`. |

---

## 8. Prioritised fix list

**Blockers (first):**
1. **F4** — hoist `FareEngineImpl` to a foreground service. Fixes fare-stops-on-back-nav / Doze / process-death.
2. **F3** — GPS fix-staleness timeout. Fixes tunnel/blackout mis-billing.
3. **F1** — measured monotonic tick delta. Fixes systematic under-charge.
4. **X1** — offline login must not accept a server rejection.
5. **S3** — queue shift-start in the outbox.
6. **§5** — make the four hardware mocks fail loudly outside debug builds.
7. Tests for `TariffSignatureVerifier` + `TariffCanonicalPayload`; resolve `TariffSignatureVerifier.kt:121`.

**Majors:** F2, F6, F8, F11, F12, T1, T2, S1, S5, X2, X3, X4, X6; delete the dead dashboard lines; fix `AppContainer` `synchronized(this)`; clear tokens on refresh-give-up; device-secret auth for OTA endpoints; `DeviceReadinessViewModel.kt:239` hardcoded `"urban"`; verify the pre-shift inspection checklist; source `TollPresets` from config; add `androidTest` Room migration tests.

**Minors:** F5, F7, F10, S2, S4, T3, T5, X5; kapt→KSP; the duplication collapses in §6.5; `RATE_PASSENGER` `popUpTo(0)`.

**Internationalisation (§2.4):** ~30 files carry hardcoded NSW/Australia assumptions. The `JurisdictionConfig` refactor is the single change that unblocks another state or country.
