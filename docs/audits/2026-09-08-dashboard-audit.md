# Fleet-Ops Web Dashboard Audit — `dashboard/src` (2026-09-08)

**Scope:** 160 files / 31,132 lines. React 18 + TS 5.6 + Vite 5 + TanStack Query v5 + react-router 7 + mapbox-gl 3 + recharts 2. No test runner, no ESLint config.

**Headline:** a genuinely mature codebase. Nearly every page is wired to a real backend endpoint with loading/error/empty states, confirm modals, and honest "we can't do this" comments. There are **zero** `TODO`/`FIXME`, zero `alert()`, zero `console.log`, zero `onClick={() => {}}`, zero hardcoded mock data arrays, and zero `any` types. The real gaps are **systemic** (tests, i18n, tenancy, design-system fragmentation, role-gating holes), not per-page stubs.

---

## 1. Page-by-page

| Page | What it does | Backend endpoints | State | Specific gaps (file:line) |
|---|---|---|---|---|
| **login** `pages/login/index.tsx` | Email/password → two-step TOTP MFA | `POST /v1/auth/login`, `/v1/auth/mfa/login` (`lib/auth.tsx:79,93`) | **DONE** | No "forgot password" / reset. No MFA recovery codes — losing the authenticator locks you out permanently. Hardcoded `"CD"` badge + `"Cab Dispatch Fleet Ops"` (`:77,79`) — no tenant white-label on the one page every tenant sees first. `bg-brand-lavender` (`:73`) is a light-only token → wrong in dark mode. |
| **getting-started** | 4-item onboarding checklist from real counts | reuses `/v1/fleet/vehicles`, `/v1/drivers`, `/v1/tariffs` | **DONE** | 4th item ("Review compliance") is a permanent unchecked `Circle` (`:113`). `dark:text-emerald-400` (`:88`) is dead (see §4). No "add a device" or "publish a tariff" step. |
| **live-map** `pages/live-map/` (13 files, 4,177 lines) | Mapbox fleet map + WS positions, vehicle/tablet sheets, trails, geofences, duress pins, remote locate | `WS /v1/fleet/live`; `GET /v1/vehicles`, `/{id}`, `/v1/drivers/{id}`, `/v1/duress`, `/v1/geofences`, `/v1/fleet/devices`; `POST /v1/fleet/positions` | **DONE** | `FleetMapCanvas.tsx` is **1,548 lines** — Mapbox imperative code, SVG fallback, marker DOM, 3 `eslint-disable` (`:1051,1083,1112`). `DEFAULT_CENTER` hardcoded to **Karachi** `[67.0011, 24.8607]` (`:111`) with user-visible copy "showing the default region (Karachi, currently, for field testing)" (`:1272`). Map fetch capped at 100 — silently truncates larger fleets. |
| **dispatch** | Job list + create, live offer panel | `GET/POST /v1/jobs`, `/{id}`, `/{id}/offers`, `DELETE` (`dispatch/api.ts:5-24`) | **PARTIAL** | **No role gate** — a `driver` login sees an enabled "New job". `CreateJobModal.tsx:93-134` requires **hand-typed lat/lng** for pickup/drop-off; no geocoder, no map picker, despite Mapbox being a dependency. Fare estimate typed by hand (`:148,159`). |
| **messages** | Driver picker + thread, templates, read receipts, load-older | `GET /v1/drivers`, `GET/POST /v1/messages`, `/{id}/read`, `/templates`, `/templates/{code}` | **PARTIAL** | Unread badges are an **N+1 fan-out** — one fetch per driver every 30 s (`messages/index.tsx:47-54`). Driver list capped (`api.ts:31`). No broadcast/all-drivers send. No attachments. No role gate. |
| **duress** `pages/duress/` (16 files) | Event desk, escalation timeline, live GPS WS, audio, snapshots, device provisioning + secret rotation | `GET/POST /v1/duress`, `/{id}/cancel\|escalate\|close\|call`, `/audio`, snapshots; `/v1/duress-devices` CRUD; `WS` live GPS | **PARTIAL** | Events table renders **raw UUIDs** for Vehicle and Driver (`duress/index.tsx:66-67`). `TriggerEventModal.tsx:66,74` asks the operator to paste UUIDs. No sound/desktop notification on a new event despite being a safety desk. |
| **trips** | Filterable ledger, detail modal w/ breakdown + route map, CRUD | `GET/POST /v1/trips`, `PATCH`, `/close`, `/flag`, `DELETE`, `/gps-trace` | **PARTIAL** | **No role gating** (no `useAuth` in the folder) — `driver` sees New/Edit/Delete. Date + text filtering is **client-side over a capped 200-row fetch** (`trips/index.tsx:36-41`). No CSV export of the filtered view. |
| **shifts** | Shift list + reconciliation, start/end/edit/delete, report modal | `GET/POST /v1/shifts`, `/start`, `/{id}/end`, `/{id}/report`, `PATCH`, `DELETE` | **DONE** | No bulk "mark reconciled". No date-range filter. |
| **tariffs** `pages/tariffs/` (12 files) | Rate cards CRUD, extras, NSW toll registry + gantry map, toll zones, change log, fares-order suggest | `/v1/tariffs` CRUD + `/extras`, `/v1/fares-order/current`, `/presets`, `/suggest`, `/v1/toll-roads` + `/gantries` + `/price-revisions`, `/v1/geofences` | **DONE** | Writes are `isPlatformOwner`-only (`:55`) — an ordinary tenant owner **cannot create a tariff**; blocks self-serve onboarding step 3. 4 raw `<table>` bypass the `Table` kit (`NswTollRoadsPanel.tsx:297,329,353`, `ChangeLogModal.tsx:110`, `TollGantryMap.tsx:248`). |
| **zones** | Live per-zone supply/demand grid + zone CRUD | `GET /v1/zones/stats` (20 s), `POST/PUT/DELETE /v1/zones` | **PARTIAL** | Zone CRUD is `isPlatformOwner`-gated (`ZonesPanel.tsx:21`) — a tenant dispatcher cannot create dispatch zones. No sort/search/map view. |
| **psl** | PSL levy ledger, top-ups, monthly remittance | `/v1/psl/ledger` CRUD, `/topups`, `/topup`, `/report` | **PARTIAL** | Backend caps at `limit=200` with no total → client-side pagination over a truncated page (`psl/index.tsx:24-28,331`). Top-ups read-only. No bulk "mark remitted". |
| **fleet** `pages/fleet/` (11 files, 3,300 lines) | Vehicles/drivers/devices CRUD, kiosk lock, force update, locate, fatigue & compliance banners, evidence pack, bulk wipe | ~25 endpoints | **PARTIAL** | `fleet/api.ts` is **804 lines**. **"Wipe all fleet data" is shipped** — self-described as "TEMPORARY testing-only tooling" that "should be removed from the dashboard entirely once onboarding testing is done" (`fleet/index.tsx:60-82`) — in the page header of a production page. VehiclesPanel/DevicesPanel have **no role gate**. Reboot column permanently disabled (`DevicesPanel.tsx:51-67`). Vehicle list capped at 100 (`api.ts:24`). |
| **compliance** | Per-vehicle document vault + cl.14 checklist, NSW PtP CSV export, revenue/GST charts | `/v1/compliance/documents` CRUD, dossier, `/v1/reports/revenue`, `/gst-summary`, `/nsw-ptp-export` | **DONE** | One vehicle at a time — no fleet-wide "what's expiring" view. No bulk upload. Recharts colours hardcoded. |
| **billing** | Per-vehicle subscriptions, invoices, Stripe Connect | `/v1/billing/subscriptions` CRUD, `/invoices`, `/connect/onboard` | **PARTIAL** | Stripe in **mock mode** (`useBilling.ts:47,53,71`), "Simulated" badge (`billing/index.tsx:210,470`). No payment-method management, no PDF, no webhook status. Invoices client-paginated over an **unbounded** fetch (`:495`). `useMemo`-as-effect anti-pattern (`:614-620`). |
| **vouchers** | Voucher + corporate-account ledgers | `/v1/vouchers`, `/v1/corporate-accounts` CRUD | **DONE** | No bulk generation, no CSV, no spend/credit limit. |
| **announcements** | CRUD | `/v1/announcements` | **DONE** | No tablet preview, no targeting. |
| **incentives** | CRUD | `/v1/incentives` | **DONE** | No per-driver progress view. |
| **wallet** | One driver's ledger + balance | `GET /v1/wallet/drivers/{id}`, `POST /v1/wallet/transactions` | **PARTIAL** | **One driver at a time** — no fleet-wide balance list. "Most recent 50" (`:164`), no load-more. No reversal. |
| **ratings** | Star ratings, fleet average, leaderboard | `GET /v1/ratings` | **PARTIAL** | Averages computed **client-side over a capped 200-row fetch** (`RatingsPage.tsx:9-17`) — "fleet average" is really "average of the last 200". |
| **audit-log** | Hash-chained trail + verification + JSON diff | `GET /v1/audit-log`, `/verify`, `/v1/users` | **DONE** | No export. Before/After as raw `<pre>` (`:318,326`). Entity/action filters are free text (`:201,210`). |
| **settings/white-label** | Logo URL + 2 hex colours, preview | `GET/PATCH /v1/tenants/me` | **PARTIAL** | Logo is a **URL field only** (`:215`). Only 2 of ~16 tokens themeable. Ships a hardcoded **"Lilly Cabs" demo preset** with a `placehold.co` URL and a "Fill with Lilly Cabs preset" button (`:38-43,240`). Backend 403s for `admin` role. |
| **settings/security** | TOTP enable/verify/disable, admin PIN | `POST /v1/auth/mfa/*`, `/v1/tenants/{id}/admin-pin` | **PARTIAL** | **No QR code** — `otpauth://` shown as text (`:186,195`). No password change. No session list. No recovery codes. |
| **platform** (owner-only) | Cross-tenant health, MRR, tenant CRUD, APK releases | `/v1/platform/*`, `/app-releases` | **DONE** | 764 lines in one file. Create-tenant form has no validation; `plan` is free text (`:691`). No tenant delete/impersonate. |

