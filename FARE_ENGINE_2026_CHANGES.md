# Fare engine — Point to Point Transport (Fares) Order 2026 compliance pass

Plain-terms summary of what changed in the Android fare engine (`android/`) to bring it into
compliance with the **Point to Point Transport (Fares) Order 2026** (effective 1 June 2026),
replacing the outdated "Fares Order 2025 (no.2)" rate card and fixing several latent compliance
bugs. Engine-layer and persistence-layer work only — no UI screens were touched (two other agents
build UI on top of this).

Worktree: `D:\cabdispatch\.claude\worktrees\agent-a4b037483707ea90a`
Branch: `fareengine-2026-fixed`, based directly on the real tip of
`android/battery-network-heartbeat-and-map-fixes` (commit `d9b2023`, the "Captain Taxis" purple
redesign checkpoint). An earlier commit on this worktree's original branch had accidentally
branched from a stale snapshot of `main` instead of that real tip — corrected by cherry-picking the
isolated fare-engine commit (16 files, 854 lines) onto the real `d9b2023` base and re-resolving the
handful of conflicts by hand (see Verification below).

## 1. New 2026 rate card

`android/app/src/main/java/au/com/threesixty/cabdispatch/domain/fare/FareEngine.kt:148-176`
(`URBAN_TARIFF`/`COUNTRY_TARIFF`), mirrored server-default-wise in
`android/app/src/main/java/au/com/threesixty/cabdispatch/data/remote/ApiService.kt`'s `TariffDto`
doc comment (its actual rate-field defaults were already all mandatory/no-literal-2025-number, so
no field values needed to change there — see Risk Notes).

| Component | Urban 2025→2026 | Country 2025→2026 |
|---|---|---|
| Flag fall | $5.00 → **$5.17** | $5.11 → **$5.29** |
| Peak Time Hiring Charge | $2.56 → **$2.65** | n/a |
| Distance rate, first 12km | $2.52 → **$2.61**/km | $2.41 → **$2.49**/km |
| Distance rate, beyond 12km | $2.29 → **$2.37**/km | $3.30 → **$3.41**/km |
| Night rate, first 12km | $3.00 → **$3.10**/km | $2.87 → **$2.97**/km |
| Night rate, beyond 12km | $2.73 → **$2.82**/km | $3.93 → **$4.07**/km |
| Holiday rate, first 12km | n/a | $2.87 → **$2.97**/km |
| Holiday rate, beyond 12km | n/a | $3.93 → **$4.07**/km |
| Waiting rate | 109.2c → **113.0c**/min | 104.5c → **108.1c**/min |
| Cleaning fee cap | (unconstant before) → **$124.14** | same |
| Airport Fixed Fare (std/maxi) | **$60 / $80 — unchanged** | — |
| Sydney Airport toll preset | $6.30 → **$6.43** | — |

The old 2025 rate card is kept as a **comment only** (changelog reference) at the top of
`FareEngine.kt` — not live, not reachable from any code path. The Airport toll change is in
`android/app/src/main/java/au/com/threesixty/cabdispatch/domain/TripModels.kt:20-22`
(`TollPresets.AIRPORT`). Cleaning fee cap is a new `Tariff.cleaningFeeCap` field
(`FareEngine.kt:141`, default `$124.14`) — `FareEngine.close()` now clamps any requested cleaning
fee to this cap on both the metered and Sydney Airport Fixed Fare paths (`FareEngine.kt:377`,
`382`, `434`).

## 2. Fix 1 — Maxi multiplier only applies to "the fare"

`FareEngine.kt`'s `close()` (`~line 407-450`) now computes `meteredFare = flagFall + peakCharge +
accruedDistanceCharge + accruedWaitingCharge`, multiplies **only that** by 1.5 when the maxi rate
applies, then adds tolls/PSL/extras/cleaning fee on top unmultiplied. Previously the whole subtotal
(tolls/PSL/cleaning fee included) got multiplied. The Sydney Airport Fixed Fare branch is
unaffected — it short-circuits to the flat $60/$80 figure and never multiplies it again.

## 3. Fix 2 — Maxi eligibility is now a derived, uncoercible flag

`FareState` (`FareEngine.kt:210-271`) replaces the old raw `maxi: Boolean` with four inputs —
`isMaxiVehicle`, `passengerCount` (default 1), `wheelchairHiring` (default false),
`airportRankRequestedMaxi` (default false) — and a **computed, non-settable** property:

```kotlin
val maxiRateApplied: Boolean
    get() = isMaxiVehicle && !wheelchairHiring && (passengerCount >= 5 || airportRankRequestedMaxi)
