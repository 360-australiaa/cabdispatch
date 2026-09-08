import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import { extractErrorMessage } from "@/lib/format";

/**
 * Data layer for Geofences (`/v1/geofences`, see shared/API_SUMMARY.md and
 * shared/openapi.json schemas GeofenceRead / GeofenceCreate / GeofenceUpdate).
 * Circular toll/region zones — toll crossings are auto-detected server-side
 * from trip GPS ticks (`app.services.geofence`), this layer only covers the
 * zone CRUD the dashboard needs (Tariff Studio's Toll Zones tab).
 *
 * `toll_amount` round-trips as a decimal string like the tariff rate fields
 * (see useTariffStudio.ts) — never parsed here, only at render/form edges.
 */

/** `airport`: a terminal taxi-rank pickup zone. Its `toll_amount` is the
 * airport ground-transport access fee (Sydney Airport: $6.43 GST inclusive,
 * passed on to the passenger under the NSW Fares Order), charged ONCE when a
 * hiring starts inside the zone -- never on a drop-off, and never on top of
 * the Sydney Airport fixed fare, which already includes it. Writes are
 * platform-owner gated server-side like `toll`. */
export type GeofenceKind = "toll" | "region" | "airport";

export interface Geofence {
  id: string;
  tenant_id: string | null;
  name: string;
  kind: GeofenceKind;
  center_lat: number;
  center_lng: number;
  radius_m: number;
  toll_amount: string | null;
  created_at: string;
  updated_at: string;
}

export interface Page<T> {
  items: T[];
  total: number;
  skip: number;
  limit: number;
}

export interface GeofenceListFilters {
  kind?: GeofenceKind;
  skip?: number;
  limit?: number;
}

/** Body shape for POST /v1/geofences. */
export interface GeofenceCreateInput {
  name: string;
  kind: GeofenceKind;
  center_lat: number;
  center_lng: number;
  radius_m: number;
  toll_amount?: string | null;
}

/** Body shape for PATCH /v1/geofences/{id} — every field optional. */
export type GeofenceUpdateInput = Partial<GeofenceCreateInput>;

/** One row of `GET /v1/geofences/presets/airport` -- the three Sydney
 * Airport terminal ranks (T1 International, T2 Domestic, T3 Domestic), ready
 * to POST as-is. */
export interface AirportPreset {
  name: string;
  kind: "airport";
  center_lat: number;
  center_lng: number;
  radius_m: number;
  toll_amount: string | number;
}

const GEOFENCES_KEY = "geofences";
const AIRPORT_PRESETS_KEY = "geofence-airport-presets";

export async function fetchAirportPresets(): Promise<AirportPreset[]> {
  const res = await apiClient.get<AirportPreset[]>("/v1/geofences/presets/airport");
  return res.data;
}

export function useAirportPresetsQuery(options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: [AIRPORT_PRESETS_KEY],
    queryFn: fetchAirportPresets,
    enabled: options.enabled ?? true,
    // Static reference data baked into the backend; no reason to refetch it
    // every time the tab mounts.
    staleTime: 60 * 60 * 1000,
  });
}

export function useGeofencesQuery(filters: GeofenceListFilters) {
  return useQuery({
    queryKey: [GEOFENCES_KEY, filters],
    queryFn: async () => {
      const params: Record<string, string | number> = {
        skip: filters.skip ?? 0,
        limit: filters.limit ?? 50,
      };
      if (filters.kind) params.kind = filters.kind;
      const res = await apiClient.get<Page<Geofence>>("/v1/geofences", { params });
      return res.data;
    },
    placeholderData: (prev) => prev,
  });
}

export function useCreateGeofenceMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: GeofenceCreateInput) => {
      const res = await apiClient.post<Geofence>("/v1/geofences", input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [GEOFENCES_KEY] });
    },
  });
}

export function useUpdateGeofenceMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: GeofenceUpdateInput }) => {
      const res = await apiClient.patch<Geofence>(`/v1/geofences/${id}`, input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [GEOFENCES_KEY] });
    },
  });
}

export function useDeleteGeofenceMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/v1/geofences/${id}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [GEOFENCES_KEY] });
    },
  });
}

export interface AddAirportPresetsResult {
  created: Geofence[];
  /** Presets whose POST failed, by name, with the server's message. */
  failed: { name: string; message: string }[];
  /** Presets skipped because a zone of that name already exists. */
  skipped: string[];
}

/**
 * "Add Sydney Airport terminals (T1, T2, T3)": fetch the presets, POST each
 * one that is not already present by name, and report per-zone outcomes so
 * the panel can name exactly which terminal failed. The list is invalidated
 * even on partial failure -- whatever did land should show up.
 */
export function useAddAirportPresetsMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (existingNames: string[]): Promise<AddAirportPresetsResult> => {
      const presets = await fetchAirportPresets();
      const existing = new Set(existingNames.map((n) => n.trim().toLowerCase()));
      const skipped: string[] = [];
      const toCreate = presets.filter((preset) => {
        if (existing.has(preset.name.trim().toLowerCase())) {
          skipped.push(preset.name);
          return false;
        }
        return true;
      });

      const settled = await Promise.allSettled(
        toCreate.map(async (preset) => {
          const body: GeofenceCreateInput = {
            name: preset.name,
            kind: "airport",
            center_lat: preset.center_lat,
            center_lng: preset.center_lng,
            radius_m: preset.radius_m,
            toll_amount: String(preset.toll_amount),
          };
          const res = await apiClient.post<Geofence>("/v1/geofences", body);
          return res.data;
        }),
      );

      const created: Geofence[] = [];
      const failed: AddAirportPresetsResult["failed"] = [];
      settled.forEach((outcome, i) => {
        if (outcome.status === "fulfilled") created.push(outcome.value);
        else failed.push({ name: toCreate[i].name, message: extractErrorMessage(outcome.reason) });
      });
      return { created, failed, skipped };
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: [GEOFENCES_KEY] });
    },
  });
}
