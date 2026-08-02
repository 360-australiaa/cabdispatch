import type { JobOfferStatus, JobStatus } from "./types";

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "medium" });
}

export function formatMoney(value: string | number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  const n = typeof value === "string" ? Number.parseFloat(value) : value;
  if (Number.isNaN(n)) return "—";
  return `$${n.toFixed(2)}`;
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
