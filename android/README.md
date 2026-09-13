# Cab Dispatch — Android meter app

Kotlin + Jetpack Compose, offline-first taxi meter. Package `au.com.threesixty.cabdispatch`,
minSdk 29, compileSdk 36, targetSdk 35, AGP 8.13.2, Kotlin 2.3.21 (see `app/build.gradle.kts` /
`build.gradle.kts` for the authoritative versions if this drifts — updated here 2026-09-12, W0
toolchain refresh; do not let this comment go stale again the way the section below it did).

## Build status — read this first

**The app builds, and every gate below is genuinely green as of 2026-09-12** (verified in this
same pass, not carried forward from an earlier claim — see `docs/plans/2026-09-12-android-meter-
optimisation-and-gps-blackout-plan.md` W0 for what was fixed to get here). A stale, much older
version of this section used to say the opposite ("has NOT been compiled or run in this
environment") — that was true of an earlier sandbox pass long since superseded; it is not true of
this checkout, and it should never again be trusted over actually running the two commands below.

**The two commands that gate a merge to `phase0/merge-to-main` or `main`** (identical to what
`.github/workflows/ci.yml`'s `android` job runs):

```
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:detekt
```

Both must exit 0 with no baseline changes. `lintDebug` has **no baseline file** — every lint
issue in the tree is either fixed or suppressed in place with a comment explaining why (see
`app/lint.xml` for the one issue that can only be suppressed at the resource-directory level); a
regression here is a real, new problem, never something to re-baseline away. `detekt` DOES run
against a baseline (`app/detekt-baseline.xml`) — regenerate it with `./gradlew :app:detektBaseline`
only when deliberately accepting new debt, and say so in the commit message; the normal way to
clear an entry is to fix the code so it disappears from the report.

Both commands need `local.properties` (gitignored) with `MAPBOX_DOWNLOADS_TOKEN` set — see
`settings.gradle.kts`'s own comment — or dependency resolution fails with a 401 before either task
runs. CI reads the same token from a repository secret.

## Opening and building this in Android Studio

1. Install **Android Studio Koala (2024.1) or newer** (anything that bundles AGP 8.5.x / supports
   Kotlin 1.9.24 works; Koala+ is the version this project's plugin versions were chosen against).
   JDK 17 is required (`compileOptions`/`kotlinOptions` in `app/build.gradle.kts` both target 17)
   — Android Studio bundles its own JDK 17, so you normally don't need a separate install.
2. `File > Open...` and select the `android/` folder (the one containing this README and
   `settings.gradle.kts`) as the project root — not the repo root.
3. Let Gradle sync run. Only `gradle/wrapper/gradle-wrapper.properties` is checked in;
   Android Studio regenerates `gradlew`/`gradlew.bat`/`gradle-wrapper.jar` on first sync if
   they're missing. `compileSdk`/`targetSdk` 35 must be installed via
   `Tools > SDK Manager` if Studio doesn't prompt you automatically.
4. Point `BuildConfig.API_BASE_URL` at your backend — it's set per build type in
   `app/build.gradle.kts` (`android.buildTypes.{debug,release}.buildConfigField("String",
   "API_BASE_URL", ...)`), not read from an env var, so change it there and re-sync:
   - **Emulator, debug build (the default)**: already `http://10.0.2.2:8001` — `10.0.2.2` is the
     emulator's fixed alias for the host machine's `localhost` (the emulator is its own network
     namespace, so `localhost`/`127.0.0.1` inside it means the emulator itself). Start the backend
     first: `uv run uvicorn app.main:app --port 8001` from `backend/` (see `shared/API_SUMMARY.md`).
   - **Physical device on the same LAN/VPN**: `10.0.2.2` doesn't resolve outside an emulator —
     change the debug `API_BASE_URL` to your host machine's LAN IP, e.g. `http://192.168.1.23:8001`.
   - **Release build**: see "Release build (signed APK)" below — a placeholder URL, or missing
     signing keys, now REFUSES to build a release artifact at all (a real Gradle-time error naming
     exactly what's missing), rather than silently shipping something wrong.
5. Build: `Build > Make Project`, or run the `app` configuration on an emulator/device (API 29+).
   `assembleDebug`/`testDebugUnitTest` from the Gradle tool window work the same way once the
   wrapper exists.

## Release build (signed APK)

**W8 release readiness (2026-09-13). This whole section is the OWNER-only step** — the owner holds
the real release keystore and its two passwords; nobody else's `local.properties` should ever have
real values for the four `RELEASE_*` keys below. A worktree with no signing keys can still run
every ordinary command (`testDebugUnitTest`, `lintDebug`, `detekt`, `assembleDebug`) — none of them
touch this section's guards, which fire only on `assembleRelease`/`bundleRelease`/`packageRelease`.

Add these keys to `android/local.properties` (gitignored — never commit real values here) or set
them as environment variables on the build machine:

| Key | What it is | Required for a release build? |
|---|---|---|
| `RELEASE_API_BASE_URL` | The real, deployed, **https://** backend URL. Pre-existing tripwire (Phase 0) — a release build refuses to proceed while this is still the built-in placeholder, or not `https://`. | Yes |
| `RELEASE_STORE_FILE` | Absolute or `local.properties`-relative path to the real release `.jks`/`.keystore` file. | Yes |
| `RELEASE_STORE_PASSWORD` | The keystore's own password. | Yes |
| `RELEASE_KEY_ALIAS` | The alias of the signing key inside that keystore. | Yes |
| `RELEASE_KEY_PASSWORD` | That key's own password (may be the same as the store password, depending on how the keystore was generated). | Yes |
| `ALLOW_CLEARTEXT_HOST` | A single hostname/IP a **debug** build is allowed to reach over plain HTTP, on top of the emulator/localhost baseline — e.g. the pilot Ubuntu server's bare IP, until it is served over TLS. | No — leave unset/blank. **A release build FAILS if this is non-blank**, on purpose (see `app/src/main/res/xml/network_security_config.xml`'s own doc). |

If any of the four `RELEASE_*` signing keys is missing, `./gradlew :app:assembleRelease` fails
immediately with a Gradle error naming exactly which one(s) are absent — it does not produce an
unsigned or wrongly-signed APK. See the "Release signing tripwire" block near the bottom of
`app/build.gradle.kts` for the exact check, and the "Release hardening tripwire" block above it for
the pre-existing URL check this one sits next to.

None of this has been exercised with a real keystore from any wave-6 agent worktree — no device,
no real keystore, no real backend TLS endpoint was ever available to do so, and generating a real
keystore or fabricating placeholder credentials from a worktree would be actively dangerous (a
device could end up running a build signed with a key the owner can never update again). What has
been verified, live, from this worktree: `:app:assembleRelease` genuinely fails with the tripwire's
own error message when the signing keys are absent, and `:app:assembleDebug` is completely
unaffected either way. Once the owner supplies the four real values and a real device, `./gradlew
:app:assembleRelease` — installs and reaches a hired fare on the tablet is the plan's own
acceptance line (**OWNER G5**).

## Module layout

Single Gradle module, `:app` (see `settings.gradle.kts`). Under
`app/src/main/java/au/com/threesixty/cabdispatch/`:

```
data/                    Retrofit contract (remote/ApiService.kt), Room (local/), the
                          TripRepository, and AppContainer — the manual service-locator
                          composition root (see its doc comment for the registration pattern).
domain/                  Screen-facing interfaces + the S3 live-ticking fare engine
                          (FareEngine.kt/FareEngineImpl), session hand-off (Session.kt),
                          auth/QR/speech/shift/trip-stats interfaces + their real or stub impls.
domain/fare/             Stateless, pure-Kotlin port of the backend's fare_engine.py — used by
                          S4/S5 to reconstruct a fare breakdown from a persisted TripEntity.
                          No Android framework dependency; this is what FareEngineTest.kt exercises.
hardware/payments/       Card payment gateway interface + mock (Stripe Terminal stand-in).
hardware/printing/       BT thermal receipt printer gateway interface + mock.
hardware/receipt/        Receipt model + SMS/email receipt gateway interfaces + mocks.
security/                TariffSignatureVerifier — the one REAL (non-mocked) hardware-adjacent
                          interface in this batch; Ed25519 signed-tariff verification
                          (Ed25519TariffSignatureVerifier, wired via sync/TariffCache.kt — the
                          original RsaTariffSignatureVerifier is kept defined but unused, see its
                          class doc).
sync/                    WorkManager glue (SyncWorker), the pure-JVM-testable drain logic
                          (OutboxDrainer), connectivity trigger, and the Room-backed TariffCache.
ui/navigation/           CabDispatchNavHost + CabDispatchRoutes — the S1-S6 NavHost.
ui/screens/<screen>/     One package per screen (login, idle, hired, closepay, shiftreport,
                          settings), each a Composable + a ViewModel.
ui/theme/                Compose Material3 theme + the S3 HIRED-screen colour palette.
```

`app/src/test/.../domain/fare/FareEngineTest.kt` and `.../sync/OutboxDrainerTest.kt` are plain
JUnit4 unit tests (no Android instrumentation needed).

## Navigation (S1-S6)

`CabDispatchNavHost` wires all six spec screens as real Compose destinations (not placeholders):

- **S1 (Login/Vehicle bind) → S2 (Idle)**: on completing the pre-shift inspection, popping S1 off
  the back stack.
- **S2 → S3 (Hired)**: "Start hire". **S3 → S4 (Close & Pay)**: "End trip".
- **S4 → S2**: "Done" pops S3 and S4 off the back stack (`popUpTo(IDLE) { inclusive = true }` then
  a fresh `IDLE` push), landing back on S2 with an empty trip-related history — matches the spec's
  S1→S2→S3→S4→back-to-S2 flow.
- **S5 (Shift report)**: reachable from S2 via a toolbar icon; "Finalize & submit" returns to S1
  with the whole back stack cleared (end of shift).
- **S6 (Settings/Diagnostics)**: reachable from every other screen via a small settings
  glyph/icon (S1/S2/S4/S5's app bar or header row; a low-contrast corner glyph on S3, since that
  screen is deliberately affordance-free apart from the hidden duress gesture — see below) and
  pops back to wherever it was opened from.

## Real vs. mocked

Per spec B6 anti-tamper and B5 S4/S6, every piece of hardware/external-service integration below
sits behind an interface (in `hardware/`, `security/`, or a `domain/` interface for the one
non-hardware case) so the mock nature is impossible to miss at a glance — **do not mistake any of
the mocks below for a working integration.**

Since A5 (hardware honesty) this is enforced rather than documented: every gateway in `hardware/`
implements `HardwareGateway.isReal`, and Close & Pay renders the TAP TO PAY and PRINT controls only
when the corresponding gateway reports `true`. Anything simulated is debug-only, cannot be
constructed in a release build (`NotImplementedError`), and marks every result it produces — a
persistent "SIMULATED — no money moved" banner in the confirmation and a `TEST RECEIPT` marker in
the receipt body itself. See `hardware/HardwareCapability.kt`.

| Interface | File | Status | Why |
|---|---|---|---|
| `TariffSignatureVerifier` | `security/TariffSignatureVerifier.kt` | **REAL** | `Ed25519TariffSignatureVerifier`, matching the backend's actual signing algorithm (`backend/app/services/tariff_signing.py`) — verified via BouncyCastle (`org.bouncycastle:bcprov-jdk18on`, since minSdk 29 predates `java.security`'s own Ed25519 support). The public key is fetched from `GET /v1/tariffs/signing-public-key` and Room-cached (`sync/TariffSigningKeyCache.kt`), not a compile-time constant — `sync/TariffCache.kt#refresh` verifies every freshly-fetched tariff's signature against it before caching, rejecting (throwing `TariffSignatureException`, never silently caching) one that doesn't verify. The file's original `RsaTariffSignatureVerifier` (with its own throwaway placeholder key, `TENANT_TARIFF_PUBLIC_KEY_B64`) is kept defined but no longer constructed by anything — the backend never signs with RSA. |
| `SpeechAnnouncer` | `domain/SpeechAnnouncer.kt` | **REAL** (`TextToSpeechAnnouncer`) | Android's built-in `TextToSpeech` engine — no special hardware or backend needed. |
| `CardPaymentGateway` | `hardware/payments/CardPaymentGateway.kt` | **SIMULATED (debug) / UNAVAILABLE (release)** — `isReal = false` | No Stripe Terminal SDK is a dependency of `build.gradle.kts`, so no money can move. A5 stopped this from being able to *look* like it does. Release builds get `UnavailableCardPaymentGateway`: every call returns a failure. Debug builds get `SimulatedCardPaymentGateway`, whose constructor throws `NotImplementedError` if it ever runs in a non-debug build, and whose results carry `simulated = true`, `approvalCode = "SIMULATED — no money moved"` and **no** fabricated card brand or PAN digits (the old mock returned `visa`/`4242`/`MOCK00`). Because `isReal` is false, **Close & Pay does not render the "CARD · TAP" button at all** — the capability is absent, not lying. Real integration is spec Phase 3. |
| `ReceiptPrinterGateway` | `hardware/printing/ReceiptPrinterGateway.kt` | **SIMULATED (debug) / UNAVAILABLE (release)** — `isReal = false` | No BLUETOOTH* permission and no vendor ESC/POS SDK, so nothing can be printed. Same debug/release split as `CardPaymentGateway` above. The simulated one names its fake devices "SIMULATED printer — prints nothing" and **refuses to print any receipt not marked `Receipt.simulated`**, so a genuinely-paid trip can never be reported as printed by a gateway that prints nothing. `isReal = false` means **Close & Pay does not render the Print action**, and the receipt panel says "No receipt printer on this device". |
| `SmsReceiptGateway` | `hardware/receipt/SmsReceiptGateway.kt` | **REAL** (`ApiSmsReceiptGateway`) | Posts to `POST /v1/trips/{trip_id}/receipt/sms`, keyed by the **server** trip id (`TripEntity.serverId`) — an unsynced trip fails with a message saying so rather than 404ing on a `clientUuid`. The backend's own `mock=true` answer (no Twilio credentials configured server-side) is surfaced as a **failure**, not as a delivery. |
| `EmailReceiptGateway` | `hardware/receipt/EmailReceiptGateway.kt` | **REAL** (`ApiEmailReceiptGateway`) | Posts to `POST /v1/trips/{trip_id}/receipt/email`, which renders and sends the branded PDF server-side. Same server-trip-id rule and same `mock=true`-is-a-failure rule as the SMS gateway above. |
| Duress networking | `domain/DuressController.kt` / `domain/DuressRepository.kt` | **REAL** | The hidden triple-tap gesture (S3/Hired and the wheel-dashboard shell, spec §2 "active throughout") now calls the real backend `POST /v1/duress/trigger` (+ `cancel`/`gps`/get) via `AppContainer.duressController` — see that class's doc for the confirmation-countdown/retry/GPS-relay/resolution-poll state machine, and `ui/overlays/DuressOverlays.kt` for the "Duress triggered"/"Duress active" UI it drives. Twilio SMS fallback-when-offline (spec B7) is still backend/dispatcher-side only, not driven from this device — see `DuressController`'s doc for the exact driver-vs-dispatcher split. GPS relay itself is best-effort last-known-fix (`LocationManager`, same as `SettingsViewModel#pollGps`), not a real fused/live location stream — see the `SpeedSource` row below for the same underlying gap. |
| `QrScanner` | `domain/QrScanner.kt` | Mock (`StubQrScanner`) | Always returns `null` (scan "unavailable") — S1 always falls back to its manual vehicle-ID text field. No CameraX/ML Kit wired in; no camera-equipped device/emulator to verify against here. |
| `SpeedSource` (fare-engine GPS input) | `domain/FareEngine.kt` (interface) / `domain/location/RealLocationProvider.kt` (impl) | **REAL** (`RealLocationProvider`, wired as `AppContainer.speedSource`) | `FusedLocationProviderClient`-backed, 1 Hz updates matching the fare engine's own tick rate, simple sanity filtering (speed-jump rejection + accuracy preference — see that file's doc; deliberately not a Kalman filter). Permission-gated: polls `ACCESS_FINE_LOCATION` every 3s and starts/stops updates accordingly, degrading to `speedKmh=0.0`/`locationFix=null` (same observable shape as the kept-around `StubSpeedSource`) rather than crashing when ungranted. The interface now also exposes `locationFix: StateFlow<LocationFix?>` (lat/lng/speed/accuracy/timestamp) — added for, and as of 2026-08-03 now actually consumed by, map-centering (`WheelDashboardScreen.kt`'s `MapBackground`/`RealMapboxMapView`) and region auto-detection (`domain/location/RegionResolver.kt`, wired into `WheelDashboardViewModel.kt`/`SettingsViewModel.kt`/`AvailableTripsWheelViewModel.kt`/`AvailableTripOfferViewModel.kt`) — see HANDOFF.md's matching entry for the full writeup. Still not consumed by the GPS status-strip dot (`SettingsViewModel.kt#pollGps`) or the duress GPS relay (`HiredViewModel.kt#lastKnownFix`), both of which still read a separate raw `LocationManager` last-known-fix. |
| Kiosk/lock-task mode | `MainActivity.kt` | Not implemented | Spec B1 calls for device-owner kiosk mode; out of scope for this pass, noted in `MainActivity`'s doc comment. |
| Local DB encryption (SQLCipher) | `data/local/AppDatabase.kt` | Not implemented | Spec B6 anti-tamper calls for it; `AppDatabase` is plain Room today, noted in its doc comment. |

All mocks/stubs above are wired as `AppContainer` (or, for the S1-S3 screen-only interfaces,
also `AppContainer`) singletons, so swapping in a real implementation later is a one-line change
in `data/AppContainer.kt` with no changes needed in the screens/ViewModels that consume them.

## Two `FareEngine` families — intentionally not merged

There are two unrelated fare-computation type families in this codebase, built independently and
left that way on purpose (see the note above `AppContainer.pureFareEngine` for the full reasoning):

- **`domain.fare.FareEngine`** (`domain/fare/FareEngine.kt`) — stateless, pure-Kotlin, BigDecimal-
  exact port of the backend's `fare_engine.py`. This is what `FareEngineTest.kt` exercises against
  the same golden vectors as `backend/tests/test_fare_engine_golden.py` (verified matching, see
  below), and what S4/S5 use to reconstruct a closing `FareBreakdown` from a trip's *persisted*
  Room totals via `domain/fare/TripFareReconstruction.kt`.
- **`domain.FareEngine`/`FareEngineImpl`** (`domain/FareEngine.kt`) — stateful, coroutine-driven,
  ticks once per second to drive S3's live on-screen running fare. Persists its cumulative
  `movingSeconds`/`waitingSeconds`/distance counters to `AppContainer.tripRepository` (via
  `HiredViewModel.openTripInRoom`/`persistTick`) so a real `TripEntity` row exists in Room for S4
  to read once the trip ends — this is the integration that was missing before this reconciliation
  pass (S4 used to always show "No active trip"; see `AppContainer.kt`'s note and the git history
  of `CloseAndPayViewModel.kt`'s doc comment for the before/after).

Merging the two engines' math (e.g. having the live-ticking engine delegate to
`domain.fare.FareEngine.tick()` per second instead of duplicating the rate-selection logic) is a
real design decision left for a future pass, not something silently picked here — the two engines
already agree on totals because both read rates from the same `TariffDto` and split distance bands
the same way (see `TripFareReconstruction.kt`'s doc for why that reconstruction is mathematically
exact, not approximate), so there's no known correctness bug from leaving them separate today.

## Fare-engine correctness vs. the backend

`app/src/test/.../domain/fare/FareEngineTest.kt`'s nine golden-vector tests (`testA`..`testI`) were
checked line-for-line against `backend/tests/test_fare_engine_golden.py` (`test_a`..`test_i`) as
part of this reconciliation pass: same scenarios, same expected `BigDecimal`/`Decimal` amounts for
every assertion (distance/waiting charges, GST component, maxi multiplier, airport fixed fare,
non-cash surcharge half-up rounding at the exact 0.5c boundary, multi-hire 75% owed, and the
Fares-Order-cap validation for rank/hail vs. booked tariffs). The `URBAN_TARIFF`/`COUNTRY_TARIFF`
rate constants in `domain/fare/FareEngine.kt` also match `fare_engine.py`'s `Tariff(...)` literals
field-for-field. **No discrepancies found** — this is a from-source comparison, not a test run (see
"Build status" above), so it does not substitute for actually running both suites once a JVM/Python
toolchain is available.

## Known remaining gaps (not fixed in this pass)

- `RemoteBackedShiftRepository` (used by S1 to open a shift) never writes a `ShiftEntity` row —
  S5 sources its trip list from `SessionHolder.session.shiftId` directly rather than
  `ShiftDao.observeActiveShift()`. Documented as a TODO in `ShiftReportViewModel.kt`.
- `OutboxDrainer` (sync engine) only drains `entityType == "trip"` rows today — the `"shift"` row
  S5 queues on submit will sit unprocessed until the drainer is extended for shifts too, but
  nothing is lost in the meantime (see `SyncOutboxEntity`'s doc).
- Duress networking (see table above) is now real, but the GPS relay it does while
  `DuressUiState.Active` is best-effort last-known-fix, not a true continuous live stream — same
  underlying `SpeedSource`/location-provider gap as the fare engine (see the next bullet).
- Peak-hiring / day-night-holiday time-class detection in the S3 live engine
  (`FareEngineImpl.resolveTimeClass`/`resolveIsPeak`) is a simplified 6am-8pm day / Fri-Sat
  10pm-6am peak heuristic with no public-holiday calendar — flagged as a TODO in that file.
- `HiredViewModel`'s live `FareEngineImpl` instance is recreated per nav entry (not
  process/foreground-service-scoped), so navigating away or a process death mid-trip loses the
  *live accrual display* only (the underlying `TripEntity` Room row and its counters, as of this
  pass, persist independently — see `FareEngine.kt`'s doc comment).
