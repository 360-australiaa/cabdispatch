import axios from "axios";

/**
 * The dashboard's display-formatting helpers, in one place.
 *
 * Before this module existed the audit (`docs/audits/2026-09-08-dashboard-audit.md`
 * §6) counted 12 near-identical `formatDateTime`, 8 `formatMoney` plus 3
 * `formatAud`, 6 `errorMessage`/`extractErrorMessage`, 4 duration formatters
 * and 2 `initials` -- each copy-pasted into one page's own `format.ts` module because the
 * repo had no shared util module and the per-page convention was to duplicate
 * rather than reach across domains. Several copies had quietly drifted apart
 * (see the notes on each function below), which meant the same figure could
 * render differently depending on which page an operator was looking at.
 *
 * Every per-page `format.ts` now re-exports from here, so no call site outside
 * those modules changed. Behaviour is pinned by `lib/formatters.test.ts`.
 *
 * WAVE 3 NOTE: the `en-AU`/`AUD` pair below is hardcoded on purpose for now.
 * Workstream X1 makes locale and currency tenant-driven; this module is the
 * single seam that change has to go through, which is the main reason for
 * consolidating in the first place.
 */

/** The locale every helper here pins. See the Wave 3 note above. */
const LOCALE = "en-AU";
const CURRENCY = "AUD";

/** What every helper renders when a value is genuinely absent. Distinct from
 * a malformed value, which is echoed back raw so an operator can read it out
 * to support -- the repo's honest-null rule: "missing" and "zero" must never
 * look the same. */
export const EM_DASH = "—";

// ---------------------------------------------------------------------------
// Money
// ---------------------------------------------------------------------------

const audFormatter = new Intl.NumberFormat(LOCALE, { style: "currency", currency: CURRENCY });

/**
 * A decimal-string (or number) money field -> display string. Money arrives
 * from the API as decimal strings (see `shared/API_SUMMARY.md`); this is the
 * one place that turns them into something to look at, and the result is
 * never parsed back for further arithmetic.
 *
 * `null`, `undefined` and `""` are all "the API did not send a figure" and
 * render as an em-dash; an explicit `"0"` is a real zero and renders as
 * `$0.00`. A non-numeric value is echoed back unchanged.
 *
 * D5 DECISION -- which of the nine implementations survived. Eight of the
 * nine (trips, shifts, psl, vouchers, tariffs, driver-engagement, and
 * `useBilling`/`useReports`' `formatAud`) already behaved exactly as above.
 * `pages/platform/format.ts`'s `formatAud` was the odd one out in three ways,
 * and all three are resolved *against* it:
 *
 *  1. It called `n.toLocaleString(undefined, ...)` -- the *browser's* locale.
 *     Outside an en-AU browser `Intl` disambiguates AUD as "A$", so the
 *     platform console rendered "A$49.00" for a figure every other page
 *     rendered "$49.00", contradicting that function's own doc comment which
 *     claimed it returned "$49.00". Pinning `en-AU` is the fix: an operator's
 *     browser locale is not a statement about the tenant's currency, and one
 *     figure must not have two spellings inside one product.
 *  2. It treated `""` as a real zero, so a field the API never sent read as
 *     "$0.00" on the console that shows tenant MRR -- exactly what the
 *     honesty rule forbids. `""` is now missing, like the other eight.
 *  3. It dashed a non-numeric value, making a malformed figure
 *     indistinguishable from a missing one. It now echoes, like the other
 *     eight, so a bad value is visible rather than silently swallowed.
 *
 * In short: the majority behaviour was also the correct behaviour on every
 * one of the three points, so the en-AU eight won and platform's variant was
 * deleted rather than generalised.
 */
export function formatMoney(value: string | number | null | undefined): string {
  if (value == null || value === "") return EM_DASH;
  const n = typeof value === "string" ? Number(value) : value;
  if (Number.isNaN(n)) return String(value);
  return audFormatter.format(n);
}

/** Historical alias. `useBilling`, `useReports` and the platform console
 * called their money helper `formatAud`; the name is kept so their call sites
 * did not have to change, but there is only one implementation now. */
export const formatAud = formatMoney;

/** A percentage field (decimal string) at two places, e.g. "12.35%". */
export function formatPercent(value: string | null | undefined): string {
  if (value == null || value === "") return EM_DASH;
  const n = Number(value);
  if (Number.isNaN(n)) return value;
  return `${n.toFixed(2)}%`;
}

/** Subtracts two decimal-string money values at two places, treating a
 * missing operand as zero. Two decimal places is enough to avoid float drift
 * for currency amounts of the size this product deals in. */
export function subtractMoney(a: string | null | undefined, b: string | null | undefined): string {
  const na = a ? Number(a) : 0;
  const nb = b ? Number(b) : 0;
  return (na - nb).toFixed(2);
}

