import { useQuery } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import type { VehiclePositionHistoryResponse } from "./types";

/**
 * `GET /v1/vehicles/{id}/position-history` -- backs VehicleDetailModal's
 * Replay scrubber and driving-signals readout (see that component). Optional
 * `since` (an ISO datetime) narrows the window; omitted, the backend's own
 * default retention window applies -- this hook never hardcodes that number
 * itself, it only ever passes `since` through when the caller supplies one
 * and otherwise takes whatever the backend decides to return (see
 * VehiclePositionHistoryResponse's own doc in types.ts for why that window is
 * a technical default, not a decided policy, and how this domain phrases
 * that honestly wherever it's shown).
 *
 * Named `usePositionHistoryQuery`, in its own `useVehiclePositionHistory.ts`
 * file rather than `usePositionHistory.ts` -- that filename is already taken
 * by this domain's existing `usePositionHistory` hook (see that file), a
 * completely unrelated, client-side-only in-memory buffer for idle detection
 * that has no backend endpoint at all and that `index.tsx` already depends
 * on. The two happen to share a natural name for "a vehicle's position
 * history"; keeping this one in its own file avoids renaming or otherwise
 * touching that existing, working hook just to make room for this one.
 */
export function usePositionHistoryQuery(vehicleId: string | null, since?: string) {
  return useQuery({
    queryKey: ["live-map", "vehicle-position-history", vehicleId, since ?? null],
    queryFn: async () => {
      const res = await apiClient.get<VehiclePositionHistoryResponse>(
        `/v1/vehicles/${vehicleId}/position-history`,
        { params: since ? { since } : undefined },
      );
      return res.data;
    },
    enabled: vehicleId != null,
  });
}
