import type { PaymentMethod, TimeClass, TripStatus, TripType } from "@/hooks/useTrips";

/** All money fields on Trip arrive as decimal strings (see shared/API_SUMMARY.md).
 * This is the one place that turns them into a display string — never parsed
 * for further arithmetic. */
export function formatMoney(value: string | null | undefined): string {
  if (value == null || value === "") return "—";
  const n = Number(value);
  if (Number.isNaN(n)) return value;
  return new Intl.NumberFormat("en-AU", { style: "currency", currency: "AUD" }).format(n);
}

export function formatPercent(value: string | null | undefined): string {
  if (value == null || value === "") return "—";
  const n = Number(value);
  if (Number.isNaN(n)) return value;
  return `${n.toFixed(2)}%`;
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleString("en-AU", {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function formatDistance(meters: number | null | undefined): string {
  if (meters == null) return "—";
  return `${(meters / 1000).toFixed(2)} km`;
}

export function formatDurationSeconds(seconds: number | null | undefined): string {
  if (seconds == null) return "—";
  const mins = Math.floor(seconds / 60);
  const secs = seconds % 60;
  return `${mins}m ${secs}s`;
}

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
