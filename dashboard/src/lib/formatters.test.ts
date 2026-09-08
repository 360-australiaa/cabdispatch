import { formatMoney as tripsMoney, formatPercent, formatDistance, formatDurationSeconds, formatDateTime as tripsDateTime } from "@/pages/trips/format";
import { formatMoney as shiftsMoney, formatKm, formatDurationMinutes, formatDateTime as shiftsDateTime, toDatetimeLocalValue, fromDatetimeLocalValue } from "@/pages/shifts/format";
import { formatMoney as pslMoney, formatDate as pslDate, formatPeriod, subtractMoney, formatDateTime as pslDateTime } from "@/pages/psl/format";
import { formatMoney as vouchersMoney, formatDateTime as vouchersDateTime } from "@/pages/vouchers/format";
import { formatMoney as tariffsMoney, formatDateTime as tariffsDateTime } from "@/pages/tariffs/format";
import { formatMoney as engagementMoney } from "@/pages/driver-engagement/format";
import { formatAud as billingAud, formatDate as billingDate } from "@/hooks/useBilling";
import { formatAud as reportsAud } from "@/hooks/useReports";
import { formatAud as platformAud, formatDateTime as platformDateTime } from "@/pages/platform/format";
import { formatDateTime as auditDateTime, shortId, actionBadgeVariant } from "@/pages/audit-log/format";
import { formatDateTime as complianceDateTime } from "@/pages/compliance/format";
import { formatDateTime as fleetDateTime } from "@/pages/fleet/format";
import { formatRelativeTime, formatLatLng, formatSpeed, formatDurationShort } from "@/pages/live-map/utils";

/**
 * Behavioural tests for the dashboard's `format*` helpers.
 *
 * The audit counted 12 copies of `formatDateTime` and 8 of `formatMoney`
 * (plus 3 `formatAud`) scattered across the per-page `format.ts` modules.
 * Workstream D5
 * will consolidate them into one module, so these tests are written against
 * *behaviour*, never location: the import block at the top is the only part
 * that a consolidation should have to touch, and if the surviving
 * implementation behaves differently from the ones it replaced, the
 * assertions below are what will say so.
 *
 * Two things get deliberate attention:
 *
 *  - **The `en-AU`/`AUD` hardcoding.** Every money helper pins the locale and
 *    currency in the call itself, and Wave 3 (X1/D6) makes both tenant-driven.
 *    Pinning today's output means that change shows up as a set of failing
 *    assertions to update on purpose, rather than as a silent shift in what
 *    an operator in another jurisdiction is shown.
 *  - **Timezone independence of the tests themselves.** These helpers render
 *    in the *viewer's* zone (there is no timezone handling in the dashboard
 *    at all -- also a Wave 3 gap), so asserting a literal "01 Sept 2026,
 *    03:00 pm" would only pass on a machine set to one particular offset.
 *    The date assertions below derive the expected local parts from the same
 *    `Date` instead, so they hold in any zone and still fail if the helper
 *    changes shape.
 */

const ISO = "2026-09-01T10:00:00Z";

const pad = (n: number) => String(n).padStart(2, "0");

/** The 12-hour local clock reading of `ISO`, e.g. "03:00 pm" -- computed, so the test is zone-agnostic. */
function localHourMinute12(iso: string): string {
  const d = new Date(iso);
  const h24 = d.getHours();
  const h12 = h24 % 12 === 0 ? 12 : h24 % 12;
  return `${pad(h12)}:${pad(d.getMinutes())} ${h24 < 12 ? "am" : "pm"}`;
}

// Every duplicate of the same helper, so the suite covers all of them at once
// and a consolidation that changes only some call sites cannot slip through.
const MONEY_HELPERS: [string, (v: string | null | undefined) => string][] = [
  ["trips", tripsMoney],
  ["shifts", shiftsMoney],
  ["psl", pslMoney],
  ["vouchers", vouchersMoney],
  ["tariffs", tariffsMoney],
  ["driver-engagement", engagementMoney],
  ["billing (formatAud)", billingAud],
  ["reports (formatAud)", reportsAud],
  // NOTE: `platform`'s formatAud is deliberately excluded -- it behaves
  // differently from all eight above, and gets its own block at the bottom of
  // this file documenting exactly how.
];

const DATETIME_AU_HELPERS: [string, (v: string | null | undefined) => string][] = [
  ["trips", tripsDateTime],
  ["shifts", shiftsDateTime],
  ["psl", pslDateTime],
  ["vouchers", vouchersDateTime],
  ["tariffs", tariffsDateTime],
  ["compliance", complianceDateTime],
];

const ALL_DATETIME_HELPERS: [string, (v: string | null | undefined) => string][] = [
  ...DATETIME_AU_HELPERS,
  ["audit-log", auditDateTime],
  ["duress-style (fleet)", fleetDateTime],
  ["platform", platformDateTime],
];

