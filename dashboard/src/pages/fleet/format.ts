import axios from "axios";

/** Small display-formatting helpers shared across the Fleet & Drivers panels. */

/** Best-effort human message out of an Axios/FastAPI error. */
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
  if (Number.isNaN(d.getTime())) return "—";
  // Explicit "en-AU" -- every other page module's formatDateTime hardcodes
  // this locale; this one used to fall back to the browser's default locale,
  // which meant this page alone could render dates in a different order
  // (e.g. MM/DD/YYYY) from the rest of the app.
  return d.toLocaleString("en-AU", { dateStyle: "medium", timeStyle: "short" });
}

/** Human "3m ago" / "Never" for last-seen style timestamps. */
export function relativeFromNow(iso: string | null | undefined): string {
  if (!iso) return "Never";
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "Never";
  const diffMs = Date.now() - then;
  if (diffMs < 0) return formatDateTime(iso);
  const mins = Math.round(diffMs / 60000);
  if (mins < 1) return "Just now";
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  return `${days}d ago`;
}

export function truncateId(id: string | null | undefined, len = 8): string {
  if (!id) return "—";
  return id.length > len ? `${id.slice(0, len)}…` : id;
}

/** Resolves a vehicle_id to its rego via a `{id: rego}` lookup map, with an
 * honest fallback when the id doesn't resolve — never the bare UUID.
 *
 * Real bug this fixes: `Shift.vehicle_id` has no foreign key (see
 * backend/app/models/shift.py's own DEVIATION note), so an admin deleting a
 * vehicle out from under a driver's OPEN shift used to leave that shift
 * pointing at a vehicle id nothing resolves any more — the drivers list then
 * rendered the raw UUID (`vehicleRegoById.get(id) ?? id`) where a rego
 * should be, which reads as a broken app, not "this vehicle was deleted".
 * (The backend now closes that dangling shift as part of the vehicle
 * delete — see `close_open_shifts_for_vehicle_deletion` — but this fallback
 * stays regardless, for any id that still doesn't resolve for other
 * reasons, e.g. a fleet with more vehicles than `useVehicleOptions`'s
 * lookup cap.) */
export function vehicleLabel(vehicleId: string | null | undefined, regoById: Map<string, string>): string {
  if (!vehicleId) return "—";
  return regoById.get(vehicleId) ?? "— (vehicle not found)";
}

/** Two-letter avatar initials from a driver's full name (e.g. "Jane Doe" -> "JD").
 * Mirrors src/pages/messages/format.ts's initials helper. */
export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  const first = parts[0]?.[0] ?? "";
  const last = parts.length > 1 ? parts[parts.length - 1]?.[0] ?? "" : "";
  return (first + last).toUpperCase() || "?";
}