---

## 2. Navigation & role gating

**Every route is in the nav and vice versa.** `Sidebar.tsx:44-82` (22 items) ↔ `router.tsx:47-78`, plus `PLATFORM_NAV_ITEM` (`:86`) via `isPlatformOwner` (`:99`) matching `PlatformOwnerRoute` (`router.tsx:73`).

Gaps: flat 22-item list, no grouping, no collapse, no search; `Sidebar.tsx:108` is a fixed `w-64 h-screen` — **no responsive behaviour anywhere**. No breadcrumbs, no `<title>`. Wildcard `*` redirects to `/live-map` (`router.tsx:78`) — no 404 page. **No error boundary** — a render throw blanks the app.

**Role gating uses five different idioms:**

| Idiom | Pages |
|---|---|
| `MANAGE_ROLES = new Set([...])` | compliance `:43`, psl `:18`, shifts `:28`, duress/DevicesPanel `:20` |
| inline `role === "owner" \|\| "admin"` | vouchers `:56`, announcements `:46`, incentives `:33`, wallet `:27`, ratings `:59`, audit-log `:32` |
| inline + dispatcher | billing `:88` |
| `isPlatformOwner(user)` | tariffs `:55`, ExtrasSection `:36`, TollZonesPanel `:26`, NswTollRoadsPanel `:31`, zones/ZonesPanel `:21` |
| `CAN_PUBLISH_ROLES` | live-map `:434,488` |
| **none** | **trips**, **dispatch**, **messages**, **fleet/VehiclesPanel**, **fleet/DevicesPanel**, **zones/index** |

