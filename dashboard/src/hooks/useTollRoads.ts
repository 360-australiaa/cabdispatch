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

export type TollPricingModel =
  | "flat"
  | "zone_flat"
  | "distance"
  | "distance_with_flagfall"
  | "time_of_day"
  | "unpriced";

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
  time_of_day_rates_class_a: TollTimeOfDayRate[] | null;
  currency: string;
  gst_included: boolean;
  effective_date: string;
  indexation: string;
  confidence: "verified" | "needs_verification" | "not_captured" | string;
  verify_note: string | null;
}

export interface TollRoad {
  id: string;
  api_code: string | null;
  name: string;
  operator: string | null;
  pricing_model: TollPricingModel;
  directional: TollDirectional;
  description: string | null;
  derived_corridor_km: string | null;
  source_note: string | null;
  gantry_count: number;
  current_price: TollRoadPriceRevision | null;
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
