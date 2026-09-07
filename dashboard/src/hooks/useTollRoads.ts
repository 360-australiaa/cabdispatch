import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";

/**
 * Data layer for the real NSW toll-road registry (`/v1/toll-roads`, see
 * `backend/app/models/toll.py` / `backend/app/services/tolls.py`) — the
 * per-road, per-gantry, direction-aware replacement for the old flat-circle
 * toll geofences (`useGeofences.ts`, still used for tenant-defined ad hoc
 * toll circles, unrelated to this real registry).
 *
 * Read-only for every authenticated user (platform-wide reference data, not
 * tenant-scoped); adding a price revision (a quarterly reindexation) is
 * platform-owner-only, same rule `useGeofences.ts` / `useTariffStudio.ts`
 * already apply to toll/tariff pricing.
 */

/** `zone_flat` was retired by the 2026-09-07 price-correction pass — the roads
 * that carried it (M2, Cross City Tunnel, Lane Cove Tunnel) now have Linkt's
 * real per-toll-point prices and are `per_point` instead. It stays in this
 * union only because a database seeded by an older build can still contain
 * the value, and a road the dashboard cannot name is worse than one it names
 * as stale. See `backend/app/models/toll.py`. */
export type TollPricingModel =
  | "flat"
  | "per_point"
  | "zone_flat"
  | "distance"
  | "distance_with_flagfall"
  | "time_of_day"
  | "unpriced";

/** How gantry crossings become a charge. NOT derivable from the pricing model:
 * Hills M2 and Lane Cove Tunnel are both `per_point` and charge differently
 * (M2 additively per point traversed; LCT once, whichever point fired first).
 * See `backend/app/models/toll.py`'s TOLL_CHARGING_POLICIES. */
export type TollChargingPolicy = "once_per_road" | "cumulative_per_point" | "distance_metered";

export type TollDirectional = "both" | "one_way" | "northbound_only" | "southbound_only" | null;

export interface TollTimeOfDayRate {
  band: string;
  price: string;
  windows?: string;
}

export interface TollRoadPriceRevision {
  id: string;
  price_class_a_min: string | null;
  price_class_a_max: string | null;
  price_class_b_min: string | null;
  price_class_b_max: string | null;
  cap_class_a: string | null;
  cap_class_b: string | null;
  /** Formula inputs for the distance-priced models, and the shared cap across a
   * `network_group` (WestConnex). Null on any revision captured before the
   * 2026-09-07 correction pass. */
  rate_per_km_class_a: string | null;
  rate_per_km_class_b: string | null;
  flagfall_class_a: string | null;
  flagfall_class_b: string | null;
  network_cap_class_a: string | null;
  network_cap_class_b: string | null;
  time_of_day_rates_class_a: TollTimeOfDayRate[] | null;
  currency: string;
  gst_included: boolean;
  effective_date: string;
  indexation: string;
  confidence: "verified" | "needs_verification" | "not_captured" | string;
  verify_note: string | null;
  /** The page this exact figure was read off, and the day it was read. On a
   * fare-regulated meter a toll an operator cannot trace is a toll it cannot
   * defend, so this is surfaced in the UI rather than kept as metadata. */
  source_url: string | null;
  retrieved_at: string | null;
}

export interface TollPointPriceRevision {
  id: string;
  price_class_a: string | null;
  price_class_b: string | null;
  currency: string;
  gst_included: boolean;
  effective_date: string;
  indexation: string;
  confidence: "verified" | "needs_verification" | "not_captured" | string;
  verify_note: string | null;
  source_url: string | null;
  retrieved_at: string | null;
}

/** One named toll point of a `per_point` road. Such a road has no meaningful
 * road-level price — its min/max is a descriptive range across these points,
 * never what a trip is charged — so these are the real prices for M2/CCT/LCT. */
export interface TollPoint {
  id: string;
  toll_road_id: string;
  name: string;
  description: string | null;
  source_note: string | null;
  gantry_count: number;
  current_price: TollPointPriceRevision | null;
}

export interface TollRoad {
  id: string;
  api_code: string | null;
  name: string;
  operator: string | null;
  pricing_model: TollPricingModel;
  charging_policy: TollChargingPolicy | string;
  /** Roads sharing one cap for a single trip (today only "WESTCONNEX"). */
  network_group: string | null;
  directional: TollDirectional;
  description: string | null;
  derived_corridor_km: string | null;
  source_note: string | null;
  gantry_count: number;
  current_price: TollRoadPriceRevision | null;
  /** Empty for every road priced at the road level. */
  toll_points: TollPoint[];
}

export interface TollGantry {
  id: string;
  toll_road_id: string;
  location: string;
  ramp: string | null;
  direction: string | null;
  latitude: number;
  longitude: number;
}

export interface TollRoadDetail extends TollRoad {
  gantries: TollGantry[];
  price_history: TollRoadPriceRevision[];
}

export interface TollRoadPriceRevisionInput {
  price_class_a_min?: string | null;
  price_class_a_max?: string | null;
  price_class_b_min?: string | null;
  price_class_b_max?: string | null;
  cap_class_a?: string | null;
  cap_class_b?: string | null;
  time_of_day_rates_class_a?: TollTimeOfDayRate[] | null;
  currency?: string;
  gst_included?: boolean;
  effective_date: string;
  indexation: string;
  confidence: string;
  verify_note?: string | null;
}

const TOLL_ROADS_KEY = "toll-roads";

export function useTollRoadsQuery() {
  return useQuery({
    queryKey: [TOLL_ROADS_KEY],
    queryFn: async () => {
      const res = await apiClient.get<TollRoad[]>("/v1/toll-roads");
      return res.data;
    },
  });
}

export function useTollRoadDetailQuery(roadId: string | null) {
  return useQuery({
    queryKey: [TOLL_ROADS_KEY, roadId],
    queryFn: async () => {
      const res = await apiClient.get<TollRoadDetail>(`/v1/toll-roads/${roadId}`);
      return res.data;
    },
    enabled: roadId != null,
  });
}

export function useCreateTollRoadPriceRevisionMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ roadId, input }: { roadId: string; input: TollRoadPriceRevisionInput }) => {
      const res = await apiClient.post<TollRoadPriceRevision>(`/v1/toll-roads/${roadId}/price-revisions`, input);
      return res.data;
    },
    onSuccess: (_data, { roadId }) => {
      queryClient.invalidateQueries({ queryKey: [TOLL_ROADS_KEY] });
      queryClient.invalidateQueries({ queryKey: [TOLL_ROADS_KEY, roadId] });
    },
  });
}