Two pages render an access notice instead of a failing table (`WalletPage.tsx:86`, `RatingsPage.tsx:151`) — good pattern, applied in 2 of 22 places.

---

## 3. Real-time: WebSocket vs polling

**3 WebSockets:** `hooks/useLiveMap.ts:66` (`WS /v1/fleet/live`, backoff to 15 s, refetches REST on re-open), `pages/messages/useMessagesLive.ts:41`, `pages/duress/useDuressLiveGps.ts:39`.

**Polling (12 sites):** 3 s `dispatch/index.tsx:46`; 5 s `live-map/index.tsx:249`; 8 s `duress/EventDetailPanel.tsx:66`; 10 s `duress/index.tsx:54`, `SnapshotGallery.tsx:47`; 20 s `live-map/index.tsx:163`, `hooks/useZones.ts:80`; 30 s `fleet/api.ts:84`, `messages/index.tsx:27,52`; 60 s `fleet/api.ts:478,514` (+ `Sidebar.tsx:104` on every page).

Gaps: no shared constants; polling continues when the tab is hidden (no `refetchIntervalInBackground: false`); Duress and Jobs lists poll despite WS-capable backends.

---

## 4. Tenancy / global readiness

**Currency/locale — hardcoded `"en-AU"` + `"AUD"` in 12 duplicate helpers:** `hooks/useBilling.ts:83,90` · `hooks/useReports.ts:91` · `dispatch/format.ts:11,20` · `compliance/format.ts:23` · `driver-engagement/format.ts:11,18` · `fleet/format.ts:31` · `psl/format.ts:8,15,22,37` · `shifts/format.ts:8,22` · `tariffs/format.ts:11,18` · `trips/format.ts:10,24` · `vouchers/format.ts:11,18` · `platform/format.ts:53` · `live-map/ResolveDuressModal.tsx:74`.