```

`FareBreakdown.maxiApplied` is renamed to `maxiRateApplied` (no other call sites existed outside
`FareEngine.kt` and its test — confirmed by a full-codebase grep, so this was a clean rename, not a
two-fields reconciliation).

Persistence: `TripEntity.maxi` (`data/local/entity/TripEntity.kt:60-75`) now means "vehicle has 5+
seats excl. driver" (i.e. feeds `isMaxiVehicle`), with two new columns —
`passengerCount: Int = 1` and `wheelchairHiring: Boolean = false` — added alongside it (Room DB
version bumped 5→6 in `AppDatabase.kt`, no migration needed per this project's established
pre-release convention). `TripCreateDto`/`TripDto`/`TripSyncItemDto`
(`data/remote/ApiService.kt`) each gained matching nullable `passenger_count`/`wheelchair_hiring`
wire fields. `TripRepository.openTrip(...)` (`data/repository/TripRepository.kt:59-99`) gained
`passengerCount`/`wheelchairHiring` parameters, both defaulted so `HiredViewModel`'s existing call
site keeps compiling/behaving unchanged (no UI wiring done — that's explicitly the next agent's
job). `TripFareReconstruction.kt` reads all three fields (`isMaxiVehicle`, `passengerCount`,
`wheelchairHiring`) back into `FareState` on reconstruction, and the Sydney Airport Fixed Fare
branch now selects `airportFixedFare(state.maxiRateApplied)` instead of the raw vehicle flag.

## 4. Fix 3 — Round DOWN, never up, for the fare total

`FareEngine.kt:78` adds `roundDownToCent()` (`RoundingMode.DOWN`), used for `FareBreakdown.fareTotal`
only (`FareEngine.kt:439`). The non-cash surcharge and GST-component figure keep `roundHalfUp`
(`RoundingMode.HALF_UP`) exactly as before — that's the Fares Order's own explicit surcharge rule
(cl 4(a)), untouched.

All 9 existing golden vectors (A-I) were hand-recomputed against the new rates — see the comment
above each test in `FareEngineTest.kt` showing the arithmetic. Test F (maxi trip) and the new
test O both land on a genuine round-down-vs-round-half-up divergence (e.g. O: raw subtotal
`99.505` → this engine now charges `99.50`, not the `99.51` the old rule would have produced) —
not contrived, this is a live demonstration that the rule actually changed behaviour.

## 5. Fix 4 — Public holiday calendar + fixed peak-charge condition

New file `android/app/src/main/java/au/com/threesixty/cabdispatch/domain/NswPublicHolidays.kt` — a
`Set<LocalDate>` of gazetted NSW public holidays (2026 actual, 2027 calculated from the standard
fixed rules — see Risk Notes), with `isPublicHoliday()`/`isDayBeforePublicHoliday()`. Deliberately
excludes the NSW Bank Holiday (public-sector-only, not a general public holiday).

`domain/FareEngine.kt` (the live 1Hz ticking engine) now derives `timeClass`/`isPeak` at
`startTrip()` via two new **top-level, testable** functions:

- `resolveTimeClassFor(now: ZonedDateTime, area: AreaClass): TimeClass` (`FareEngine.kt:241-249`) —
  10pm-6am is always `NIGHT`; for `COUNTRY` only, 6am-10pm on a Sunday or gazetted public holiday
  is `HOLIDAY` (urban never returns `HOLIDAY` — it has no holiday rate); otherwise `DAY`.
- `resolveIsPeakFor(now: ZonedDateTime): Boolean` (`FareEngine.kt:260-265`) — 10pm-6am on a Friday,
  Saturday, or the night before a gazetted public holiday.

`FareEngineImpl`'s private `resolveTimeClass(area)`/`resolveIsPeak()` (`FareEngine.kt:223,227`) are
now thin `ZonedDateTime.now()`-supplying wrappers around these, so the actual classification logic
is unit-testable without faking the system clock. `startTrip()` (`FareEngine.kt:118-121`) now
determines `area` from `TariffDto.region` and passes it through.

New test file `TimeClassResolutionTest.kt` (9 tests) proves: a Thursday night before a gazetted
Friday public holiday gets the peak charge; an ordinary Thursday night doesn't; Friday/Saturday
nights always get it; a country Sunday daytime trip gets `HOLIDAY`; an urban Sunday daytime trip
stays `DAY`; a country public holiday (non-Sunday) daytime trip also gets `HOLIDAY`; 10pm-6am is
always `NIGHT` regardless of area/day. `FareEngineTest.kt`'s new tests P/Q additionally prove the
**fare-math consequence** of these classifications (peak charge applied / holiday rates applied)
at the pure-engine level.

## 6. Fix 5 — Fares Order maximum validator wired into tariff ingestion

`sync/TariffCache.kt`'s `refresh()` (`line 71-92`) now calls a new private
`validateFaresOrderOrThrow(dto)` (`line 127-140`) immediately after signature verification and
before ever caching the tariff. It maps `TariffDto.region` to the matching reference tariff
(`URBAN_TARIFF`/`COUNTRY_TARIFF`), skips the check entirely when `TariffDto.booked == true` (per
the Order's booked-fare deregulation), and — on a `FaresOrderViolation` — rethrows as
`TariffSignatureException`, the exact same exception type a failed signature check throws, so it's
rejected identically: never written to Room, previous verified-and-valid cached tariff stays in
place.

## 7. Fix 6 — PSL on by default

`ui/screens/closepay/CloseAndPayViewModel.kt:252,260` — `loadTariffAndInit()`'s default `includePsl`
changed from `false` to `true` (still overridable via the existing `setIncludePsl()`, a UI toggle
for which is a future pass). Confirmed the Sydney Airport Fixed Fare branch of `FareEngine.close()`
never reads `includePsl` at all regardless of value (was already true before this pass; verified,
not assumed — see `FareEngineTest.testG`).

## 8. Fix 7 — Negotiated ("Set Price") trips bill the agreed amount

`FareState` gained `negotiatedTotal: BigDecimal?` (`FareEngine.kt:266`), read from
`TripEntity.negotiatedTotal` in `TripFareReconstruction.kt` (already-existing field — it was
recorded but never consumed by `close()` before this pass). In `close()`
(`FareEngine.kt:432-434`): `effectiveFare = negotiatedTotal ?: meteredFare` — when a negotiated
total is set, it (not the metered flagfall+peak+distance+waiting accrual) is what
`fareTotal`/`grandTotal` are built from; tolls/PSL/extras/cleaning fee/surcharge/GST all still
compute and add on top exactly as for a metered trip. The metered accrual is untouched and still
reported on `FareBreakdown.flagFall`/`.distanceCharge`/`.waitingCharge`/`.peakCharge` for
reference/display. New `FareBreakdown.negotiatedTotal: BigDecimal?` field surfaces which amount was
actually billed. Both `CloseAndPayViewModel` and `TripDetailViewModel` derive their `FareBreakdown`
from the same `reconstructFareState()` + `FareEngine.close()` pair, so this fix applies to both
screens' displays with no duplicated logic. Golden test R proves the $25/$6.43-toll/PSL-on scenario
from the brief bills `$32.75`, not the (much larger) metered accrual.

## 9. Golden test suite

`android/app/src/test/java/au/com/threesixty/cabdispatch/domain/fare/FareEngineTest.kt` — tests A-I
recomputed by hand for the 2026 rates/rounding rule (arithmetic shown in a comment above each);
new tests J-S added:

- **J-N**: the full maxi-eligibility matrix (5+ pax; 4 pax not eligible — hard cutoff; wheelchair
  overrides pax count; airport-rank request independent of pax count; non-maxi vehicle never
  eligible regardless of pax count).
- **O**: dedicated rounding-down boundary case (`99.505` → `99.50`, contrasted against the old
  `99.51` half-up result).
- **P**: peak charge on a Thursday night before a gazetted Friday public holiday.
- **Q**: country Sunday daytime trip gets `HOLIDAY` time class and holiday rates.
- **R**: negotiated fare billing (the $25/$6.43-toll/PSL-on scenario).
- **S**: Fares Order validation rejects an over-max tariff at the ingestion wrapper's logic level
  (see Risk Notes for why this is a focused unit test rather than a full `TariffCache.refresh()`
  integration test).

New `TimeClassResolutionTest.kt` (9 tests, `domain` package) — see Fix 4 above.

`OutboxDrainerTest.kt` was not modified and does not depend on anything this pass changed
(`TripRepository.openTrip`'s new params are both defaulted, so its existing call site is
unaffected). Confirmed unaffected by inspection; could not be executed via Gradle in this
environment — see Verification below for why, and what was actually run instead.

## Verification actually performed

**Worktree-base correction (see header):** this worktree's branch had originally been created from
a stale snapshot of `main`, not the real `android/battery-network-heartbeat-and-map-fixes` tip —
this was caught (by the orchestrating session) after an initial pass already reported "~25
pre-existing compile errors", which turned out to be compiling against pre-redesign files that
don't reflect the real current app at all. Corrected by: fetching the real branch tip (`d9b2023`)
from the main checkout, isolating this task's own commit as a clean diff against its own immediate
parent (16 files, 854 lines — confirmed via `git diff <my-commit>^ <my-commit>`, which excludes two
unrelated stale commits that had been sitting underneath it), creating a new branch at `d9b2023`,
and `git cherry-pick -n`-ing that isolated commit onto it. Four files hit real merge conflicts
against independent base drift:
- `network_security_config.xml` and `domain/format/TripDisplayFormat.kt`/
  `ui/screens/earnings/StatGrid.kt`: the real branch tip had **already independently fixed the same
  two pre-existing bugs** this pass had fixed against the stale base (the invalid `--` XML comment;
  the nested-comment-lexer doc-comment corruption) — took the real base's own fix in all three
  cases (confirmed byte-identical to `d9b2023` afterward, zero diff).
- `domain/FareEngine.kt` (the live ticking engine): the real branch tip had independently done its
  own "consolidation pass" delegating `FareEngineImpl`'s math entirely to the pure
  `domain.fare.FareEngine`/`FareState` this task already modifies, and had independently fixed the
  night-boundary bug in `resolveTimeClass()` — but had NOT added holiday-calendar awareness. Merged
  by hand: kept the real base's richer class-level doc history (the "consolidation pass"/"night
  boundary" narrative, genuinely valuable content), and layered this task's `area`-aware
  `resolveTimeClass(area)` + top-level `resolveTimeClassFor`/`resolveIsPeakFor` functions on top,
  dropping one now-dead `toBigDecimalOrZero()` helper the real base's consolidation had already
  made unnecessary (nothing else referenced it).

Post-merge, confirmed the resulting diff against `d9b2023` touches **exactly this task's own 13
fare-engine files** (854 insertions / 159 deletions, `git diff --stat d9b2023 -- android/`) — no
`main`-only backend/dashboard/docs content, no unrelated UI file, nothing outside this task's own
scope.

1. **`./gradlew.bat :app:compileDebugKotlin` — genuine `BUILD SUCCESSFUL`.** The real branch tip
   compiles clean, exactly as expected. Only pre-existing warnings remain (an unused
   `MapboxOfflineRegion.kt` parameter, a few deprecated-icon warnings in `CloseAndPayScreen.kt`/
   `HiredScreen.kt`/`ProfileScreen.kt`, none of it touched by or related to this pass) — zero
   errors. (The Kotlin-daemon "dirty-sources.txt used by another process" warning did appear, as
   flagged as known-harmless in the original task brief — falls back to non-daemon compile and
   still reports `BUILD SUCCESSFUL`.)
2. **`./gradlew.bat :app:testDebugUnitTest` — genuine `BUILD SUCCESSFUL`, all 29 tests pass, 0
   failures, 0 errors** (confirmed via the JUnit XML reports under
   `android/app/build/test-results/testDebugUnitTest/`, not just the task's own exit code):
   - `FareEngineTest`: 19/19 (golden vectors A-S, including test S now genuinely exercising the
     real `data/remote/ApiService.kt`'s `TariffDto` end to end).
   - `TimeClassResolutionTest`: 9/9.
   - `OutboxDrainerTest`: 1/1 — the real branch tip's own independently-updated version of this
     test (121 lines of base drift, none of it touched by this pass) still passes unmodified
     alongside this pass's changes.
3. Independently re-verified every day-of-week/holiday-calendar claim used in the tests and in
   `NswPublicHolidays.kt` against Python's `datetime` (an independent authority) before this
   correction — every single one matched exactly (Good Friday 2026-04-03, Christmas 2026-12-25
   Friday → Boxing Day 2026-12-26 Saturday → extra holiday 2026-12-28 Monday, etc.) — unaffected by
   the rebase, since none of that logic/content changed.
4. Grepped the whole module for `maxiApplied` (zero hits — confirmed clean rename to
   `maxiRateApplied`), `roundHalfUp`/`roundDownToCent` (confirmed `fareTotal` is the only caller of
   the new down-rounding function; every other caller is unchanged), `includePsl` (confirmed
   `TripEntity`'s own field default of `false` is a separate, harmless thing — it's always
   overwritten from the ViewModel's now-`true` default at `closeTrip()` time), and `openTrip(`
   (confirmed `HiredViewModel`'s only call site passes every new parameter's default, so its
   behaviour is unchanged, per the brief).

## Risk notes — flagging what I was unsure about

- **2027 public holiday dates are calculated, not gazetted.** NSW hasn't published an official 2027
  public holidays list yet (this far out, it wouldn't have). I computed all 2027 dates from the
  standard fixed rules this state has applied consistently for years (Easter via the standard
  computus, King's Birthday = 2nd Monday of June, Labour Day = 1st Monday of October, and the
  "falls on a weekend → extra public holiday on the next available weekday" convention for
  Christmas/Boxing Day) and independently verified every resulting day-of-week against Python's
  `datetime`, but a government can occasionally vary from its own usual rule for a specific year.
  `NswPublicHolidays.kt` carries an explicit `TODO` to re-verify against the real gazette once
  published. 2026 dates are the actual gazetted holidays and are not in question.
- **Tolls excluded from the maxi multiplier**: per the plan, tolls/PSL/extras/cleaning fee are
  deliberately excluded from the 150% maxi multiplier — only flagfall+peak+distance+waiting ("the
  fare") is multiplied. I'm confident this matches the Fares Order's wording ("the fare"
  specifically, not the full charged amount), but flagging that I have not seen an explicit,
  named legal opinion on this point beyond the plan's own instruction to implement it this way.
- **Cleaning fee cap enforcement was my own extension of the ask.** The brief asked for a
  `cleaningFeeCap` field on `Tariff`; I additionally chose to have `FareEngine.close()` actually
  clamp any requested cleaning fee to that cap (on both the metered and airport-fixed paths),
  since leaving the cap unenforced anywhere felt like it would defeat the point of adding it. This
  is a small behavioural addition beyond the literal ask — flagging it explicitly rather than
  burying it.
- **`FareEngineTest.testS`** exercises the same `validateAgainstFaresOrder()` call and region-dispatch
  logic `TariffCache.validateFaresOrderOrThrow()` uses, but does not go through
  `TariffCache.refresh()` itself. A true ingestion-level test through the real `TariffCache` needs
  `Ed25519TariffSignatureVerifier`, which calls `android.util.Base64` — this project has no
  Robolectric/instrumented test setup (confirmed: not a dependency, and `OutboxDrainerTest.kt`'s own
  doc comment already documents this exact constraint for itself), so that class can only be
  exercised in a real Android runtime. This matches the task's own stated fallback ("a focused unit
  test on the validation call site's wrapper function") rather than a workaround I invented.
- **No UI call site sets `passengerCount`/`wheelchairHiring`/a negotiated-price entry point to
  anything but their safe defaults.** Per the brief, that wiring is explicitly a different agent's
  job — this pass only makes the engine and persistence layer correct and ready to receive real
  values.
- **`gradlew`/`gradlew.bat`/`gradle-wrapper.jar` and `local.properties` were added to this worktree**
  (copied from the sibling checkout at `D:\cabdispatch\android\`) purely so a real build/test could
  be attempted at all — they were never tracked in git history on any branch (confirmed via
  `git ls-tree`), so this doesn't touch anything a `git status`/diff would show as a code change.
