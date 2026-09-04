import type { JobOfferStatus, JobStatus } from "./types";

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  // Explicit "en-AU" -- matches the rest of the app's date-formatting
  // convention (Trips/Shifts/Tariffs/Vouchers all pin this locale); this was
  // previously left as the browser default, so this page alone could render
  // dates in a different order on a non-AU browser.
  return d.toLocaleString("en-AU", { day: "2-digit", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
}

export function formatMoney(value: string | number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  const n = typeof value === "string" ? Number.parseFloat(value) : value;
  if (Number.isNaN(n)) return "—";
  // Was a raw `$${n.toFixed(2)}` -- no thousands separator, not locale-aware.
  // Matches Trips/Vouchers/Wallet's Intl.NumberFormat AUD convention.
  return new Intl.NumberFormat("en-AU", { style: "currency", currency: "AUD" }).format(n);
}

export function jobStatusBadgeVariant(
  status: string,
): "default" | "primary" | "accent" | "success" | "destructive" | "outline" {
  switch (status as JobStatus) {
    case "queued":
      return "outline";
    case "offered":
      return "accent";
    case "accepted":
      return "success";
    case "expired":
      return "destructive";
    case "cancelled":
      return "default";
    default:
      return "default";
  }
}

export function offerStatusBadgeVariant(
  status: string,
): "default" | "primary" | "accent" | "success" | "destructive" | "outline" {
  switch (status as JobOfferStatus) {
    case "pending":
      return "accent";
    case "accepted":
      return "success";
    case "declined":
      return "default";
    case "expired":
      return "destructive";
    default:
      return "default";
  }
}

/** Whole seconds remaining until `deadlineIso`, clamped to >= 0. */
export function secondsUntil(deadlineIso: string | null | undefined, nowMs: number): number {
  if (!deadlineIso) return 0;
  const deadlineMs = new Date(deadlineIso).getTime();
  if (Number.isNaN(deadlineMs)) return 0;
  return Math.max(0, Math.round((deadlineMs - nowMs) / 1000));
}
