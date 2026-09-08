# Dashboard Command Centre: from admin panel to dispatch-grade console

**Date:** 2026-09-09 · **Trunk:** `phase0/merge-to-main` in worktree `D:\cabdispatch-phase0` ·
**Execution:** autonomous agent (Claude Sonnet 5), one workstream per commit, in the order of §12.

The owner's brief, verbatim: *"When I click on the driver, I can only see the basic
information. There should be the proper whole page for the drivers. There should be the whole
page for the vehicle. There should be the whole page for the device. Study how MTI dispatch
does it and make ours that type of system."*

This document is the plan. §0 is the operating manual for the agent executing it. §1 is what
exists. §2 is the target. §3 onward are the workstreams, each with files, endpoints,
acceptance criteria and tests. §12 is the order.

---

## 0. Operating manual for the executing agent

**Repo facts**
- Dashboard: `dashboard/` — React 18, TypeScript, Vite, TanStack Query v5, react-router v6,
  Tailwind, recharts, mapbox-gl, MSW for tests (vitest). Backend: `backend/` — FastAPI,
  SQLAlchemy async, Alembic, pytest. Tablet: `android/` (not touched by this plan except where
  §11 says).
- Commands (from `dashboard/`): `npm test -- --run` · `npx tsc --noEmit -p tsconfig.json` ·
  `npx eslint <changed files>` · `npm run build`. From `backend/`:
  `D:/cabdispatch/backend/.venv/Scripts/python.exe -m pytest tests/<subset>` with env
  `_TEST_DB_FILE_OVERRIDE=<unique>.db`; `ruff format app tests && ruff check app tests`.
- The production server (`72.61.107.107`) is **never** written to by an agent: no seeds, no
  migrations, no API writes. The owner pulls and redeploys. Read-only `curl` of `/health`
  and `/openapi.json` is fine.
- Commit per workstream, on the trunk branch, with the trailer block used across this
  program. Push only when the owner has said pushes are authorised for the session.

**Design rules that already exist and must not be broken**
- Every number on screen comes from a real endpoint. No placeholder, no estimated figure.
  Where a value cannot be loaded, show "—" and say why (`ErrorBanner`), never a stale or
  guessed number. (This rule is stated at the top of `src/pages/overview/index.tsx`.)
- Calm UI: no decorative animation, no looping motion. Motion only when caused by data.
- Dark and light themes both work; tokens from `src/lib/theme.tsx`, brand colours from
  `tenant.theme_json` (white-label). Do not hardcode colours in new pages.
- Use the kit in `src/components/ui` first (`Table`, `Sheet`, `Modal`, `Tabs`, `Badge`,
  `EmptyState`, `ErrorBanner`, `PageHeader`, `Skeleton`, `Toast`, …). Extend the kit rather
  than inlining one-off widgets.
- Polling bands come from `src/lib/pollIntervals.ts`; never invent a new interval.
- Every new page gets an MSW test that renders it with realistic data and one with an error,
  and every new backend endpoint gets a pytest. Coverage is currently skewed to the UI kit;
  the workstreams below each carry a test budget.

**Definition of done per workstream**: types/lint/tests/build all pass; new route reachable
from the sidebar and from at least one existing surface (a row click, a link in a modal); the
old modal it replaces either redirects to the page or is deleted; the plan's acceptance list
ticked in the commit message.

---

## 1. Where the dashboard is today (inventory, 2026-09-09)

Routes: 28 flat routes in `src/router.tsx`, all lazy. **No `:id` route exists.** Every entity
detail is a modal:

| Entity | Surface | What it shows | What it cannot do |
|---|---|---|---|
| Driver | `pages/fleet/DriversPanel.tsx` modal | photo, driver code, phone, status, on-shift, vehicle, shift start, current trip, two compliance dates | trips, earnings, ratings, wallet, shift history, documents, messages, edit name/phone, deactivate, reset PIN |
| Vehicle | `pages/fleet/VehiclesPanel.tsx` opens an **edit form**; the rich view is `pages/live-map/VehicleDetailModal.tsx` (517 lines) reachable only by clicking a map dot | live status, device, driver, shift history, 72 h position replay (SVG), harsh-brake counts; separate `VehicleReportsModal` (lifetime totals, pilot report) | one place for all of it; compliance dossier is not linked from the vehicle |
| Device | `pages/fleet/DevicesPanel.tsx` edit form + row buttons; `live-map/TabletDetailSheet.tsx` for unpaired tablets | android id, model, app version, battery, network, last seen, vehicle, kiosk, update pending, locate state | heartbeat history, command log, rotate secret, version rollout view, offline alerting |
| Trip | `pages/trips/TripDetailModal.tsx` (492 lines) | full fare breakdown, variance vs device, close with split payments, flag, edit, delete | GPS trace on a map inline, payments list, receipt resend, rating, audit trail |
| Shift | `pages/shifts/ShiftReportModal.tsx` | driver, vehicle, times, trips, distance, cash/card/PSL, PDF/CSV | breaks (endpoints exist, never called), trip list, timeline |

Backend capability the UI never uses (all real, all tested): `GET /v1/vehicles/{id}/position-history`
(only inside the map modal), `GET /v1/ratings` per driver/trip, `GET /v1/wallet/drivers/{id}`,
`POST /v1/shifts/{id}/break/start|end`, `POST /v1/trips/{id}/receipt/email|sms`,
`GET /v1/trips/earnings/today`, `POST /v1/zones/{id}/plot` and `/unplot` (the rank queue),
`POST /v1/jobs/offers/{id}/accept|decline`, `POST /v1/jobs/availability`,
`POST /v1/fleet/devices/{id}/rotate-secret`, six of nine `/v1/payments/*` endpoints (detail,
patch/refund, link, cash, manual, Cabcharge authorize, TTSS claim), `GET /v1/fatigue-alerts/{id}`,
`GET /v1/tariffs/active` (signed) and `/signing-public-key`, `GET /v1/tariffs/change-log/{id}`.

Kit gaps: no global search or command palette, no date-range picker (bare `<input type=date>`
pairs), no shared export utility (six bespoke CSV/PDF paths), table has client-side sort only
(no server sort, selection, bulk actions, column config, virtualisation), `Sheet` has no scrim
and no click-outside close, only two charts in the whole app, role gating is scattered
`role === "owner" || role === "admin"` checks over four string literals, i18n is a 50-key
nav-only dictionary. Ten page directories have zero tests.

---

## 2. Target: what an MTI / iCabbi-class console has that we do not

From MTI's own product pages (Smart Dispatch, Driver Management & Training, Fleet Portal) and
iCabbi Dispatch, the recurring capabilities, mapped to us:

| Capability (their wording) | Ours today | Workstream |
|---|---|---|
| Driver records: profiles, licences, insurance, performance statistics; "driver performance profiling", "five star ratings", "jobs accepted and declined", "driver onboarding" | 7 fields in a modal | §4 Driver page |
| Vehicle records: registration, compliance, attributes | edit form + map modal | §5 Vehicle page |
| In-cab MDT/tablet fleet management | row buttons | §6 Device page |
| Fleet operator portal: "real-time driver ratings, upcoming trips, lost trips" | Overview (done 2026-09-08) | §3 F6, §8 |
| Zone profiling, dispatch by zone/ETA, plot queue | zones page, no queue UI | §8 Dispatch |
| Payments & billing, multiple channels | read-only payments list | §9 Payments |
| "Over 290 reporting parameters" | 3 report endpoints inside Compliance | §10 Reports |
| Staff records & user management, access control | users only via Drivers tab | §11 Staff & roles |
| Passenger records, corporate accounts, VIP flags | corporate accounts + vouchers only | §13 (later) |
| Real-time driver notification & feedback | Messages page | §4 (message from driver page) |

