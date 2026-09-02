import axios from "axios";
import type { DuressCallResult, DuressStatus } from "./types";

/** Best-effort human message out of an Axios/FastAPI error -- same shape as
 * `pages/fleet/format.ts`'s helper of the same name, duplicated here rather
 * than shared cross-domain (no shared error-formatting util exists yet). */
export function errorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const detail = (err.response?.data as { detail?: unknown } | undefined)?.detail;
    if (typeof detail === "string") return detail;
    if (Array.isArray(detail)) {
      const first = detail[0] as { msg?: string; loc?: unknown[] } | undefined;
      if (first?.msg) {
        const field = Array.isArray(first.loc) ? first.loc.at(-1) : undefined;
        return field ? `${String(field)}: ${first.msg}` : first.msg;
      }
    }
    if (err.response?.status) return `Request failed (${err.response.status}).`;
    return err.message;
  }
  return err instanceof Error ? err.message : "Something went wrong.";
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString(undefined, {
    dateStyle: "medium",
    timeStyle: "medium",
  });
}

export function formatTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleTimeString(undefined, { timeStyle: "medium" });
}

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

/** Whole seconds remaining until `deadlineIso`, clamped to >= 0. */
export function secondsUntil(deadlineIso: string | null | undefined, nowMs: number): number {
  if (!deadlineIso) return 0;
  const deadlineMs = new Date(deadlineIso).getTime();
  if (Number.isNaN(deadlineMs)) return 0;
  return Math.max(0, Math.round((deadlineMs - nowMs) / 1000));
}