describe("formatMoney / formatAud", () => {
  it.each(MONEY_HELPERS)("%s formats a decimal string as AUD currency", (_name, fn) => {
    expect(fn("1234.5")).toBe("$1,234.50");
  });

  it.each(MONEY_HELPERS)("%s renders the em-dash sentinel for null", (_name, fn) => {
    expect(fn(null)).toBe("—");
  });

  it.each(MONEY_HELPERS)("%s renders the em-dash sentinel for undefined", (_name, fn) => {
    expect(fn(undefined)).toBe("—");
  });

  it.each(MONEY_HELPERS)("%s renders the em-dash sentinel for an empty string", (_name, fn) => {
    // Distinct from "0.00": the API never having sent a figure is not the
    // same as a figure of zero, and the dashboard's honesty rule says so.
    expect(fn("")).toBe("—");
  });

  it.each(MONEY_HELPERS)("%s formats an explicit zero as money, not a dash", (_name, fn) => {
    expect(fn("0")).toBe("$0.00");
  });

  it.each(MONEY_HELPERS)("%s falls back to the raw value when it is not a number", (_name, fn) => {
    expect(fn("not-a-number")).toBe("not-a-number");
  });

  it.each(MONEY_HELPERS)("%s renders a negative amount with a leading sign", (_name, fn) => {
    expect(fn("-7")).toBe("-$7.00");
  });

  it.each(MONEY_HELPERS)("%s rounds to two decimal places", (_name, fn) => {
    expect(fn("10.005")).toBe("$10.01");
    expect(fn("10.004")).toBe("$10.00");
  });

  it.each(MONEY_HELPERS)("%s groups thousands", (_name, fn) => {
    expect(fn("1000000")).toBe("$1,000,000.00");
  });

  it("hardcodes AUD with a bare dollar sign and no currency code", () => {
    // The `en-AU`/`AUD` pair is what Wave 3 makes tenant-driven. Pinned here
    // so that becomes a deliberate edit: a NZ or GB tenant must not silently
    // keep seeing these.
    const out = tripsMoney("5");
    expect(out).toBe("$5.00");
    expect(out).not.toContain("A$");
    expect(out).not.toContain("AUD");
  });

});

/**
 * KNOWN DEFECTS in `pages/platform/format.ts`'s `formatAud`, asserted as-is.
 *
 * It is the odd one out among the nine money helpers in three ways, and all
 * three are visible to the platform owner on the console that shows tenant
 * MRR:
 *
 *  1. It calls `n.toLocaleString(undefined, ...)` -- the *browser's* locale --
 *     where the other eight pin `Intl.NumberFormat("en-AU", ...)`. Outside an
 *     en-AU browser, AUD renders with the disambiguating "A$" symbol, so the
 *     platform console shows "A$49.00" for the same figure every other page
 *     shows as "$49.00". Its own doc comment claims it returns "$49.00".
 *  2. Empty string is not treated as missing: `""` coerces to 0 and renders
 *     as money, so a field the API never sent reads as a real zero -- the
 *     exact thing the repo's honesty rule forbids, and which the other eight
 *     handle correctly.
 *  3. A non-numeric value returns the em-dash instead of echoing the raw
 *     value, so a malformed figure is indistinguishable from a missing one.
 *
 * D5 consolidates these helpers; whichever survives should be the en-AU
 * eight, and this block should then fail and be deleted.
 */
describe("platform formatAud (known divergences)", () => {
  it("follows the browser locale instead of pinning en-AU", () => {
    const platform = platformAud("1234.5");
    const everyoneElse = tripsMoney("1234.5");
    expect(everyoneElse).toBe("$1,234.50");
    expect(platform).toBe(
      (1234.5).toLocaleString(undefined, { style: "currency", currency: "AUD" }),
    );
  });

  it("treats an empty string as zero rather than as missing", () => {
    expect(platformAud("")).not.toBe("—");
    expect(tripsMoney("")).toBe("—");
  });

  it("dashes a non-numeric value instead of echoing it", () => {
    expect(platformAud("not-a-number")).toBe("—");
    expect(tripsMoney("not-a-number")).toBe("not-a-number");
  });

  it("agrees with the others on null and on grouping/rounding", () => {
    expect(platformAud(null)).toBe("—");
    expect(platformAud(undefined)).toBe("—");
    expect(platformAud(1000000)).toContain("1,000,000.00");
    expect(platformAud("10.005")).toContain("10.01");
  });
});

