# Driver-facing UI audit — Captain Taxis Android meter (2026-09-08)

Owner's brief, verbatim: *"When driver logs in and lands on the dashboard screen, it looks like shit. I want beautiful animations / UI like a game interface. The top bar should be compact. The Live Dispatch / announcements section shows empty — if it's empty why are we showing it? Make the dashboard more accessible with all features."*

All paths relative to `android/app/src/main/java/au/com/threesixty/cabdispatch/`.

## 0. Which home screen is live

`ui/navigation/CabDispatchNavHost.kt:176-183` — route `IDLE` renders **`DeckHomeScreen`**. `CabDispatchNavHost.kt:184-194` — route `HIRED` renders the *same* composable with `startOnMeter = true`. `postAuthDestination()` (`CabDispatchNavHost.kt:339-371`) sends a logged-in driver to `IDLE`. So **`ui/screens/dashboard/DeckHomeScreen.kt` is the post-login home. Nothing else is.**

Confirmed dead code (no live reference outside itself/other dead files):

| File | Lines | Status |
|---|---|---|
| `ui/screens/dashboard/WheelDashboardScreen.kt` | 1264 | **Dead.** Only referenced from KDoc comments (`CabDispatchNavHost.kt:68,107,180`; `DuressOverlays.kt:48,196`; `MapboxOfflineRegion.kt:26`). |
| `ui/screens/dashboard/HomeDashboardV2.kt` | 641 | **Dead.** `HomeDashboardV2ChromeOverlay` is called only from `WheelDashboardScreen.kt:213`. |
| `ui/screens/dashboard/DockScreenChromeV2.kt` | 664 | **Dead.** Zero callers anywhere. |
| `ui/wheel/WheelState.kt`, `WheelGeometry.kt`, `WheelGesture.kt` | 449 | **Dead** (only the wheel dashboard drove them). |
| `ui/deck/DeckChrome.kt` (`DeckStatusStrip`, `DeckNavRail`, `DeckNavRailCarousel`) | 380 | **Dead** — internally self-referential only. `ui/deck/DeckWidgets.kt`'s `DeckKeypad` **is** live (`DeckHomeScreen.kt:2538`). |
| `ui/theme/WheelColorsV2.kt` | 109 | **Dead** — only `DockScreenChromeV2` reads it. |
| `ui/theme/Theme.kt`'s `WheelColors` object (`Theme.kt:48-81`) | — | Effectively dead for the home screen; `CabDispatchTheme` (`Theme.kt:129-149`) is live. |
| `ui/screens/zones/PlotZoneScreen.kt`, `ZoneStatisticsScreen.kt` | 978 | Routes registered (`CabDispatchNavHost.kt:270-275`) but **nothing navigates to them** — their content is re-hosted as tabs in `ZonesPaneContent` (`ZonesPaneContent.kt:36-43`). |

**~3,500 lines of dead UI** are sitting in the tree. Deleting them is a prerequisite for a sane redesign, otherwise the next agent will edit the wrong file (this has already happened once — see the reconciliation notes in `CabDispatchNavHost.kt:68`).

### The canvas is 1280×800dp, not 1920×1200

`MainActivity.kt:75-125` — `FixedDesignCanvas` overrides `LocalDensity` so `scale = safeAreaWidthPx / 1280f`. On the SM‑T575 (1920×1200, kiosk-pinned so insets are 0) the *entire app* lays out on exactly **1280 × 800 dp**. Every `dp` below is in that space.

**Latent bug:** the scale is derived from **width only** (`MainActivity.kt:118`). On a 16:9 tablet (1920×1080) the canvas becomes 1280×**720**dp, the dashboard content pane drops to ~381dp, and `MeterDial`'s hardcoded `.size(414.dp)` (`DeckHomeScreen.kt:1275`) no longer fits — the dial gets clipped. Same for any device with visible nav bars eating the safe area.

---

## 1. THE HOME SCREEN — composable tree, data, and space

Entry: `DeckHomeScreen(navController, viewModel = WheelDashboardViewModel, dispatchViewModel = AvailableTripsWheelViewModel, startOnMeter)` — `DeckHomeScreen.kt:282-695`.

State sources:
- `state` = `WheelDashboardViewModel.uiState` (`WheelDashboardViewModel.kt:140-142`): session, isAvailable, availabilityError, todayStats, tariff, status strip.
- `dispatchState` = `AvailableTripsWheelViewModel.uiState` (`AvailableTripsWheelViewModel.kt:64-65`).
- `activeTrip` = Room `observeActiveTrip()` (`DeckHomeScreen.kt:310`) → `hasActiveTrip`.
- `homeExtras` = `rememberHomeExtras(...)` (`DeckHomeScreen.kt:326`, impl `778-813`) — 4 fire-and-forget REST calls.
- `pendingTrip` = `SessionHolder.pendingTrip` (`DeckHomeScreen.kt:330`).
- `duressState` (`DeckHomeScreen.kt:325`).

Root stack (`DeckHomeScreen.kt:384-694`):
1. `TechGridBackdrop()` — `390`, impl `721-740`. Static Canvas, 64dp grid at 5% alpha, no animation.
2. Two 760dp / 620dp radial glow blobs at TopStart / BottomEnd — `395-408`.
3. `Column` → `CaptainHeader` / error banner / 1dp divider / content `Row` / nav rail.

