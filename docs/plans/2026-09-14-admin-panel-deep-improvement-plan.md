# Admin panel (Fleet Ops dashboard) — deep improvement plan

Written 2026-09-14 for implementation by Sonnet 5. Based on (a) a logged-in walkthrough of every
page of the production dashboard at http://72.61.107.107 as the tenant owner, and (b) a code audit
of `dashboard/src` against `backend/app/api/v1`. Facts first, then the work, in priority order.
Every item names the files to touch and the acceptance check. Nothing here changes the meter
app; the one backend endpoint the plan adds is called out explicitly.

## 0. Ground truth the plan is built on

- 22 routes (`dashboard/src/router.tsx:111-173`): Overview, Getting Started, Live Map, Dispatch,
  Messages, Duress Desk, Trips (+detail), Shifts (+detail), Tariff Studio, Zones & Demand, PSL
  Centre, Fleet & Drivers (+driver/vehicle/device detail), Compliance Vault, Billing, Payment
  Reconciliation, Vouchers & Accounts, Announcements, Incentives, Driver Wallets, Ratings, Audit
  Log, Security, White-label, Platform (owner-gated).
- Tests: Vitest 62 files / ~433 cases; Playwright 1 spec (6 tests) skipped by default. Zero tests
  in `compliance/`, `driver-engagement/`, `getting-started/`, `messages/`, `payment-recon/`,
  `platform/`, `psl/`, `vouchers/`.
- No dead API calls: every path the dashboard uses exists in the backend.
- Tenant data as seen today: 4 vehicles (2 of them Karachi bench tablets), 11 drivers (mostly
  test accounts), 140 trips, 157 shifts, 7 duress events (4 "dispatched" and never closed, from
  31 Aug–4 Sep), 0 PSL ledger rows, 0 vouchers, 0 ratings, 0 subscriptions, 1 announcement
  ("We are doing maintenance", live since 5 Sept, open-ended).

## 1. Bugs seen live (fix first, each is small)

1. **Live map and Overview map render black.** Both load the custom Mapbox Studio style
   `mapbox://styles/benfarid/cmtbnyhe4000e01pcgx2t51za` (`pages/live-map/mapInit.ts`,
   `MAP_STYLE_URL`); the style JSON and iconsets return 200 but the page issues **no tile
   requests at all**, so only markers draw on a black canvas. Either the Studio style has no
   sources/visible layers or the tile URLs are blocked. Fix: verify the style in Mapbox Studio;
   until then fall back to `mapbox://styles/mapbox/dark-v11` when the custom style loads zero
   sources (`map.getStyle().sources` empty after `style.load`). Acceptance: streets visible
   behind PRCH01 on `/live-map` and on the Overview tile.
