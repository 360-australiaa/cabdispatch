import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import type { Page as LiveOpsPage, VehicleLiveRead } from "@/pages/live-map/types";
import type {
  ComplianceExpiryItem,
  Page,
  PairingCode,
  Vehicle,
  VehicleFormValues,
  VehicleLifetimeTotals,
  VehiclePilotReport,
} from "../types";
import { LOOKUP_LIMIT, PAGE_LIMIT } from "./constants";

/**
 * Vehicles: full CRUD against /v1/fleet/vehicles, plus the two read-only
 * operations-cycle reports and the evidence-pack download that hang off a
 * single vehicle.
 *
 * Split out of `pages/fleet/api.ts` (804 lines) in Phase 0. Every function
 * here is the original, unchanged; `api/index.ts` re-exports all of them so no
 * call site's import path changed.
 */

// ---------------------------------------------------------------------------
// Vehicles — full CRUD against /v1/fleet/vehicles
// ---------------------------------------------------------------------------

export interface VehicleFilters {
  status?: string;
  vehicle_class?: string;
  rego?: string;
}

export function useVehicles(skip: number, filters: VehicleFilters) {
  return useQuery({
    queryKey: ["fleet", "vehicles", skip, filters],
    queryFn: async () => {
      const { data } = await apiClient.get<Page<Vehicle>>("/v1/fleet/vehicles", {
        params: { skip, limit: PAGE_LIMIT, ...filters },
      });
      return data;
    },
    placeholderData: (prev) => prev,
  });
}

/** Unpaginated-ish (first 100) vehicle list for cross-reference dropdowns/labels. */
export function useVehicleOptions() {
  return useQuery({
    queryKey: ["fleet", "vehicles", "options"],
    queryFn: async () => {
      const { data } = await apiClient.get<Page<Vehicle>>("/v1/fleet/vehicles", {
        params: { skip: 0, limit: LOOKUP_LIMIT },
      });
      return data.items;
    },
  });
}

/** Cross-references each vehicle's CURRENT driver -- "who has this vehicle
 * checked out right now" -- by hitting the Live Ops rollup (`GET /v1/vehicles`,
 * a different domain/endpoint from the CRUD `/v1/fleet/vehicles` list above;
 * see backend/app/services/live_ops.py's module docstring for why these are
 * two separate endpoints). Same "second lightweight lookup query, joined
 * client-side" pattern already used for `deviceByVehicleId` in
 * VehiclesPanel.tsx -- this domain deliberately never denormalizes a
 * "current driver" pointer onto the Vehicle row itself; it's always derived
 * live from the shift domain's open shifts, so it can never go stale. Polls
 * every 30s so a fleet list left open catches shift changeovers reasonably
 * promptly without needing a live socket. */
export function useVehicleLiveOptions() {
  return useQuery({
    queryKey: ["fleet", "vehicles", "live-options"],
    queryFn: async () => {
      const { data } = await apiClient.get<LiveOpsPage<VehicleLiveRead>>("/v1/vehicles", {
        params: { skip: 0, limit: LOOKUP_LIMIT },
      });
      return data.items;
    },
    refetchInterval: 30_000,
  });
}

function toVehiclePayload(values: VehicleFormValues) {
  return {
    rego: values.rego.trim(),
    vin: values.vin.trim() || null,
    make: values.make.trim() || null,
    model: values.model.trim() || null,
    vehicle_class: values.vehicle_class,
    camera_serial: values.camera_serial.trim() || null,
    tracking_device_id: values.tracking_device_id.trim() || null,
    meter_device_id: values.meter_device_id.trim() || null,
    status: values.status,
    registration_expiry: values.registration_expiry || null,
    insurance_expiry: values.insurance_expiry || null,
  };
}

export function useCreateVehicle() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (values: VehicleFormValues) => {
      const { data } = await apiClient.post<Vehicle>(
        "/v1/fleet/vehicles",
        toVehiclePayload(values),
      );
      return data;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["fleet", "vehicles"] }),
  });
}

