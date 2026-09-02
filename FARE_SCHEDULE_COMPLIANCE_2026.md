# Fare Schedule screen — NSW compliance pass (2026-09-02)

File touched: `android/app/src/main/java/au/com/threesixty/cabdispatch/ui/screens/settings/SettingsScreen.kt`
(only file changed — `SettingsViewModel.kt` needed no changes, see "Why no ViewModel changes" below).

## What was already there, verified unchanged

`FareScheduleContent` (`SettingsScreen.kt:495-565`) — the existing tariff-rate panel (hiring
charge, peak charge, distance day/night first-12km/beyond, waiting rate, non-cash surcharge cap)
was checked against the current `TariffDto` shape (`data/remote/ApiService.kt:774-800`) and the
2026 fare-engine pass's own `Tariff`/`TariffDto` docs
(`domain/fare/FareEngine.kt:107-130`, `FARE_ENGINE_2026_CHANGES.md`). Field names (`flagFall`,
`peakCharge`, `distRate1/2`, `nightRate1/2`, `waitingRatePerMin`, `surchargePctCap`) are unchanged
from before this pass, so these rows kept working with no edits — confirmed live on-device (see
Verification below), not just by reading code.

## New content added

1. **Taxi Fare Hotline notice** (`TaxiFareHotlineNotice`, `SettingsScreen.kt:606-621`) — placed at
   the top of the screen, right after the intro line. Static text (fixed regulatory number, not
   tariff data): "1800 500 410", "Ask your driver, or call this number, if you believe you've been
   charged incorrectly.", and "The meter must always be switched on during a rank or hail trip." —
   satisfies Regulation cl 15(1A). No dial intent added (none exists anywhere in this codebase to
   reuse, and the task brief said not to invent one for a single row); no QR code (nice-to-have,
   not required, no QR library in the project).

2. **Maxi-cab fares section** (`MaxiCabFaresSection`, `SettingsScreen.kt:632-646`) — a `CaptainChip`
   labelled "MAXI RATE" showing the tariff's actual `maxiMultiplier` formatted as a percentage via
   `formatMaxiPercent` (`SettingsScreen.kt:708-711`, e.g. `"1.5"` → `"150"` — never a hardcoded
   `"150%"` literal), plus plain-language explanatory text citing Fares Order 2026 cl 2(d): 5+
   passengers, or an airport-rank maxi request, never for a wheelchair hiring.

3. **Additional charges section** (`AdditionalChargesSection`, `SettingsScreen.kt:658-680`):
   - Passenger Service Levy row — live off `tariff.pslAmount` (`TariffDto.pslAmount`,
     `ApiService.kt:795`), with explanatory text (optional, once per trip, cl 3).
   - Cleaning fee cap row — `TariffDto` carries no wire field for this yet (confirmed by reading
     its full definition and doc comment at `ApiService.kt:774-800`, and cross-checking against
     `domain/fare/Tariff.cleaningFeeCap`'s own doc at `FareEngine.kt:125-129`, which explicitly
     says "not per-tariff-configurable server-side today ... every Tariff uses this same
     default"). Rather than fabricate a new literal, this reads
     `au.com.threesixty.cabdispatch.domain.fare.URBAN_TARIFF.cleaningFeeCap.toPlainString()`
     (`SettingsScreen.kt:668`) — the same `$124.14` constant `FareEngine.close()` itself clamps
     every cleaning fee to. This is the "minimal read-only accessor" the brief asked for: a read
     of an existing public engine constant, not a new field and not a change to engine logic
     (`domain/fare/FareEngine.kt` was never edited — confirmed by `git diff`, see Commit below).
   - A one-line tolls note (actual-cost-only, never a "return" toll) — no numeric field, since
     tolls vary per trip; informational text only.

4. **Sydney Airport Fixed Fare section** (`SydneyAirportFixedFareSection`,
   `SettingsScreen.kt:689-703`) — two chips (Standard / Maxi) reading
   `au.com.threesixty.cabdispatch.domain.fare.AIRPORT_FIXED_FARE_STANDARD`/`AIRPORT_FIXED_FARE_MAXI`
   (`FareEngine.kt:176-177`) rather than hardcoding `$60`/`$80` again — same single source of truth
   the engine itself bills from.

Small shared helpers added alongside these: `FareScheduleSectionTitle` (`:575-583`) and
`FareScheduleNote` (`:586-597`) — consistent section-header/body-text styling reused across all
four new panels, following this screen's `CaptainPanel`/`CaptainChip` design language (both already
used elsewhere in Settings, e.g. `PairMeterContent`) rather than inventing new visual primitives.
The screen's inner `Column` also gained `.verticalScroll(rememberScrollState())`
(`SettingsScreen.kt:511-513`) since the added content no longer fits one screen.

