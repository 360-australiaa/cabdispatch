import { useQuery } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import { POLL, pollingQueryOptions } from "@/lib/pollIntervals";
import type { DriverLiveRead, VehicleLiveRead } from "./types";

/**
 * `GET /v1/vehicles/{id}` -- same `VehicleLiveRead` shape as one row of
 * `GET /v1/vehicles` (see backend/app/services/live_ops.py::get_vehicle_live),
 * just resolved fresh for a single vehicle rather than read off whatever page
 * of the list happens to be cached. Backs the map-marker / table-row
 * drill-down (VehicleDetailModal) and, with `poll: true`, the Vehicle page's
 * Live tab (`pages/vehicles/tabs/LiveTab.tsx`) -- that tab is the first
 * caller that needs this to stay current on its own rather than only
 * refreshing when the sheet/modal that opened it remounts, so polling is an
 * opt-in addition rather than the default, leaving VehicleDetailModal's
 * existing one-shot-per-open behaviour unchanged.
 */
export function useVehicleDetailQuery(vehicleId: string | null, options: { poll?: boolean } = {}) {
  return useQuery({
    queryKey: ["live-map", "vehicle-detail", vehicleId],
    queryFn: async () => {
      const res = await apiClient.get<VehicleLiveRead>(`/v1/vehicles/${vehicleId}`);
      return res.data;
    },
    enabled: vehicleId != null,
    ...(options.poll ? pollingQueryOptions(POLL.LIVE_POSITIONS) : {}),
  });
}

/**
 * `GET /v1/drivers/{id}` -- only called when the detail panel needs a field
 * `VehicleLiveRead.current_driver_*` doesn't already carry (e.g. phone
 * number); id/name/on-shift status come straight off the vehicle row.
 */
export function useDriverDetailQuery(driverId: string | null) {
  return useQuery({
    queryKey: ["live-map", "driver-detail", driverId],
    queryFn: async () => {
      const res = await apiClient.get<DriverLiveRead>(`/v1/drivers/${driverId}`);
      return res.data;
    },
    enabled: driverId != null,
  });
}