The single biggest structural gap is **no entity pages**. Everything else hangs off that.

---

## 3. Foundations (build first; every later workstream depends on these)

### F1. Entity routes and the page shell
- Add nested routes in `src/router.tsx`: `/drivers/:driverId`, `/vehicles/:vehicleId`,
  `/devices/:deviceId`, `/trips/:tripId`, `/shifts/:shiftId`. Keep the list pages where
  they are (`/fleet` tabs, `/trips`, `/shifts`); rows navigate to the page instead of opening
  a modal. The modals stay for one release behind a `?legacy=1` query so nothing regresses,
  then are deleted in the workstream that replaces them.
- New `src/components/layout/EntityPage.tsx`: header strip (avatar/icon, title, status badge,
  key facts row, primary actions on the right), tab bar (`Tabs` from the kit, tab in the URL
  as `?tab=`), content area, right rail for "at a glance" cards on wide screens. Back link to
  the list that preserves the list's filters (store list filter state in the URL first: see F4).
- Breadcrumbs component (`Fleet & Drivers › Drivers › Arsalan Rehman`).
- Deep links from everywhere an entity is named: `VehicleDetailModal` driver name → driver
  page; Overview "Fleet right now" rows → vehicle page; duress event → driver and vehicle;
  Trips rows → driver/vehicle links in cells. Add one `EntityLink` component
  (`<EntityLink kind="driver" id name />`) and use it in every table cell that names an entity.
- Tests: route renders each page shell with a mocked entity; 404 shell for a missing id.

### F2. Global search and command palette (`Ctrl/⌘+K` and `/`)
- Endpoint: `GET /v1/search?q=` (new, backend) returning up to 5 hits per kind: drivers
  (name, phone, driver code), vehicles (rego, VIN), devices (android id, model), trips
  (id prefix, receipt ref), shifts (id). Tenant-scoped, indexed by simple `ILIKE` on the
  columns above; Postgres `pg_trgm` later if slow. Test with pytest.
- `src/components/CommandPalette.tsx`: opens with `⌘K`, lists recent pages and quick actions
  ("Start shift", "New job", "Push update to every tablet"), and live search results grouped
  by kind, arrow-key navigation, Enter opens the entity page. Also the topbar search box on
  every page (currently there is none). Recent items in `localStorage`.
- Tests: MSW search results, keyboard flow.

### F3. Date-range picker and shared export
- `src/components/ui/DateRangePicker.tsx`: presets (Today, Yesterday, This week, Last 7 days,
  This month, Last 30 days, Custom), timezone-aware (`Australia/Sydney` from tenant when the
  jurisdiction field lands; browser-local until then, labelled), emits ISO from/to. Replace
  the bare date inputs in `trips/index.tsx`, `VehicleReportsModal`, `hooks/useReports`.
- `src/lib/export.ts`: `downloadCsv(rows, columns, filename)` and `downloadJson`; port
  `pages/trips/csv.ts` and `pages/audit-log/csv.ts` onto it. Every table in the plan gets an
  Export button through this one path.
- Tests for both.

### F4. Table upgrades (`src/components/ui/Table.tsx`)
- Server-side sort and pagination props (`sort`, `onSortChange`, `page`, `onPageChange`,
  `total`) alongside the existing client mode; URL-synced filter state helper
  (`useUrlState`) so list filters survive navigation and are shareable.
