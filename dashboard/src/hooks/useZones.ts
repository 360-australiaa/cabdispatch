import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";

/**
 * Data layer for the Zones domain (`/v1/zones`) — named dispatch zones with a
 * driver-facing short `number`, plus the live per-zone demand-stats screen
 * (`GET /v1/zones/stats`) matching a real competitor taxi meter's
 * "Statistics" screen, just viewed from the dispatcher's side.
 *
 * Write endpoints (`POST`/`PUT`/`DELETE /v1/zones/{id}`) are owner/admin
 * gated server-side; read endpoints (`GET /v1/zones`, `GET /v1/zones/stats`)
 * are open to any authenticated tenant user.
 */

export interface Zone {
  id: string;
  tenant_id: string;
  name: string;
  number: string;
  center_lat: number;
  center_lng: number;
  radius_m: number;
  created_at: string;
  updated_at: string;
}

export interface Page<T> {
  items: T[];
  total: number;
  skip: number;
  limit: number;
}

/** Body shape for `POST /v1/zones` and `PUT /v1/zones/{id}` — this domain
 * uses a full-replace PUT for edits, not PATCH. */
export interface ZoneWriteInput {
  name: string;
  number: string;
  center_lat: number;
  center_lng: number;
  radius_m: number;
}

/** One row of `GET /v1/zones/stats` — live per-zone supply/demand snapshot. */
export interface ZoneStats {
  zone_id: string;
  zone_name: string;
  zone_number: string;
  plotted_vehicles: number;
  vacant_vehicles: number;
  busy_vehicles: number;
  jobs_holding: number;
  bookings_last_hour: number;
  street_hails_last_hour: number;
}

const ZONES_KEY = "zones";
const PAGE_LIMIT = 50;

export function useZonesQuery(skip = 0, limit = PAGE_LIMIT) {
  return useQuery({
    queryKey: [ZONES_KEY, "list", skip, limit],
    queryFn: async () => {
      const { data } = await apiClient.get<Page<Zone>>("/v1/zones", { params: { skip, limit } });
      return data;
    },
    placeholderData: (prev) => prev,
  });
}

/** Live per-zone demand stats — auto-refetches every 20s, same
 * live-polling convention as `useOpenFatigueAlerts`/`useComplianceExpiry`. */
export function useZoneStatsQuery() {
  return useQuery({
    queryKey: [ZONES_KEY, "stats"],
    queryFn: async () => {
      const { data } = await apiClient.get<ZoneStats[]>("/v1/zones/stats");
      return data;
    },
    refetchInterval: 20_000,
  });
}

export function useCreateZoneMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: ZoneWriteInput) => {
      const { data } = await apiClient.post<Zone>("/v1/zones", input);
      return data;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: [ZONES_KEY] }),
  });
}

export function useUpdateZoneMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: ZoneWriteInput }) => {
      const { data } = await apiClient.put<Zone>(`/v1/zones/${id}`, input);
      return data;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: [ZONES_KEY] }),
  });
}

export function useDeleteZoneMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/v1/zones/${id}`);
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: [ZONES_KEY] }),
  });
}
