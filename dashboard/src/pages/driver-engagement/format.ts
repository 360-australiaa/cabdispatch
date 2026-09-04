import axios from "axios";

/** Decimal-string money field -> display string. Never parsed for further
 * arithmetic — same convention as `pages/vouchers/format.ts::formatMoney`
 * (duplicated here rather than imported cross-domain, matching this
 * codebase's per-page convention). */
export function formatMoney(value: string | null | undefined): string {
  if (value == null || value === "") return "—";
  const n = Number(value);
  if (Number.isNaN(n)) return value;
  return new Intl.NumberFormat("en-AU", { style: "currency", currency: "AUD" }).format(n);
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

/** `<input type="datetime-local">` wants "YYYY-MM-DDTHH:mm" in local time. */
export function toDatetimeLocalValue(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Inverse of `toDatetimeLocalValue`. Empty string -> undefined. */
export function fromDatetimeLocalValue(value: string): string | undefined {
  if (!value) return undefined;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return undefined;
  return d.toISOString();
}

/** Extracts a human-readable message from an axios error — handles both
 * shapes this API returns on 4xx: a plain string `detail` and the FastAPI/
 * pydantic `HTTPValidationError` array shape. */
export function extractErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const detail = err.response?.data?.detail;
    if (typeof detail === "string") return detail;
    if (Array.isArray(detail)) {
      return detail
        .map((d) => {
          const field = Array.isArray(d?.loc) ? d.loc.at(-1) : undefined;
          return field ? `${field}: ${d.msg}` : (d?.msg ?? JSON.stringify(d));
        })
        .join("; ");
    }
    return err.message;
  }
  return err instanceof Error ? err.message : "Something went wrong";
}

/** Live-ness of a windowed row (announcement / incentive) as the driver
 * tablet would see it right now — mirrors the server-side window filter in
 * `app.services.driver_engagement.list_live_announcements` /
 * `list_live_incentives`. */
export function windowStatus(row: { active: boolean; starts_at: string; ends_at: string | null }): {
  label: string;
  variant: "success" | "outline" | "destructive" | "default";
} {
  if (!row.active) return { label: "Inactive", variant: "outline" };
  const now = Date.now();
  if (new Date(row.starts_at).getTime() > now) return { label: "Scheduled", variant: "default" };
  if (row.ends_at && new Date(row.ends_at).getTime() <= now) return { label: "Ended", variant: "destructive" };
  return { label: "Live", variant: "success" };
}

export const ANNOUNCEMENT_KIND_LABELS: Record<string, string> = {
  info: "Info",
  maintenance: "Maintenance",
  surge: "Surge",
  feature: "New feature",
};

export const WALLET_KIND_LABELS: Record<string, string> = {
  trip_earning: "Trip earning",
  top_up: "Top-up",
  adjustment: "Adjustment",
  payout: "Payout",
};