export function useUpdateVehicle() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, values }: { id: string; values: VehicleFormValues }) => {
      const { data } = await apiClient.patch<Vehicle>(
        `/v1/fleet/vehicles/${id}`,
        toVehiclePayload(values),
      );
      return data;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["fleet", "vehicles"] }),
  });
}

export function useDeleteVehicle() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/v1/fleet/vehicles/${id}`);
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["fleet", "vehicles"] }),
  });
}

export function useGeneratePairingCode() {
  return useMutation({
    mutationFn: async (vehicleId: string) => {
      const { data } = await apiClient.post<PairingCode>(
        `/v1/fleet/vehicles/${vehicleId}/pairing-code`,
      );
      return data;
    },
  });
}

// ---------------------------------------------------------------------------
// Compliance expiry — READ-only rollup from /v1/fleet/compliance-expiry
// (driver licence/authority + vehicle registration/insurance). No
// acknowledge/dismiss endpoint exists for these; it's a point-in-time query.
//
// Lives with vehicles rather than drivers because it is a fleet-wide endpoint
// covering both -- it is a `/v1/fleet/` route, and `useUpdateDriverCompliance`
// (drivers.ts) invalidates it by query key, not by import.
// ---------------------------------------------------------------------------

/** Open (expiring-soon or already-expired) accreditation/registration items,
 * used to surface a warning banner on the Fleet & Drivers page. */
export function useComplianceExpiry(withinDays = 30) {
  return useQuery({
    queryKey: ["fleet", "compliance-expiry", withinDays],
    queryFn: async () => {
      const { data } = await apiClient.get<Page<ComplianceExpiryItem>>(
        "/v1/fleet/compliance-expiry",
        { params: { skip: 0, limit: LOOKUP_LIMIT, within_days: withinDays } },
      );
      return data;
    },
    refetchInterval: 60_000,
  });
}

// ---------------------------------------------------------------------------
// Vehicle operations-cycle reports — lifetime cumulative-totals register and
// date-range pilot-report, both READ-only against /v1/fleet/vehicles/{id}/...
// ---------------------------------------------------------------------------

/** Per-vehicle lifetime cumulative-totals register — a single all-time
 * snapshot computed fresh on every request, no pagination/filters. */
export function useVehicleLifetimeTotals(vehicleId: string | null) {
  return useQuery({
    queryKey: ["fleet", "vehicles", vehicleId, "lifetime-totals"],
    queryFn: async () => {
      const { data } = await apiClient.get<VehicleLifetimeTotals>(
        `/v1/fleet/vehicles/${vehicleId}/lifetime-totals`,
      );
      return data;
    },
    enabled: Boolean(vehicleId),
  });
}

/** Per-vehicle date-range pilot report (fare-accuracy variance, device-uptime
 * estimate, duress counts, flagged-trip count) over [from, to] inclusive. */
export function useVehiclePilotReport(
  vehicleId: string | null,
  range: { from: string; to: string },
) {
  return useQuery({
    queryKey: ["fleet", "vehicles", vehicleId, "pilot-report", range],
    queryFn: async () => {
      const { data } = await apiClient.get<VehiclePilotReport>(
        `/v1/fleet/vehicles/${vehicleId}/pilot-report`,
        { params: { from: range.from, to: range.to } },
      );
      return data;
    },
    enabled: Boolean(vehicleId && range.from && range.to),
    placeholderData: (prev) => prev,
  });
}

// ---------------------------------------------------------------------------
// Vehicle evidence pack -- GET /v1/fleet/vehicles/{vehicle_id}/evidence-pack.
// Streams a zip via the authenticated apiClient (no public download URL) and
// triggers a browser save via a throwaway object URL + anchor click -- same
// pattern as `downloadNswPtpExport` (src/hooks/useReports.ts).
// ---------------------------------------------------------------------------

export async function downloadVehicleEvidencePack(vehicle: Pick<Vehicle, "id" | "rego">): Promise<void> {
  const res = await apiClient.get(`/v1/fleet/vehicles/${vehicle.id}/evidence-pack`, {
    responseType: "blob",
  });
  const blob = new Blob([res.data], { type: "application/zip" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `evidence-pack_${vehicle.rego}_${vehicle.id}.zip`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
