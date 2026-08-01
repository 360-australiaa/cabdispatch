import axios from "axios";
import type { CappedRateField, Region, UncappedRateField } from "@/hooks/useTariffStudio";

/** All rate fields on a Tariff arrive as decimal strings. This is the one
 * place that turns them into a display string — never parsed for further
 * arithmetic. */
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

/** `<input type="datetime-local">` wants "YYYY-MM-DDTHH:mm" in local time,
 * no timezone/seconds suffix. */
export function toDatetimeLocalValue(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Inverse of `toDatetimeLocalValue` — local wall-clock input value to an
 * ISO instant the backend can parse. Empty string -> undefined (caller
 * decides whether that means "omit" or "clear"). */
export function fromDatetimeLocalValue(value: string): string | undefined {
  if (!value) return undefined;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return undefined;
  return d.toISOString();
}

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

/** Extracts a human-readable message from an axios error. Handles both
 * shapes the tariffs API returns on 4xx: a plain string `detail` (raised
 * directly by `HTTPException`, e.g. the Fares Order cap violation) and the
 * FastAPI/pydantic `HTTPValidationError` array shape (field-level 422s). */
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

/** True if the Fares Order 422 detail string names this specific field —
 * lets the form put the inline error next to the offending input as well as
 * in the general error banner. Backend message format: "...field '<f>' =
 * <candidate> exceeds Fares Order reference '<name>' cap <cap>". */
export function errorMentionsField(message: string, field: string): boolean {
  return message.includes(`field '${field}'`);
}
