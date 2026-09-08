# Maxi taxi UI wiring — Point to Point Transport (Fares) Order 2026

UI-wiring pass on top of the fare-engine-compliance work described in `FARE_ENGINE_2026_CHANGES.md`
(sections 3 and 8). That pass made `domain.fare.FareEngine`/`FareState` maxi-aware and
persistence-ready but explicitly left every UI entry point unwired. This pass builds those entry
points: a pre-trip declaration step, a local maxi-vehicle self-declaration store, an unmissable
live meter indicator, and a mid-trip passenger-count correction — without touching engine math,
`ui/screens/closepay/*`, or `ui/screens/settings/*` beyond the one added row.

Worktree: `D:\cabdispatch\.claude\worktrees\agent-a28379a2d982c6967`
Branch: `android/battery-network-heartbeat-and-map-fixes`, HEAD `d8c7ef4` ("Merge NSW 2026
fare-engine compliance pass") at the start of this pass.

## 1. What was built, file:line

### Local maxi-vehicle self-declaration store (new)

`android/app/src/main/java/au/com/threesixty/cabdispatch/domain/MaxiVehicleStore.kt` (new file) —
mirrors `DevicePairingStore`'s own `getSharedPreferences(..., MODE_PRIVATE)` pattern (the existing
precedent for small durable per-device state in this app). `isMaxiVehicle()` (line 33) defaults
`false`; `setMaxiVehicle(value)` (line 35) persists it. Own SharedPreferences file
(`"maxi_vehicle_declaration"`), deliberately not folded into `DevicePairingStore` — this is
vehicle-characteristic state, not device-pairing state, and should survive a device re-pair.

Wired into `data/AppContainer.kt:127` (`lateinit var maxiVehicleStore`), constructed in `init()` at
line 139 alongside `devicePairingStore`.

### Engine plumbing (display-facing `domain.FareState` + live `domain.FareEngine`)

- `domain/TripModels.kt` — `FareState` (the simpler, display-oriented struct
  `HiredViewModel.fareState: StateFlow<FareState>` actually exposes to the UI) gained three new
  fields: `passengerCount: Int = 1` (line 70), `wheelchairHiring: Boolean = false` (line 77),
  `maxiRateApplied: Boolean = false` (line 87). All copied verbatim from the pure engine's own
  shadow state — never recomputed in the UI layer.
- `domain/FareEngine.kt` (the live 1Hz ticking engine, its own class-doc explains it now delegates
  all accrual math to the pure `domain.fare.FareEngine`) — `FareEngine.startTrip(...)` (interface,
  line 119) gained `isMaxiVehicle`, `passengerCount`, `wheelchairHiring`, `airportRankRequestedMaxi`
  params, all defaulted so no other call site's behavior changes. New interface method
  `updatePassengerCount(count: Int)` (line 148). `FareEngineImpl.startTrip` (line 219) builds the
  shadow `CalcFareState` with these four inputs and seeds the UI-facing `FareState` with
  `passengerCount`/`wheelchairHiring`/`maxiRateApplied` read straight off that shadow state's own
  `maxiRateApplied` property (never re-derived). `FareEngineImpl.updatePassengerCount` (line 266)
  mutates only `calcState.passengerCount` and re-reads `calcState.maxiRateApplied` into `_state`
  immediately — it does **not** touch any already-accrued `breakdown` figure (those were already raw,
  unmultiplied numbers per this class's own pre-existing "consolidation pass" doc — the maxi
  multiplier has never been applied on this live-ticking display, only later at real Close & Pay
  time via the pure engine's `close()`; see Verification below for the live confirmation of that
  final-billing effect).

### Meter-start flow

- `domain/Session.kt` — `TripContext` gained `isMaxiVehicle`/`passengerCount`/`wheelchairHiring`/
  `airportRankRequestedMaxi` (lines 92, 100, 107, 114), all defaulted to the pre-existing
  behavior (false/1/false/false).
- `ui/screens/dashboard/WheelDashboardViewModel.kt:194` — `startMeter(...)` gained the same four
  params (defaulted), threaded into the `TripContext` it builds.
- `ui/screens/hired/HiredViewModel.kt:89` — `init`'s `fareEngine.startTrip(...)` call now passes
  the four `TripContext` fields through. `openTripInRoom` (line 149) passes
  `maxi = tripContext.isMaxiVehicle` (plus `passengerCount`/`wheelchairHiring`, already-existing
  params on `TripRepository.openTrip`) so the persisted `TripEntity` carries the real declaration
  from the very first Room write, not just the live display.