### 1.1 Header — `CaptainHeader`, `DeckHomeScreen.kt:860-1010`

| Element | Line | Data | Empty behaviour |
|---|---|---|---|
| `DriverAvatar` 88dp in a `neonGlow` halo | `889-891` (impl `CaptainWidgets.kt:84-110`) | `GET /v1/users/{id}/photo` → Bitmap | falls back to 2-letter initials, else `"—"` |
| "CAPTAIN / TAXIS" wordmark | `892-895` | hardcoded | — |
| Driver name 28sp | `899-905` | `session.driverName` | `"No driver"` |
| Rego, RobotoMono 15sp | `906-919` | `session.vehicleId` | `"—"`. Comment at `907-913`: no make/model field exists |
| VERIFIED pill 40dp | `925-940` | `homeExtras.verified` ← `GET /v1/auth/me` `suitabilityStatus == "clear"` | **hidden entirely** when null/false |
| Status pill 64dp (HIRED/AVAILABLE/ON BREAK/OFF DUTY) | `945-976`, enum `838-858` | `hasActiveTrip` / `isAvailable` / `shiftId != null` | never empty; tap = `setAvailable(!isAvailable)` |
| GPS / WI-FI / PRINTER / battery strip, 56dp glass | `985-1007`, `StatusDot` at `1055-1079` | `state.status` polled every **4000ms** (`WheelDashboardViewModel.kt:36,156-161,302-319`) | battery `"—"` if unreadable |
| `SosControl` 72dp + "HOLD" caption | `1008` (impl `CaptainWidgets.kt:150-178`) | long-press only → `duressController.trigger` | always visible, always breathing |

**Height:** 88dp (avatar) + `vertical = 20.dp` ×2 = **≈128dp of an 800dp canvas — 16% of the screen** before a single piece of content. This is the "top bar should be compact" complaint. It publishes itself via `.reportsChromeHeader()` (`882`, impl `ChromeMetrics.kt:99-104`) so every app-level banner sits at `headerHeight + 10dp` (`ChromeMetrics.kt:51`).

Also: a `Spacer(weight(1f))` at `977` between the status pill and the system strip — ~200dp of pure void in the middle of the header.

**Availability error banner** — `429-451`. `AnimatedVisibility` fade, full width.

### 1.2 Content row — `DeckHomeScreen.kt:453-636`

`Row(weight(1f), padding(top=20, start=32, end=12, bottom=20))`. Width math on the 1280dp canvas:

```
1280 − 32 − 12                = 1236 inner
1236 − 116 (rail) − 12 (gap)  = 1108 left column
1108 − 660 (MeterCard) − 16   =  432 right column
```
Height math:
```
800 − 128 (header) − 1 (rule) − 20 − 20 − 18 (spacer) − 152 (stats bar) = 461dp content pane
```

The comment at `DeckHomeScreen.kt:476-479` claims the right column is "~316dp wide" — **stale**, it is 432dp.

#### MeterCard — `DeckHomeScreen.kt:458-471`, impl `1085-1136`
`Modifier.width(660.dp).fillMaxHeight()` — a hard 660dp (53% of screen width) devoted to one dial. Inside (`1106-1134`), an absolute-positioned `Box`:
- `NightFareTile` TopStart, `width(156.dp)` — `1107`, impl `1141-1185`. "NIGHT FARE" / `nightMultiplierLabel(tariff)` 32sp / **hardcoded `"10:00 PM – 6:00 AM"`** at `1176-1183`. The multiplier is *real* (`nightRate1 / distRate1`, `1187-1194`); shows `"—"` when tariff is null. Lines `1167-1175` record that the app's own `FareEngine` disagrees with the backend (8pm vs 10pm) — an unfixed real bug.
- `MeterDial` Center, **`.size(414.dp)` fixed** — `1108-1114`, impl `1257-1338`. Built on `GlowingMeterGauge` (`Hud.kt:398-423`) with `sweepDeg=360`. Inner column: car icon 36dp (spring-scaled), "METER STATUS" 16sp, `AnimatedContent` label at **76sp** (`1305-1318`), sub line 20sp, then `StartMeterButton` 240×76 (`1334`, impl `1351-1373`).
- Two `QuickActionTile`s CenterEnd, **each `width(156.dp).height(172.dp)`** — `1119-1132`, impl `1198-1232`. "SET PRICE" (subtitle real, `1122-1123`) and "VOUCHERS" (subtitle **hardcoded** `"Redeemed at payment"`, `1129`, tap opens an info dialog only, `2239-2270`).

The dial's 414dp box inside a 421dp-tall inner area is a **7dp margin**. The side tiles overlap the dial's bounding box by ~53dp per side and only survive because a circle doesn't reach its box corners — documented as measured-on-device at `1146`, `1207-1209`, `1270-1272`. This is why the card can't shrink.

