import { useQuery } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import type { Page } from "@/hooks/useGeofences";
import { POLL, pollingQueryOptions } from "@/lib/pollIntervals";
import { bboxParam, type TrafficBBox, type TrafficHazard, type TrafficHazardCategory } from "./trafficTypes";

/** Same reasoning as CAMERAS_FETCH_LIMIT in useLiveTrafficCameras.ts -- the
 * backend caps `limit` at 200 and defaults to 50. */
const HAZARDS_FETCH_LIMIT = 200;

/**
 * Live Map's read of `GET /v1/traffic/hazards?bbox=...&category=...&active_only=...`
 * -- real Transport for NSW incidents/roadworks/closures.
 *
 * Polls on `POLL.SUPPORTING` (20s), the same band `pollIntervals.ts` already
 * names for "slow-moving supporting data on a live screen: zones, geofences,
 * the map's vehicle roster" -- a hazard is exactly that from a dispatcher's
 * seat: worth having current, but nobody is staring at this one row waiting
 * for it to change the way they watch a duress event.
 *
 * `activeOnly` defaults true (a cleared hazard is drawn fainter rather than
 * dropped only when the caller explicitly asks for it -- v1 never does).
 */
export interface UseLiveTrafficHazardsOptions {
  category?: TrafficHazardCategory;
  activeOnly?: boolean;
  enabled?: boolean;
}

const LIVE_TRAFFIC_HAZARDS_KEY = "live-traffic-hazards";

export function useLiveTrafficHazards(bbox: TrafficBBox | null, options: UseLiveTrafficHazardsOptions = {}) {
  const { category, activeOnly = true, enabled = true } = options;
  return useQuery({
    queryKey: [LIVE_TRAFFIC_HAZARDS_KEY, bbox ? bboxParam(bbox) : null, category ?? null, activeOnly],
    queryFn: async () => {
      const res = await apiClient.get<Page<TrafficHazard>>("/v1/traffic/hazards", {
        params: {
          bbox: bboxParam(bbox as TrafficBBox),
          category,
          active_only: activeOnly,
          limit: HAZARDS_FETCH_LIMIT,
        },
      });
      return res.data.items;
    },
    enabled: enabled && bbox != null,
    ...pollingQueryOptions(POLL.SUPPORTING),
  });
}