describe("formatDateTime", () => {
  it.each(ALL_DATETIME_HELPERS)("%s renders the em-dash sentinel for null", (_name, fn) => {
    expect(fn(null)).toBe("—");
  });

  it.each(ALL_DATETIME_HELPERS)("%s renders the em-dash sentinel for undefined", (_name, fn) => {
    expect(fn(undefined)).toBe("—");
  });

  it.each(ALL_DATETIME_HELPERS)("%s renders the em-dash sentinel for an empty string", (_name, fn) => {
    expect(fn("")).toBe("—");
  });

  it.each([...DATETIME_AU_HELPERS, ["audit-log", auditDateTime] as const])(
    "%s echoes an unparseable value rather than 'Invalid Date'",
    (_name, fn) => {
      // Showing the raw field beats showing "Invalid Date": an operator can at
      // least read it out to support.
      expect(fn("not a date")).toBe("not a date");
    },
  );

  it("fleet and platform dash an unparseable value instead of echoing it (divergence)", () => {
    // Seven of the nine echo the raw value; these two swallow it. Neither is
    // obviously wrong, but they must not both survive D5's consolidation --
    // whichever wins, "missing" and "malformed" should not look identical on
    // some pages and different on others.
    expect(fleetDateTime("not a date")).toBe("—");
    expect(platformDateTime("not a date")).toBe("—");
  });

  it.each(DATETIME_AU_HELPERS)("%s renders 2-digit day, short month, year and a 12-hour clock", (_name, fn) => {
    const out = fn(ISO);
    const d = new Date(ISO);
    expect(out).toContain(pad(d.getDate()));
    expect(out).toContain(String(d.getFullYear()));
    expect(out).toContain(localHourMinute12(ISO));
    // en-AU short months are "Sept", not "Sep" -- a locale detail that changes
    // column widths if the locale ever moves.
    expect(out).toMatch(/\d{2} [A-Za-z]{3,5} \d{4}, \d{2}:\d{2} [ap]m/);
  });

  it.each(DATETIME_AU_HELPERS)("%s renders in the viewer's local zone, not UTC", (_name, fn) => {
    // No timezone handling exists in the dashboard (Wave 3 gap). This pins
    // today's behaviour: whatever the browser's zone is, that is what shows.
    expect(fn(ISO)).toContain(localHourMinute12(ISO));
  });

  it("audit-log and duress use a different shape from the rest -- seconds and a system locale", () => {
    // Not a bug so much as a divergence D5 has to resolve one way or the
    // other: these two pass `undefined` as the locale and ask for
    // `timeStyle: "medium"`, so they show seconds and follow the browser's
    // locale while the other ten pin en-AU and stop at minutes.
    const audit = auditDateTime(ISO);
    expect(audit).toMatch(/\d{1,2}:\d{2}:\d{2}/);
    expect(tripsDateTime(ISO)).not.toMatch(/\d{2}:\d{2}:\d{2}/);
  });
});

