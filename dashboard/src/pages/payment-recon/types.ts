/**
 * TS mirror of the backend PaymentRead schema (see
 * backend/app/schemas/payments.py), scoped to what the CabCharge/TTSS
 * reconciliation view needs. This is a read-only audit view -- no
 * create/update request shapes are needed here.
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
