import axios from "axios";

/** Best-effort human message out of an Axios/FastAPI error. Mirrors
 * `src/pages/fleet/format.ts`'s `errorMessage`. */
export function errorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const detail = (err.response?.data as { detail?: unknown } | undefined)?.detail;
    if (typeof detail === "string") return detail;
    if (Array.isArray(detail)) {
      const first = detail[0] as { msg?: string; loc?: unknown[] } | undefined;
      if (first?.msg) {
        const field = Array.isArray(first.loc) ? first.loc.at(-1) : undefined;
        return field ? `${String(field)}: ${first.msg}` : first.msg;
      }
    }
    if (err.response?.status) return `Request failed (${err.response.status}).`;
    return err.message;
  }
  return err instanceof Error ? err.message : "Something went wrong.";
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

/** Tenant lifecycle status -> Badge variant. active=green, trial=gold/amber,
 * suspended=red. Same "one switch per status field" convention as
 * `pages/duress/format.ts`'s `statusBadgeVariant`. */
export function tenantStatusBadgeVariant(
  status: string,
): "default" | "primary" | "accent" | "success" | "destructive" | "outline" {
  switch (status) {
    case "active":
      return "success";
    case "trial":
      return "accent";
    case "suspended":
      return "destructive";
    default:
      return "default";
  }
}

/** "$49.00" from a Decimal-as-string like the backend's mrr_aud/price_aud
 * fields. */
export function formatAud(amount: string | number | null | undefined): string {
  if (amount == null) return "—";
  const n = typeof amount === "string" ? Number(amount) : amount;
  if (Number.isNaN(n)) return "—";
  return n.toLocaleString(undefined, { style: "currency", currency: "AUD" });
}
