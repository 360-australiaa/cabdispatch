import { useQuery } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import type { Geofence, Page } from "@/hooks/useGeofences";

/**
 * Live Map's own read of `GET /v1/geofences`, for the geofence-breach overlay
 * (FleetMapCanvas draws every circle + badges any vehicle currently inside
 * one). Reuses the `Geofence`/`Page` types from hooks/useGeofences.ts (that
 * file owns the actual CRUD -- create/update/delete -- for Tariff Studio's
 * Toll Zones tab) but keeps its own query key and posture: this page only
 * ever reads the list, and geofence rows are admin-managed and rarely change
 * (see backend/app/services/geofence.py's own doc: "the (small,
 * admin-managed) set of geofence rows"), so a long staleTime is deliberate --
 * unlike the vehicle/duress queries on this same page, there's no value in
 * refetching this every poll tick.
 */
const LIVE_MAP_GEOFENCES_KEY = "live-map-geofences";

// Matches this being a small admin-managed reference set (see the module doc
// above) -- if a tenant configures more than this, the map would need real
// pagination here, the same caveat MAP_FETCH_LIMIT already carries in
// live-map/index.tsx for vehicles.
const GEOFENCE_FETCH_LIMIT = 200;

export function useLiveMapGeofencesQuery() {
  return useQuery({
    queryKey: [LIVE_MAP_GEOFENCES_KEY],
    queryFn: async () => {
      const res = await apiClient.get<Page<Geofence>>("/v1/geofences", {
        params: { skip: 0, limit: GEOFENCE_FETCH_LIMIT },
      });
      return res.data.items;
    },
    staleTime: 5 * 60 * 1000,
  });
}