#### Right column — `DeckHomeScreen.kt:482-496`
`Column(weight(1f).fillMaxHeight().verticalScroll(...))`:
- **`LiveDispatchCard`, `Modifier.fillMaxWidth().height(300.dp)`** — `488-493`, impl `1379-1461`.
  - Body `when` at `1421-1442`: loading → "Loading offers…"; **`cards.isEmpty()` → a `Box(weight(1f))` centring `dispatchState.error ?: "No live offers right now"`**; else `LazyColumn` of `DispatchOfferRow` (`1463-1541`).
  - Footer "VIEW ALL JOBS →" bar always drawn (`1446-1458`).
  - **This is the owner's complaint.** With no offers — the normal state for most of a shift — the card still occupies a fixed **300dp × 432dp** block containing one line of grey 17sp text and a button. It never collapses. Error is rendered in the *same slot, same colour* as empty (`1427`) — indistinguishable.
- `DriverEngagementTiles` — `495`, impl `EngagementTiles.kt:88-146`. "MY ACCOUNT" eyebrow + 44dp refresh + four `GlassCard`s: `WalletTile` (`152-187`), `RatingTile` (`260-291`), `AnnouncementsTile` (`313-331`), `IncentiveTile` (`375-389`). Backed by `DriverEngagementViewModel` (four independent `GET /v1/me/*` calls, no polling).
  - **These four tiles stack to roughly 700–800dp inside a 461dp scroller.** Rating, Announcements and Incentives are below the fold with **no scroll indicator, no scrollbar, no peek**. The driver never sees them.
  - `AnnouncementsTile` empty text is `"No announcements"` (`EngagementTiles.kt:318`) — this is the "announcements section shows empty" the owner is describing.
  - Announcement rows (`EngagementTiles.kt:333-367`) are **not tappable** — `maxLines = 1` + ellipsis with no way to read the full text.

#### Bottom stat tiles — `DeckHomeScreen.kt:595-620`, `ShiftStatsBar` at `1568-1631`
Gated: only on `DASHBOARD`, or `METER` with no active trip (`595`). `Row(height(152.dp))`, four cells:

| Tile | Line | Value | No-shift state |
|---|---|---|---|
| Shift time | `1592-1600` | `shiftElapsedLabel(session.shiftStartAt)` + `ShiftProgressBar` | `"—"`, "No active shift", bar at 0 |
| Trips | `1601-1610` | `todayStats.tripsCount` (Room) | `"0"` / "Completed" |
| Earnings | `1611-1620` | `todayStats.earningsTotal` whole dollars | `"$0"` / "Today" |
| Next break | `1621-1629`, impl `1707-1808` | `ShiftDurationLimit.remaining()` + `HudRing` | ring 0, "—", "No active shift", "Start a shift to see your limit" |

**The `"-100% vs yesterday"` the owner saw is real backend data** — `EarningsDelta` (`1678-1688`) formats `homeExtras.earningsPctChange` from `GET /v1/trips/earnings/today`. A driver who has earned $0 so far today against a non-zero yesterday genuinely gets −100%. *True* and *useless and demoralising* at 09:00. Presentation bug.

`NextBreakTile`'s "TAKE BREAK"/"▶ RESUME" chip (`1787-1804`) is ≈ **30dp tall** — well under the 48dp minimum, on the one tile the whole app pushes as elderly-friendly.

Note `810-1816`: the old `SystemStatusCard` was removed 2026-09-06; the row now has a single `weight(1f)` child, so the `Row(height(152.dp))` wrapper is redundant scaffolding.

### 1.3 Nav rail — `CaptainNavRail`, `DeckHomeScreen.kt:1917-2025`

`RAIL_WIDTH = 116.dp` (`273`), tiles 96×72dp (`RailTile`, `2042-2116`), scrollable (`1973-1980` — 14 items × 78dp = 1092dp > 461dp available; only ~6 visible at rest).

Items (`railItems`, `1892-1909`): DASHBOARD, TRIPS, DISPATCH, METER, EARNINGS, HISTORY, ZONES, PRICING, VOUCHERS, DRIVER, SETTINGS, MESSAGES, MAP, LOG OUT.

Problems:
- **METER aliases DASHBOARD when no fare is open** (`1896`) — tapping METER does nothing visible. The selection resolver at `1967-1968` picks the *first* matching item so DASHBOARD wins the highlight; METER looks permanently unselectable.
- **HISTORY → `CaptainPane.SHIFT`** (`1898`) which renders `ShiftWheelContent` — the *shift summary*, not history. TRIPS (`1894`) is what actually renders `TripsPaneVariant.HISTORY` (`509-517`). The two labels are swapped relative to their content.
- Mid-fare lock (`1948-1959`): every action except METER and SETTINGS is a no-op, tiles dim to 35% (`2066`), plus a **9sp** "FARE RUNNING / Meter + Settings only" caption (`1993-2005`).
- Only 8 of 14 items fit without scrolling; no scroll affordance, so 6 destinations are effectively hidden.