// ---------------------------------------------------------------------------
// Dates and times
// ---------------------------------------------------------------------------

/**
 * The canonical timestamp rendering: "01 Sept 2026, 03:00 pm".
 *
 * Ten of the twelve former copies wanted this shape; eight of them already
 * spelled it exactly this way. Missing -> em-dash, unparseable -> echoed raw.
 *
 * Renders in the *viewer's* timezone with no zone indicator, which is a known
 * gap (audit §4) that Wave 3 closes -- pinned here rather than fixed, because
 * fixing it is a product decision about what zone an operator should see, not
 * a formatting one.
 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return EM_DASH;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleString(LOCALE, {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/**
 * The same instant with seconds, for the surfaces where second-level
 * precision is the point: the audit log (ordering two entries written in the
 * same minute), duress incidents (the escalation timeline) and message
 * threads.
 *
 * D5 note: the three former copies passed `undefined` as the locale, so these
 * three pages alone followed the browser's locale and could render
 * MM/DD/YYYY. They now pin `en-AU` like everything else -- the seconds were
 * the deliberate part of the divergence, the locale was not.
 */
export function formatDateTimeSeconds(value: string | null | undefined): string {
  if (!value) return EM_DASH;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleString(LOCALE, { dateStyle: "medium", timeStyle: "medium" });
}

/**
 * The compact "1 Sept 2026, 8:00 pm" shape used by the dense tables on Fleet
 * and the platform console, where the padded form above costs column width.
 *
 * These two former copies dash an unparseable value instead of echoing it.
 * That divergence is preserved but now has exactly one implementation behind
 * it rather than two that could drift apart again, which was the substance of
 * the audit's complaint. (Whether "malformed" should look like "missing"
 * anywhere is a real question, but changing it is a visible behaviour change
 * on two pages with no bug reported against them, so it is left alone here
 * rather than smuggled into a consolidation commit.)
 */
export function formatDateTimeShort(value: string | null | undefined): string {
  if (!value) return EM_DASH;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return EM_DASH;
  return d.toLocaleString(LOCALE, { dateStyle: "medium", timeStyle: "short" });
}

/** A date with no time part: "01 Sept 2026". */
export function formatDate(value: string | null | undefined): string {
  if (!value) return EM_DASH;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleDateString(LOCALE, { day: "2-digit", month: "short", year: "numeric" });
}

/** As `formatDate` but with a non-padded day ("5 Sept 2026") -- the billing
 * page's invoice rows, which sit in running text rather than a column. */
export function formatDateNumericDay(value: string | null | undefined): string {
  if (!value) return EM_DASH;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleDateString(LOCALE, { day: "numeric", month: "short", year: "numeric" });
}

/** Just the clock part, to seconds. */
export function formatTimeSeconds(value: string | null | undefined): string {
  if (!value) return EM_DASH;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleTimeString(LOCALE, { timeStyle: "medium" });
}

/** Just the clock part, to minutes. */
export function formatTimeShort(value: string | null | undefined): string {
  if (!value) return EM_DASH;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleTimeString(LOCALE, { hour: "2-digit", minute: "2-digit" });
}

/** Expands a `YYYY-MM` period string to "July 2026". Anything that is not
 * `YYYY-MM` comes back untouched. */
export function formatPeriod(period: string | null | undefined): string {
  if (!period) return EM_DASH;
  const match = /^(\d{4})-(\d{2})$/.exec(period);
  if (!match) return period;
  const d = new Date(Number(match[1]), Number(match[2]) - 1, 1);
  return d.toLocaleDateString(LOCALE, { month: "long", year: "numeric" });
}

/** The current month as `YYYY-MM`, for defaulting period pickers. */
export function currentPeriod(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
}

/** `<input type="datetime-local">` wants "YYYY-MM-DDTHH:mm" in local wall
 * time -- no zone suffix, no seconds. */