## Why no `SettingsViewModel.kt` changes

`loadFareSchedule()` (`SettingsViewModel.kt:283-298`) already fetches the live signed `TariffDto`
via `AppContainer.tariffCache.getActiveTariff(...)` and stores it on `SettingsUiState.fareSchedule`
— every new section reads fields already present on that same DTO instance, or reads the fare
engine's own public constants directly. No new ViewModel state, network call, or field was needed.

## Verification

1. **`./gradlew.bat :app:compileDebugKotlin`** — genuine `BUILD SUCCESSFUL`, zero errors (only
   pre-existing unrelated warnings: `MapboxOfflineRegion.kt` unused param, deprecated-icon warnings
   in `CloseAndPayScreen.kt`/`HiredScreen.kt`/`ProfileScreen.kt`, none touched by this pass). Run
   twice (once before, once after the interruption/resume) with identical results.
2. **`./gradlew.bat :app:testDebugUnitTest`** — genuine `BUILD SUCCESSFUL`, 29/29 tests pass, 0
   failures/errors (confirmed via the JUnit XML under
   `android/app/build/test-results/testDebugUnitTest/`: `FareEngineTest` 19/19,
   `TimeClassResolutionTest` 9/9, `OutboxDrainerTest` 1/1) — unmodified, exactly as before this
   pass, since no engine file was touched.
3. **Live on-device check** — a real Samsung tablet was connected (`adb devices`), the debug APK
   was built and installed, and the app was driven end-to-end: quick-login demo driver → bind to
   vehicle → pre-shift checklist → start shift (tariff shown as "Lilly Cabs urban rank/hail ·
   Ed25519 signed") → Settings → Fare Schedule. Screenshots confirm every new section renders real
   numbers, not `"—"` placeholders:
   - Taxi Fare Hotline: "1800 500 410" + both explanatory lines, at the top of the screen.
   - Existing tariff panel: Hiring charge $5.0000, Peak $2.5600, Distance $2.5200/$2.2900 per km,
     Night $3.0000/$2.7300 per km, Waiting $1.0920/min, Non-cash surcharge cap 5.0000%.
   - Maxi-cab fares: "MAXI RATE 150%" plus the plain-language condition text.
   - Additional charges: "PASSENGER SERVICE LEVY $1.3200", "CLEANING FEE CAP $124.14 + GST", tolls
     note.
   - Sydney Airport Fixed Fare: "STANDARD $60.00", "MAXI $80.00".
   - (Note: the device this pass ran on was shared with another concurrent automated process for
     part of the session — several intermediate screenshots show stray text/navigation from that
     interference, e.g. an unrelated "Bind vehicle" rego getting garbled mid-typing. None of that
     affected the app build actually verified — the final screenshots above were taken immediately
     after a fresh `adb install -r` of this pass's own APK and a clean re-login, and match the
     source code exactly.)

## Commit

Scoped to the one file actually changed:
`android/app/src/main/java/au/com/threesixty/cabdispatch/ui/screens/settings/SettingsScreen.kt`,
plus this doc. `domain/fare/FareEngine.kt` and `domain/FareEngine.kt` were read but never edited —
confirmed via `git status`/`git diff` before committing.
