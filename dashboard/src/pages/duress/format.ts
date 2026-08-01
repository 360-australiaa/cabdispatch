import type { DuressStatus } from "./types";

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

/** Whole seconds remaining until `deadlineIso`, clamped to >= 0. */
export function secondsUntil(deadlineIso: string | null | undefined, nowMs: number): number {
  if (!deadlineIso) return 0;
  const deadlineMs = new Date(deadlineIso).getTime();
  if (Number.isNaN(deadlineMs)) return 0;
  return Math.max(0, Math.round((deadlineMs - nowMs) / 1000));
}