### 1.4 Panes (`CaptainPane`, `DeckHomeScreen.kt:711`)
`456-584` swaps the left region: DISPATCH → `AvailableTripsWheelContent`; TRIPS → `TripsWheelContent(HISTORY)`; EARNINGS → `EarningsWheelContent`; SHIFT → `ShiftWheelContent`; ZONES → `ZonesPaneContent`; PRICING → `PricingPaneContent`; VOUCHERS → `VouchersPaneContent`; MESSAGES → `MessagesWheelContent`; MAP → `StatusMapPanel` (`2337-2439`); METER → `HiredScreen` (`563-583`, with a mid-fare recovery redirect at `576-579`). All except METER are wrapped in `PaneShell` (`CaptainWidgets.kt:296-321`) which adds its *own* 48dp back button + 28sp title — a second header inside a screen that already has one.

### 1.5 Dialogs
`SetPriceDialogV2` (`2498-2555`), `TripDetailsDialog` (`2578-2665`), `VoucherInfoDialog` (`2239-2270`), `DriverIdCard` (`2279-2330`, 560dp with a **300dp** avatar). Duress overlays at `685-692`.

---

## 2. ANIMATION & MOTION INVENTORY

### The rules the repo has already committed to
- **`ui/theme/Hud.kt:437-455`** — the calm-motion doctrine. Verbatim: *"A first attempt here added a travelling highlight that continuously circled the ring — reverted immediately on direct feedback ('this circle is moving continuously, its doing pain in my head... calm animations')."* Allowed: brightness that scales with **real speed**, and an **ember** whose rate is × `speedFraction` so it **stops dead at a standstill** (`Hud.kt:705-728`, `761-786`). *"There is no decorative loop anywhere in this file that could keep running with the vehicle stopped."*
- **`ui/theme/Hud.kt:105`** — *"full-layer `RenderEffect` blur is NOT used anywhere here, per the SM-T575 frame budget."* Restated at `Hud.kt:901-913` and `CaptainWidgets.kt:546`. The one sanctioned `Modifier.blur()` is `GlassCard`'s fixed 96dp sheen (`Hud.kt:930-940`).
- `CaptainPalette.kt:70-73`, `DeckHomeScreen.kt:2038-2039`, `FleetCommandOverlays.kt:349` — "no new looping/timer-driven animation" rule restated three more times.

### State-driven (good — the vocabulary to build on)
| Where | Line | Driver |
|---|---|---|
| `hudSpring()` — the one shared physics spring | `Hud.kt:138-139` | `DampingRatioLowBouncy / StiffnessLow` |
| Gauge progress | `Hud.kt:407-411` | `progress` |
| Speedometer needle | `Hud.kt:480-484` | real km/h |
| Band crossfade + one-shot bloom (`Animatable`) | `Hud.kt:490-504` | `SpeedBand` change |
| `RollingMoneyText` per-digit `AnimatedContent` | `Hud.kt:839-887`, `HUD_ROLL_MS=220` | fare value |
| `HudRing` | `Hud.kt:1171-1182` | progress |
| `gameClick` press squash + glow flash | `CaptainWidgets.kt:196-219` | press |
| Meter icon scale / METER label `AnimatedContent` | `DeckHomeScreen.kt:1268`, `1305-1318` | phase |
| Nav route transitions (300ms fade + 24dp drift) | `CabDispatchNavHost.kt:159-168` | navigation |
| Meter fare tick pop, band pop | `HiredScreen.kt:1646-1652`, `1700-1704` | value change |

### Looping (run forever)
| Where | Line | Loop | Verdict |
|---|---|---|---|
| `rememberInfiniteFloat` (shared primitive) | `CaptainWidgets.kt:126-139` | returns a constant when `enabled=false` | correct design |
| Header status pill dot | `DeckHomeScreen.kt:958` | breathes whenever not neutral — **always, all shift** | questionable |
| `SosControl` glow | `CaptainWidgets.kt:159` | `enabled = true`, **hardcoded**, never stops | always-on decorative loop in the driver's eyeline |
| Live Dispatch badge pulse | `DeckHomeScreen.kt:1397` | only when offers exist | acceptable |
| Rail tile halo breathe | `DeckHomeScreen.kt:2059` | `selected || live` | always one tile breathing |
| `StatusMapPanel` halo | `DeckHomeScreen.kt:2382-2387` | MAP pane only | ok |
| Engagement refresh spinner | `EngagementTiles.kt:522-528` | transition always running, applied only when `refreshing` | wasted frames |
| Meter "RUNNING" glow blur | `HiredScreen.kt:1720-1726` | 900ms 12→28px blur, **unconditional** even when PAUSED | the one loop that contradicts the calm rule inside the fare dial |
| Earnings sparkline breath | `EarningsWheelContent.kt:414` | `enabled = true` | always-on |
| Available-trips card breathes | `AvailableTripsWheelContent.kt:206`, `278` | always-on | always-on |
| Duress arming ring / stealth lamp | `DuressOverlays.kt:100-105`, `207-212` | alarm state | justified |
| Speedometer ember + hum | `Hud.kt:713-726` | `withFrameNanos`, rate × speedFraction | the sanctioned pattern |

`Crossfade`: zero uses. `AnimatedContent`: 3. `withFrameNanos`: 1.

**Summary:** the motion system is technically sound and the primitives exist. The home screen uses almost none of them — only press feedback, four breathing halos, and a 76sp "OFF" label. There is no entrance choreography; everything pops in fully formed.

---

