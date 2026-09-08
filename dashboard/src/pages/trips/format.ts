/** Display formatting for the Trips page.
 *
 * The `format*` helpers below are re-exported from `@/lib/format`, which is
 * now the single implementation of each (see that module's header: this file
 * used to carry its own copy, one of 12 near-identical ones across the
 * per-page `format.ts` modules). Only the page-specific helpers below are local.
 */

import type { PaymentMethod, TimeClass, TripStatus, TripType } from "@/hooks/useTrips";

export {
  formatMoney,
  formatPercent,
  formatDateTime,
  formatDistance,
  formatDurationSeconds,
} from "@/lib/format";

export const TRIP_TYPE_LABELS: Record<TripType, string> = {
  rank_hail: "Rank / Hail",
  booked: "Booked",
  airport_fixed: "Airport (fixed)",
  multi_hire: "Multi-hire",
};

export const TIME_CLASS_LABELS: Record<TimeClass, string> = {
  day: "Day",
  night: "Night",
  holiday: "Public holiday",
};

export const PAYMENT_METHOD_LABELS: Record<PaymentMethod, string> = {
  cash: "Cash",
  card: "Card",
  voucher: "Voucher",
  account: "Account",
  split_fare: "Split fare",
};

export function statusBadgeVariant(status: TripStatus): "accent" | "success" {
  return status === "open" ? "accent" : "success";
}

export const TRIP_TYPE_OPTIONS = (Object.keys(TRIP_TYPE_LABELS) as TripType[]).map((value) => ({
  value,
  label: TRIP_TYPE_LABELS[value],
}));

export const TIME_CLASS_OPTIONS = (Object.keys(TIME_CLASS_LABELS) as TimeClass[]).map((value) => ({
  value,
  label: TIME_CLASS_LABELS[value],
}));

export const PAYMENT_METHOD_OPTIONS = (Object.keys(PAYMENT_METHOD_LABELS) as PaymentMethod[]).map(
  (value) => ({ value, label: PAYMENT_METHOD_LABELS[value] }),
);
