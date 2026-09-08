/** Display formatting for the Dispatch page.
 *
 * The `format*` helpers below are re-exported from `@/lib/format`, which is
 * now the single implementation of each (see that module's header: this file
 * used to carry its own copy, one of 12 near-identical ones across the
 * per-page `format.ts` modules). Only the page-specific helpers below are local.
 */

import type { JobOfferStatus, JobStatus } from "./types";

export { formatDateTime, formatMoney } from "@/lib/format";

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