- `ui/screens/dashboard/DeckHomeScreen.kt` — new `showTripDetails` state (line 279); the plain
  (non-Set-Price) Start Meter tap now opens `TripDetailsDialog` (line 2218, new composable) instead
  of calling `onStartMeter()` directly (`onStartMeter = { showTripDetails = true }` at line 404).
  The dialog: a big ±64dp-button passenger stepper (1–11, default 1, via `StepperButton` at line
  2308), three toggles rendered through `TripDetailToggleRow` (line 2331) — "This vehicle has 5+
  passenger seats" (prefilled from `AppContainer.maxiVehicleStore.isMaxiVehicle()`), "Carrying a
  wheelchair passenger" (with the NSW Reg cl 82 reminder inline), "Requested as a maxi at a Sydney
  Airport rank" (precisely scoped copy — not a general "airport trip" flag). Confirming persists the
  maxi-vehicle toggle back to the store and calls the local `onStartMeter(...)` function (line 305)
  with the four values. Tapping "Cancel" or the scrim just closes the dialog — no trip is touched.
  A driver who never opens/changes anything in this dialog and just taps "Start meter" gets exactly
  today's pre-existing behavior (1 passenger, no maxi, no wheelchair, no airport-rank).
- Set Price ("fixed fare") flow is untouched — it's a separate, already-engine-correct feature
  this pass does not touch (also explicitly out of scope per the brief).

### Meter screen (`ui/screens/hired/HiredScreen.kt`)

- **MAXI RATE ×1.5 ACTIVE banner** (line 232) — a full-width, high-contrast amber
  (`CaptainPalette.warning`) banner between the top bar and the meter well, `AnimatedVisibility`
  gated on `fareState.maxiRateApplied` alone — never recomputed from raw inputs in the UI.
- **Wheelchair-hiring indicator** (line 252) — a smaller informational banner, gated on
  `fareState.wheelchairHiring`, reminding the driver of the NSW Reg cl 82 safe-securement rule.
  Informational only; does not touch meter start/stop mechanics.
- **Passenger tap-to-edit affordance** (line 315/320) — a small "PAX n ✎" text row below the T1/T2
  band badge (top-right of the meter well), deliberately unobtrusive. Opens `PassengerEditDialog`
  (line 678, a ±64dp stepper matching the pre-trip dialog's style) via `CaptainDialogScrim` (wired
  at line 499). Confirming calls `viewModel.updatePassengerCount(count)`.
- `HiredViewModel.updatePassengerCount(count)` (line 219, new method — no existing method
  signature on this ViewModel was touched) calls `fareEngine.updatePassengerCount(count)`
  immediately (so the chip/PAX label update on the next recomposition, no tick delay) and
  best-effort persists the correction via the new `TripRepository.updatePassengerCount(clientUuid,
  count)` (line 232, new method) so the eventual Close & Pay reconstruction
  (`domain/fare/TripFareReconstruction.kt`, unmodified) bills off the corrected count, not the
  stale one.
- The hidden duress-gesture zone, `togglePause()`/`addToll()`/`endTrip()`/duress call signatures
  are byte-for-byte unmodified — confirmed by re-reading the diff before commit.

### Settings — driver-editable maxi declaration

- `ui/screens/settings/SettingsViewModel.kt:91` — `SettingsUiState.isMaxiVehicle`, loaded from the
  store in `init` (line 135) and updated via the new `setMaxiVehicle(value)` method (line 146,
  persists + updates state — no existing method touched).
- `ui/screens/settings/SettingsScreen.kt` — `FareScheduleContent` (line 474) gained an
  `onSetMaxiVehicle` callback (wired at line 129 as `viewModel::setMaxiVehicle`) and a new row below
  the existing tariff-schedule card: "This vehicle has 5+ passenger seats" (line 546) with a
  Material `Switch` (line 564) bound to `state.isMaxiVehicle`. This is the app's existing
  vehicle/fare-schedule area (checked first, per the brief) — no new settings section was invented.

## 2. Exactly how the maxi-vehicle self-declaration is stored

