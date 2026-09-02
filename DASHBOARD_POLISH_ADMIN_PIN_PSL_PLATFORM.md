# Dashboard polish: Admin PIN, PSL top-ups, Platform Console tenant detail

Three frontend-only fixes from a CRUD-completeness audit. No backend files were touched — all
three endpoints already existed and were already correctly permission-gated.

## 1. Admin PIN management UI (was entirely absent)

Backend: `POST /v1/tenants/{tenant_id}/admin-pin` (`backend/app/api/v1/tenants.py:68`), owner-only
(`_require_owner = require_role("owner")`, `tenants.py:31`). Request/response schemas:
`AdminPinSetRequest { pin: str }` (4-8 digits, `pattern=^\d{4,8}$`) and
`AdminPinSetResponse { tenant_id, admin_pin_configured }` (`backend/app/schemas/tenant.py:13-21`).
There is no GET/read-back route — the PIN is write-only by design.

Added:
- `dashboard/src/pages/settings/security/types.ts:20-29` — `AdminPinSetRequest` /
  `AdminPinSetResponse` types mirroring the backend schema.
- `dashboard/src/pages/settings/security/api.ts:31-46` — `setAdminPin(tenantId, body)`, posting to
  `/v1/tenants/${tenantId}/admin-pin`, following the exact pattern of the existing `mfaSetup` /
  `mfaVerify` / `mfaDisable` calls in the same file.