## 3. DESIGN SYSTEM

**`ui/theme/CaptainPalette.kt`** (452 lines) — the live palette. `mutableStateOf`-backed vars swapped by `applyTheme()` (`240-281`) from `DarkTokens` (`330-384`) / `LightTokens` (`405-451`). Light tokens carry measured WCAG-AA ratios (`386-403`). **Dark mode has had no such audit since the 2026-09-07 neon reskin.**

Key dark tokens: `bg #080B14`, `panel #12131C`, `hudBg #0B0B10`, `accent #6E3FF3`, `primary #5B3FD6`, `neonCyan #00E5FF`, **`success` retinted to `#00E5FF`** (`349-353` — "good/online" is cyan, not green), `warning #FFB51B`, `danger #EF4444`, `textPrimary #F5F7FB`, `textSecondary #93AAD1`, **`textMuted #5F6478`**.

**There is no spacing scale and no type scale in use.** `DeckType` exists (`DeckTheme.kt:184-216`) and `DeckHomeScreen.kt` references it **zero times**. Same for `Deck`'s dimension constants (`DeckTheme.kt:99-109`).

Font sizes used on the home screen: 9, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 24, 26, 28, 32, 34, 40, 44, 76 sp — **twenty distinct sizes on one screen.** Paddings/heights: 39 distinct dp values. Corner radii: 12 distinct values.

Components (`ui/theme/Hud.kt`): `GlowingMeterGauge` (`398-423`), `GlowingSpeedometer` (`457-637`), `HudRing`, `GlassCard` (`916-961`), `HudStatusPill`, `HudStatTile`, `RollingMoneyText` (`839-887`), `GlowLineLayers`. `ui/theme/CaptainWidgets.kt`: `DriverAvatar`, `PulsingDot`, `rememberInfiniteFloat`, `SosControl`, `gameClick`, `CaptainButton` (default 64dp), `PaneShell`, `TwoPaneShell`, `CaptainPanel`, `CaptainChip`, `CaptainKeypad`, `CaptainDialogScrim`, `neonGlow`.

**Where the home screen bypasses the design system:**
- `NightFareTile` (`1147-1153`) and `QuickActionTile` (`1211-1218`) hand-roll `clip + verticalGradient + border` instead of `GlassCard`/`CaptainPanel` — noted as a gap at `1101-1104`.
- `StartMeterButton` (`1351-1373`) is bespoke, not `CaptainButton`.
- Four dialogs (`2239`, `2279`, `2498`, `2578`) each hand-roll scrim + panel instead of `CaptainDialogScrim` (`CaptainWidgets.kt:523-542`).
- `Color.Black.copy(alpha = 0.6f/0.7f)` raw at `2241`, `2287`, `2502`, `2590`; `Color.White` raw at `2394`.
- ~90 `Text` call sites each set `fontFamily` + `fontWeight` + `fontSize` + `letterSpacing` inline. No `TextStyle` constants.

Fonts (`DeckTheme.kt:161-181`): `ChakraPetch` (numerals/data), `InterFamily` (everything else), `RobotoMonoFamily` (regos/IDs).

---

## 4. THE METER SCREEN — `ui/screens/hired/HiredScreen.kt`

Embedded as `CaptainPane.METER` (`DeckHomeScreen.kt:563-583`), so it inherits the 128dp header and 116dp rail; the bottom stats bar collapses while a fare is live.

- `HiredScreen` (`247-...`): `BackHandler(enabled = true) {}` at `281` swallows back to protect the live `FareEngine`.
- Stacked banners (`388-441`): SIMULATED GPS, MAXI RATE ×1.5, wheelchair notice. Transient overlays: "● METER STARTED" 2s, auto-toll banner 7s.
- `MeterPaneLayout` (`810-924`): **50/50 split** (`716-718`). Left = `GlassCard` + `MeterDial` + `ControlsHandle`. Right = map panel with `MeterBackdropMap`, status pills, `MapDestinationSearchBar` (46dp), `NavTurnBanner`, `NavBottomBar`.
- `MeterDial` (`1609-1863`): `GlowingSpeedometer(motion = true)`, inner disc at 0.70. Contents: car icon 26dp, "ACTIVE FARE" 13sp, `RollingMoneyText` **76sp**, RUNNING/PAUSED 16sp with the pulsing blur (`1720-1735`), band label 12sp, three `DialReadout`s, then PAUSE 96×44 + END FARE 150×44 (`1767-1817`).
- **Both in-dial buttons are 44dp tall — under the 48dp minimum, and `END FARE` is the single most consequential control in the app.**
- `ControlsHandle` → `ControlsDrawer` (`995-1017`, `1031-1093`): 440dp × max 600dp panel with NightFareTile, 2×2 grid of SET PRICE / ADD TOLL / PAUSE FARE / MORE, `FareBreakdownCard`, `TripDetailsCard`, `AccrualNote`.
- Backdrop map: `ui/screens/hired/MeterBackdropMap.kt` (487 lines) — real Mapbox `MapView`, `dimAlpha = 0.30f`, driven route + planned route via `createGlowLine`, recentre FAB.

---

## 5. TOP CHROME / OVERLAYS

