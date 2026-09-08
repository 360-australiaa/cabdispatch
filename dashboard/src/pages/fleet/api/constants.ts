/**
 * The two page-size caps shared by every Fleet & Drivers query.
 *
 * Split out of the old single `pages/fleet/api.ts` (Phase 0) so vehicles.ts,
 * drivers.ts, devices.ts and wipe.ts can each import them without any of those
 * four having to own the constant the other three depend on.
 */

export const PAGE_LIMIT = 10;

/** Cap used for the lightweight "all vehicles / all devices" lookups used to
 * cross-reference labels (e.g. a vehicle's linked device android_id). Fine
 * for demo/dev-scale fleets; a tenant with >100 vehicles would need a real
 * search-as-you-type endpoint instead. */
export const LOOKUP_LIMIT = 100;