- `dashboard/src/pages/settings/security/index.tsx`:
  - `index.tsx:19` — `ADMIN_PIN_PATTERN = /^\d{4,8}$/`, mirrors the backend's `_PIN_PATTERN`.
  - `index.tsx:41-42` — PIN + confirm-PIN form state.
  - `index.tsx:70-76` — `adminPinMutation` (`useMutation` wrapping `setAdminPin`), clears both
    fields on success.
  - `index.tsx:107-115` — validation (`ADMIN_PIN_PATTERN` + PIN===confirm match) and submit
    handler.
  - `index.tsx:271-347` — new "Admin PIN" `<Card>` section, rendered only when
    `user?.role === "owner"` (client-side mirror of the server's owner-only gate). Two numeric
    `<Input inputMode="numeric">` fields (PIN + confirm), stripping non-digits on change exactly
    like the existing MFA 6-digit code input (`index.tsx:296-323` vs. the pre-existing
    `verify-code` input pattern). Copy explains the PIN gates factory-reset on the driver's
    tablet. The PIN is never displayed back — only a success/error message after saving
    (`index.tsx:335-346`).

## 2. PSL top-up history (was fetched but never rendered)

Confirmed via grep before any edits: `useTopUpsQuery` (`dashboard/src/hooks/usePSLCentre.ts:159`)
had zero call sites anywhere under `dashboard/src/pages`.

Added a third "Top-ups" tab to `dashboard/src/pages/psl/index.tsx` alongside the existing
"Ledger"/"Remittance report" tabs:
- `index.tsx:34` — `type Tab = "ledger" | "topups" | "report"`.
- `index.tsx:57-62` — `useTopUpsQuery({ driver_id, period, skip: 0, limit: FETCH_LIMIT })`, reusing
  the same `driverFilter`/`periodFilter` state the Ledger tab already uses (same filter UI is now
  shared by both tabs — `index.tsx:286` wraps the filter `<Card>` in
  `tab === "ledger" || tab === "topups"`).
- `index.tsx:181-220` — `topUpColumns: TableColumn<PSLTopUp>[]`: Period (`formatPeriod`), Driver
  (via the existing `driverLabelById` map), Amount (`formatMoney`), Payment method (mapped through
  `PAYMENT_METHOD_OPTIONS` from `./format.ts` to show "Card"/"Cash"/"Bank transfer" instead of the
  raw enum value), Status (`<Badge variant="outline">`), Recorded (`formatDateTime`).
- `index.tsx:262-271` — third tab toggle button ("Top-ups", `Receipt` icon).
- `index.tsx:349-370` — top-ups `<Table>` render branch, with its own `isError`/`FETCH_LIMIT`
  messaging mirroring the Ledger tab's existing pattern.

The existing "Record top-up" button in the page header (`TopUpFormModal`) already invalidates the
`psl-topups` query key on success, so recording a top-up immediately refreshes this new tab with no
further wiring needed.

## 3. Platform Console tenant detail (was dead code) + missing error states

Confirmed via grep before any edits: `useTenantSummary`
(`dashboard/src/hooks/usePlatformConsole.ts:76`) had zero call sites, and the tenants table in
`dashboard/src/pages/platform/index.tsx` had no row-click or detail action.

Added, in `dashboard/src/pages/platform/index.tsx`:
- `index.tsx:82-131` — new `TenantDetailModal` component, driven by `useTenantSummary(tenantId)`.
  Modal-based (matches the row-click-opens-a-modal convention in
  `dashboard/src/pages/fleet/VehiclesPanel.tsx`, appropriate here since this page has no existing
  two-column layout to host a duress-style side panel). Shows a 2x2 grid of stat tiles: Vehicles
  (`vehicle_count`), Drivers (`driver_count`), Trips (last 30 days) (`trip_count_last_30_days`),
  Active duress events (`active_duress_count`) — the exact fields on `TenantSummary`.
- `index.tsx:150` — `selectedTenantId` state.
- `index.tsx:232` — `<Table onRowClick={(t) => setSelectedTenantId(t.id)} .../>` makes each tenant
  row open the detail modal.
- `index.tsx:327-331` — mounts `TenantDetailModal`, resolving `tenantName` from the already-loaded
  tenants list.

Also fixed the two missing error states called out in the audit:
- `index.tsx:51-70` — `HealthSummary` now checks `healthQuery.isError` and shows a
  `text-destructive` error message instead of silently rendering `"-"` in every tile (previously
  indistinguishable from a legitimately-empty value).
- `index.tsx:108-120` — `TenantDetailModal` likewise checks `summaryQuery.isError`.
- `index.tsx:218-223` — the tenants table now checks `tenantsQuery.isError` and shows an error
  banner (matching the `<AlertTriangle>` + `text-destructive` idiom used in
  `dashboard/src/pages/settings/security/index.tsx` and `VehiclesPanel.tsx`), and
  `index.tsx:229-231` swaps the `<Table emptyState>` copy to "Couldn't load tenants." on error so a
  failed fetch no longer reads as "the platform genuinely has no tenants."

## Verification

- `cd dashboard && npm run lint` (`tsc --noEmit`) — clean, zero errors, after `npm install`
  (worktree had no `node_modules`).
- Backend reachable at `http://127.0.0.1:8001`. Verified with real bearer tokens
  (`owner@lillycabs.test` / `ChangeMe123!` for the tenant-scoped calls, and the seeded platform
  owner `admin@cabdispatch.test` / `ChangeMe123!` for the platform endpoints):
  - `POST /v1/tenants/{tenant_id}/admin-pin` → `{"tenant_id":"...","admin_pin_configured":true}`.
  - `GET /v1/psl/topups` → `[]` (empty array, matches `PSLTopUp[]` type).
  - `GET /v1/platform/tenants` (owner-role but non-platform-tenant token) → correctly `403
    "Platform-owner access required"`; with the platform-owner token → real tenant list.
  - `GET /v1/platform/tenants/{id}/summary` (platform-owner token) → real
    `{tenant_id, tenant_name, vehicle_count, driver_count, trip_count_last_30_days,
    active_duress_count}` payload, matching the `TenantSummary` type exactly.
- Dashboard dev server started (`npm run dev -- --port 5175`), served `HTTP 200` at `/` with a
  clean Vite build log (no compile errors).