`CaptainChromeMetrics` (`ui/overlays/ChromeMetrics.kt:38-96`): process-wide holder; `headerHeight` written only by `Modifier.reportsChromeHeader()` (applied at `DeckHomeScreen.kt:882`); `FALLBACK = 54.dp`; `topOverlayInset = headerHeight + 10.dp`. `fullScreenGateVisible` lets readiness/permissions/terms suppress all banners (fixed 2026-09-08).

Stacking, hosted in `MainActivity.kt:147-191`:
1. `OfflineBanner` (`FleetCommandOverlays.kt:428-498`) — slim strip at y=0.
2. `ForceUpdatePendingBanner` (`141-271`) — top-centre at `topOverlayInset`, max 720dp. The only hit-testable overlay.
3. `KioskLockedBanner` (`307-333`) + `DeviceUnpairedBanner` (`358-393`) — stacked TopStart at `topOverlayInset`.
4. Duress: `DuressTriggeredOverlay` full-bleed; `DuressActiveBanner` renders only a 10dp lamp (stealth); `HiddenDuressGestureZone` invisible 56dp triple-tap.

**Consequence for the redesign:** with a 128dp header, `topOverlayInset` is 138dp. Any banner lands on the Night Fare tile / Live Dispatch title. Shrinking the header automatically fixes overlay placement.

---

## 6. ACCESSIBILITY

**contentDescription:** 85 × `null`, 18 non-null across all of `ui/`. On the home screen **every icon is `null`**: avatar (`CaptainWidgets.kt:104`), VERIFIED (`928`), all four status dots (`1059`, `996`), night-fare icon (`1156`), quick-action icons (`1226`), meter car icon (`1283`), all 14 rail icons (`2082`), all stat-tile icons (`Hud.kt:1127`). The only labelled control is the engagement refresh button (`EngagementTiles.kt:538`). **A TalkBack user gets a wall of unlabelled buttons.**

No `Modifier.semantics`, no `role = Role.Button`, no `stateDescription` anywhere in `ui/`.

**Touch targets under 48dp on the home screen:**
| Control | Line | Size |
|---|---|---|
| "VIEW ALL" (Live Dispatch) | `DeckHomeScreen.kt:1411-1418` | ≈34dp |
| "VIEW ALL JOBS →" | `1446-1454` | 34dp visible |
| TAKE BREAK / RESUME | `1787-1794` | ≈30dp |
| Engagement refresh | `EngagementTiles.kt:531` | 44dp |
| Driver name/rego → Profile | `896-897` | no min size |
| END FARE / PAUSE (meter) | `HiredScreen.kt:1773-1774`, `1796-1797` | **44dp** |
| `MapDestinationSearchBar` | `HiredScreen.kt:955` | 46dp |
| Dialog close X | `HiredScreen.kt:1055` | 22dp icon |
| Destination clear X | `HiredScreen.kt:979` | 15dp icon |

**Text sizes:** floor is **9sp** (`DeckHomeScreen.kt:1998`), then 10sp (`HiredScreen.kt:1854`), 11sp × many. The class doc (`DeckHomeScreen.kt:253-269`) commits to *"majority users are old age… deliberately larger type"*; the commitment holds for headline numbers and breaks for labels.

**Contrast (dark):** `textMuted #5F6478` on `hudGlass` over `bg #080B14` ≈ **3.0:1** — fails WCAG AA for the 11–13sp labels it's used on. `textSecondary #93AAD1` ≈ 8:1, fine.

**Orientation:** `sensorLandscape`; `FixedDesignCanvas` scales from width only, so a non-16:10 panel silently changes available height. **No `fontScale` handling:** at 1.3× the 76sp/32sp/28sp values grow inside fixed-height containers and clip.

---

## 7. EMPTY / LOADING / ERROR STATES

### Home screen cards
| Card | Loading | Empty | Error | Should collapse? |
|---|---|---|---|---|
| Live Dispatch (`1421-1442`) | "Loading offers…" | `"No live offers right now"` in a **fixed 300dp** card | same slot, same colour as empty | **Yes — #1 complaint** |
| Wallet (`EngagementTiles.kt:152-187`) | pulsing dot | balance always renders | inline warning + RETRY | No |
| Rating (`260-291`) | as above | `"No ratings yet"` | as above | No |
| Announcements (`313-331`) | as above | `"No announcements"` | as above | **Yes — hide when empty** |
| Incentives (`375-389`) | as above | `"No active incentives"` | as above | **Yes — hide when empty** |
| Shift time / Trips / Earnings / Next break | n/a | honest dashes | n/a | No |
| Verified badge (`925-940`) | hidden | hidden | hidden | already correct |

`SectionBody` (`EngagementTiles.kt:476-503`) is the best-designed piece of state handling in the codebase — four explicit branches, stale data preserved across a failed refresh. It just isn't applied to Live Dispatch.

