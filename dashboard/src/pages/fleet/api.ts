import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import type {
  ComplianceExpiryItem,
  Device,
  DeviceFormValues,
  Driver,
  FatigueAlert,
  Page,
  PairingCode,
  Vehicle,
  VehicleFormValues,
  VehicleLifetimeTotals,
  VehiclePilotReport,
} from "./types";

export const PAGE_LIMIT = 10;
/** Cap used for the lightweight "all vehicles / all devices" lookups used to
 * cross-reference labels (e.g. a vehicle's linked device android_id). Fine
 * for demo/dev-scale fleets; a tenant with >100 vehicles would need a real
 * search-as-you-type endpoint instead. */
const LOOKUP_LIMIT = 100;

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

function toVehiclePayload(values: VehicleFormValues) {
  return {
    rego: values.rego.trim(),
    vin: values.vin.trim() || null,
    vehicle_class: values.vehicle_class,
    camera_serial: values.camera_serial.trim() || null,
    tracking_device_id: values.tracking_device_id.trim() || null,
    meter_device_id: values.meter_device_id.trim() || null,
    status: values.status,
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
// Devices — full CRUD + remote kiosk-lock / force-update against /v1/fleet/devices
// ---------------------------------------------------------------------------

export interface DeviceFilters {
  android_id?: string;
  kiosk_locked?: boolean;
}

export function useDevices(skip: number, filters: DeviceFilters, limit = PAGE_LIMIT) {
  return useQuery({
    queryKey: ["fleet", "devices", skip, limit, filters],
    queryFn: async () => {
      const { data } = await apiClient.get<Page<Device>>("/v1/fleet/devices", {
        params: { skip, limit, ...filters },
      });
      return data;
    },
    placeholderData: (prev) => prev,
  });
}

/** Unpaginated-ish (first 100) device list, used to label a vehicle's linked device. */
export function useDeviceOptions() {
  return useQuery({
    queryKey: ["fleet", "devices", "options"],
    queryFn: async () => {
      const { data } = await apiClient.get<Page<Device>>("/v1/fleet/devices", {
        params: { skip: 0, limit: LOOKUP_LIMIT },
      });
      return data.items;
    },
  });
}

export function useCreateDevice() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (values: DeviceFormValues) => {
      const { data } = await apiClient.post<Device>("/v1/fleet/devices", {
        android_id: values.android_id.trim(),
        model: values.model.trim() || null,
        app_version: values.app_version.trim() || null,
        vehicle_id: values.vehicle_id || null,
        kiosk_locked: values.kiosk_locked,
      });
      return data;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["fleet", "devices"] }),
  });
}

/** `android_id` is immutable server-side (identifies the physical unit), so the
 * edit form omits it from the PATCH payload even though it's shown read-only. */
export function useUpdateDevice() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({
      id,
      values,
    }: {
      id: string;
      values: Omit<DeviceFormValues, "android_id">;
    }) => {
      const { data } = await apiClient.patch<Device>(`/v1/fleet/devices/${id}`, {
        model: values.model.trim() || null,
        app_version: values.app_version.trim() || null,
        vehicle_id: values.vehicle_id || null,
        kiosk_locked: values.kiosk_locked,
      });
      return data;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["fleet", "devices"] }),
  });
}

