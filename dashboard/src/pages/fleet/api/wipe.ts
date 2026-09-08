import { useMutation, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import { errorMessage } from "../format";
import type { Device, Driver, Page, Vehicle } from "../types";
import { LOOKUP_LIMIT } from "./constants";

/**
 * The two temporary bulk-wipe actions.
 *
 * Split out of `pages/fleet/api.ts` (804 lines) in Phase 0, into their own
 * module rather than being filed under vehicles/drivers/devices: both of them
 * span all three collections, so there is no one of the three they belong to.
 * Keeping them separate also means the day these are removed (they are
 * explicitly temporary -- see the note below) is a file deletion, not surgery
 * on a module that holds real product code. Every function here is the
 * original, unchanged; `api/index.ts` re-exports all of them so no call site's
 * import path changed.
 */

// ---------------------------------------------------------------------------
// TEMPORARY testing-only bulk wipe (2026-09-07, direct product instruction:
// "make the option in admin panel to delete all drivers, vehicles and
// devices in one go temporary until we finish all testing") — meant to be
// removed once onboarding/pairing testing is done, not a permanent fleet
// feature. No new backend endpoint: loops the existing per-item DELETE
// endpoints (DELETE /v1/fleet/vehicles/{id}, /v1/fleet/devices/{id},
// /v1/users/{id}) the same way useForceUpdateAll loops the per-device
// force-update endpoint (api/devices.ts), since none of these rows are
// FK-constrained against each other (see app/models/jobs.py's own "DEVIATION"
// doc for why this codebase already accepts unconstrained string ids between
// these domains) -- deleting in any order is safe.
// ---------------------------------------------------------------------------

export interface WipeAllFleetDataResult {
  vehiclesDeleted: number;
  driversDeleted: number;
  devicesDeleted: number;
  /** Rows that failed to delete (id + a short reason), one entry per failed
   * row across all three collections. Empty on a fully clean wipe. See
   * `deleteAllSequentially`'s doc for why this can be non-empty even on a
   * healthy backend. */
  failures: { kind: "vehicle" | "driver" | "device"; id: string; reason: string }[];
}

/**
 * Deletes every item one at a time (NOT `Promise.all`) and keeps going past
 * individual failures, rather than aborting the whole batch on the first one.
 *
 * This replaced an all-at-once `Promise.all` version (2026-09-06) that a real
 * bulk wipe against production reliably broke: firing every DELETE in a
 * collection simultaneously produced a mix of HTTP 503s and bare axios
 * "Network Error"s (no response at all) -- the single backend process
 * (`entrypoint.sh` runs one uvicorn worker, no reverse proxy in front of it,
 * see docker-compose.yml) plus its Postgres connection pool visibly couldn't
 * absorb a burst of a dozen-plus concurrent write requests while the page's
 * own background polling (compliance-expiry, live vehicle options) was also
 * in flight. `Promise.all` then meant that ONE dropped connection killed the
 * entire wipe with zero rows actually confirmed deleted. One-at-a-time keeps
 * the backend's concurrent load at 1 regardless of fleet size, and catching
 * each row's own error means a single flaky row (or a genuinely undeletable
 * one) no longer blocks every row after it.
 */
async function deleteAllSequentially<T>(
  items: T[],
  kind: WipeAllFleetDataResult["failures"][number]["kind"],
  idOf: (item: T) => string,
  del: (id: string) => Promise<unknown>,
): Promise<{ deleted: number; failures: WipeAllFleetDataResult["failures"] }> {
  let deleted = 0;
  const failures: WipeAllFleetDataResult["failures"] = [];
  for (const item of items) {
    const id = idOf(item);
    try {
      await del(id);
      deleted += 1;
    } catch (err) {
      failures.push({ kind, id, reason: errorMessage(err) });
    }
  }
  return { deleted, failures };
}

export function useWipeAllFleetData() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (): Promise<WipeAllFleetDataResult> => {
      const [vehiclesPage, driversPage, devicesPage] = await Promise.all([
        apiClient.get<Page<Vehicle>>("/v1/fleet/vehicles", { params: { skip: 0, limit: LOOKUP_LIMIT } }),
        apiClient.get<Page<Driver>>("/v1/drivers", { params: { skip: 0, limit: LOOKUP_LIMIT } }),
        apiClient.get<Page<Device>>("/v1/fleet/devices", { params: { skip: 0, limit: LOOKUP_LIMIT } }),
      ]);
      // Devices first (they reference a vehicle), then vehicles, then drivers --
      // purely for a sane order to read in logs if one step fails partway
      // through; no FK actually requires this order (see module note above).
      // Each collection is deleted one row at a time -- see
      // deleteAllSequentially's doc for why this replaced a Promise.all.
      const devicesResult = await deleteAllSequentially(
        devicesPage.data.items,
        "device",
        (d) => d.id,
        (id) => apiClient.delete(`/v1/fleet/devices/${id}`),
      );
      const vehiclesResult = await deleteAllSequentially(
        vehiclesPage.data.items,
        "vehicle",
        (v) => v.id,
        (id) => apiClient.delete(`/v1/fleet/vehicles/${id}`),
      );
      const driversResult = await deleteAllSequentially(
        driversPage.data.items,
        "driver",
        (d) => d.id,
        (id) => apiClient.delete(`/v1/users/${id}`),
      );
      return {
        vehiclesDeleted: vehiclesResult.deleted,
        driversDeleted: driversResult.deleted,
        devicesDeleted: devicesResult.deleted,
        failures: [...devicesResult.failures, ...vehiclesResult.failures, ...driversResult.failures],
      };
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["fleet", "vehicles"] });
      qc.invalidateQueries({ queryKey: ["fleet", "drivers"] });
      qc.invalidateQueries({ queryKey: ["fleet", "devices"] });
    },
  });
}

