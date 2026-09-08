# Close & Pay — itemized NSW 2026 compliance display

Follow-up UI pass on top of the fare-engine compliance work described in
`FARE_ENGINE_2026_CHANGES.md` (worktree `D:\cabdispatch\.claude\worktrees\agent-a4b037483707ea90a`,
branch `fareengine-2026-fixed`). That pass fixed the engine (PSL default-on, cleaning-fee cap
enforcement, maxi-multiplier scope, negotiated-total billing) but explicitly left the UI untouched —
"no UI call site sets ... a negotiated-price entry point to anything but their safe defaults ... this
wiring is explicitly a different agent's job." This is that pass, scoped to
`ui/screens/closepay/CloseAndPayScreen.kt` + `CloseAndPayViewModel.kt`, and the equivalent read-only
display fixes in `ui/screens/tripdetail/TripDetailScreen.kt` + `TripDetailViewModel.kt`.

Worktree: `D:\cabdispatch\.claude\worktrees\agent-a48f46afc1867a049`
Branch: `android/battery-network-heartbeat-and-map-fixes`

**No engine/domain files were touched.** `domain/fare/FareEngine.kt` and everything under
`domain/fare/` are exactly as the prior pass left them — this work only reads the real
`FareBreakdown`/`Tariff`/`CloseAndPayUiState` fields those files already expose.

## What changed, file:line

### `android/app/src/main/java/au/com/threesixty/cabdispatch/ui/screens/closepay/CloseAndPayScreen.kt`

- **`TotalCol` rewritten** (`:218-326`) — now takes the full `CloseAndPayUiState.ReadyToClose` +
  `CloseAndPayViewModel` (previously just the `FareBreakdown`) so it can read `tariff`/`includePsl`/
  `surchargePct`/`cleaningFee`/`trip.type` and wire the new toggle/dialog. The count-up total
  animation and premium purple styling are unchanged — only the itemized rows inside the existing
  breakdown card changed.
  - **Negotiated (Set Price) framing** (`:281-283`): when `breakdown.negotiatedTotal != null`, the
    card shows a single "Agreed price (Set Price)" row instead of Flagfall/Fare(distance+time)/Maxi
    — because that's genuinely what was billed (`FareEngine.close()`'s `effectiveFare = negotiatedTotal
    ?: meteredFare`; the metered accrual is computed but discarded when a negotiated total is set).
  - **Maxi-rate line, its own row** (`:288-296`): when `breakdown.maxiRateApplied` (and the trip is
    NOT negotiated), a "Maxi-cab rate (×N, 5+ passengers)" row shows the uplift, computed as
    `(flagFall + peakCharge + distanceCharge + waitingCharge) * (tariff.maxiMultiplier - 1)` — real
    breakdown fields times the tariff's own `maxiMultiplier` field (`FareEngine.kt:121`, currently
    `1.5`), never a hardcoded "×1.5". These four breakdown fields are stored pre-multiplier by the
    engine (`FareEngine.kt:451-454`), so this reproduces exactly what the engine internally computed
    before folding it into `fareTotal`.
  - **PSL line + toggle** (`:300-306`, `PslToggleRow` at `:329-357`): "Point to Point Transport Levy"
    shows `breakdown.psl` (the real `tariff.pslAmount`, `FareEngine.kt:123` = `$1.32`) with a
    `Switch` wired to `CloseAndPayViewModel.setIncludePsl` — the first UI call site for that function
    (it existed with no caller before this pass, per `FARE_ENGINE_2026_CHANGES.md` Fix 6). Suppressed
    entirely (`isAirportFixed` check, `:227,300`) for `trip.type == "airport_fixed"` — **verified, not
    assumed**: `TripFareReconstruction.kt:89-91` sets `state.fixedFare` for that trip type, and
    `FareEngine.kt`'s `fixedFare` branch (`:387-410`) hardcodes `psl = BigDecimal.ZERO` and never reads
    `includePsl` at all, so a toggle there would control nothing real.
  - **Cleaning fee, own line** (`:299`): `breakdown.cleaningFee` now gets its own row when non-zero,
    split out of the old combined `(extras + cleaningFee)` row.
  - **Non-cash surcharge, real percentage** (`:307-310`): shown only when
    `breakdown.surcharge.signum() > 0` (which is exactly the card-family payment methods — see
    `CloseAndPayViewModel.recompute`, surcharge only computes when
    `paymentMethod.persistedValue == "card"`, i.e. Tap-to-Pay/Payment-Link/CabCharge — cash, voucher,
    account and split-fare all persist non-"card" values so this is naturally zero and the row is
    naturally hidden for them, with no extra branching needed). The percentage shown is
    `state.surchargePct` — the live value `CloseAndPayViewModel.selectPaymentMethod` (`:320-340`,
    unchanged by this pass) already computes as `1.5% .min(tariff.surchargePctCap)` — not a
    hardcoded "1.5%".
