# Home Dashboard Redesign — 2026-09-02

Rebuild of the Android driver app's Home dashboard (`DeckHomeScreen.kt`) to match a supplied
premium mockup ("match this design, look how prominent it is, lot of shades, colors, content is
missing, properly make it"). This is a visual/data-honesty pass on top of the existing
Captain Taxis purple redesign (`CaptainPalette`/`CaptainWidgets`) — it does not restructure the
screen's data flow or its nav-rail contents, both of which were already correct.

**Environment note, worth recording:** this task's worktree branched from `origin/main` before
the Captain Taxis redesign (`DeckHomeScreen.kt`, `CaptainPalette.kt`, `CaptainWidgets.kt`, the
whole wheel-nav dashboard, device pairing, QR scanner, etc.) had merged into it — none of those
files existed here at the start of this session. The full `android/battery-network-heartbeat-and-map-fixes`
line of work (78 files) was pulled in file-by-file via `git show <branch>:<path>` before any of the
work below could start; `git diff --stat` against that branch afterward showed zero remaining
differences under `android/`, confirming a byte-exact sync, not a partial one.

## Files changed

- `android/app/src/main/java/au/com/threesixty/cabdispatch/domain/GpsQuality.kt` — **new.** Shared
  `GpsQuality` enum + `GpsQualityClassifier` (permission → accuracy-tier logic), extracted out of
  `SettingsViewModel.kt` so both screens read one threshold instead of two independent copies.
- `android/app/src/main/java/au/com/threesixty/cabdispatch/ui/screens/settings/SettingsViewModel.kt`
  — removed the local `enum class GpsQuality`, `pollGps()` now calls `GpsQualityClassifier.classify`.
  Behavior-preserving (same thresholds, same permission short-circuit).
- `android/app/src/main/java/au/com/threesixty/cabdispatch/ui/screens/settings/SettingsScreen.kt`
  — added the now-needed `import ...domain.GpsQuality` (same package no longer implicitly visible).
- `android/app/src/main/java/au/com/threesixty/cabdispatch/ui/screens/dashboard/WheelDashboardViewModel.kt`
  — `DashboardStatusStrip` gained `gpsQuality: GpsQuality` and `networkType: String?`; `pollStatus()`
  rewritten to derive `gpsOk` from a real fix-quality tier (`AppContainer.speedSource.locationFix`
  via `GpsQualityClassifier`) instead of "permission granted + provider enabled", and `networkOk`/
  `networkType` from `DeviceTelemetry.readNetworkType()` instead of a `NET_CAPABILITY_INTERNET`-only
  boolean. Removed the now-dead `isLocationEnabled()`/`hasActiveInternet()` helpers.
- `android/app/src/main/java/au/com/threesixty/cabdispatch/domain/TripStatsRepository.kt` —
  added `RemoteTripStatsRepository`, backed by `GET /v1/trips/earnings/today`. `StubTripStatsRepository`
  kept (not deleted) for tests/previews.
- `android/app/src/main/java/au/com/threesixty/cabdispatch/data/AppContainer.kt` — `tripStatsRepository`
  now binds `RemoteTripStatsRepository()` instead of `StubTripStatsRepository()`.
- `android/app/src/main/java/au/com/threesixty/cabdispatch/ui/theme/CaptainPalette.kt` — added
  prominence tokens (`cardTop`/`cardBottom` gradient pair, `glowPurpleSoft`/`glowPurpleStrong`,
  `glowSuccessSoft`/`glowWarningSoft`/`glowDangerSoft`) — alpha/shade variants of the *existing*
  primary/accent/success/warning/danger hues, not new colors.
- `android/app/src/main/java/au/com/threesixty/cabdispatch/ui/screens/dashboard/DeckHomeScreen.kt`
  — the main file; see below for what changed line-by-line (line numbers as of this commit).

## Bugs fixed along the way

(File:line references are all in `DeckHomeScreen.kt` unless noted otherwise.)

1. **`availabilityError` was produced but never rendered** (`WheelDashboardViewModel.setAvailable`'s
   failure path had always set it; nothing in `DeckHomeScreen.kt` read it). Fixed: `DeckHomeScreen()`
   (~line 348), right after `CaptainHeader(...)` (composable at line 632) — an `AnimatedVisibility`
   banner (`Icons.Rounded.WarningAmber` + the real error text on a `CaptainPalette.glowDangerSoft`
   background) shows exactly when `state.availabilityError != null`. **Live-verified on a real
   device**: toggling availability while the backend was unreachable surfaced the real
   `CLEARTEXT communication to 10.0.2.2 not permitted by network security policy` message inline —
   previously this failure was completely silent to the driver.
2. **SET PRICE tile's "ACTIVE" subtitle was an unconditional hardcoded literal** (`"Fixed Fare · ACTIVE"`,
   always shown, regardless of whether a fixed fare was actually pending). Fixed: `DeckHomeScreen()`
   line 287 collects `SessionHolder.pendingTrip` as state; `MeterCard` (line 809, bug-fix comment at
   812) now takes a `negotiatedTotal: String?` param and shows `"Fixed Fare · ACTIVE"` (green) only
   when non-null, else `"Tap to set a price"` (neutral). **Live-verified**: on a fresh session with
   no pending trip, the tile correctly read "Tap to set a price".
