# Payments: current state and what a real integration needs

**Status as of 2026-09-08 (Wave 4, B11): deprecated / unwired mock. No money
has ever moved through this system, in test or in the field.**

This is an **owner decision**, not a code decision (program plan
`docs/plans/2026-09-08-global-meter-program.md` §5 item 4): *is a real
Stripe Terminal integration scheduled, or does Close & Pay stay
cash/link/CabCharge-manual-entry only?* Nobody has answered it yet. Until
someone does, this document records the honest state and the checklist for
turning it on — it does not itself wire anything up.

## What exists today

`backend/app/api/v1/payments.py` + `backend/app/services/payments.py`
implement a complete `Payment` CRUD domain with method-specific creation
flows for Stripe Tap-to-Pay, Stripe payment links, cash, CabCharge
authorization, and TTSS subsidy claims, plus a Stripe webhook receiver. Each
non-cash rail has a "real-or-mock" pattern: if the relevant API key is
absent or a placeholder (`STRIPE_SECRET_KEY`, `CABCHARGE_API_KEY`,
`TTSS_API_KEY` — see `app/core/config.py`), the call returns a clearly
labeled `mock: true` response instead of touching a real network.

**No environment this app has ever run in has had a real key configured.**
Every payment ever created through this router has been a mock. This is not
a fallback path being exercised occasionally — it is the only path that has
ever executed.

**Nothing calls this router in production use.** The Android Close & Pay
screen (`android/.../ui/screens/closepay/`) does not call
`/v1/payments/**` at all — it drives local `hardware/payments/*Gateway`
mock/real implementations (owned by workstream A5) that write straight onto
`Trip.payment_method`/`Trip.payment_status`, never creating a `Payment` row.
The dashboard's `payment-recon` page only reads this router
(`GET /v1/payments`, to reconcile manually-entered CabCharge/TTSS dockets);
no write call site exists anywhere in `dashboard/src`.

So this router is, today, a tested but disconnected implementation — the
"dead surface" this workstream was asked to reconcile. It has been left in
place (not deleted) and marked deprecated in its module docstring, per the
owner decision's stated default: *"mocks made honest; Tap-to-Pay hidden in
release builds."*

## The one defect found and fixed here

`PATCH /v1/payments/{id}` used to accept an unrestricted `status` field from
any authenticated tenant user, including `status="succeeded"` — with no
check on which rail the payment used. For `tap_to_pay`/`link` (the two
methods with a real settlement authority, the Stripe webhook), that meant
any client holding a valid token could mark a Stripe-rail payment
"succeeded" by PATCHing it directly, without Stripe ever having confirmed
anything — a way to report money moved when it hadn't, on a `Payment` row
that had already been created (even if only as a mock intent). That path is
now closed: `update_payment` rejects `status ∈ {"succeeded", "failed"}` on
`tap_to_pay`/`link` payments with a 422; those two methods can only reach a
terminal state via `stripe_webhook`. `cash` is created already `succeeded`
(the driver holds the cash at the moment of creation — there's nothing to
defer). `cabcharge`/`ttss` remain PATCH-able to `succeeded` because they are
manual-docket rails by design (see `ManualPaymentRequest`'s docstring) —
an admin/staff member marking one settled after reconciling the physical
docket against a bank statement is the intended workflow, not a gap, since
neither rail has (or is planned to have) a live settlement webhook.

This does not touch Android — Android does not call this endpoint at all
today (see above). It closes a latent server-side gap the audit's own
"never fabricate a success" rule (program plan §1 rule 11) applies to.

## What "wire up a real Stripe Terminal integration" would actually require

None of this has been started. In rough dependency order:

1. **Hardware.** A physical Stripe Terminal-compatible card reader (or a
   phone's NFC via Tap to Pay on Android/iOS SDK) paired to the tablet.
   `hardware/payments/CardPaymentGateway` (A5's surface) would need a real
   implementation alongside the existing mock, selected by a build/config
   flag — never silently replacing the mock in a build that hasn't actually
   been tested against real hardware.
2. **Stripe account + keys.** A live Stripe account, `STRIPE_SECRET_KEY` and
   `STRIPE_WEBHOOK_SECRET` set to real (non-placeholder) values in
   production config (`app/core/config.py`'s `_PLACEHOLDER_KEYS` already
   gates this correctly — nothing else needs to change there), and a Stripe
   Terminal location/reader registered per fleet/tenant.
3. **Webhook reachability.** `POST /v1/stripe/webhook` must be reachable
   from the public internet over HTTPS (B3's TLS prerequisite, program plan
   §5 item 1) with signature verification actually enforced — today's tests
   rely on the no-`STRIPE_WEBHOOK_SECRET` dev-mode bypass
   (`test_stripe_webhook_updates_payment_status_unsigned_dev_mode`), which
   must not ship to production; `assert_production_secrets_safe` (B3) should
   be extended to require a real webhook secret whenever Stripe is
   configured at all.
4. **Android integration.** Close & Pay would need to actually call
   `POST /v1/payments/tap-to-pay/intent`, hand the returned `client_secret`
   to the Terminal SDK, poll/await the result, and only then show a
   "Payment received" state — today it never calls this API at all, so this
   is new client work, not a config flip.
5. **Reconciliation.** The dashboard's `payment-recon` page (currently
   read-only against `cabcharge`/`ttss`) would need a Stripe-rail view too,
   plus a documented process for chargebacks/disputes (Stripe sends those as
   separate webhook event types this receiver does not yet map —
   `payments_service.status_for_event` only maps success/failure events
   today; check `services/payments.py` before assuming dispute handling
   exists).
6. **Surcharge cap compliance.** The 5% non-cash surcharge cap
   (`SURCHARGE_CAP_PCT` in `services/payments.py`) is already enforced
   server-side and should not need to change, but must be re-verified against
   whatever jurisdiction the tenant is in once X1's jurisdiction seam
   (Wave 3) makes that a per-tenant question rather than an NSW constant.
7. **CabCharge/TTSS**, if ever moved off manual-docket entry, would need
   equivalent real credentials (`CABCHARGE_API_KEY`/`TTSS_API_KEY`) and a
   contractual/API integration with those providers — no such integration
   exists or has been scoped; the mock fallback there is not "Stripe but
   smaller," it is a distinct integration this document does not size.

None of the above is safe to attempt without the owner's explicit go-ahead —
it involves real money, a live Stripe account, and PCI-relevant handling on
the tablet.
