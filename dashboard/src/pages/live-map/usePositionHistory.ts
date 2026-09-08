import { useCallback, useEffect, useRef } from "react";
import type { VehicleLiveRead } from "./types";
import { IDLE_WINDOW_MS, computeIdleInfo, type IdleInfo, type PositionSample } from "./utils";

/**
 * Client-side-only position history for idle detection (see utils.ts's
 * computeIdleInfo for the actual rule this feeds). There is no backend
 * endpoint for this and deliberately isn't one -- this backend has no
 * scheduler/background-job runner at all, and every position this needs is
 * already in the same `GET /v1/vehicles` snapshot + `WS /v1/fleet/live`
 * stream the Live Map page already polls, so persisting history server-side
 * would be a much bigger, riskier change than the payoff justifies.
 *
 * History lives only in the returned ref (per browser tab), trimmed to the
 * last IDLE_WINDOW_MS on every update, and is lost on refresh -- fine here,
 * since "has this vehicle moved in the last N minutes" is inherently a
 * live observation window, not a durable fact worth persisting (same
 * reasoning as `useFleetLiveSocket`'s own ephemeral-cache doc in
 * hooks/useLiveMap.ts).
 *
 * Updated in a `useEffect` (after render, not during it) so a vehicle whose
 * lat/lng didn't change this tick is a guaranteed no-op -- the returned
 * getter can therefore lag the very latest `vehicles` snapshot by at most one
 * render, which is immaterial given idle status only flips on minutes-scale
 * thresholds.
 */
export function usePositionHistory(vehicles: VehicleLiveRead[]): (vehicle: VehicleLiveRead) => IdleInfo {
  const historyRef = useRef<Map<string, PositionSample[]>>(new Map());

  useEffect(() => {
    const now = Date.now();
    const cutoff = now - IDLE_WINDOW_MS;
    const seen = new Set<string>();

    for (const v of vehicles) {
      seen.add(v.id);
      if (v.lat == null || v.lng == null) continue;

      const history = historyRef.current.get(v.id) ?? [];
      const last = history[history.length - 1];
      if (!last || last.lat !== v.lat || last.lng !== v.lng) {
        history.push({ lat: v.lat, lng: v.lng, at: now });
      }
      while (history.length > 1 && history[0].at < cutoff) history.shift();
      historyRef.current.set(v.id, history);
    }

    // Drop history for any vehicle no longer present in this snapshot
    // (retired, or fell out of the map's fetch limit) so a long-lived tab
    // doesn't grow this map unbounded.
    for (const id of historyRef.current.keys()) {
      if (!seen.has(id)) historyRef.current.delete(id);
    }
  }, [vehicles]);

  return useCallback(
    (vehicle: VehicleLiveRead) => computeIdleInfo(vehicle, historyRef.current.get(vehicle.id)),
    [],
  );
}
