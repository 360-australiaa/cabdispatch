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

/** `GET /v1/tariffs/presets` — static named-preset library (see
 * `app/services/tariff_presets.py`). Every decimal default arrives as a
 * string, matching the wire convention documented above; the field set is
 * exactly `CappedRateField | UncappedRateField` plus `region`/`booked`, so a
 * preset's defaults can be splatted straight into a `TariffFormModal`
 * `FormState`. */
export type PresetKey = "airport_rank" | "special_event" | "shared_ride" | "wheelchair_accessible";

export type TariffPresetDefaults = {
  region: Region;
  booked: boolean;
} & Record<CappedRateField | UncappedRateField, string>;

export interface TariffPreset {
  key: PresetKey;
  label: string;
  description: string;
  defaults: TariffPresetDefaults;
}

/** Body shape for `POST /v1/tariffs/from-preset`. Not used by the Tariff
 * Studio form flow today (the UI only reads `/presets` to prefill the
 * regular create form so the operator keeps full edit + validation control),
 * kept here so the type is available if a one-shot creation path is added
 * later. */
export interface TariffFromPresetInput {
  preset: PresetKey;
  name: string;
  effective_from: string;
  effective_to?: string | null;
  overrides?: Partial<TariffPresetDefaults>;
}

/** `GET /v1/tariffs/suggest` result — see `app.services.tariffs.suggest_tariff`. */
export interface TariffSuggestion {
  tariff_id: string;
  tariff_name: string;
  time_class: "day" | "night";
  reason: string;
}

export const VALID_VEHICLE_CLASSES = ["standard", "premium", "maxi", "wat"] as const;
export type VehicleClass = (typeof VALID_VEHICLE_CLASSES)[number];

export const VEHICLE_CLASS_LABELS: Record<VehicleClass, string> = {
  standard: "Standard",
  premium: "Premium",
  maxi: "Maxi",
  wat: "WAT (wheelchair accessible)",
};

export interface TariffSuggestParams {
  lat: number;
  lng: number;
  vehicleClass?: VehicleClass | "";
  at?: string | null;
}

const TARIFFS_KEY = "tariffs";
const FARES_ORDER_KEY = "fares-order";
const CHANGE_LOG_KEY = "tariff-change-log";
const TARIFF_PRESETS_KEY = "tariff-presets";
const TARIFF_SUGGEST_KEY = "tariff-suggest";

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

/** `GET /v1/tariffs/presets` — static data (no tenant scoping), so it's
 * cheap to keep fresh for a long while. `enabled` lets callers only fire
 * this when the create-tariff picker is actually in view. */
export function useTariffPresetsQuery(enabled = true) {
  return useQuery({
    queryKey: [TARIFF_PRESETS_KEY],
    queryFn: async () => {
      const res = await apiClient.get<TariffPreset[]>("/v1/tariffs/presets");
      return res.data;
    },
    enabled,
    staleTime: 5 * 60_000,
  });
}

/** `GET /v1/tariffs/suggest`. Pass `null` to leave the query idle (no
 * lat/lng chosen yet). A 404 ("nothing resolves for this location/time" per
 * the endpoint's contract) is treated as "no suggestion" rather than a query
 * error, mirroring `useFaresOrderQuery`'s handling of its own 404 case. */
export function useTariffSuggestQuery(params: TariffSuggestParams | null) {
  return useQuery({
    queryKey: [TARIFF_SUGGEST_KEY, params],
    queryFn: async () => {
      if (!params) return null;
      try {
        const res = await apiClient.get<TariffSuggestion>("/v1/tariffs/suggest", {
          params: {
            lat: params.lat,
            lng: params.lng,
            ...(params.vehicleClass ? { vehicle_class: params.vehicleClass } : {}),
            ...(params.at ? { at: params.at } : {}),
          },
        });
        return res.data;
      } catch (err) {
        if (axios.isAxiosError(err) && err.response?.status === 404) {
          return null;
        }
        throw err;
      }
    },
    enabled: params != null,
    retry: false,
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