- **`CleaningFeeEntryRow`** (`:366-401`) — "Report vehicle soiling" entry point, previously
  nonexistent anywhere in the UI (confirmed by the prior pass's audit). Shows the real
  `tariff.cleaningFeeCap` (`$124.14`) as the honest legal maximum, never implying a driver can charge
  more.
- **`CleaningFeeDialog`** (`:409-465`) — mirrors `HiredScreen.kt`'s `CustomTollDialog` shape/keypad
  pattern (same amount-typed-as-cents convention, same `CaptainKeypad`/`CaptainButton` styling) for
  visual consistency with the app's other "type an amount" flows. Shows a live "will be capped"
  warning if the typed amount exceeds the cap, though the actual clamp happens in the ViewModel (see
  below) so the UI warning is informational, not the only safeguard. Includes a "Remove cleaning fee"
  action when one is already set.
- **`MethodPickerScreen`** (`:469-527`): hoists `showCleaningDialog` state and renders the dialog via
  the existing `CaptainDialogScrim` pattern (same as `HiredScreen.kt`'s toll dialog). Added the TTSS
  caption (`:492-496`): "TTSS/CabCharge trips remain fare-regulated and metered even when arranged as
  a booking" — a one-line informational note under the CabCharge/TTSS payment cards, no new logic.

### `android/app/src/main/java/au/com/threesixty/cabdispatch/ui/screens/closepay/CloseAndPayViewModel.kt`

- **`setCleaningFee` now also caps at the tariff's cap** (`:351-357`), not just `coerceAtLeast(ZERO)`
  as before. This is a genuine small fix beyond pure UI wiring, flagged explicitly: previously only
  the *computed* `FareBreakdown.cleaningFee` (used for the on-screen total) was capped, via
  `FareEngine.close()`'s own defensive clamp — but the *raw* `CloseAndPayUiState.ReadyToClose.cleaningFee`
  value this screen persists to the backend on close (`finalizeClose`'s
  `cleaningFee = state.cleaningFee...toPlainString()`) was never itself capped, so a driver typing an
  over-cap figure in the (now-added) dialog would have had that raw over-cap number round-trip to the
  server even though the on-screen total correctly showed the capped amount. Fixed at the ViewModel
  layer only — no engine/domain change.

### `android/app/src/main/java/au/com/threesixty/cabdispatch/ui/screens/tripdetail/TripDetailScreen.kt`

`TripDetailViewModel` already derives its `FareBreakdown` via the same
`reconstructFareState()` + `FareEngine.close()` pair `CloseAndPayViewModel` uses (confirmed by
reading both files), so PSL/cleaning-fee/extras/surcharge lines were **already correctly itemized
and conditioned on `signum() > 0`** before this pass — no change needed there. Two display-layer
gaps genuinely didn't flow through for free and needed real UI work:

- **Maxi-rate line splitting** (`:242-250`): same derivation as `CloseAndPayScreen.kt`'s `TotalCol`
  (pre-multiplier breakdown fields × the trip's own `tariff.maxiMultiplier`). Needed
  `TripDetailUiState.Loaded` to gain a `tariff: Tariff` field (`TripDetailViewModel.kt:33-37`,
  populated at `:98` from the same `tariff` already loaded for `reconstructFareState`) — this
  previously wasn't stored on the UI state at all, only used transiently inside `load()`.
- **Negotiated-price framing** (`:235-241`): "Agreed price (Set Price)" replaces the
  Flagfall/Peak/Distance/Waiting rows when `breakdown.negotiatedTotal != null` — mirrors
  `CloseAndPayScreen.kt` exactly, same reasoning (the metered rows would otherwise show numbers that
  contradict what was actually billed for a Set Price trip).
- **Surcharge percentage** (`:255-261`): now shows "Non-cash payment surcharge (N%)" using
  `trip.surchargePct` (a field already persisted on `TripEntity` at close time), not just "Non-cash
  surcharge" with no percentage as before.
- **PSL label**: relabelled from "PSL levy" to "Point to Point Transport Levy" for consistency with
  the Close & Pay screen's wording — the same `signum() > 0` gating (already correct) was kept as-is.

## The new itemized layout (Close & Pay)

Top-to-bottom, inside the existing "TOTAL DUE" purple panel + breakdown card:

1. Big count-up **TOTAL DUE** figure (unchanged, existing premium-pass animation).
2. Either:
   - **metered trip**: Flagfall → Fare (distance + time) → *Maxi-cab rate (×N, 5+ passengers)* (only
     when applicable), or
   - **negotiated trip**: a single **Agreed price (Set Price)** row.
3. Tolls (unchanged, unconditional as before).
4. Extras (only if non-zero).
5. **Cleaning fee** (only if non-zero) — its own row now, previously folded into Extras.
6. **Point to Point Transport Levy** + a `Switch` toggle (suppressed entirely for Sydney Airport
   Fixed Fare trips) — the real `$1.32`/whatever the active tariff's `pslAmount` is, live-toggleable.
7. **Non-cash payment surcharge (N%)** — only shown for card-family payment methods, with the real
   live percentage.
8. GST included (unchanged).
9. A one-line caption for negotiated trips explaining tolls/PSL/cleaning/surcharge still apply on
   top of the agreed amount.
10. **"Report vehicle soiling"** entry row → opens the cleaning-fee dialog.

The TTSS/CabCharge payment cards on the method-picker screen now carry a one-line caption that they
remain fare-regulated and metered even when booked.

## Verification performed

1. **`./gradlew.bat :app:compileDebugKotlin` from `android/`** — genuine `BUILD SUCCESSFUL` (only
   pre-existing warnings: the harmless Kotlin-daemon `dirty-sources.txt` fallback, and a handful of
   pre-existing deprecated-icon/unused-parameter warnings unrelated to this pass, matching exactly
   what `FARE_ENGINE_2026_CHANGES.md` already documented as pre-existing). Re-ran a second time after
   a mid-session interruption to reconfirm — still `BUILD SUCCESSFUL`, `UP-TO-DATE` on every task,
   confirming no work was lost.
2. **`./gradlew.bat :app:testDebugUnitTest`** — genuine `BUILD SUCCESSFUL`, all 29 tests pass, 0
   failures, 0 errors (`FareEngineTest` 19/19, `TimeClassResolutionTest` 9/9, `OutboxDrainerTest`
   1/1 — confirmed via the JUnit XML reports under `app/build/test-results/testDebugUnitTest/`), byte
   for byte the same test counts as the prior engine pass reported. Expected and correct: this pass
   never touched `domain/fare/` or any other engine/domain file.
3. **Live device walkthrough — partially completed, honestly reporting where it stopped.** A real
   device was connected (`adb devices` → `R52TB07AQVL`, a Samsung SM-T575 tablet on Android 13).
   `./gradlew.bat :app:installDebug` succeeded and the app launched cleanly with no crash. Using the
   debug-only "QUICK LOGIN (DEMO DRIVER)" shortcut
   (`ui/screens/login/LoginVehicleBindViewModel.kt:108-116`), sign-in against the real configured
   backend succeeded — confirmed via `adb logcat`, a genuine JWT `access_token`/`refresh_token` pair
   came back for `driver@lillycabs.test`. Progressed through vehicle binding (manual rego entry — the
   real QR scanner, per the recent "Replace StubQrScanner" commit, auto-launches on this screen and
   gracefully falls back to "No code detected — tap to try again, or use manual entry" when no
   physical QR is present, exactly as it should) and the pre-shift safety-inspection checklist.
   Beyond that point, blind `adb shell input tap` automation against this specific device proved
   unreliable — `adb logcat` showed duplicate/drifting pointer-down events for single tap commands
   (`ViewPostIme pointer 0` / `pointer 1` for one `input tap` call), causing taps to land on
   unintended targets (extra characters typed into text fields, an unintended tap re-triggering the
   camera QR scanner) rather than reliably reaching Start Shift → a trip → Close & Pay. This is a
   device/tooling automation limitation, not a defect surfaced in the code under test — the app
   itself never crashed or misbehaved on its own. **I did not reach Close & Pay on-device this
   session**, so the PSL-toggle-drops-total-by-exactly-$1.32 / cleaning-fee-cap /
   card-surcharge-line checks from the brief's own verification step were not exercised live. The
   compile+test verification above is genuine and passing; the live walkthrough should be finished
   by a human tester or a session with more reliable touch-input tooling for this hardware.

## Gaps found — flagged as backend-requirements candidates, not fixed here

None found that require a new backend/wire field. Every value this pass needed
(`Tariff.pslAmount`/`.surchargePctCap`/`.cleaningFeeCap`/`.maxiMultiplier`,
`FareBreakdown.maxiRateApplied`/`.negotiatedTotal`/`.psl`/`.cleaningFee`/`.surcharge`,
`CloseAndPayUiState.ReadyToClose.surchargePct`/`.includePsl`/`.cleaningFee`,
`TripEntity.surchargePct`/`.type`) already existed on the real domain/wire types from the prior
fare-engine pass — nothing here needed a placeholder or an invented field.

One pre-existing gap noted but deliberately NOT changed (out of scope for a UI pass, and not a
compliance bug): `PaymentMethodOption.CABCHARGE`'s own doc already flags that CabCharge/TTSS is
persisted server-side as generic `"card"` (the backend's `payment_method` enum has no CabCharge-
specific value yet) — this pass's new TTSS caption is purely informational and doesn't change that
persistence behavior.