export function useDeleteDevice() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/v1/fleet/devices/${id}`);
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["fleet", "devices"] }),
  });
}

export function useKioskLock() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, enabled }: { id: string; enabled: boolean }) => {
      const { data } = await apiClient.post<Device>(`/v1/fleet/devices/${id}/kiosk-lock`, {
        enabled,
      });
      return data;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["fleet", "devices"] }),
  });
}

export function useForceUpdate() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const { data } = await apiClient.post<Device>(`/v1/fleet/devices/${id}/force-update`, {
        enabled: true,
      });
      return data;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["fleet", "devices"] }),
  });
}

export function useLocateDevice() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const { data } = await apiClient.post<Device>(`/v1/fleet/devices/${id}/locate`, {
        enabled: true,
      });
      return data;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["fleet", "devices"] }),
  });
}

export function useRebootDevice() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const { data } = await apiClient.post<Device>(`/v1/fleet/devices/${id}/reboot`, {
        enabled: true,
      });
      return data;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["fleet", "devices"] }),
  });
}

// ---------------------------------------------------------------------------
// Drivers — READ-ONLY rollup from /v1/drivers. The backend exposes no
// create/update/delete for the user domain (see API_SUMMARY.md router map),
// so there is no real endpoint to wire a driver CRUD form to.
// ---------------------------------------------------------------------------

export interface DriverFilters {
  status?: string;
  on_shift?: boolean;
}

export function useDrivers(skip: number, filters: DriverFilters) {
  return useQuery({
    queryKey: ["fleet", "drivers", skip, filters],
    queryFn: async () => {
      const { data } = await apiClient.get<Page<Driver>>("/v1/drivers", {
        params: { skip, limit: PAGE_LIMIT, ...filters },
      });
      return data;
    },
    placeholderData: (prev) => prev,
  });
}

// ---------------------------------------------------------------------------
// Fatigue alerts — READ + acknowledge rollup from /v1/fatigue-alerts. Alerts
// themselves are raised server-side as a side effect of PATCH /v1/trips/{id}/tick;
// there is no create endpoint here.
// ---------------------------------------------------------------------------

/** Small unpaginated-ish pull of open (unacknowledged) fatigue alerts, used to
 * surface a lightweight warning badge on the Fleet & Drivers page. */
export function useOpenFatigueAlerts() {
  return useQuery({
    queryKey: ["fleet", "fatigue-alerts", "open"],
    queryFn: async () => {
      const { data } = await apiClient.get<Page<FatigueAlert>>("/v1/fatigue-alerts", {
        params: { skip: 0, limit: LOOKUP_LIMIT, acknowledged: false },
      });
      return data;
    },
    refetchInterval: 60_000,
  });
}

export function useAcknowledgeFatigueAlert() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const { data } = await apiClient.post<FatigueAlert>(
        `/v1/fatigue-alerts/${id}/acknowledge`,
        { acknowledged: true },
      );
      return data;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["fleet", "fatigue-alerts"] }),
  });
}

// ---------------------------------------------------------------------------
// Compliance expiry — READ-only rollup from /v1/fleet/compliance-expiry
// (driver licence/authority + vehicle registration/insurance). No
// acknowledge/dismiss endpoint exists for these; it's a point-in-time query.
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
// Driver photo -- GET/POST /v1/users/{user_id}/photo. `GET /v1/drivers` (the
// list this page renders) does not itself return `photo_url`, so rather than
// guess at a second field on `Driver`, each avatar independently asks for its
// own photo and falls back to initials on 404 (no photo set) -- see
// `DriverAvatar.tsx`.
// ---------------------------------------------------------------------------

/** Fetches a driver's photo as a blob and exposes it as an object URL for
 * `<img src>`. 404 (no photo uploaded) surfaces as a normal query error --
 * callers should fall back to initials rather than showing an error state. */
export function useDriverPhoto(userId: string) {
  return useQuery({
    queryKey: ["fleet", "drivers", userId, "photo"],
    queryFn: async () => {
      const { data } = await apiClient.get<Blob>(`/v1/users/${userId}/photo`, {
        responseType: "blob",
      });
      return URL.createObjectURL(data);
    },
    retry: false,
    staleTime: 5 * 60 * 1000,
  });
}

/** `POST /v1/users/{user_id}/photo` -- multipart upload, staff-role-gated
 * (owner/admin/dispatcher) server-side. Invalidates the driver's own photo
 * query so the new image is refetched. */
export function useUploadDriverPhoto() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ userId, file }: { userId: string; file: File }) => {
      const formData = new FormData();
      formData.append("file", file);
      const { data } = await apiClient.post<{ id: string; photo_url: string | null }>(
        `/v1/users/${userId}/photo`,
        formData,
        { headers: { "Content-Type": "multipart/form-data" } },
      );
      return data;
    },
    onSuccess: (_data, variables) => {
      qc.invalidateQueries({ queryKey: ["fleet", "drivers", variables.userId, "photo"] });
      qc.invalidateQueries({ queryKey: ["fleet", "drivers"] });
    },
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
