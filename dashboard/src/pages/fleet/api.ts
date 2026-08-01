import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import type {
  Device,
  DeviceFormValues,
  Driver,
  Page,
  PairingCode,
  Vehicle,
  VehicleFormValues,
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