3. **"TRIPS completed today" / "EARNINGS today" always rendered `0`/`$0`** — `AppContainer.kt`
   (~line 277) bound `tripStatsRepository` to `StubTripStatsRepository`, whose `observeTodayStats()`
   is a hardcoded-zero `flowOf(TodayStats())`. Fixed: new `RemoteTripStatsRepository`
   (`domain/TripStatsRepository.kt`) polls `GET /v1/trips/earnings/today`
   (`DriverEarningsTodayReadDto.tripsCompletedToday`/`.todayTotal`) every 30s, degrading to the last
   successfully-loaded value (never resetting to zero) on a failed poll. This is the same endpoint
   `HomeExtras`'s existing `earningsPctChange` fetch already calls (`DeckHomeScreen.kt` ~line 596) —
   left as two independent reads (simpler, no new shared cache) rather than consolidated, since the
   endpoint is cheap and idempotent.
4. **GPS status dot was "permission granted + provider enabled", not fix quality** — true the
   instant a driver grants location permission, regardless of whether a usable fix exists. Fixed:
   `WheelDashboardViewModel.pollStatus()` (line 232) now classifies the *real* last GPS fix
   (`AppContainer.speedSource.locationFix`) through the same accuracy tiers
   `SettingsViewModel`'s diagnostics screen already used (extracted to
   `domain/GpsQuality.kt`'s `GpsQualityClassifier` so there's one shared threshold, not two).
5. **Network status was a hardcoded "4G" label** regardless of actual transport — `DashboardStatusStrip.networkOk`
   was real, but the header's label text was a literal `"4G"` string (old line 643). Fixed: now
   reads `DeviceTelemetry.readNetworkType()` (already shipped for device heartbeats) via the new
   `networkStatusLabel()` helper (line 776) and renders "WI-FI"/"4G"/"OFFLINE" at the header
   (line 740) and a compact "WIFI"/"4G"/"NONE" in the `SystemStatusCard` grid (line 1493) —
   deliberately no signal-strength adjective ("STRONG"), since no `TelephonyManager`/`SignalStrength`
   reading exists anywhere in this codebase to honestly back one. **Live-verified**: on the test
   device (real Wi-Fi), the header correctly showed "WI-FI" with a green dot.

## Mockup elements — real data vs. honest substitute vs. honest omission