// ---------------------------------------------------------------------------
// TEMPORARY force-wipe (see backend app.services.fleet_wipe's module
// docstring for full context). `useWipeAllFleetData` above (correctly)
// leaves behind exactly the drivers who still have real PSL/wallet/rating/
// compliance/tariff-change-log evidence on file -- `assert_user_deletable`
// refuses those, and it must keep refusing them for the DEFAULT wipe. This
// is a SEPARATE, owner-only, explicitly-opted-into action for a genuinely
// empty test tenant: it calls a real backend endpoint
// (`POST /v1/fleet/wipe-test-data/force`) that purges those evidence rows
// server-side -- never implemented by looping per-row deletes from the
// client, which would silently defeat assert_user_deletable from outside
// with none of its "here's exactly what's blocking" honesty. It never
// deletes AuditLog rows, under any circumstance -- see
// `audit_log_preserved` below, always true, and the backend module
// docstring for why.
// ---------------------------------------------------------------------------

export interface FleetForceWipeFailure {
  kind: "vehicle" | "device" | "driver";
  id: string;
  reason: string;
}

export interface FleetForceWipeResult {
  vehiclesDeleted: number;
  devicesDeleted: number;
  driversDeleted: number;
  /** Evidence category -> row count PERMANENTLY destroyed by this call. */
  evidenceRowsDestroyed: Record<string, number>;
  /** Always true -- see this section's module note. Kept as a real field
   * (not assumed) so the confirm/result UI reflects exactly what the
   * backend says happened, not what the frontend assumes it did. */
  auditLogPreserved: boolean;
  failures: FleetForceWipeFailure[];
}

export function useForceWipeAllFleetData() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (): Promise<FleetForceWipeResult> => {
      const { data } = await apiClient.post("/v1/fleet/wipe-test-data/force", { confirm: true });
      return {
        vehiclesDeleted: data.vehicles_deleted,
        devicesDeleted: data.devices_deleted,
        driversDeleted: data.drivers_deleted,
        evidenceRowsDestroyed: data.evidence_rows_destroyed,
        auditLogPreserved: data.audit_log_preserved,
        failures: data.failures,
      };
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["fleet", "vehicles"] });
      qc.invalidateQueries({ queryKey: ["fleet", "drivers"] });
      qc.invalidateQueries({ queryKey: ["fleet", "devices"] });
    },
  });
}