### Pane screens
- **Trips** (`TripsWheelContent.kt:104-125`): **no error state** — a failed load looks identical to "no trips".
- **Dispatch pane** (`AvailableTripsWheelContent.kt:205-...`): `EmptyOfferState()` — a proper illustrated `GlassCard`. *This is the good empty state the dashboard card should reuse and doesn't.*
- **Earnings**, **Shift/History**, **Zones**: no error states.
- **Vouchers** (`VouchersPaneContent.kt:100-145`): the most complete.
- **Map pane** (`2337-2439`): `IllustrativeStreetGrid` fake map with hardcoded suburb labels SYDNEY CITY / REDFERN / AIRPORT / LAKEMBA (`2481-2484`) drawn at fixed offsets regardless of real position.

---

## 8. PRIORITISED REDESIGN RECOMMENDATIONS

Everything below stays inside the repo's rules: no full-layer `RenderEffect`, no decorative loop that runs while parked, no fabricated data.

### P0 — Compact the top bar (128dp → 72dp; frees 56dp = 7% of the canvas)
In `CaptainHeader` (`DeckHomeScreen.kt:860-1010`):
1. Avatar `sizeDp = 88` → **`52`** (`889-891`); wrap in `Modifier.size(56.dp)`. Row `padding(vertical = 20.dp)` → **`10.dp`**. Header = **72dp**.
2. Delete the "CAPTAIN / TAXIS" wordmark block (`892-895`).
3. Driver name 28sp → **20sp**; rego 15sp → **13sp** (`899-919`).
4. Merge VERIFIED into the avatar as a 16dp cyan check badge; delete the 40dp pill (`925-940`).
5. Status pill `height(64)` → **`48`**, title 21sp → 16sp, sub 13sp → 11sp (`945-976`).
6. System strip `height(56)` → **`44`**, `spacedBy(20)` → `12`, icons 18→16dp, labels 13→11sp (`985-1007`).
7. `SosControl(sizeDp = 72)` → **`56`** with "HOLD" inline-right (`CaptainWidgets.kt:160-177`).
8. Replace the `Spacer(weight(1f))` (`977`) with `Arrangement.spacedBy(16.dp)` + one `weight(1f)` spacer between the identity group and the status group.

`reportsChromeHeader()` makes every overlay follow automatically.

### P0 — Collapse Live Dispatch when empty
In `DeckHomeScreen.kt:488-493`:
```kotlin
val hasOffers = dispatchState.cards.isNotEmpty()
AnimatedVisibility(
    visible = hasOffers || dispatchState.loading,
    enter = fadeIn(tween(220)) + expandVertically(tween(260)),
    exit  = fadeOut(tween(160)) + shrinkVertically(tween(200)),
) {
    LiveDispatchCard(
        ...,
        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 340.dp),   // was .height(300.dp)
    )
}
if (!hasOffers && !dispatchState.loading) {
    DispatchIdleStrip(                                // new: 56dp
        error = dispatchState.error,
        onViewAll = { pane = CaptainPane.DISPATCH },
    )
}
```
`DispatchIdleStrip` = one 56dp `GlassCard` row: `PulsingDot(animated = state.isAvailable)`, "Listening for jobs — you're available" (or the error in `danger`), and a right-aligned "VIEW ALL" with a 48dp target.

Apply the same rule to `AnnouncementsTile` (`EngagementTiles.kt:313-331`) and `IncentiveTile` (`375-389`): when `section.data?.isEmpty() == true && section.error == null`, render nothing. Keep Wallet and Rating always visible.

### P0 — Fix the nav rail's two lies
- `railItems` (`1892-1909`): rename `HISTORY → CaptainPane.SHIFT` to **"SHIFT"**, and `TRIPS → CaptainPane.TRIPS` to **"HISTORY"** — or swap the panes. They are backwards.
- Drop the METER item when `!hasActiveTrip` instead of aliasing it to DASHBOARD (`1896`). Keep it, glowing, only while a fare is open.
- Raise the mid-fare lock caption from **9sp → 12sp** (`1998`).

### P1 — Layout grid for the 1280×800 landscape canvas
Introduce `ui/theme/CaptainSpace.kt`:
```kotlin
object Space { val xs=4.dp; val sm=8.dp; val md=16.dp; val lg=24.dp; val xl=32.dp }
object Radius { val sm=12.dp; val md=16.dp; val lg=20.dp; val pill=999.dp }
object Type {   // TextStyle constants — replaces ~90 inline declarations
  val display, h1, h2, h3, body, label, tiny, mono, numeral
}
```
Rule: only `Space` in `padding`/`spacedBy`; only `Radius` in `RoundedCornerShape`; only `Type` in `Text`. Floor becomes **12sp**.

