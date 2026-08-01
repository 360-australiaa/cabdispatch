/** All money fields on PSL records arrive as decimal strings (see
 * shared/API_SUMMARY.md). This is the one place that turns them into a
 * display string — never parsed for further arithmetic. */
export function formatMoney(value: string | null | undefined): string {
  if (value == null || value === "") return "—";
  const n = Number(value);
  if (Number.isNaN(n)) return value;
  return new Intl.NumberFormat("en-AU", { style: "currency", currency: "AUD" }).format(n);
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return "—";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleDateString("en-AU", { day: "2-digit", month: "short", year: "numeric" });
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

/** Formats a `YYYY-MM` period string (e.g. from a PSL ledger row) as "July 2026". Falls back to the raw value if it doesn't match. */
export function formatPeriod(period: string | null | undefined): string {
  if (!period) return "—";
  const match = /^(\d{4})-(\d{2})$/.exec(period);
  if (!match) return period;
  const d = new Date(Number(match[1]), Number(match[2]) - 1, 1);
  return d.toLocaleDateString("en-AU", { month: "long", year: "numeric" });
}

/** Current month as `YYYY-MM`, used to default period pickers. */
export function currentPeriod(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
}

export const PAYMENT_METHOD_OPTIONS = [
  { value: "card", label: "Card" },
  { value: "cash", label: "Cash" },
  { value: "bank_transfer", label: "Bank transfer" },
];

/** Subtracts two decimal-string money values without float drift for typical 2dp currency amounts. */
export function subtractMoney(a: string | null | undefined, b: string | null | undefined): string {
  const na = a ? Number(a) : 0;
  const nb = b ? Number(b) : 0;
  return (na - nb).toFixed(2);
}