**Regulatory copy in UI strings:** `compliance/NswPtpExportCard.tsx:38,40-41` · `compliance/index.tsx:57` · `fleet/VehiclesPanel.tsx:474` · `fleet/DriversPanel.tsx:351,389,483,504` · `fleet/index.tsx:38-39` · `getting-started/index.tsx:41,61,117` · `trips/index.tsx:243` · `tariffs/index.tsx:43,147` · `psl/index.tsx:232` · `tariffs/NswTollRoadsPanel.tsx:17,21,112` · `hooks/useTollRoads.ts:5,168-169` · `hooks/useReports.ts:133,137`. The entire `/psl` route and the "NSW Toll Roads" tab are NSW-only concepts.

**Geographic defaults:** `live-map/FleetMapCanvas.tsx:106-111` Karachi; `:1272` user-visible copy; Sydney placeholders in `dispatch/CreateJobModal.tsx:82-134`, `live-map/PublishPositionModal.tsx:107,123`, `zones/ZoneFormModal.tsx:146`, `tariffs/TollZoneFormModal.tsx:135,155`, `trips/TripFormModal.tsx:434`; `duress/DeviceFormModal.tsx:138` `"+61…"` with no country selector.

**Tenant naming:** `settings/white-label/index.tsx:38-43,240` Lilly Cabs preset; `Sidebar.tsx:119,122`, `login/index.tsx:77,79`, `settings/security/index.tsx:125` "Cab Dispatch".

**Timezone:** every timestamp is browser-local `toLocaleString` with no indicator. **i18n:** none.

---

## 5. Design consistency

**UI kit** (`components/ui/index.ts`): `Button` (6 variants × 4 sizes, CVA), `Badge` (6 variants), `Card`, `Input`, `Select`, `Modal`, `Sheet` (used in 2 places), `Table` (client sort + optional pagination), `PageHeader`.

**Missing from the kit — hand-rolled repeatedly:**
- **No `Tabs`.** Eight pages hand-roll tab bars in **two incompatible styles**: underline (`zones/index.tsx:31-45`, `tariffs/index.tsx:158-173`, `vouchers/index.tsx:206-221`, `fleet/index.tsx:282-297`) vs. segmented pill (`billing/index.tsx:107-122`, `compliance/index.tsx:60-71`, `duress/index.tsx:103-110`, `psl/index.tsx:247-284`).
- **No `Checkbox`.** Raw `<input type="checkbox">` in 20 files.
- **No `Pagination`.** The prev/next block is copy-pasted in **11 places** plus a separate `fleet/PaginationBar.tsx`.
- **No `Toast`, `Spinner`/`Skeleton`, `EmptyState`, `ErrorBanner`, `Tooltip`.**

**Raw-Tailwind bypasses:** 4 raw `<table>` in Tariffs; 8 files never import the kit.

