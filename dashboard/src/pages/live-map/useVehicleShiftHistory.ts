import { useQuery } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import type { Page, VehicleShiftHistoryItem } from "./types";

/**
 * `GET /v1/fleet/vehicles/{id}/shift-history` -- "which drivers has this
 * vehicle had", not just the live current one (see
 * backend/app/services/fleet.py::list_vehicle_shift_history). Fetches a
 * single generously-sized page (the `Table` component below paginates
 * client-side) since a per-vehicle shift count is small enough per tenant
 * that a second server round-trip per page click isn't worth it -- same
 * "small fleet, fine to compute/page in one go" reasoning already used by
 * GET /v1/fleet/compliance-expiry.
 */
export function useVehicleShiftHistoryQuery(vehicleId: string | null) {
  return useQuery({
    queryKey: ["live-map", "vehicle-shift-history", vehicleId],
    queryFn: async () => {
      const res = await apiClient.get<Page<VehicleShiftHistoryItem>>(
        `/v1/fleet/vehicles/${vehicleId}/shift-history`,
        { params: { limit: 100, skip: 0 } },
      );
      return res.data;
    },
    enabled: vehicleId != null,
  });
}