2. **Shift cash total ignores fare corrections.** Benn's T5453 shift of 14 Sept 6:04 pm shows
   Cash $32.52 and "Reconciled: Yes", but the trip was corrected to $59.03 via
   `POST /v1/trips/{id}/fare-correction`. The shift report is computed from the stored total at
   reconciliation time and never recomputed. Fix in backend `services/shifts.py` (report query
   should read the trip's current `total`), and the correction endpoint should invalidate the
   shift's reconciled flag or append a note. Acceptance: shift row shows $59.03 after correction.
3. **Fare corrections are missing from the Audit Log.** `POST /v1/trips/{id}/fare-correction`
   writes `review_notes` only; the audit chain (`/v1/audit-log`) has no `fare_correction` action.
   Fix: emit an audit entry (`entity_type=trip`, `action=fare_correction`, before/after totals,
   actor) from the endpoint. Acceptance: entry appears in `/audit-log` with a diff.
4. **Duress events stuck "dispatched" for two weeks** (4 rows since 31 Aug) and the Live Map
   "Active duress events" panel shows the driver as a raw UUID (`bdb0c6db-…`). Fix: (a) show
   driver name (join like the Duress Desk does), (b) add "Close" to that panel, (c) add an
   auto-escalation/auto-close rule after N hours with a visible "stale" badge.
5. **Security page lists 20 browser sessions from one IP** — every API login (scripts, tablets,
   the dashboard) creates a session that never expires. Fix: idle-expiry (e.g. 24 h without
   activity) server-side plus "Sign out everywhere else" is already there; also de-duplicate by
   device fingerprint. Acceptance: session list stays under a handful.
6. **Trips table row click does nothing** (clicking the first row leaves the list unchanged); the
   detail route `/trips/:tripId` exists. Check the row `onClick` in `pages/trips/index.tsx`
   (likely swallowed by the cell's `stopPropagation`). Acceptance: clicking a row opens the
   fare breakdown with the max-fare check and the GPS-blackout segments.
7. **Messages page freezes the renderer** (Chrome's screenshot of `/messages` timed out; the
   other 21 pages rendered in under 2 s). Profile the thread list: likely the WebSocket
   `useMessagesLive.ts` re-rendering every driver card per message, or an unbounded list.
8. **Vehicle names carry bench data**: "Samsung Bench tablet (Karachi) - typo rego" (PRCH01),
   "3333 3333 333" (T1222 make/model). Add an "archived/test" vehicle status so bench rigs stop
   polluting fleet KPIs (Overview shows "Offline 2" for them).

## 2. Data that the meter produces but the panel never shows

These are already in the API payloads (`trips.py:300-418`) and cost nothing on the backend.

- **GPS-blackout segments** on the trip detail: entry/exit points, duration, resolution
  (`CORRIDOR` / `INERTIAL` / `STATIONARY` / `DEVICE_*`), estimated vs matched km, correction,
  confidence, ZUPT count. Render as a timeline strip under the trip map, and draw the
  dead-reckoned segment dashed on the route map (the live map already draws estimated
  positions with a dashed ring — reuse `pages/live-map/markers.ts`).
- **Fare check / variance**: show the 1 % variance figure and both totals (device vs server) in
  the row tooltip, not only "Failed / Flagged".
- **Auto-toll evidence**: which gantries were detected, at what time, price source (verified /
  unpriced), and the "unpriced road" prompts the driver saw. Field case to design around: the
  T5453 Rozelle Interchange trip (registry has no price; Linkt publishes none).
- **Live-map vehicle card**: `position_source` (live / estimated / stale), fix age, speed,
  heading, battery and connectivity are all in `GET /v1/vehicles` — show the source badge and
  the age; today the card shows "50 km/h · just now" only.
- **Device diagnostics**: the Devices tab has "Commands" and "Heartbeats" placeholders
  (`pages/devices/tabs/CommandsTab.tsx:16`, `HeartbeatsTab.tsx:16-18`). Backend has heartbeat
  ingestion but no history table; add a 7-day heartbeat history table (backend) and render it.

## 3. Workflows the backend supports that the panel cannot do

From the audit, the unexposed endpoints that matter to an operator:

- **Payments** (`payments.py:175-375`): view/update a payment, tap-to-pay intent, payment link,
  cash, manual, CabCharge authorise, TTSS claim. The Payment Reconciliation page is read-only
  and shows 0 rows; build a real reconciliation workflow: mark docket settled/claimed, attach a
  settlement reference, export the claim CSV.
- **Fare correction** (`trips.py:1023`): add an owner-only "Correct fare" action on the flagged
  trip detail with a reason field (this is the endpoint used by hand today).
- **Zones**: `POST /v1/zones/{id}/plot` and `/unplot` — let dispatch plot/unplot a vehicle from
  Zones & Demand (today every zone shows 0 plotted).
- **Dispatch live feed**: `WS /v1/jobs/live` exists; the page polls (`pages/dispatch/index.tsx:48`).
  Switch to the socket, and expose accept/decline on behalf of a driver (`jobs.py:194-231`).
- **Tariffs**: `POST /v1/tariffs/from-preset` (one-click NSW Fares Order card) and
  `GET /v1/tariffs/active` (show which card each vehicle is on right now).
- **Fleet positions history** (`live_ops.py:237-247`) for a "replay the last hour" control on
  the live map.
- **App releases**: `GET /v1/app-releases/{id}/download` — add the APK download link on the
  Platform page so the owner stops copying APKs by hand.

## 4. Pages that are empty shells today and need seeding or wiring

- **PSL Centre**: 0 ledger rows although 140 trips each carried the $1.32 levy. Either the
  ledger is not populated on trip close (check `services/trips.py` close path → `psl` service)
  or the page filters default to a period with no data. Acceptance: monthly remittance report
  shows the levy total for September.
- **Compliance Vault**: every vehicle "Non-compliant, 0 docs". Add the expiry dashboard
  (registration, insurance, driver authority) with a 30/7-day reminder list; wire the existing
  compliance-expiry warnings to an acknowledge action (`pages/fleet/api/vehicles.ts:170-173`).
- **Incentives / Wallets**: wallet balances are one request per driver (N+1, capped at 100,
  `pages/driver-engagement/hooks.ts:295-320`); incentive progress is recomputed in the browser
  from `/v1/trips` because `/v1/me/incentives` is driver-scoped. Add
  `GET /v1/wallet/balances` and `GET /v1/incentives/{id}/progress` (backend) and use them.
- **Ratings**: 0 ratings — the tablet's Close & Pay rating step posts `POST /v1/trips/{id}/rating`;
  verify it fires (the bench receipt flow showed "Rate this passenger" then "This trip hasn't
  synced yet — try again"), i.e. rating fails when the trip has not synced. Queue it instead.

## 5. Structural / tech debt

- Hard-coded `limit: 100/200` lookups for vehicles, drivers, tariffs, trips-per-day
  (`useTrips.ts:267-293`, `pages/shifts/api.ts:98-111`, `pages/duress/api.ts:167-188`,
  `pages/audit-log/api.ts:73`, `pages/drivers/tabs/OverviewTab.tsx:46`). Replace with
  server-side search/typeahead endpoints and cursor pagination.
- `ErrorBoundary` wraps `AppShell` instead of its `Outlet` (`src/router.tsx:76-81`): a page
  crash removes the sidebar. Move the boundary inside the shell.
- `TODO(X1)` at `lib/i18n/jurisdiction.ts:41` — tenant jurisdiction field.
- Audit log "Actor" shows raw user UUIDs; resolve to names (users list is already fetched at
  `pages/audit-log/api.ts:72`).
- Announcements: an open-ended "maintenance" notice has been live on every tablet for 9 days.
  Add an end-date requirement for `maintenance` kind and a "still live" warning on the page.

## 6. Design pass (the owner asked for "deep improvement", not only fixes)

- One operator "attention" queue on Overview: flagged trips, stale duress, expiring documents,
  tablets needing attention, unreconciled shifts — each a link into the page with the filter
  pre-applied. Today the tiles are counts only.
- Live map: cluster markers at low zoom, vehicle trail for the last 10 minutes, and a "follow"
  toggle. Show estimated (tunnel) positions with the dashed ring already implemented.
- Trip detail: fare breakdown side by side with the device breakdown; blackout timeline;
  toll evidence; one "Correct fare" button (owner/admin) with the audit entry.
- Empty states with a real next action (Getting Started already does this well; PSL, Vouchers,
  Incentives, Ratings, Billing show bare "No rows").
- Keep the brand palette; it is consistent and readable. Dark-only is fine for a desk product.

## 7. Suggested order and size

| # | Item | Size |
|---|------|------|
| 1 | Map style fallback (1.1) + trips row click (1.6) + audit entry for fare correction (1.3) | S |
| 2 | Shift totals follow fare corrections (1.2) + session expiry (1.5) | S |
| 3 | Blackout/toll/variance evidence on trip detail (2) + "Correct fare" action (3) | M |
| 4 | PSL ledger population + remittance report (4) | M |
| 5 | Payment reconciliation workflow (3) | M |
| 6 | Duress stale handling + names + close (1.4) | S |
| 7 | Dispatch WebSocket + on-behalf accept/decline; zone plot/unplot (3) | M |
| 8 | Wallet/incentive server endpoints, remove N+1 (4) | S |
| 9 | Overview attention queue + live-map trail/clusters (6) | M |
| 10 | Pagination/typeahead, ErrorBoundary, actor names, test coverage for the 8 untested dirs (5) | M |

Each PR: Vitest coverage for the page touched, and a Playwright smoke step where the page is
user-facing. Run `npm test` in `dashboard/` and the backend suite
(`backend/.venv/Scripts/python.exe -m pytest` with `_TEST_DB_FILE_OVERRIDE`) before pushing.
