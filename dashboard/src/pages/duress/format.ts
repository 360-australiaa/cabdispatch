/** Display formatting for the Duress page.
 *
 * The `format*` helpers below are re-exported from `@/lib/format`, which is
 * now the single implementation of each (see that module's header: this file
 * used to carry its own copy, one of 12 near-identical ones across the
 * per-page `format.ts` modules). Only the page-specific helpers below are local.
 */

import { isTerminalStatus, type DuressCallResult, type DuressEvent, type DuressStatus } from "./types";

export {
  errorMessage,
  formatDateTimeSeconds as formatDateTime,
  formatTimeSeconds as formatTime,
} from "@/lib/format";

/** "sms_emergency_contacts" -> "Sms emergency contacts" */
export function formatStageLabel(stage: string): string {
  const spaced = stage.replace(/_/g, " ");
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

export function statusBadgeVariant(
  status: string,
): "default" | "primary" | "accent" | "success" | "destructive" | "outline" {
  switch (status as DuressStatus) {
    case "open":
      return "accent";
    case "escalating":
      return "destructive";
    case "dispatched":
      return "primary";
    case "resolved":
      return "success";
    case "cancelled":
      return "outline";
    default:
      return "default";
  }
}

/** Human label for a `DuressEvent.source` value, for the incident header
 * badge. */
export function sourceLabel(source: string): string {
  switch (source) {
    case "tablet":
      return "Tablet";
    case "device":
      return "Device";
    case "both":
      return "Both";
    default:
      return source;
  }
}

export function sourceBadgeVariant(
  source: string,
): "default" | "primary" | "accent" | "success" | "destructive" | "outline" {
  switch (source) {
    case "tablet":
      return "outline";
    case "device":
      return "accent";
    case "both":
      return "primary";
    default:
      return "default";
  }
}

/** One-line summary of a `DuressCallResult` (either a fresh mutation result
 * or the persisted `device_call_result_json`) for inline display next to the
 * "Call the cab" action and in the device-call summary panel. */
export function formatCallResultSummary(result: DuressCallResult): string {
  if (result.mock && result.skipped) {
    return "Simulated — no call-centre number configured";
  }
  if (result.to_phone && result.twilio_call_sid) {
    return `Calling ${result.to_phone} (Twilio SID: ${result.twilio_call_sid})`;
  }
  if (result.to_phone) {
    return `Calling ${result.to_phone}`;
  }
  return "Call placed.";
}

/** How long a non-terminal event may sit before the desk calls it stale.
 * 12 hours: a real panic event is either resolved or escalated to 000 well
 * inside a shift; anything open past that is almost certainly a forgotten
 * test/false alarm nobody closed (the live tenant had four of those). Same
 * threshold the backend's `stale` flag is specified against. */
export const STALE_AFTER_MS = 12 * 60 * 60 * 1000;

/** Is this event stale? Prefers the server's own `stale` flag when the
 * backend sends one (single source of truth once it lands); otherwise
 * computes it here from `opened_at` so an older backend still gets the
 * badge. Terminal events are never stale -- they are simply closed. */
export function isStaleEvent(
  event: Pick<DuressEvent, "status" | "opened_at"> & { stale?: boolean },
  nowMs: number = Date.now(),
): boolean {
  if (isTerminalStatus(event.status)) return false;
  if (typeof event.stale === "boolean") return event.stale;
  const openedMs = new Date(event.opened_at).getTime();
  if (Number.isNaN(openedMs)) return false;
  return nowMs - openedMs >= STALE_AFTER_MS;
}

/** Whole seconds remaining until `deadlineIso`, clamped to >= 0. */
export function secondsUntil(deadlineIso: string | null | undefined, nowMs: number): number {
  if (!deadlineIso) return 0;
  const deadlineMs = new Date(deadlineIso).getTime();
  if (Number.isNaN(deadlineMs)) return 0;
  return Math.max(0, Math.round((deadlineMs - nowMs) / 1000));
}