`MaxiVehicleStore` (`domain/MaxiVehicleStore.kt`) wraps a dedicated
`SharedPreferences("maxi_vehicle_declaration", MODE_PRIVATE)` file, holding one boolean
(`is_maxi_vehicle`, default `false`). It is read/written from exactly two UI surfaces — the Start
Meter card's `TripDetailsDialog` (prefills from it, writes back on confirm) and Settings → Fare
schedule (reads/writes directly via a `Switch`) — both going through the same
`AppContainer.maxiVehicleStore` singleton, so the two surfaces always agree. It survives app
restarts and reinstalls-over-the-same-package (confirmed live — see Verification), and is
**per-device**, not per-vehicle-record: a tablet moved to a different physical vehicle keeps
whatever was last declared until a driver changes it. This is the same honesty tradeoff
`DevicePairingStore` already accepts for its own state; it is not attempted to be smarter than that
here.

Every place this value is read is labelled honestly in-app: "Your own declaration for this
vehicle — not read from a vehicle record" (both the Start Meter dialog and the Settings row use
near-identical copy), so a driver can never mistake it for fleet-registry data.

## 3. Backend requirements (flagged, not built here)

No backend field exists for "is this vehicle a maxi cab" — `VehicleDto`
(`data/remote/ApiService.kt`) is `id`+`rego` only, confirmed again this pass (matches the prior
audit cited in the task brief and in `DASHBOARD_REDESIGN_2026.md`'s own "honest omission" list for
vehicle make/model). A real fix would add:

1. **`VehicleDto.seatingCapacity` (or a plain `isMaxi: Boolean`)** on the fleet-vehicle table/API,
   settable by a fleet operator (owner/admin) rather than self-declared by whichever driver happens
   to be signed in that shift. This is the single highest-value change — it would let
   `TripDetailsDialog`'s "This vehicle has 5+ passenger seats" toggle become a read-only display of
   verified fleet data instead of a per-device guess, and would remove the possibility of a driver
   forgetting to toggle it (or toggling it on a tablet later moved to a non-maxi vehicle).
