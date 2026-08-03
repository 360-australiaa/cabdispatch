import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";

/**
 * Data layer for the Trips module (`src/pages/trips`). Mirrors
 * `shared/openapi.json` schemas TripRead / TripCreate / TripUpdate /
 * TripCloseRequest and the `/v1/trips`, `/v1/vehicles`, `/v1/drivers`,
 * `/v1/tariffs` read endpoints (see shared/API_SUMMARY.md).
 *
 * Money fields (flag_fall, dist_amount, wait_amount, peak_amount, tolls,
 * psl, extras, subtotal, surcharge, total, gst_component, variance_pct) are
 * decimal strings straight off the wire — never coerced here. Formatting
 * happens explicitly at render time in the page component.
 */

export type TripType = "rank_hail" | "booked" | "airport_fixed" | "multi_hire";
export type TripStatus = "open" | "closed";
export type TimeClass = "day" | "night" | "holiday";
export type PaymentMethod = "cash" | "card";

export interface Trip {
  id: string;
  tenant_id: string;
  client_uuid: string;
  vehicle_id: string;
  driver_id: string;
  shift_id: string | null;
  tariff_id: string;
  type: TripType;
  status: TripStatus;
  time_class: TimeClass;
  is_peak: boolean;
  maxi: boolean;
  start_at: string;
  end_at: string | null;
  start_lat: number;
  start_lng: number;
  end_lat: number | null;
  end_lng: number | null;
  distance_m: number;
  moving_s: number;
  waiting_s: number;
  flag_fall: string;
  dist_amount: string;
  wait_amount: string;
  peak_amount: string;
  tolls: string;
  psl: string;
  extras: string;
  subtotal: string;
  surcharge: string;
  total: string;
  gst_component: string;
  payment_method: PaymentMethod;
  gps_trace_ref: string | null;
  max_fare_check_passed: boolean;
  variance_pct: string | null;
  receipt_ref: string | null;
  flagged_for_review: boolean;
  review_notes: string | null;
  created_at: string;
  updated_at: string;
}

export interface TripListResponse {
  items: Trip[];
  total: number;
  skip: number;
  limit: number;
}

export interface TripListFilters {
  status?: TripStatus;
  type?: TripType;
  vehicle_id?: string;
  driver_id?: string;
  flagged_for_review?: boolean;
  skip?: number;
  limit?: number;
}

export interface TripCreateInput {
  client_uuid: string;
  vehicle_id: string;
  driver_id: string;
  shift_id?: string | null;
  tariff_id: string;
  type: TripType;
  start_at?: string | null;
  start_lat: number;
  start_lng: number;
  payment_method?: PaymentMethod;
  time_class?: TimeClass;
  is_peak?: boolean;
  maxi?: boolean;
  tolls?: string | number;
  extras?: string | number;
  gps_trace_ref?: string | null;
}

export interface TripUpdateInput {
  vehicle_id?: string | null;
  driver_id?: string | null;
  shift_id?: string | null;
  tariff_id?: string | null;
  payment_method?: PaymentMethod | null;
  tolls?: string | number | null;
  extras?: string | number | null;
  gps_trace_ref?: string | null;
  receipt_ref?: string | null;
  end_lat?: number | null;
  end_lng?: number | null;
}

export interface TripCloseInput {
  end_at?: string | null;
  end_lat?: number | null;
  end_lng?: number | null;
  payment_method?: PaymentMethod | null;
  surcharge_pct?: string | number | null;
  cleaning_fee?: string | number;
  include_psl?: boolean;
  receipt_ref?: string | null;
}

/** Body for `PATCH /v1/trips/{id}/flag` — Blueprint 5.2.5's dispute-flag
 * button. `flagged=true` (default) requires a non-empty `reason`; the trip
 * must be closed (409 otherwise). Clearing a flag (`flagged=false`) is
 * staff-only server-side and ignores `reason`. */
export interface TripFlagInput {
  flagged?: boolean;
  reason?: string | null;
}

export interface VehicleLite {
  id: string;
  rego: string;
  vehicle_class: string;
  status: string;
}

export interface DriverLite {
  id: string;
  name: string;
  phone: string | null;
  user_status: string;
  on_shift: boolean;
}

export interface TariffLite {
  id: string;
  name: string;
  region: "urban" | "country" | "exempt";
  booked: boolean;
}

const TRIPS_KEY = "trips";

export function useTripsQuery(filters: TripListFilters) {
  return useQuery({
    queryKey: [TRIPS_KEY, filters],
    queryFn: async () => {
      const res = await apiClient.get<TripListResponse>("/v1/trips", { params: filters });
      return res.data;
    },
    placeholderData: (prev) => prev,
  });
}

/** Lookup lists for the create/edit forms and for resolving id -> label in the table. Capped at each endpoint's server-side max. */
export function useVehiclesLookupQuery() {
  return useQuery({
    queryKey: ["trips-vehicles-lookup"],
    queryFn: async () => {
      const res = await apiClient.get<{ items: VehicleLite[] }>("/v1/vehicles", {
        params: { limit: 100 },
      });
      return res.data.items;
    },
    staleTime: 60_000,
  });
}

export function useDriversLookupQuery() {
  return useQuery({
    queryKey: ["trips-drivers-lookup"],
    queryFn: async () => {
      const res = await apiClient.get<{ items: DriverLite[] }>("/v1/drivers", {
        params: { limit: 100 },
      });
      return res.data.items;
    },
    staleTime: 60_000,
  });
}

export function useTariffsLookupQuery() {
  return useQuery({
    queryKey: ["trips-tariffs-lookup"],
    queryFn: async () => {
      const res = await apiClient.get<{ items: TariffLite[] }>("/v1/tariffs", {
        params: { limit: 200 },
      });
      return res.data.items;
    },
    staleTime: 60_000,
  });
}

export function useCreateTripMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: TripCreateInput) => {
      const res = await apiClient.post<Trip>("/v1/trips", input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [TRIPS_KEY] });
    },
  });
}

export function useUpdateTripMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: TripUpdateInput }) => {
      const res = await apiClient.patch<Trip>(`/v1/trips/${id}`, input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [TRIPS_KEY] });
    },
  });
}

export function useDeleteTripMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/v1/trips/${id}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [TRIPS_KEY] });
    },
  });
}

export function useCloseTripMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: TripCloseInput }) => {
      const res = await apiClient.post<Trip>(`/v1/trips/${id}/close`, input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [TRIPS_KEY] });
    },
  });
}

export function useFlagTripMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: TripFlagInput }) => {
      const res = await apiClient.patch<Trip>(`/v1/trips/${id}/flag`, input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [TRIPS_KEY] });
    },
  });
}
