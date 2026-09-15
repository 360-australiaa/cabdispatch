import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import { POLL, pollingQueryOptions } from "@/lib/pollIntervals";

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

/** Response of `POST /v1/zones/{id}/plot` and `POST /v1/zones/unplot` --
 * the affected shift's plotting state (`app.schemas.zones.ZonePlotRead`). */
export interface ZonePlotRead {
  shift_id: string;
  driver_id: string;
  vehicle_id: string;
  plotted_zone_id: string | null;
  plotted_at: string | null;
}

/** Who to plot/unplot. The backend's plot/unplot routes are identity-scoped
 * today (`driver_id=user.id` in `app/api/v1/zones.py`) -- a driver plots
 * their own open shift from the tablet. A dispatcher plotting *for* a driver
 * from this desk needs the route to accept the target, which the backend is
 * adding in parallel; this body carries every identifier it could key on
 * (shift, driver, vehicle) so whichever the backend settles on works.
 * Against an older backend the request still goes through FastAPI unread
 * (no body param declared) and 409s "no currently-open shift" for a
 * dispatcher -- surfaced verbatim by the panel rather than swallowed. */
export interface ZonePlotTarget {
  shift_id: string;
  driver_id: string;
  vehicle_id: string;
}

/** The slice of `ShiftRead` this domain reads to know who is plotted where
 * -- `GET /v1/shifts?active_only=true`. Open shifts carry
 * `plotted_zone_id`/`plotted_at` (see `app/models/shift.py`); there is no
 * separate "plotted list" endpoint, the zone stats only carry the count. */
export interface PlottedShift {
  id: string;
  driver_id: string;
  vehicle_id: string;
  start_at: string;
  plotted_zone_id: string | null;
  plotted_at: string | null;
}

const ZONES_KEY = "zones";
const PAGE_LIMIT = 50;
/** `GET /v1/shifts`'s own per-request cap. */
const ACTIVE_SHIFTS_LIMIT = 200;

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
    ...pollingQueryOptions(POLL.SUPPORTING),
  });
}

/** Every open shift in the tenant, for "who is plotted into which zone" and
 * for the plot picker's list of on-shift vehicles. Polls on the same band as
 * the stats it sits next to, so a driver plotting from the tablet shows up
 * here without a reload. Capped at the endpoint's 200 -- a fleet with more
 * than 200 concurrently open shifts is not this dashboard's scale. */
export function useActiveShiftsQuery() {
  return useQuery({
    queryKey: [ZONES_KEY, "active-shifts"],
    queryFn: async () => {
      const { data } = await apiClient.get<{ items: PlottedShift[]; total: number }>("/v1/shifts", {
        params: { active_only: true, limit: ACTIVE_SHIFTS_LIMIT, offset: 0 },
      });
      return data.items;
    },
    ...pollingQueryOptions(POLL.SUPPORTING),
  });
}

/** `POST /v1/zones/{id}/plot` -- see `ZonePlotTarget` for the on-behalf
 * caveat. Invalidates zone stats and the active-shift list so the plotted
 * count and the per-zone roster refresh together. */
export function usePlotVehicleMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ zoneId, target }: { zoneId: string; target: ZonePlotTarget }) => {
      const { data } = await apiClient.post<ZonePlotRead>(`/v1/zones/${zoneId}/plot`, target);
      return data;
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: [ZONES_KEY, "stats"] });
      qc.invalidateQueries({ queryKey: [ZONES_KEY, "active-shifts"] });
      qc.invalidateQueries({ queryKey: ["shifts"] });
    },
  });
}

/** `POST /v1/zones/unplot` -- clears the shift's plotted zone. */
export function useUnplotVehicleMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (target: ZonePlotTarget) => {
      const { data } = await apiClient.post<ZonePlotRead>("/v1/zones/unplot", target);
      return data;
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: [ZONES_KEY, "stats"] });
      qc.invalidateQueries({ queryKey: [ZONES_KEY, "active-shifts"] });
      qc.invalidateQueries({ queryKey: ["shifts"] });
    },
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