2. **A rank-location-aware way to know when a Sydney Airport rank request happened.** Today
   "Requested as a maxi at a Sydney Airport rank" is a bare, honestly-scoped checkbox with no
   location backing — this app has no rank-location detection anywhere (confirmed again this pass,
   consistent with the existing `RegionResolver`'s own "distance-from-Sydney-CBD circle, not a real
   geofence" limitation noted in `WheelDashboardViewModel.kt`). A real fix needs either a
   rank-geofence lookup (mirroring the existing toll/region geofence pattern in
   `backend/app/services/geofence.py`) or an explicit "hired at a designated airport rank" flag
   passed from a booking/dispatch system, cross-checked against GPS at hire time.

Neither gap blocks this pass — both are handled today via an honest, clearly-labelled driver
self-declaration, exactly as the task brief asked for.

## 4. Verification

1. **`./gradlew.bat :app:compileDebugKotlin` — genuine `BUILD SUCCESSFUL`, zero errors.** First
   attempt hit this environment's known Kotlin-daemon file-lock flakiness (`dirty-sources.txt used
   by another process`, falls back to non-daemon compile) and, on this occasion, that fallback
   produced a **stale** compiled artifact that Gradle's `UP-TO-DATE` caching then kept feeding to
   `assembleDebug` — caught only by directly grepping the built `.dex` files for strings unique to
   this pass's new code (`grep`/dex-byte-search came up empty for e.g. `MaxiVehicleStore`,
   `showTripDetails`, even the pre-existing `START METER` — all files were being served from the
   wrong dex split), not by trusting Gradle's own "successful" exit code. Fixed with
   `./gradlew.bat --stop` (kill the wedged daemon) + `:app:clean` + a fresh
   `compileDebugKotlin`/`testDebugUnitTest`/`assembleDebug` sequence, then re-confirmed the new
   `.dex` genuinely contained `Before you start the meter`, `showTripDetails`, `TripDetailsDialog`,
   `passengerCount`, `This vehicle has 5` (all present) before reinstalling. Only pre-existing
   warnings remain (`MapboxOfflineRegion.kt` unused param, a few deprecated-icon warnings, an
   unused `ProfileScreen.kt` param) — none touched by or related to this pass.
2. **`./gradlew.bat :app:testDebugUnitTest` — genuine `BUILD SUCCESSFUL`, 29/29 tests pass, 0
   failures, 0 errors** (confirmed via the JUnit XML under
   `android/app/build/test-results/testDebugUnitTest/`): `FareEngineTest` 19/19 (golden vectors
   A–S, including the maxi-eligibility matrix J–N and the negotiated-fare test R — the exact
   frozen-engine logic this pass's UI now drives, completely unaffected since `domain/fare/`
   was never touched), `TimeClassResolutionTest` 9/9, `OutboxDrainerTest` 1/1.
3. **Live device walkthrough — actually performed, not skipped.** `adb devices` found
   `R52TB07AQVL` (a real, connected physical tablet); the corrected debug APK was installed and
   driven end-to-end through quick-login → vehicle bind → shift start → Home dashboard:
   - Tapped START METER on a fresh, non-maxi dashboard → the new "Before you start the meter"
     dialog appeared (not the old direct-start behavior), correctly prefilled "This vehicle has
     5+ passenger seats" **on** from an earlier live edit of the same toggle in Settings → Fare
     schedule (confirming the store round-trips correctly between the two surfaces and survives
     an app reinstall over the same package).
   - Bumped passenger count to 6 via the dialog's stepper, confirmed maxi-vehicle stayed on, tapped
     "Start meter" → landed on the live meter with a "PAX 6" indicator and an unmissable amber
     "⚠ MAXI RATE ×1.5 ACTIVE" banner, exactly as designed.
   - Ended and paid that trip: Close & Pay showed Flagfall $5.00 + Fare (distance+time) $1.69 +
     PSL $1.32 + Tolls $4.30 → **Total $15.66**. The metered component ($5.00+$1.69=$6.69 raw) at
     ×1.5 is $10.035, +$4.30 tolls +$1.32 PSL ≈ $15.66 — matches, confirming tolls/PSL are added
     **unmultiplied on top** exactly as `domain/fare/FareEngine.close()`'s own Fix-1 design (and
     its already-passing golden vectors) specify.
   - Started a **second, plain trip leaving every Trip Details field at its default** (1
     passenger; the persisted maxi-vehicle toggle stays on from the prior step, honestly reflecting
     that per-device state) → live meter showed "PAX 1" and correctly showed **no** MAXI RATE
     banner (1 passenger < 5, no airport-rank request — not eligible despite the vehicle
     declaration), confirming the zero-behavior-change default path.
   - Mid-trip, tapped the small "PAX 1 ✎" affordance, corrected the count to 6 in the
     `PassengerEditDialog`, tapped Update → the MAXI RATE ×1.5 ACTIVE banner appeared **live,
     immediately**, with no re-navigation. Ended and paid that trip: Close & Pay showed Total
     $13.03 (Flagfall $5.00 + Distance $0.22 + Waiting $2.58 = $7.80 raw × 1.5 ≈ $11.70 + PSL
     $1.32 ≈ $13.02–13.03) — confirming the mid-trip correction genuinely reached the persisted
     `TripEntity.passengerCount` and was picked up by `TripFareReconstruction` at real billing
     time, not just the live display.
   - Both trips synced cleanly ("Trip synced ✓ · outbox clear · fare posted to shift totals").

No screens under `ui/screens/closepay/` or `ui/screens/settings/` (other than the one row added to
`FareScheduleContent`, per the brief's own carve-out) were touched, and `domain/fare/FareEngine.kt`
/`domain.fare.FareState` (the frozen, pure engine) was never edited — grepped the whole diff to
confirm before committing.

## 5. Files touched

```
android/app/src/main/java/au/com/threesixty/cabdispatch/domain/MaxiVehicleStore.kt      (new)
android/app/src/main/java/au/com/threesixty/cabdispatch/domain/TripModels.kt
android/app/src/main/java/au/com/threesixty/cabdispatch/domain/FareEngine.kt
android/app/src/main/java/au/com/threesixty/cabdispatch/domain/Session.kt
android/app/src/main/java/au/com/threesixty/cabdispatch/data/AppContainer.kt
android/app/src/main/java/au/com/threesixty/cabdispatch/data/repository/TripRepository.kt
android/app/src/main/java/au/com/threesixty/cabdispatch/ui/screens/dashboard/WheelDashboardViewModel.kt
android/app/src/main/java/au/com/threesixty/cabdispatch/ui/screens/dashboard/DeckHomeScreen.kt
android/app/src/main/java/au/com/threesixty/cabdispatch/ui/screens/hired/HiredViewModel.kt
android/app/src/main/java/au/com/threesixty/cabdispatch/ui/screens/hired/HiredScreen.kt
android/app/src/main/java/au/com/threesixty/cabdispatch/ui/screens/settings/SettingsViewModel.kt
android/app/src/main/java/au/com/threesixty/cabdispatch/ui/screens/settings/SettingsScreen.kt
```
