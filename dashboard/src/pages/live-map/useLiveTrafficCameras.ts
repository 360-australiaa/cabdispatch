import { useQuery } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import type { Page } from "@/hooks/useGeofences";
import { POLL, pollingQueryOptions } from "@/lib/pollIntervals";
import { bboxParam, type TrafficBBox, type TrafficCamera } from "./trafficTypes";

/** The backend caps `limit` at 200 (`Query(..., le=200)` in
 * `backend/app/api/v1/traffic.py`) and defaults to 50 -- passed explicitly
 * here so a busy viewport's camera count is not silently truncated to the
 * default. */
const CAMERAS_FETCH_LIMIT = 200;

/**
 * Live Map's read of `GET /v1/traffic/cameras?bbox=...` -- real Transport for
 * NSW traffic camera locations, each with a live-refreshing JPEG snapshot URL
 * (see trafficLayers.ts's popup builder for how that URL is shown).
 *
 * Cameras barely move and a stale one costs nothing beyond a slightly out of
 * date pin position, so this polls on the slowest named band (`POLL.AMBIENT`)
 * -- the same judgement call `pollIntervals.ts` already makes for "background
 * counts and badges", just applied to a slow-moving reference layer instead.
 * Hazards (see useLiveTrafficHazards.ts) are the half of this feed that
 * actually changes minute to minute and poll on `POLL.SUPPORTING` instead.
 *
 * `bbox` is null until the map has a real viewport to ask about (see
 * FleetMapCanvas's `trafficBbox` state, set from `map.getBounds()` on load
 * and again on every `moveend`) -- the query stays disabled until then
 * rather than firing once for "the whole world" and again a moment later for
 * wherever the map actually opened.
 */
const LIVE_TRAFFIC_CAMERAS_KEY = "live-traffic-cameras";

export function useLiveTrafficCameras(bbox: TrafficBBox | null, enabled = true) {
  return useQuery({
    queryKey: [LIVE_TRAFFIC_CAMERAS_KEY, bbox ? bboxParam(bbox) : null],
    queryFn: async () => {
      const res = await apiClient.get<Page<TrafficCamera>>("/v1/traffic/cameras", {
        params: { bbox: bboxParam(bbox as TrafficBBox), limit: CAMERAS_FETCH_LIMIT },
      });
      return res.data.items;
    },
    enabled: enabled && bbox != null,
    ...pollingQueryOptions(POLL.AMBIENT),
  });
}
