# Dashboard billing: Stripe Connect onboarding UI + subscription status correction

Two gaps closed from the Billing page CRUD-completeness audit. Frontend-only; no
`backend/` changes.

## 1. Stripe Connect onboarding UI

Previously `POST /v1/billing/connect/onboard` had no caller anywhere in the
dashboard.

- `dashboard/src/hooks/useBilling.ts:56-60` — added `ConnectOnboardResponse`
  type (`{ mock: boolean; url: string; stripe_account_id: string | null }`),
  matching `backend/app/schemas/billing.py:78-81` exactly.
- `dashboard/src/hooks/useBilling.ts:153-161` — added `useConnectOnboard()`,
  a `useMutation` that `POST`s `/v1/billing/connect/onboard` with no body
  (the endpoint takes none — see `backend/app/api/v1/billing.py:210-222`).
- `dashboard/src/pages/billing/index.tsx:170-241` — added `ConnectPaymentsButton`:
  - Renders a "Connect payments" button (with a `Link2` icon) in the page
    header, gated by the same `canManage` (owner/admin/dispatcher) check that
    already gates "New subscription" and the per-row subscription actions
    (`index.tsx:88`, wired into the header actions at `index.tsx:96-104`).
    Shown on both the Subscriptions and Invoices tabs, since Connect
    onboarding is tenant-level, not subscription-specific.
  - Clicking it opens a `Modal` and fires the mutation immediately.
  - On success: if `result.mock` is `true`, shows an outline `Badge` reading
    "Simulated" (the same convention already used for mock invoice rows at
    `index.tsx:387-390`) plus copy stating plainly that no real Stripe flow
    was started. If not mock, shows copy warning the link leaves the
    dashboard. In both cases the returned `url` is rendered as a plain
    `<a href={url} target="_blank" rel="noreferrer">` — never auto-navigated,
    so nothing silently leaves the dashboard.
  - Errors are surfaced via the existing `apiErrorMessage(err, fallback)` +
    `ErrorBanner` pattern used by every other mutation on this page
    (`index.tsx:72-81`).

Verified live against a running backend (see below): `POST
/v1/billing/connect/onboard` returns
`{"mock":true,"url":"https://connect.mock.stripe.com/onboard/...","stripe_account_id":"mock_acct_..."}`,
which the new UI renders correctly (Simulated badge + link, not auto-navigated).

## 2. `ChangePlanModal` can now set `status`

`SubscriptionUpdate` (`backend/app/schemas/billing.py:27-33`) accepts both
`plan` and `status` (`SubscriptionStatus = Literal["trialing", "active",
"past_due", "canceled", "incomplete"]`, schema line 11), but the modal only
ever sent `plan`.

- `dashboard/src/pages/billing/index.tsx:599-702` (`ChangePlanModal`):
  - Added `subscriptionStatus` state, defaulting to
    `subscription?.status ?? "trialing"` and reset alongside `plan` whenever
    a new subscription is targeted (existing `useMemo` keyed on `subId`,
    now sets both).
  - Added a "Status (manual correction)" `Select` using the page's existing
    `STATUS_OPTIONS` constant (`index.tsx:40-46`), which already lists
    exactly the five backend-schema values (`trialing`, `active`,
    `past_due`, `canceled`, `incomplete`) — no new/invented values.
  - `handleSubmit` now calls `updateMutation.mutate({ id, body: { plan,
    status: subscriptionStatus } })`, sending both fields in the same
    `PATCH /v1/billing/subscriptions/{id}` call.
  - Copy under the select explicitly frames this as a manual override for a
    stuck state (e.g. flipping a stale "Past due" back to "Active"), not a
    billing action, and points at the separate, unchanged Cancel flow
    (`CancelSubscriptionModal`, `index.tsx:696`) for ending a subscription
    normally.

Verified live against a running backend: created a test subscription via
`POST /v1/billing/subscriptions`, then `PATCH
/v1/billing/subscriptions/{id}` with `{"plan":"pro","status":"past_due"}`
— response confirmed both fields updated (`"status":"past_due"`,
`"plan":"pro"`, recalculated `"price_aud":"49.00"`). Flipped back to
`"active"` in a second PATCH to confirm the round-trip, then canceled the
test subscription via `DELETE` to leave no test data behind.

## Verification

- `cd dashboard && npm run lint` (`tsc --noEmit`) — clean, zero errors.
- Backend reachable at `http://127.0.0.1:8001`; both new/changed calls
  sanity-checked directly with `curl` using an `owner@lillycabs.test` token,
  as detailed above.

## Files touched

- `dashboard/src/hooks/useBilling.ts`
- `dashboard/src/pages/billing/index.tsx`
