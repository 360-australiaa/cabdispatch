# Web Ops Dashboard — Overview

**What this is.** The fleet-ops web dashboard at `dashboard/` — a React +
TypeScript + Vite SPA that talks to the FastAPI backend under `/v1/**`. This
is the browser app used by tenant staff (owner/admin/dispatcher) and, for
one route, the platform operator. It is a separate application from the
Android driver app; see `ANDROID_HOME_REDESIGN_2026.md` for that surface
(this file used to be misleadingly named `DASHBOARD_REDESIGN_2026.md` —
renamed 2026-09-08 to stop that confusion).

This document describes what is actually in the repo as of 2026-09-08
(Wave 4, B11). Where an earlier doc claimed something this workstream could
not verify against the code, it is corrected or omitted here rather than
repeated.

## Stack

- React Router (`createBrowserRouter`) with every authenticated page
  code-split via `React.lazy` — see the doc comment at the top of
  `dashboard/src/router.tsx` for why (a 3.1 MB single-chunk bundle at the
  login screen, per the dashboard audit).
- `@tanstack/react-query` for all server data.
- Mapbox GL (`mapbox-gl`) for the live map and geocoding-backed pages.
- `axios` via `lib/apiClient.ts`, with refresh-on-401 handling.
- Tailwind + a small hand-rolled UI kit under `components/ui/**`
  (`Tabs`, `Pagination`, `Checkbox`, `Spinner`, `Toast`, etc. — check that
  directory directly for the current set rather than trusting a list here,
  since it is actively growing).
- Build/verify scripts, from `dashboard/package.json`: `npm run dev`,
  `npm run build` (`tsc -b && vite build`), `npm run lint`
  (`eslint . && tsc -b --noEmit`), `npm run test` (`vitest run`),
  `npm run test:e2e` (Playwright).

## Routes (from `dashboard/src/router.tsx`)

Public: `/login`.

Everything else requires an authenticated session (`ProtectedRoute`) and
renders inside `AppShell` (sidebar + content). `/` redirects to `/live-map`.
The current route table, one page per line:

| Path | Page | Notes |
|---|---|---|
| `/getting-started` | Getting Started | onboarding checklist |
| `/live-map` | Live Map | Mapbox fleet view |
| `/dispatch` | Dispatch | job creation/assignment |
| `/messages` | Messages | driver messaging |
| `/duress` | Duress | duress event desk |
| `/trips` | Trips | trip history |
| `/shifts` | Shifts | shift history |
| `/tariffs` | Tariffs | tariff/toll-road config |
| `/zones` | Zones | zone management |
| `/psl` | PSL | Passenger Service Levy ledger |
| `/fleet` | Fleet | vehicles/drivers/devices |
| `/compliance` | Compliance | compliance/expiry tracking |
| `/billing` | Billing | invoicing |
| `/payment-recon` | Payment Reconciliation | **read-only** view over `GET /v1/payments`, scoped to CabCharge/TTSS manual dockets — see `docs/PAYMENTS_INTEGRATION.md` for why this is the only live use of the payments API |
| `/vouchers` | Vouchers | voucher management |
| `/announcements`, `/incentives`, `/wallet`, `/ratings` | Driver Engagement | four separate pages under `pages/driver-engagement/` |
| `/audit-log` | Audit Log | `GET /v1/audit-log` list + `GET /v1/audit-log/verify` hash-chain check — **read-only**, see `app/api/v1/audit_log.py`'s module docstring for why there is no write route |
| `/settings/white-label` | White-label settings | |
| `/settings/security` | Security settings | |
| `/platform` | Platform console | gated additionally by `PlatformOwnerRoute` — see `lib/platformAdmin.ts`: role must be `owner` **and** the user's tenant must be the distinguished platform tenant (`PLATFORM_TENANT_ID`), not just any tenant owner |
| `*` | 404 | real not-found page, not a silent redirect |

That is 23 authenticated routes plus `/login` — this workstream did not
independently re-verify a specific total elsewhere in the docs and is not
asserting one; count the table above if a number is needed, since the route
table is the actual source of truth and will drift from any hand-maintained
count faster than this file gets updated.

## Auth and roles

`lib/auth.tsx` types the current user's `role` as
`"owner" | "admin" | "dispatcher" | "driver" | string` (the trailing
`| string` is in the code as of this writing — not a typo introduced here).
There is no single `lib/permissions.ts` abstraction in this tree gating
writes page-by-page; `lib/platformAdmin.ts`'s `isPlatformOwner()` is the one
role check that exists as a shared helper today, used only for `/platform`.
Do not assume a broader role-gating library exists without checking
`dashboard/src/lib/` and `components/` directly — this doc will not be kept
in sync with that work if it lands in a later wave.

## What this workstream did NOT change

Per this workstream's file ownership (`docs/plans/2026-09-08-global-meter-program.md`,
B11's row), no file under `dashboard/src/**` was touched — this document is
descriptive only. If a route, page, or gating rule above looks wrong after a
later change, the router/lib files are the source of truth, not this file.

## Backend surface this app talks to

Every backend route the dashboard calls is versioned under `/v1/...` and
requires `Authorization: Bearer <token>`, tenant-scoped server-side via
`get_current_tenant_id` — see `shared/API_SUMMARY.md` for a human-oriented
walkthrough of the API and `shared/openapi.json` (regenerated from the live
app as of this workstream's commit — see that file's header comment) for
the exact, current request/response shapes. Do not hand-edit either of
those two files; regenerate `openapi.json` from `app.main:app` and
re-derive `API_SUMMARY.md`'s claims from it.