**Wired to real data, restyled for prominence (no behavior change):**
- Driver name/avatar, VERIFIED badge (`UserDto.suitabilityStatus == "clear"`), availability pill
  (`isAvailable`/`setAvailable`), GPS/network/printer/battery status dots (now GPS+network are
  *more* real than before, see bugs #4/#5 above), night-fare tile's `1.19×` (real
  `nightRate1 ÷ distRate1` from the signed tariff — **confirmed live-verified as a real, non-1.25
  value**, not the mockup's fabricated "1.25×"), meter dial + START METER, LIVE DISPATCH cards
  (`AvailableTripsWheelViewModel`, real `JobDto` fields, BOOKED/RANK JOB badges, `$low–$high` fare
  range), nav rail (unchanged item-for-item, already an exact mockup match), SHIFT TIME +
  progress bar, TRIPS/EARNINGS counts (now real, see bug #3), earnings %-vs-yesterday.
- Header background/meter dial/cards/nav rail all restyled with gradient washes
  (`CaptainPalette.cardTop`/`cardBottom`), a soft ambient purple glow behind the whole screen, and
  colour-coded borders (purple = booked/active, amber = rank-job, green = healthy, red = danger) —
  see `DeckHomeScreen.kt`'s `MeterCard`, `LiveDispatchCard`, `DispatchOfferRow`, `ShiftStatsBar`,
  `SystemStatusCard`, `CaptainNavRail`, `CaptainNavFlyout`.

**Honest substitute (real signal, reframed rather than matching the mockup's literal claim):**
- **"NEXT BREAK" → "SHIFT LIMIT" + fatigue-alert line + "Take break now" link**
  (`ShiftLimitRing`, `DeckHomeScreen.kt` line 1412). No break-tracking API exists anywhere
  (`ShiftDto` has no break fields, no start/stop-break endpoint) — confirmed again this pass. Built
  as an honest fatigue/shift-limit awareness card: (a) `GET /v1/fatigue-alerts` is now actually
  called (`HomeExtras.fatigueAlertCount`/`latestFatigueKind`, wired in `rememberHomeExtras`) — a
  real, already-fully-defined backend endpoint (`FatigueAlertDto`/`FatigueAlertPageDto` in
  `ApiService.kt`) that **nothing in this app called before this pass**; (b) the countdown ring is
  the real `ShiftDurationLimit.remaining()` 12h shift-duration-limit clock, labelled "SHIFT LIMIT",
  never "next break"; (c) "☕ Take break now" is a real local action wired to the existing
  `setAvailable(false)` call — it does the one honest thing this app can actually do (stop
  receiving job offers) and invents no return-time.
- **SYSTEM STATUS is now a genuine 2×2 grid** (GPS/NET/PRN/MTR) — was 3 cells (GPS/PRN/MTR) in a
  single row; NET added using the same real `networkOk`/`networkType` flag the header uses.

**Honest omission (confirmed no backing source; not fabricated):**
- **Vehicle make/model.** The mockup shows "CAP-5517 · Toyota Camry Hybrid". `VehicleDto` is
  `id`+`rego` only (confirmed against `ApiService.kt` again this pass) — no make/model field
  anywhere client- or server-side. The header (`DeckHomeScreen.kt` line 679) now shows the real
  rego alone (`state.session?.vehicleId`), styled prominently, with no fabricated descriptor.
  **Backend-requirements candidate**, flagged for the parent session's write-up: a `make`/`model`
  (or `description`) field on `VehicleDto`/the fleet-vehicle table would unlock this.
- **VOUCHERS count.** No voucher wallet/count exists anywhere (no entity, repository, or count
  endpoint — only a free-text code field entered at payment time). Kept the existing honest copy
  ("Redeemed at payment"), restyled the tile (icon box, gradient background) without inventing a
  number. **Backend-requirements candidate**: a voucher-balance/count endpoint would unlock a real
  "N Available" figure.
- **Network signal strength.** No `TelephonyManager`/`SignalStrength` reading exists anywhere in
  this codebase — the network label shows connection type only ("WI-FI"/"4G"/"OFFLINE"), never a
  fabricated "STRONG"/"WEAK" adjective.

## Visual direction implemented

- Layered radial-gradient glow washes behind the header and the whole page background
  (`CaptainPalette.glowPurpleSoft`), rather than a flat fill.
- Gradient (`cardTop`→`cardBottom`) backgrounds on every major card (meter card, live-dispatch
  card, shift-stats bar, system-status card, nav rail, nav flyout) instead of one flat panel color.
- Colour-coded borders/badges: purple = primary/booked/active (dispatch BOOKED badge, offer-row
  border tint, SET PRICE ACTIVE state), amber/gold = rank-job/warning (RANK JOB badge, fatigue-alert
  text), green = success/healthy (AVAILABLE pill, GPS/NET/PRN/MTR "ON" states), red = danger
  (SOS, availability-error banner, danger system-status states).
- Reused existing animation primitives (`rememberInfiniteFloat`, `animateColorAsState`, `gameClick`)
  for new glow pulses — the live-dispatch count badge now breathes, the shift-limit ring pulses once
  it's genuinely close to the 12h limit — without touching any of the prior premium/game-feel pass's
  existing animations (meter dial sweep, flyout stagger, button press-spring all untouched).

## Verification

1. `cd android && ./gradlew.bat :app:compileDebugKotlin` — **BUILD SUCCESSFUL** (one real compile
   error was found and fixed mid-pass: `SettingsScreen.kt` needed an explicit import for
   `GpsQuality` after it moved out of same-package `SettingsViewModel.kt` into `domain`).
2. `./gradlew.bat :app:testDebugUnitTest` — **BUILD SUCCESSFUL**, all existing tests still pass.
3. **Live device verification was actually performed**, not skipped: a physical tablet was
   connected (`adb devices` found `R52TB07AQVL`), the debug APK was assembled and installed, and
   the redesigned Home dashboard was reached and screenshotted end-to-end (quick-login → vehicle
   bind → Home). Confirmed live and working: the purple ambient header glow, the real rego-only
   vehicle line (no fabricated make/model), the real `1.19×` night-fare ratio (not the mockup's
   fictional `1.25×`), the SET PRICE tile correctly showing "Tap to set a price" (not a hardcoded
   "ACTIVE"), real green GPS/WI-FI status dots, the honest VOUCHERS copy, the new SHIFT LIMIT +
   fatigue-aware card, the SYSTEM STATUS 2×2 grid, and — most usefully — the previously-invisible
   `availabilityError` banner actually rendering a real backend connectivity error inline. The
   LIVE DISPATCH panel showed a `CLEARTEXT communication to 10.0.2.2 ...` error, which is a
   pre-existing dev-environment networking limitation (the app's configured dev backend host is an
   Android-emulator loopback alias, unreachable from a real device on this network) — unrelated to
   this pass's changes, not a new regression.

## Known follow-ups / minor observations

- The bottom stats row (`ShiftStatsBar` + `SystemStatusCard`) sits close to the device's usable
  screen height on the 1200×1920 tablet used for live verification — the "Take break now" link and
  the PRN/MTR system-status cells sat right at the bottom edge of the visible content. This is a
  pre-existing layout tightness this row already had before this pass (its own code comments note a
  prior on-device measurement finding the ring nearly as tall as its allotted space); adding the
  fatigue-alert line and "Take break now" text made it marginally tighter. Not a crash or clipped
  interaction (both remained visible and tappable in the live check), but worth a follow-up pass if
  a future design wants more breathing room there.
- Backend-requirements candidates for the parent session's write-up: (1) a vehicle make/model (or
  fleet "description") field, (2) a voucher-balance/count endpoint.
