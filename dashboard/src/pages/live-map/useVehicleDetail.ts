import { useQuery } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import type { DriverLiveRead, VehicleLiveRead } from "./types";

/**
 * `GET /v1/vehicles/{id}` -- same `VehicleLiveRead` shape as one row of
 * `GET /v1/vehicles` (see backend/app/services/live_ops.py::get_vehicle_live),
 * just resolved fresh for a single vehicle rather than read off whatever page
 * of the list happens to be cached. Backs the map-marker / table-row
 * drill-down (VehicleDetailModal).
 */
export function useVehicleDetailQuery(vehicleId: string | null) {
  return useQuery({
    queryKey: ["live-map", "vehicle-detail", vehicleId],
    queryFn: async () => {
      const res = await apiClient.get<VehicleLiveRead>(`/v1/vehicles/${vehicleId}`);
      return res.data;
    },
    enabled: vehicleId != null,
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