describe("other single-purpose formatters", () => {
  it("formatPercent fixes two decimals and appends a percent sign", () => {
    expect(formatPercent("12.3456")).toBe("12.35%");
    expect(formatPercent("0")).toBe("0.00%");
    expect(formatPercent(null)).toBe("—");
    expect(formatPercent("abc")).toBe("abc");
  });

  it("formatDistance converts metres to kilometres at two decimals", () => {
    expect(formatDistance(12345)).toBe("12.35 km");
    expect(formatDistance(0)).toBe("0.00 km");
    expect(formatDistance(null)).toBe("—");
  });

  it("formatDurationSeconds splits into whole minutes and seconds", () => {
    expect(formatDurationSeconds(0)).toBe("0m 0s");
    expect(formatDurationSeconds(59)).toBe("0m 59s");
    expect(formatDurationSeconds(605)).toBe("10m 5s");
    expect(formatDurationSeconds(null)).toBe("—");
  });

  it("formatDurationMinutes drops the hours part under an hour", () => {
    expect(formatDurationMinutes(45)).toBe("45m");
    expect(formatDurationMinutes(60)).toBe("1h 0m");
    expect(formatDurationMinutes(125.6)).toBe("2h 6m");
    expect(formatDurationMinutes(null)).toBe("—");
  });

  it("formatKm fixes one decimal", () => {
    expect(formatKm("12.34")).toBe("12.3 km");
    expect(formatKm(null)).toBe("—");
    expect(formatKm("")).toBe("—");
    expect(formatKm("abc")).toBe("abc");
  });

  it("pslDate renders a date with no time part", () => {
    expect(pslDate(ISO)).toMatch(/^\d{2} [A-Za-z]{3,5} \d{4}$/);
    expect(pslDate(null)).toBe("—");
  });

  it("billingDate uses a non-padded day, unlike psl's", () => {
    // Another D5 divergence: `day: "numeric"` here vs `"2-digit"` in psl.
    expect(billingDate("2026-09-05T00:00:00Z")).toMatch(/^\d{1,2} [A-Za-z]{3,5} \d{4}$/);
    expect(billingDate(null)).toBe("—");
  });

  it("formatPeriod expands YYYY-MM to a full month and year", () => {
    expect(formatPeriod("2026-07")).toBe("July 2026");
    expect(formatPeriod("2026-12")).toBe("December 2026");
    expect(formatPeriod(null)).toBe("—");
    // Anything that is not YYYY-MM comes back untouched.
    expect(formatPeriod("July")).toBe("July");
    expect(formatPeriod("2026-7")).toBe("2026-7");
  });

  it("subtractMoney subtracts decimal strings at two places and treats missing as zero", () => {
    expect(subtractMoney("10.00", "2.50")).toBe("7.50");
    expect(subtractMoney("0.30", "0.10")).toBe("0.20");
    expect(subtractMoney(null, "2")).toBe("-2.00");
    expect(subtractMoney("2", null)).toBe("2.00");
  });

  it("datetime-local values round-trip through the shift form helpers", () => {
    const local = toDatetimeLocalValue(ISO);
    expect(local).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/);
    // Back out to an instant: minute precision, so seconds are lost but the
    // moment is otherwise preserved (as a full ISO string with millis).
    expect(fromDatetimeLocalValue(local)).toBe(new Date(ISO).toISOString());
    expect(toDatetimeLocalValue(null)).toBe("");
    expect(toDatetimeLocalValue("rubbish")).toBe("");
    expect(fromDatetimeLocalValue("")).toBeUndefined();
    expect(fromDatetimeLocalValue("rubbish")).toBeUndefined();
  });

  it("shortId truncates only what is longer than 8 characters", () => {
    expect(shortId("0123456789abcdef")).toBe("01234567…");
    expect(shortId("012345")).toBe("012345");
    expect(shortId("01234567")).toBe("01234567");
  });

  it("actionBadgeVariant buckets an audit action by verb", () => {
    expect(actionBadgeVariant("vehicle.delete")).toBe("destructive");
    expect(actionBadgeVariant("trip.cancel")).toBe("destructive");
    expect(actionBadgeVariant("vehicle.create")).toBe("success");
    expect(actionBadgeVariant("duress.trigger")).toBe("success");
    expect(actionBadgeVariant("tariff.update")).toBe("accent");
    expect(actionBadgeVariant("KEY.ROTATE")).toBe("accent");
    expect(actionBadgeVariant("something.else")).toBe("outline");
  });
});

describe("live-map formatters", () => {
  it("formatSpeed distinguishes never-reported from stationary", () => {
    // The honest-null rule: a missing reading is a dash, never a zero.
    expect(formatSpeed(null)).toBe("—");
    expect(formatSpeed(0)).toBe("Stationary");
    expect(formatSpeed(0.9)).toBe("Stationary");
    expect(formatSpeed(1)).toBe("1 km/h");
    expect(formatSpeed(59.6)).toBe("60 km/h");
  });

  it("formatLatLng fixes four decimals, or dashes when either half is missing", () => {
    expect(formatLatLng(-33.8688, 151.2093)).toBe("-33.8688, 151.2093");
    expect(formatLatLng(null, 151.2093)).toBe("—");
    expect(formatLatLng(-33.8688, null)).toBe("—");
    expect(formatLatLng(0, 0)).toBe("0.0000, 0.0000");
  });

  it("formatRelativeTime steps through the seconds/minutes/hours/days bands", () => {
    const now = new Date("2026-09-01T12:00:00Z");
    vi.useFakeTimers();
    vi.setSystemTime(now);
    try {
      const ago = (ms: number) => new Date(now.getTime() - ms).toISOString();
      expect(formatRelativeTime(null)).toBe("—");
      expect(formatRelativeTime("rubbish")).toBe("—");
      expect(formatRelativeTime(ago(2_000))).toBe("just now");
      expect(formatRelativeTime(ago(30_000))).toBe("30s ago");
      expect(formatRelativeTime(ago(5 * 60_000))).toBe("5m ago");
      expect(formatRelativeTime(ago(3 * 3_600_000))).toBe("3h ago");
      expect(formatRelativeTime(ago(2 * 86_400_000))).toBe("2d ago");
      // A clock-skewed future timestamp clamps to "just now" rather than
      // printing a negative age.
      expect(formatRelativeTime(new Date(now.getTime() + 60_000).toISOString())).toBe("just now");
    } finally {
      vi.useRealTimers();
    }
  });

  it("formatDurationShort labels a millisecond span, with a sub-minute floor", () => {
    expect(formatDurationShort(0)).toBe("<1m");
    expect(formatDurationShort(20_000)).toBe("<1m");
    expect(formatDurationShort(5 * 60_000)).toBe("5m");
    expect(formatDurationShort(90 * 60_000)).toBe("1h 30m");
    expect(formatDurationShort(-1000)).toBe("<1m");
  });
});
