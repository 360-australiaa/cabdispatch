/** Display formatting for the Fleet & Drivers page.
 *
 * The `format*` helpers below are re-exported from `@/lib/format`, which is
 * now the single implementation of each (see that module's header: this file
 * used to carry its own copy, one of 12 near-identical ones across the
 * per-page `format.ts` modules). Only the page-specific helpers below are local.
 */

export {
  errorMessage,
  formatDateTimeShort as formatDateTime,
  relativeFromNow,
  truncateId,
  initials,
} from "@/lib/format";

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