**Dark mode — partially broken:** `tailwind.config.js:3` sets `darkMode: ["class"]` but nothing adds `.dark`, so all three `dark:` utilities are dead (`fleet/DriversPanel.tsx:400`, `getting-started/index.tsx:88`, `tariffs/NswTollRoadsPanel.tsx:283`). Nothing sets `data-theme` — no theme toggle. `--brand-lavender` (`index.css:13`) never redefined for dark and equals `--foreground`'s dark value — affects `login/index.tsx:73`, `messages/index.tsx:112`, `zones/ZoneStatsPanel.tsx:45`, `platform/index.tsx:85,124,464`.

---

## 6. Code quality

- `any` types: zero.
- **`eslint-disable`: 25**, 22 of which are `react-hooks/exhaustive-deps` on the same "reset form when the target prop changes" anti-pattern in every form modal; `billing/index.tsx:619` is a `useMemo`-as-effect actual bug. **None are checked — there is no ESLint config; `npm run lint` is `tsc --noEmit`** (`package.json:9`).
- **Duplicated helpers:** `formatDateTime` — **12 copies**; `formatMoney` — **8 copies** + `formatAud` ×3; `errorMessage` — 6 implementations; duration formatters — 4; `initials` — 2.
- **Files >800 lines:** `FleetMapCanvas.tsx` (1,548). ≥500: `fleet/api.ts` 804, `platform/index.tsx` 764, `live-map/index.tsx` 749, `billing/index.tsx` 743, `DevicesPanel.tsx` 594, `VehiclesPanel.tsx` 572, `TripFormModal.tsx` 567, `VehicleDetailModal.tsx` 517, `DriversPanel.tsx` 512, `NswTollRoadsPanel.tsx` 501.
- **Accessibility:** 17 of 97 `.tsx` files use any `aria-*`. `Modal.tsx:44` has no focus trap/restore. `Table.tsx:96,136` sort headers and row clicks are mouse-only. No skip-link, no `aria-live`.
- **No code splitting** — `router.tsx:5-27` statically imports all 22 pages + mapbox-gl + recharts.

---

## 7. Tests

**None.** Zero `*.test.*`, no vitest/jest/playwright/cypress in `package.json`, no `test` block in `vite.config.ts`. Coverage 0%. No test on the auth refresh-on-401 interceptor (`apiClient.ts:100-138`) — subtle shared-promise logic the code's own comment says was written after a real production data-loss bug (`:56-58`).

---

## 8. Docs vs. code

| Doc | Reality |
|---|---|
| **`DASHBOARD_REDESIGN_2026.md`** | **Misleading filename — entirely about the Android driver app** (`DeckHomeScreen.kt`, `CaptainPalette.kt`). Never mentions `dashboard/src/`. **There is no design doc for the web dashboard at all.** |
| `DASHBOARD_BILLING_CONNECT_ONBOARDING.md` | Accurate except drifted line numbers. |
| `DASHBOARD_LIVEMAP_VEHICLE_DETAIL.md` | Substantially drifted — `VehicleDetailModal.tsx` is now 517 lines and renders a `Sheet`, not a `Modal`. |
| `DASHBOARD_TARIFF_EXTRAS_UI.md` | **Gating claim wrong** — `ExtrasSection.tsx:36` uses `isPlatformOwner`, not owner/admin. |
| `DASHBOARD_POLISH_ADMIN_PIN_PSL_PLATFORM.md` | Content accurate, line numbers drifted 50–400 lines. |
| `DASHBOARD_MESSAGES_TEMPLATES_READRECEIPTS_PAGINATION.md`, `DASHBOARD_DURESS_DEVICE_AUDIO_SNAPSHOTS.md` | Accurate. |

---

## 9. Prioritised backlog — parallelisable workstreams

### P0

**WS-A · Test & lint infrastructure** — *new files only; `package.json`, `vite.config.ts`*
1. `vitest` + `@testing-library/react` + `jsdom`; `test` block; `test`/`test:watch` scripts.
2. Real `eslint.config.js` (typescript-eslint + react-hooks + jsx-a11y); make `npm run lint` lint.
3. First tests: `lib/apiClient.ts:100-138` refresh-on-401, `lib/auth.tsx` MFA branch, the 9 UI-kit primitives, `format*` helpers.
4. Playwright + one smoke E2E: login → MFA → live-map → each nav group.
5. Error boundary at `router.tsx` and a real 404 page.

