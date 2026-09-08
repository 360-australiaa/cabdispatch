/** Display formatting for the Driver engagement page.
 *
 * The `format*` helpers below are re-exported from `@/lib/format`, which is
 * now the single implementation of each (see that module's header: this file
 * used to carry its own copy, one of 12 near-identical ones across the
 * per-page `format.ts` modules). Only the page-specific helpers below are local.
 */

export {
  formatMoney,
  formatDateTime,
  toDatetimeLocalValue,
  fromDatetimeLocalValue,
  extractErrorMessage,
} from "@/lib/format";

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
