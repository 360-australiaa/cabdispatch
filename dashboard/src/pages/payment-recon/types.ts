/**
 * TS mirror of the backend payments schemas (see
 * backend/app/schemas/payments.py), scoped to what the CabCharge/TTSS
 * reconciliation workflow needs: the `PaymentRead` row, the `PATCH
 * /v1/payments/{id}` partial update, and the two real-or-mock creation
 * flows this page drives (`POST /v1/payments/cabcharge/authorize`,
 * `POST /v1/payments/ttss/claim`).
 */

export type PaymentMethod = "tap_to_pay" | "link" | "cash" | "cabcharge" | "ttss";

export type PaymentStatus =
  | "pending"
  | "requires_action"
  | "succeeded"
  | "failed"
  | "refunded"
  | "canceled";

/** The two methods this page reconciles. */
export type ReconciliationMethod = "cabcharge" | "ttss";

export interface PaymentRead {
  id: string;
  tenant_id: string;
  trip_id: string;
  method: PaymentMethod;
  amount: string;
  surcharge: string;
  stripe_pi_id: string | null;
  status: PaymentStatus;
  captured_at: string | null;
  change_given: string | null;
  docket_number: string | null;
  notes: string | null;
  /** TTSS-only: 50% of fare, capped at $60.00 -- see TTSSClaimResponse. */
  subsidy_amount: string | null;
  /** TTSS-only: amount - subsidy_amount, what the passenger actually paid. */
  passenger_paid_amount: string | null;
  created_at: string;
  updated_at: string;
}

export interface PaymentListResponse {
  items: PaymentRead[];
  total: number;
  skip: number;
  limit: number;
}

/** `PaymentUpdate` -- amount/surcharge/method/trip_id are immutable
 * (financial audit trail); only the status transition, the Stripe
 * reference, the capture time and the free-text notes/docket are patchable.
 * For the two methods this page reconciles the backend accepts every
 * `PaymentStatus` here (the succeeded/failed refusal applies to the Stripe
 * rails only). */
export interface PaymentUpdateBody {
  status?: PaymentStatus;
  stripe_pi_id?: string | null;
  captured_at?: string | null;
  notes?: string | null;
  docket_number?: string | null;
}

/** `CabChargeAuthorizeRequest` -- authorisation -> docket creation. The
 * backend creates a NEW `pending` payment row carrying the authorisation
 * id in `notes` and the CabCharge-issued docket number. Money fields are
 * decimal strings, never floats. */
export interface CabChargeAuthorizeBody {
  trip_id: string;
  card_identifier: string;
  amount: string;
  surcharge?: string;
}

export interface CabChargeAuthorizeResponse {
  payment: PaymentRead;
  /** True whenever no CABCHARGE credentials are configured -- which, per the
   * backend's own module docstring, is every environment this app has run
   * in so far. Shown to the operator rather than hidden. */
  mock: boolean;
  authorization_id: string | null;
  cabcharge_status: string | null;
}

/** `TTSSClaimRequest` -- subsidy calculation -> claim submission. The
 * subsidy (50% of `amount`, capped at $60.00) is always computed
 * server-side; the client never supplies it. */
export interface TTSSClaimBody {
  trip_id: string;
  concession_identifier: string;
  amount: string;
}

export interface TTSSClaimResponse {
  payment: PaymentRead;
  mock: boolean;
  claim_id: string | null;
  ttss_status: string | null;
  subsidy_amount: string;
  passenger_paid_amount: string;
}