Target grid (dp on the fixed 1280×800 canvas):
```
┌────────────────────────────────────────────────────────────┬──────┐
│ HEADER 72                                                  │      │
├────────────────────────────────────────────────────────────┤ RAIL │
│                                                            │ 104  │
│  METER COLUMN 560           │  RIGHT COLUMN 468            │      │
│  ┌───────────────────────┐  │  ┌────────────────────────┐  │ 14   │
│  │ dial 360 (responsive) │  │  │ dispatch 56 idle /     │  │ items│
│  │ NIGHT | SET | VOUCH   │  │  │  180–340 active        │  │  @   │
│  │ 3 chips, 92 tall      │  │  ├────────────────────────┤  │ 68   │
│  └───────────────────────┘  │  │ MY ACCOUNT 2×2 grid    │  │ tall │
│                             │  │ 4 tiles, 228×~150 each │  │      │
├─────────────────────────────┴──┴────────────────────────┴──┤      │
│ STAT BAR 120  (Shift · Trips · Earnings · Break)           │      │
└────────────────────────────────────────────────────────────┴──────┘
72 + 16 + 508 + 16 + 120 + 16 = 748  ·  32 gutters top/bottom
```
Concrete edits:
- `RAIL_WIDTH` 116 → **104** (`273`); `RailTile` 96×72 → **88×68** (`2069-2070`), label 11 → 12sp.
- `MeterCard` `width(660)` → **`560.dp`** (`470`).
- `MeterDial` `.size(414.dp)` → **`BoxWithConstraints { minOf(maxWidth, maxHeight).coerceIn(300.dp, 380.dp) }`** (`1275`) — kills the 16:9 clip bug.
- Replace the absolute-positioned NightFare/QuickAction tiles (`1107`, `1115-1133`) with a **`Row` of three 92dp-tall chips under the dial**: NIGHT FARE `1.19× · 10pm–6am`, SET PRICE, VOUCHERS.
- Right column: **2×2 grid** of the engagement tiles at 228dp wide instead of a vertical scroller (`482-496`).
- Stat bar `height(152)` → **`120`** (`600`), value 32sp → 28sp (`1566`). Delete the single-child `Row` wrapper.
- `EarningsDelta` (`1678-1688`): suppress when `todayStats.tripsCount == 0`; show `"First trip of the day"` instead of "−100% vs yesterday".

### P1 — "Game-like but calm" motion language
1. **Entrance choreography, once per pane entry, never looping.** Add a staggered reveal driven by `MutableTransitionState` (already imported at `DeckHomeScreen.kt:140`):
   ```kotlin
   @Composable fun StaggerIn(index: Int, content: @Composable () -> Unit) {
       val state = remember { MutableTransitionState(false).apply { targetState = true } }
       AnimatedVisibility(
           visibleState = state,
           enter = fadeIn(tween(240, delayMillis = index * 45)) +
                   slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow)) { it / 12 },
       ) { content() }
   }
   ```
   Apply with `index` 0..5 across MeterCard, dispatch strip, the four account tiles, the stat tiles. Total settle ≈ 500ms, then **completely static**. This is where "game interface" lives — arrival, not ambience.
2. **Value changes are physics, not fades.** Swap the stat tiles' plain `Text` for `RollingMoneyText` (`Hud.kt:839-887`) on Earnings; add the meter's one-shot `Animatable` pop (`HiredScreen.kt:1700-1704`) to Trips when the count increments.
3. **Delete the always-on loops that aren't tied to state.** `SosControl` `enabled = true` (`CaptainWidgets.kt:159`) → static red ring. `HiredScreen.kt:1720-1726` RUNNING glow → drive from `fareState.currentSpeedKmh` like `GlowingSpeedometer` (`Hud.kt:538-539`). `EarningsWheelContent.kt:414`, `AvailableTripsWheelContent.kt:206,278` → `enabled = <the state they represent>`.
4. **Keep exactly three sanctioned loops, all state-gated:** header status dot while AVAILABLE, DISPATCH badge while offers pending, rail METER halo while a fare is live.
5. **Depth instead of motion for the "game" feel.** `neonGlow`, `GlassCard` sheen, `drawHoloRing` static particles, `TechGridBackdrop`. Raise `TECH_GRID_ALPHA` 0.05 → 0.08 and add a subtle radial vignette toward the meter card.

### P2 — Accessibility pass
- `contentDescription` on every non-decorative icon on the home screen (build status-dot labels from `HudTone` at `991-996`; rail icons from `item.label` at `2082`; stat-tile icons at `Hud.kt:1127`).
- `SosControl`: `Modifier.semantics { role = Role.Button; contentDescription = "Emergency duress alarm — press and hold" }`.
- Raise every sub-48dp target in §6 to 48dp; **END FARE and PAUSE to 56dp** (`HiredScreen.kt:1774`, `1797`).
- Lift `textMuted` from `#5F6478` to ≈`#8A90A8` (≈4.6:1) in `DarkTokens` (`CaptainPalette.kt:343`).
- Change fixed `height(152.dp)` / `height(300.dp)` / `size(414.dp)` to `heightIn(min=...)` so 1.3× `fontScale` doesn't clip.

### P2 — Delete dead code before touching anything else
Remove `WheelDashboardScreen.kt`, `HomeDashboardV2.kt`, `DockScreenChromeV2.kt`, `ui/wheel/Wheel{State,Geometry,Gesture}.kt`, `ui/deck/DeckChrome.kt`, `ui/theme/WheelColorsV2.kt`, and `WheelColors` from `Theme.kt:48-81`. Keep `ui/deck/DeckWidgets.kt` (`DeckKeypad` is live) — or migrate that one call site to `CaptainKeypad` (`CaptainWidgets.kt:496-515`) and delete it too. Decide explicitly whether `PlotZoneScreen`/`ZoneStatisticsScreen` stay as unreachable routes or get deleted with their nav entries (`CabDispatchNavHost.kt:270-275`).
