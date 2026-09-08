import type { CappedRateField, Region, UncappedRateField } from "@/hooks/useTariffStudio";
import { formatMoney } from "@/lib/format";

/** Re-exported from the one shared implementation — see `@/lib/format`. */
export {
  formatMoney,
  formatDateTime,
  toDatetimeLocalValue,
  fromDatetimeLocalValue,
  extractErrorMessage,
} from "@/lib/format";

export const REGION_LABELS: Record<Region, string> = {
  urban: "Urban",
  country: "Country",
  exempt: "Exempt",
};

export const REGION_OPTIONS = (Object.keys(REGION_LABELS) as Region[]).map((value) => ({
  value,
  label: REGION_LABELS[value],
}));

export function regionBadgeVariant(region: Region): "primary" | "accent" | "outline" {
  if (region === "urban") return "primary";
  if (region === "country") return "accent";
  return "outline";
}

/** Label + unit suffix for every rate field, used both in the form and in
 * the Fares Order cap helper text. */
export const RATE_FIELD_META: Record<
  CappedRateField | UncappedRateField,
  { label: string; unit: "currency" | "currency-per-min" | "km" | "kmh" | "multiplier" | "percent" }
> = {
  flag_fall: { label: "Flag fall", unit: "currency" },
  peak_charge: { label: "Peak charge", unit: "currency" },
  dist_rate_1: { label: "Distance rate 1 (per km, under threshold)", unit: "currency" },
  dist_rate_2: { label: "Distance rate 2 (per km, over threshold)", unit: "currency" },
  night_rate_1: { label: "Night rate 1", unit: "currency" },
  night_rate_2: { label: "Night rate 2", unit: "currency" },
  holiday_rate_1: { label: "Public holiday rate 1", unit: "currency" },
  holiday_rate_2: { label: "Public holiday rate 2", unit: "currency" },
  waiting_rate_per_min: { label: "Waiting rate", unit: "currency-per-min" },
  dist_km_threshold: { label: "Distance rate threshold", unit: "km" },
  speed_threshold_kmh: { label: "Speed threshold (distance vs waiting mode)", unit: "kmh" },
  maxi_multiplier: { label: "Maxi taxi multiplier", unit: "multiplier" },
  multi_hire_pct: { label: "Multi-hire percentage", unit: "percent" },
  psl_amount: { label: "PSL amount", unit: "currency" },
  surcharge_pct_cap: { label: "Non-cash surcharge cap", unit: "percent" },
  cleaning_fee_cap: { label: "Cleaning fee cap", unit: "currency" },
};

export function formatRateValue(value: string, unit: (typeof RATE_FIELD_META)[keyof typeof RATE_FIELD_META]["unit"]) {
  if (value === "" || value == null) return "—";
  const n = Number(value);
  if (Number.isNaN(n)) return value;
  switch (unit) {
    case "currency":
      return formatMoney(value);
    case "currency-per-min":
      return `${formatMoney(value)}/min`;
    case "km":
      return `${n} km`;
    case "kmh":
      return `${n} km/h`;
    case "multiplier":
      return `×${n}`;
    case "percent":
      return `${n}%`;
    default:
      return value;
  }
}

/** True if the Fares Order 422 detail string names this specific field —
 * lets the form put the inline error next to the offending input as well as
 * in the general error banner. Backend message format: "...field '<f>' =
 * <candidate> exceeds Fares Order reference '<name>' cap <cap>". */
export function errorMentionsField(message: string, field: string): boolean {
  return message.includes(`field '${field}'`);
}