export function toDatetimeLocalValue(value: string | null | undefined): string {
  if (!value) return "";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Inverse of `toDatetimeLocalValue`. Empty string -> `undefined`, leaving
 * the caller to decide whether that means "omit the field" or "clear it". */
export function fromDatetimeLocalValue(value: string): string | undefined {
  if (!value) return undefined;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return undefined;
  return d.toISOString();
}

// ---------------------------------------------------------------------------
// Durations and relative time
// ---------------------------------------------------------------------------

/** "10m 5s" from a whole-second count. */
export function formatDurationSeconds(seconds: number | null | undefined): string {
  if (seconds == null) return EM_DASH;
  const mins = Math.floor(seconds / 60);
  const secs = seconds % 60;
  return `${mins}m ${secs}s`;
}

/** "2h 6m", dropping the hours part entirely under an hour. */
export function formatDurationMinutes(minutes: number | null | undefined): string {
  if (minutes == null) return EM_DASH;
  const total = Math.round(minutes);
  const hrs = Math.floor(total / 60);
  const mins = total % 60;
  return hrs > 0 ? `${hrs}h ${mins}m` : `${mins}m`;
}

/** Compact "1h 30m" / "5m" for a millisecond span, with a "<1m" floor.
 * Labels a plain duration -- distinct from `formatRelativeTime`, which
 * suffixes "ago" for a point in time. */
export function formatDurationShort(ms: number): string {
  const totalMinutes = Math.max(0, Math.round(ms / 60_000));
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (totalMinutes < 1) return "<1m";
  return `${minutes}m`;
}

/** "just now" / "30s ago" / "5m ago" / "3h ago" / "2d ago" for a timestamp.
 * A clock-skewed future timestamp clamps to "just now" rather than printing a
 * negative age. Missing or unparseable -> em-dash. */
export function formatRelativeTime(iso: string | null | undefined): string {
  if (!iso) return EM_DASH;
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return EM_DASH;
  const diffSec = Math.max(0, Math.floor((Date.now() - then) / 1000));
  if (diffSec < 5) return "just now";
  if (diffSec < 60) return `${diffSec}s ago`;
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) return `${diffHr}h ago`;
  const diffDay = Math.floor(diffHr / 24);
  return `${diffDay}d ago`;
}

/** As `formatRelativeTime` but for last-seen columns, where "never reported"
 * is a meaningful state of its own and a future timestamp is worth showing in
 * full rather than rounding away. */
export function relativeFromNow(iso: string | null | undefined): string {
  if (!iso) return "Never";
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "Never";
  const diffMs = Date.now() - then;
  if (diffMs < 0) return formatDateTimeShort(iso);
  const mins = Math.round(diffMs / 60000);
  if (mins < 1) return "Just now";
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  return `${days}d ago`;
}

// ---------------------------------------------------------------------------
// Distances
// ---------------------------------------------------------------------------

/** Metres -> "12.35 km". */
export function formatDistance(meters: number | null | undefined): string {
  if (meters == null) return EM_DASH;
  return `${(meters / 1000).toFixed(2)} km`;
}

/** A decimal-string kilometre field -> "12.3 km", one decimal. */
export function formatKm(value: string | null | undefined): string {
  if (value == null || value === "") return EM_DASH;
  const n = Number(value);
  if (Number.isNaN(n)) return value;
  return `${n.toFixed(1)} km`;
}

// ---------------------------------------------------------------------------
// Identifiers and names
// ---------------------------------------------------------------------------

/** Truncates anything longer than `len` with an ellipsis. Missing -> em-dash. */
export function truncateId(id: string | null | undefined, len = 8): string {
  if (!id) return EM_DASH;
  return id.length > len ? `${id.slice(0, len)}…` : id;
}

/** Two-letter avatar initials from a full name ("Jane Doe" -> "JD"). A
 * single-word name gives one letter; an empty one gives "?". */
export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  const first = parts[0]?.[0] ?? "";
  const last = parts.length > 1 ? parts[parts.length - 1]?.[0] ?? "" : "";
  return (first + last).toUpperCase() || "?";
}

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/**
 * Best-effort human message out of an Axios/FastAPI error.
 *
 * Handles both shapes this API returns on 4xx: a plain string `detail`
 * (raised directly by `HTTPException` -- e.g. the Fares Order cap violation)
 * and the FastAPI/pydantic `HTTPValidationError` array shape (field-level
 * 422s), which is flattened to "field: message" clauses joined with "; ".
 *
 * D5 note: there were six implementations in two camps. One camp reported
 * only the *first* validation error but added a `Request failed (409).`
 * fallback when the body carried no usable detail; the other reported every
 * validation error but fell through to Axios' own opaque "Request failed with
 * status code 409". This keeps the better half of each: all the errors, and
 * the readable status fallback. Reporting every field is the right call for a
 * form modal -- telling an operator about one bad field at a time, when the
 * server already told us about three, makes them submit three times.
 */
export function errorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const detail = (err.response?.data as { detail?: unknown } | undefined)?.detail;
    if (typeof detail === "string") return detail;
    if (Array.isArray(detail)) {
      const parts = detail
        .map((d) => {
          const item = d as { msg?: string; loc?: unknown[] } | undefined;
          if (!item?.msg) return typeof d === "string" ? d : JSON.stringify(d);
          const field = Array.isArray(item.loc) ? item.loc.at(-1) : undefined;
          return field ? `${String(field)}: ${item.msg}` : item.msg;
        })
        .filter(Boolean);
      if (parts.length > 0) return parts.join("; ");
    }
    if (err.response?.status) return `Request failed (${err.response.status}).`;
    return err.message;
  }
  return err instanceof Error ? err.message : "Something went wrong.";
}

/** The name half the pages used for the same thing. One implementation. */
export const extractErrorMessage = errorMessage;
