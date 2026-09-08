/**
 * E.164 phone number helpers for `pages/duress/DeviceFormModal.tsx` (audit
 * §4, WS-F.5: "+61…" placeholder with no country selector).
 *
 * Deliberately small and hand-rolled rather than a `libphonenumber` import:
 * the one field that needs this is an optional callback number for a duress
 * hardware unit, not a general-purpose phone book, and a real E.164
 * validator library is tens of kB gzipped for a single `<input>`. This
 * covers full E.164 syntax validation and a short list of countries this
 * product plausibly operates in (extend the list, not the parser, as new
 * jurisdictions are onboarded).
 */

export interface CountryDialCode {
  /** ISO 3166-1 alpha-2. */
  iso: string;
  dialCode: string;
  name: string;
}

/** Ordered roughly by expected frequency for this product today. Add a row
 * here, not a special case elsewhere, when a new jurisdiction is onboarded. */
export const COUNTRY_DIAL_CODES: CountryDialCode[] = [
  { iso: "AU", dialCode: "+61", name: "Australia" },
  { iso: "NZ", dialCode: "+64", name: "New Zealand" },
  { iso: "GB", dialCode: "+44", name: "United Kingdom" },
  { iso: "US", dialCode: "+1", name: "United States / Canada" },
  { iso: "IE", dialCode: "+353", name: "Ireland" },
  { iso: "SG", dialCode: "+65", name: "Singapore" },
  { iso: "ZA", dialCode: "+27", name: "South Africa" },
];

export const DEFAULT_COUNTRY_ISO = "AU";

/** Full E.164: '+' then 8-15 digits total, first digit 1-9 (ITU-T E.164 §6). */
const E164_RE = /^\+[1-9]\d{7,14}$/;

export function isValidE164(value: string): boolean {
  return E164_RE.test(value);
}

/**
 * Splits a stored E.164 value into the country whose dial code prefixes it
 * (longest-prefix match, since `+1` is a prefix of nothing else here but a
 * future entry might not be) and the remaining national digits. Returns
 * `null` for anything that isn't valid E.164 or whose dial code isn't in
 * `COUNTRY_DIAL_CODES` — callers fall back to showing the raw value rather
 * than guessing, per the honesty rule.
 */
export function splitE164(value: string): { iso: string; national: string } | null {
  if (!isValidE164(value)) return null;
  const byLength = [...COUNTRY_DIAL_CODES].sort((a, b) => b.dialCode.length - a.dialCode.length);
  for (const country of byLength) {
    if (value.startsWith(country.dialCode)) {
      return { iso: country.iso, national: value.slice(country.dialCode.length) };
    }
  }
  return null;
}

/** Inverse of `splitE164`: dial code for `iso` + digits-only `national` ->
 * full E.164 string. Strips anything that isn't a digit from `national`
 * (a leading trunk '0', spaces, dashes) since those are never part of E.164. */
export function joinE164(iso: string, national: string): string {
  const country = COUNTRY_DIAL_CODES.find((c) => c.iso === iso);
  const dialCode = country?.dialCode ?? "";
  const digits = national.replace(/\D/g, "").replace(/^0+/, "");
  return `${dialCode}${digits}`;
}
