import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";

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

export type GeofenceKind = "toll" | "region";

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

const GEOFENCES_KEY = "geofences";

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