**WS-B · Role-gating holes** — *`pages/trips/`, `pages/dispatch/`, `pages/messages/`, `pages/fleet/VehiclesPanel.tsx`, `pages/fleet/DevicesPanel.tsx`, `pages/zones/index.tsx`*
1. Gate Trips, Dispatch, Messages, Fleet Vehicles + Devices.
2. One shared `lib/permissions.ts` (`canManage`/`canWrite`/`canAdminister`) replacing the five idioms; migrate all 16 gated sites.
3. Adopt the "render an access notice, not a failing table" pattern uniformly.

**WS-C · Remove test-only tooling from production UI** — *`pages/fleet/index.tsx`, `pages/fleet/api.ts`, `pages/settings/white-label/index.tsx`, `live-map/FleetMapCanvas.tsx`*
1. Remove or env-flag "Wipe all fleet data" (`fleet/index.tsx:83-254`).
2. Remove the "Lilly Cabs" preset and button (`white-label/index.tsx:38-43,240`).
3. Replace the Karachi `DEFAULT_CENTER` (`:106-111`) and copy (`:1272`) with a tenant-configured default centre.

### P1

**WS-D · Design system consolidation** — *`components/ui/` (new) + mechanical swaps*
1. `Tabs`; migrate the 8 hand-rolled bars. 2. `Pagination`; replace 11 copies + `fleet/PaginationBar.tsx`. 3. `Checkbox`, `Spinner`/`Skeleton`, `Toast`, `EmptyState`, `ErrorBanner`, `Tooltip`. 4. Fix dark mode: drop `darkMode: ["class"]` or apply `.dark`; real theme toggle writing `data-theme`; define `--brand-lavender` and semantic colours for dark; fix `login/index.tsx:73`. 5. Convert the 4 raw `<table>`s. 6. Accessibility: focus trap in `Modal.tsx`, keyboard sort/rows in `Table.tsx`, `aria-live`. 7. Responsive sidebar + grouped nav.

**WS-E · Shared utilities + file surgery** — *`lib/` (new) + every `pages/*/format.ts`*
1. `lib/format.ts`: one of each helper; delete the duplicates. 2. `lib/pollIntervals.ts` + `refetchIntervalInBackground: false`. 3. Split `FleetMapCanvas.tsx`, `fleet/api.ts`, `platform/index.tsx`. 4. `useResetOnChange` hook replacing the 22 suppressions; fix `billing/index.tsx:614-620`. 5. `React.lazy` routes + `manualChunks`.

**WS-F · Internationalisation & tenancy** — *every `format.ts`, `hooks/useBilling.ts`, `hooks/useReports.ts`, tenant settings*
1. Currency + locale from the tenant record (needs backend `Tenant.currency`/`timezone`). 2. Timezone handling with indicator. 3. i18n library; extract strings starting with `components/ui` + `Sidebar`. 4. Jurisdiction config abstracting `/psl`, "NSW Toll Roads", NSW PtP export, "Point to Point" copy (~15 sites). 5. Country-code selector + E.164 validation. 6. Tenant branding on login.

### P2 — feature completion
**WS-G · Dispatch usability** — geocoder + map picker; fare estimate from the active tariff.
**WS-H · Duress desk polish** — resolve UUIDs to rego/name; `<Select>` inputs; audible/desktop alert.
**WS-I · Security & account settings** — QR for `otpauth://`; password change; recovery codes; password reset; session list.
**WS-J · Reporting & export** — CSV for audit trail and trips; field-level diff; `<Select>` filters; invoice PDF.
**WS-K · Server-side pagination** — replace the five capped-fetch patterns (needs backend query params).
**WS-L · Driver-engagement completion** — fleet-wide wallet list; ledger pagination; incentive progress; announcement preview.
**WS-M · Web-dashboard design doc** — write `docs/DASHBOARD_WEB_*.md`; refresh stale line citations; rename `DASHBOARD_REDESIGN_2026.md` → `ANDROID_HOME_REDESIGN_2026.md`.