- Row selection + bulk action bar (used by §6 "push update to selected", §4 "message
  selected drivers").
- Column visibility menu persisted per table id in `localStorage`.
- Sticky header, density toggle, empty/loading rows via `Skeleton`.
- Tests for selection, sort callbacks, URL state.

### F5. Permissions matrix
- `src/lib/permissions.ts`: `can(user, "driver.edit" | "driver.deactivate" | "vehicle.delete" |
  "device.command" | "payment.refund" | "tariff.write" | …)` backed by one table keyed by
  role (`owner`, `admin`, `dispatcher`, `driver`, plus `platformOwner` predicate). Replace the
  scattered `role === …` checks as each page is touched. Backend already enforces; this is
  for honest UI (disabled + tooltip, not hidden). Sidebar keeps showing items (deliberate,
  see `Sidebar.tsx:124-131`).
- Tests: matrix snapshot per role.

### F6. Realtime hub
- One `src/lib/realtime.ts` that owns the three hand-rolled sockets (`useLiveMap`,
  `messages/useMessagesLive`, `duress/useDuressLiveGps`) with shared backoff, one connection
  per socket, and a `useRealtimeEvent(kind)` subscription API. Needed so a driver page can
  show "on trip now" without opening a second position socket.
- Backend: extend `WS /v1/fleet/live` to carry `{type:"position"|"status"|"trip"|"device"}`
  frames (today it is positions only; the placeholder `"unknown"` status bug fixed on
  2026-09-09 shows why the frame needs an explicit type and a real status). Additive, keep
  the old shape valid.

---

## 4. Driver page `/drivers/:id`

Header: photo, name, driver code, phone (click to copy), status badge (`available` /
`on trip` / `break` / `offline` from live feed), on-shift since, current vehicle (link),
current trip (link). Actions: **Message**, **Edit**, **Assign vehicle**, **Start/End shift**,
**Reset meter PIN** (new endpoint), **Deactivate/Reactivate** (`PATCH /v1/users/{id}` status),
**Upload photo**.

Tabs (each a lazy component under `src/pages/drivers/tabs/`):

1. **Overview** — today: trips, fares, km, hours on shift (`GET /v1/trips?driver_id&start_from`,
   `GET /v1/shifts?driver_id`); 30-day sparklines (trips/day, revenue/day) from
   `GET /v1/reports/revenue?group_by=day&driver_id=` (add `driver_id` filter to the report,
   backend); rating summary (`GET /v1/ratings/summary?driver_id=`); acceptance rate from jobs
   (`GET /v1/jobs?driver_id=` offers accepted/declined — the MTI "jobs accepted and declined"
   metric); fatigue: open alerts (`GET /v1/fatigue-alerts?driver_id=`), hours in the last 24 h.
2. **Shifts** — table with breaks (`break/start`/`break/end` exist; show them), reconciled
   badge, PDF/CSV per row (reuse `ShiftReportModal` internals), "Record break" action for the
   open shift.
3. **Trips** — server-paginated `Table` (F4) filtered to the driver, with the fare columns the
   Trips page has, Export, click → trip page.
4. **Earnings & wallet** — wallet balance and transactions (`GET /v1/wallet/drivers/{id}`,
   `GET /v1/wallet/transactions?driver_id=`), add adjustment (`POST /v1/wallet/transactions`),
   PSL owed from the shifts, incentives earned (`GET /v1/incentives?driver_id=`).
5. **Ratings** — list (`GET /v1/ratings?driver_id=`) with comment, trip link, distribution bar.
6. **Compliance** — licence and authority expiries (edit for owner/admin, existing), documents
   for this driver (`GET /v1/compliance/documents?subject_type=driver&subject_id=` — add the
   subject filter on the backend if missing), upload, expiry timeline, the sidebar compliance
   badge count should come from the same query.
7. **Messages** — the driver's thread (`GET /v1/messages?driver_id=`), send from here
   (`POST /v1/messages`), templates.
8. **Devices & vehicles** — vehicles this driver has been bound to (from shift history), the
   tablet last used (`GET /v1/fleet/devices?driver_id=` or derived from shifts), kiosk state.
9. **Activity** — audit log entries where `actor_id` or `subject_id` is this driver
   (`GET /v1/audit-log?subject_id=` — add filter on backend).

Backend additions: `PATCH /v1/users/{id}` status transitions with audit; `POST /v1/users/{id}/reset-pin`
(owner/admin, returns a one-time PIN, audited); `driver_id` filters on ratings summary, revenue
report, wallet transactions, audit log, compliance documents, incentives. Each with a pytest.

Acceptance: the modal in `DriversPanel` is deleted; clicking a driver anywhere opens the
page; every tab renders real data in MSW tests; a non-admin sees actions disabled with a
reason; page loads in under 1 s with the Overview tab's queries only (others lazy).

## 5. Vehicle page `/vehicles/:id`

Header: rego, make/model/year, class badge (standard / WAT / maxi), vehicle status
(`active` / `maintenance` / `suspended` / `retired`), live status, current driver (link),
paired tablet (link), position age. Actions: **Edit**, **Pair tablet** (pairing code flow
from `VehiclesPanel`), **Locate**, **Evidence pack**, **Compliance dossier PDF**
(`GET /v1/compliance/vehicles/{id}/dossier.pdf` — exists, unlinked), **Set status**,
**Delete** (owner, confirm).

Tabs:
1. **Live** — embed `FleetMapCanvas` selected on this vehicle with follow, trail controls
   and the position-history replay moved here from `VehicleDetailModal` (promote the SVG
   `ReplayMiniMap` to the Mapbox trail already built on the Live Map); harsh brake / rapid
   accel counts with the "informational, not a safety score" framing the backend insists on.
2. **Trips** — as driver trips, filtered by vehicle; toll column and airport-fee column.
3. **Shifts** — `GET /v1/fleet/vehicles/{id}/shift-history` (exists).
4. **Compliance** — registration, CTP, inspection, camera cert, taxi licence expiry dates
   (fields exist on `Vehicle`, see `fleet.py`), the `compliance-expiry` feed, documents for
   `subject_type=vehicle`, dossier download. The 0028 expiry-year bug seen on the live data
   gets a validation: year must be within ±20 of today.
5. **Reports** — the two tabs of `VehicleReportsModal` (lifetime totals, pilot report) with
   the F3 date range and F3 export.
6. **Tolls** — tolls charged on this vehicle's trips grouped by road, from trips'
   `auto_tolls_applied` + the registry (`GET /v1/toll-roads`); airport access fees listed
   separately (kind `airport` zones, 2026-09-09 workstream).
7. **Maintenance** *(new backend)* — odometer readings (from trip distance accumulation),
   service log entries (`vehicle_maintenance` table: date, odometer, type, notes, cost,
   next-due), next service due banner. Small Alembic migration + CRUD + tests.
8. **Activity** — audit entries for the vehicle.

Acceptance: `VehicleDetailModal` and `VehicleReportsModal` are removed; map dot click →
vehicle page Live tab (`/vehicles/:id?tab=live`); the Fleet list row click → page; the edit
form becomes a `Sheet` opened by the Edit action.

## 6. Device (tablet) page `/devices/:id`

Header: model, android id (copy), app version vs latest release (`GET /v1/platform/app-releases`
— show "update available" when behind), battery with charging state, network, last heartbeat
age (colour by the 15-minute offline rule used in `overview/activityFeed.ts`), paired vehicle
(link), kiosk state. Actions: **Kiosk lock/unlock**, **Restart app**, **Force update**,
**Locate**, **Reboot**, **Rotate secret** (`POST /v1/fleet/devices/{id}/rotate-secret`,
exists, unwired; shows the new secret once, audited), **Unpair**, **Revoke**.

Tabs:
1. **Status** — the header facts expanded, current location on a small map, last locate
   response with accuracy.
2. **Heartbeats** *(new backend)* — `device_heartbeats` table (device_id, at, battery,
   charging, network, app_version, lat/lng if present) written by the existing heartbeat
   handler with a 30-day retention job; endpoint `GET /v1/fleet/devices/{id}/heartbeats?from&to`;
   UI: battery and connectivity charts (recharts), gaps highlighted as offline periods.
3. **Commands** *(new backend)* — `device_commands` log (kind, requested_by, requested_at,
   acked_at, result) written by kiosk-lock / restart / force-update / locate / reboot handlers
   (they already track pending/acked flags on the device row; persist the history);
   endpoint `GET /v1/fleet/devices/{id}/commands`; UI table with pending → acked timing.
4. **Versions** — app version history from heartbeats; rollout view on the Devices list
   (how many tablets on each version) with "push update to selected" using F4 selection.
5. **Activity** — audit entries.

Alerts (see §12 Notifications): tablet offline > 15 min during an open shift, battery < 20 %
not charging, version behind for > 7 days.

## 7. Trip page `/trips/:id` and shift page `/shifts/:id`

Trip: header (driver, vehicle, start → end, status, total, payment method, flags); tabs
**Fare** (the existing breakdown + variance vs device, tolls itemised by road, airport fee
line, PSL, GST); **Route** (`TripRouteMap` + `gps-trace` with speed colouring, stops);
**Payments** (`GET /v1/payments?trip_id=`, detail, refund/adjust via `PATCH`, payment link,
manual/cash capture — §9); **Receipt** (preview, resend by email/SMS — endpoints exist);
**Rating**; **Audit**. Actions: close (split legs, existing), flag, edit, delete.

Shift: header (driver, vehicle, started/ended, duration, reconciled); tabs **Summary**
(report figures), **Trips** (list), **Breaks** (record/edit), **Timeline** (shift start,
breaks, trips, duress, fatigue alerts on one time axis), **Reconciliation** (cash/card/PSL,
mark reconciled). Replace `ShiftReportModal`.

## 8. Dispatch upgrades

1. **Rank/zone plot queue** — `POST /v1/zones/{id}/plot`, `/unplot`, `GET /v1/zones/stats`
   exist. UI on the Zones page and on the Dispatch board: per zone, the ordered queue of
   plotted vehicles with wait time, drag to reorder (owner/dispatcher), unplot, and the
   zone stats. The tablet's ZONES screen already plots; this makes it visible.
2. **Offer intervention** — on a job's offers list, dispatcher can force-accept/decline
   (`POST /v1/jobs/offers/{id}/accept|decline`) and set driver availability
   (`POST /v1/jobs/availability`). Show the offer timeline per job.
3. **Booking board** — jobs list as a kanban (New → Offered → Accepted → En route → On
   trip → Done/Cancelled) with the live socket (F6) moving cards; ETA column from the
   route hook already used on the map (`useVehicleRoutes`).
4. **Dispatch by ETA** (later) — needs a routing call per candidate; design only in this plan.

## 9. Payments module (`/payments`, replaces read-only Payment Recon)

List with F3/F4; detail sheet; refund/adjust (`PATCH /v1/payments/{id}`), payment link
(`POST /v1/payments/link`), manual and cash capture, Cabcharge authorize, TTSS claim — all six
endpoints exist and are tested on the backend. Reconciliation view keeps the current page's
matching logic. Permission `payment.refund` (F5) owner/admin only.

## 10. Reports & analytics module (`/reports`)

A real reports route: Revenue (day/week/month, by driver, by vehicle, by payment method),
GST summary, NSW Point to Point export, Tolls by road, Airport fees, Driver performance
(trips, revenue, rating, acceptance, fatigue), Vehicle utilisation (hours on shift vs trips,
km), Tablet health. Each report: F3 range, chart (recharts, the `dataviz` skill's palette),
table, Export. Backend: extend `/v1/reports/revenue` with `driver_id`, `vehicle_id`,
`group_by=driver|vehicle|payment_method`; add `/v1/reports/tolls`, `/v1/reports/drivers`,
`/v1/reports/vehicles`, `/v1/reports/devices` — each a pure aggregation with tests. Scheduled
email reports are out of scope for this plan.

## 11. Staff & roles (`/settings/staff`)

Users of role owner/admin/dispatcher: list, invite (existing `/v1/platform/invites` pattern
or `POST /v1/users`), role change, deactivate, MFA status (from `/v1/auth`), sessions revoke
(exists). Role descriptions rendered from the F5 matrix so an owner sees what each role can
do. Tablet impact: none.

## 12. Notifications and alert centre

Bell in the topbar, `/alerts` page: open duress, compliance expiring (30/14/7 days), tablet
offline during shift, battery low, fatigue alerts, flagged trips, failed payments, version
behind. Backed by a `GET /v1/alerts` aggregator (backend: compose from existing tables, no
new storage; mark-read stored per user in a small `alert_reads` table). Browser notifications
opt-in for duress only.

---

## 13. Backend additions summary (each is its own commit with tests)

| Endpoint / change | Needed by |
|---|---|
| `GET /v1/search?q=` | F2 |
| `driver_id` / `vehicle_id` / `subject_*` filters on ratings summary, revenue report, wallet transactions, audit log, compliance documents, incentives, messages | §4, §5 |
| `POST /v1/users/{id}/reset-pin`, `PATCH /v1/users/{id}` status with audit | §4 |
| `vehicle_maintenance` table + CRUD | §5.7 |
| `device_heartbeats` history table + retention + `GET …/heartbeats` | §6.2 |
| `device_commands` log + `GET …/commands` | §6.3 |
| Typed frames on `WS /v1/fleet/live` | F6 |
| `/v1/reports/tolls`, `/drivers`, `/vehicles`, `/devices`; revenue `group_by` extensions | §10 |
| `GET /v1/alerts`, `alert_reads` | §12 |
| Vehicle expiry-date validation (year within ±20) | §5.4 |

Nothing here changes the tablet's API contract; the tablet is untouched except that the
airport-fee zones (2026-09-09) and typed socket frames are additive.

---

## 14. Order of execution and parallelism

Phase A (foundations, sequential, ~5 commits): F1 → F4 → F3 → F5 → F2. F6 can run in
parallel with F2.
Phase B (entity pages, can run three in parallel on separate files): §4 Driver → §5 Vehicle →
§6 Device, then §7 Trip/Shift. Each page lands with its backend filters in the same commit
series (backend commit first, dashboard commit second).
Phase C: §8 Dispatch, §9 Payments, §10 Reports (parallel, independent).
Phase D: §11 Staff, §12 Alerts.

Guard for an autonomous run: after each commit run the full dashboard suite, tsc and build;
after each backend commit run the affected pytest subset and ruff; never leave the trunk
red. If a workstream needs a decision the plan does not settle (a new table's exact
columns, a report's grouping), choose the simplest option that keeps every displayed number
real, write the choice into the commit message, and continue.

## 15. Acceptance for the whole plan

- Clicking any driver, vehicle, tablet, trip or shift anywhere in the app opens a full page
  with a shareable URL, tabs, and every related record the backend holds.
- `⌘K` finds any rego, driver, phone, android id or trip in under 300 ms against MSW and
  under 1 s against the real server.
- Every list has server pagination, URL-persisted filters, a date range with presets, and
  Export.
- Payments, rank queue, offers, breaks, receipt resend, rotate secret, dossier: all reachable
  in the UI.
- Test count at least doubles (45 → 90+ files); every page directory has at least one test.
- No placeholder numbers anywhere; error states name the endpoint that failed.

## 16. Out of scope for this plan

Passenger app and passenger records, corporate invoicing beyond the existing accounts,
dispatch-by-ETA routing engine, scheduled email reports, multi-site/bureau networking,
telephone booking (IVR) integration, i18n beyond `en-AU` (the jurisdiction field on Tenant
must land first). Each deserves its own plan once Phases A–D are in.
