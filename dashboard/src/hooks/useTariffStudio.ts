import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import axios from "axios";
import apiClient from "@/lib/apiClient";

/**
 * Data layer for the Tariff Studio module (`src/pages/tariffs`). Mirrors
 * `shared/openapi.json` schemas TariffRead / TariffCreate / TariffUpdate /
 * TariffChangeLogRead and the `/v1/tariffs`, `/v1/fares-order` endpoints (see
 * shared/API_SUMMARY.md).
 *
 * Money/rate fields are decimal strings straight off the wire — never
 * coerced here. Formatting happens explicitly at render time (see
 * `./format.ts`). The backend accepts number OR decimal-string on
 * create/update; we always send strings from the form layer to avoid float
 * rounding surprises.
 */

export type Region = "urban" | "country" | "exempt";

/** The 9 rate fields the backend runs `validate_against_fares_order` on for
 * non-booked (rank/hail) urban/country tariffs — see
 * `backend/app/services/fare_engine.py::Tariff._RATE_FIELDS`. Every other
 * rate field (thresholds, multipliers, psl, surcharge cap) is unregulated. */
export const FARES_ORDER_CAPPED_FIELDS = [
  "flag_fall",
  "peak_charge",
  "dist_rate_1",
  "dist_rate_2",
  "night_rate_1",
  "night_rate_2",
  "holiday_rate_1",
  "holiday_rate_2",
  "waiting_rate_per_min",
] as const;

export type CappedRateField = (typeof FARES_ORDER_CAPPED_FIELDS)[number];

/** Remaining rate fields on a tariff — not subject to the Fares Order cap. */
export const UNCAPPED_RATE_FIELDS = [
  "dist_km_threshold",
  "speed_threshold_kmh",
  "maxi_multiplier",
  "multi_hire_pct",
  "psl_amount",
  "surcharge_pct_cap",
] as const;

export type UncappedRateField = (typeof UNCAPPED_RATE_FIELDS)[number];

export interface Tariff {
  id: string;
  tenant_id: string | null;
  name: string;
  region: Region;
  effective_from: string;
  effective_to: string | null;
  booked: boolean;
  flag_fall: string;
  peak_charge: string;
  dist_rate_1: string;
  dist_rate_2: string;
  night_rate_1: string;
  night_rate_2: string;
  holiday_rate_1: string;
  holiday_rate_2: string;
  waiting_rate_per_min: string;
  dist_km_threshold: string;
  speed_threshold_kmh: string;
  maxi_multiplier: string;
  multi_hire_pct: string;
  psl_amount: string;
  surcharge_pct_cap: string;
  created_at: string;
  updated_at: string;
}

export interface Page<T> {
  items: T[];
  total: number;
  skip: number;
  limit: number;
}

export interface TariffListFilters {
  region?: Region | "";
  booked?: boolean | "";
  skip?: number;
  limit?: number;
}

/** Body shape for POST /v1/tariffs. Rate fields sent as strings from the
 * form layer regardless of the number|string union the backend accepts. */
export interface TariffCreateInput {
  name: string;
  region: Region;
  effective_from: string;
  effective_to?: string | null;
  booked: boolean;
  flag_fall: string;
  peak_charge: string;
  dist_rate_1: string;
  dist_rate_2: string;
  night_rate_1: string;
  night_rate_2: string;
  holiday_rate_1: string;
  holiday_rate_2: string;
  waiting_rate_per_min: string;
  dist_km_threshold: string;
  speed_threshold_kmh: string;
  maxi_multiplier: string;
  multi_hire_pct: string;
  psl_amount: string;
  surcharge_pct_cap: string;
}

/** Body shape for PATCH /v1/tariffs/{id}. `region` is immutable per the
 * backend's TariffUpdate docstring — create a new tariff instead. */
export type TariffUpdateInput = Partial<Omit<TariffCreateInput, "region">>;

export interface TariffChangeLogEntry {
  id: string;
  tariff_id: string;
  tenant_id: string | null;
  actor_user_id: string;
  before_json: Record<string, unknown> | null;
  after_json: Record<string, unknown>;
  at: string;
}

const TARIFFS_KEY = "tariffs";
const FARES_ORDER_KEY = "fares-order";
const CHANGE_LOG_KEY = "tariff-change-log";

export function useTariffsQuery(filters: TariffListFilters) {
  return useQuery({
    queryKey: [TARIFFS_KEY, filters],
    queryFn: async () => {
      const params: Record<string, string | number | boolean> = {
        skip: filters.skip ?? 0,
        limit: filters.limit ?? 50,
      };
      if (filters.region) params.region = filters.region;
      if (filters.booked !== "" && filters.booked !== undefined) params.booked = filters.booked;
      const res = await apiClient.get<Page<Tariff>>("/v1/tariffs", { params });
      return res.data;
    },
    placeholderData: (prev) => prev,
  });
}

/** GET /v1/fares-order/current?region=. 404s until the platform seeds the
 * global reference rows for that region — treated as "no reference
 * available yet" (null) rather than a query error, since it's an expected
 * steady state per the endpoint's own docstring. Not queried for
 * region="exempt" (no Fares Order jurisdiction there). */
export function useFaresOrderQuery(region: Region | "") {
  return useQuery({
    queryKey: [FARES_ORDER_KEY, region],
    queryFn: async () => {
      try {
        const res = await apiClient.get<Tariff>("/v1/fares-order/current", {
          params: { region },
        });
        return res.data;
      } catch (err) {
        if (axios.isAxiosError(err) && err.response?.status === 404) {
          return null;
        }
        throw err;
      }
    },
    enabled: region === "urban" || region === "country",
    staleTime: 60_000,
    retry: false,
  });
}

export function useTariffChangeLogQuery(tariffId: string | null, opts?: { skip?: number; limit?: number }) {
  return useQuery({
    queryKey: [CHANGE_LOG_KEY, tariffId, opts],
    queryFn: async () => {
      const res = await apiClient.get<Page<TariffChangeLogEntry>>(
        `/v1/tariffs/${tariffId}/change-log`,
        { params: { skip: opts?.skip ?? 0, limit: opts?.limit ?? 100 } },
      );
      return res.data;
    },
    enabled: tariffId != null,
  });
}

export function useCreateTariffMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: TariffCreateInput) => {
      const res = await apiClient.post<Tariff>("/v1/tariffs", input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [TARIFFS_KEY] });
    },
  });
}

export function useUpdateTariffMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: TariffUpdateInput }) => {
      const res = await apiClient.patch<Tariff>(`/v1/tariffs/${id}`, input);
      return res.data;
    },
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: [TARIFFS_KEY] });
      queryClient.invalidateQueries({ queryKey: [CHANGE_LOG_KEY, variables.id] });
    },
  });
}

export function useDeleteTariffMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/v1/tariffs/${id}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [TARIFFS_KEY] });
    },
  });
}
