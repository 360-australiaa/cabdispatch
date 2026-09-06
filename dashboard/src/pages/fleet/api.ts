import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import { errorMessage } from "./format";
import type { Page as LiveOpsPage, VehicleLiveRead } from "@/pages/live-map/types";
import type {
  ComplianceExpiryItem,
  Device,
  DeviceFormValues,
  Driver,
  DriverComplianceRead,
  DriverComplianceUpdate,
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

/** Flags every registered device for update in one action — there is no
 * bulk endpoint on the backend, so this fetches up to `LOOKUP_LIMIT` devices
 * (same "good enough for one fleet" cap `useDeviceOptions` already accepts)
 * and fires the existing one-at-a-time `/force-update` call for each one
 * that isn't already pending. Sequential, not `Promise.all` -- see
 * `deleteAllSequentially`'s doc above for why a burst of concurrent writes
 * against this backend isn't safe to assume will all land; a flagged count
 * that undercounts because a couple of calls got dropped is honest, a bulk
 * action that throws and flags none of them because ONE call dropped is not.
 */
export function useForceUpdateAll() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const { data } = await apiClient.get<Page<Device>>("/v1/fleet/devices", {
        params: { skip: 0, limit: LOOKUP_LIMIT },
      });
      const targets = data.items.filter((d) => !d.force_update_pending);
      let flagged = 0;
      for (const d of targets) {
        try {
          await apiClient.post<Device>(`/v1/fleet/devices/${d.id}/force-update`, { enabled: true });
          flagged += 1;
        } catch {
          // Best-effort -- one device's flakiness shouldn't stop the rest of
          // the fleet from getting flagged. `flagged` undercounting `total`
          // in the result already tells the confirming UI something didn't
          // fully land.
        }
      }
      return { flagged, total: data.items.length };
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

// NOTE: there is deliberately no `useRebootDevice` hook here (a
// `POST /v1/fleet/devices/{id}/reboot` mutation, same shape as
// useLocateDevice above, used to exist and was removed). The backend
// endpoint itself still exists -- it's real groundwork for a future
// device-owner-aware app build, see `Device.reboot_requested`'s own HONESTY
// NOTE in backend/app/models/fleet.py -- but nothing on the Android side
// ever reads that flag today (DeviceCommandHeartbeat.kt says so explicitly),
// so a dashboard control wired to it would silently do nothing while
// looking like a working "Reboot" button and a "Pending" status that never
// resolves. DevicesPanel.tsx's reboot column/button are permanently
// disabled with an explanatory tooltip instead of calling this endpoint --
// see REBOOT_NOT_SUPPORTED_REASON there. Re-add this hook only once a real
// device-owner-aware reboot path exists end to end.

// ---------------------------------------------------------------------------
// Drivers — the list itself is a READ-ONLY live-status rollup from
// /v1/drivers (a User joined with on-shift status; no PATCH/DELETE exists
// for it). Creation goes through the general user endpoint instead: see
// useCreateDriver below, which POSTs /v1/users with role="driver".
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

/** Form input for creating a driver via `POST /v1/users` (`UserCreate`,
 * `role` fixed to `"driver"`). `driver_code` is intentionally omitted — the
 * backend auto-generates one when not supplied. */
export interface CreateDriverInput {
  name: string;
  email: string;
  /** Becomes the login PIN for the meter app. The meter keypad is numeric
   * only, so this should be digits-only in practice, though the backend
   * accepts any string of at least 6 characters. */
  password: string;
  phone?: string;
  driver_licence_no?: string;
  /** `YYYY-MM-DD` (from `<input type="date">`) — matches `UserCreate`'s
   * `driver_license_expiry`/`driver_authority_expiry` (`date | None`).
   * Optional: a driver record must remain saveable without them. */
  driver_license_expiry?: string;
  driver_authority_expiry?: string;
}

/** `POST /v1/users` with `role: "driver"` — the real driver-creation
 * endpoint. Invalidates the `["fleet", "drivers"]` query-key prefix so every
 * open drivers list (any skip/filters combination) refetches, matching the
 * prefix-invalidation pattern used by `useUploadDriverPhoto` below. */
export function useCreateDriver() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (values: CreateDriverInput) => {
      const { data } = await apiClient.post<{ id: string }>("/v1/users", {
        name: values.name.trim(),
        email: values.email.trim(),
        password: values.password,
        role: "driver",
        phone: values.phone?.trim() || null,
        driver_licence_no: values.driver_licence_no?.trim() || null,
        driver_license_expiry: values.driver_license_expiry || null,
        driver_authority_expiry: values.driver_authority_expiry || null,
      });
      return data;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["fleet", "drivers"] }),
  });
}

// ---------------------------------------------------------------------------
// Driver compliance dates -- GET/PATCH /v1/users/{id}. The `/v1/drivers` list
// above is a read-only rollup (DriverLiveRead) that doesn't carry
// driver_license_expiry/driver_authority_expiry, so editing these two dates
// after a driver has already been created goes through the general user
// endpoint directly rather than the drivers rollup.
// ---------------------------------------------------------------------------

export function useDriverCompliance(userId: string | null) {
  return useQuery({
    queryKey: ["fleet", "drivers", userId, "compliance"],
    queryFn: async () => {
      const { data } = await apiClient.get<DriverComplianceRead>(`/v1/users/${userId}`);
      return data;
    },
    enabled: Boolean(userId),
  });
}

/** `PATCH /v1/users/{id}` with just the compliance-date fields. Invalidates
 * this driver's own compliance query plus the compliance-expiry rollup (see
 * useComplianceExpiry below) so the Fleet & Drivers banner reflects the new
 * date immediately. */
export function useUpdateDriverCompliance() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, values }: { id: string; values: DriverComplianceUpdate }) => {
      const { data } = await apiClient.patch<DriverComplianceRead>(`/v1/users/${id}`, values);
      return data;
    },
    onSuccess: (_data, variables) => {
      qc.invalidateQueries({ queryKey: ["fleet", "drivers", variables.id, "compliance"] });
      qc.invalidateQueries({ queryKey: ["fleet", "compliance-expiry"] });
    },
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

// ---------------------------------------------------------------------------
// TEMPORARY testing-only bulk wipe (2026-09-07, direct product instruction:
// "make the option in admin panel to delete all drivers, vehicles and
// devices in one go temporary until we finish all testing") — meant to be
// removed once onboarding/pairing testing is done, not a permanent fleet
// feature. No new backend endpoint: loops the existing per-item DELETE
// endpoints (DELETE /v1/fleet/vehicles/{id}, /v1/fleet/devices/{id},
// /v1/users/{id}) the same way useForceUpdateAll loops the per-device
// force-update endpoint above, since none of these rows are FK-constrained
// against each other (see app/models/jobs.py's own "DEVIATION" doc for why
// this codebase already accepts unconstrained string ids between these
// domains) -- deleting in any order is safe.
// ---------------------------------------------------------------------------

export function useDeleteDriver() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/v1/users/${id}`);
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["fleet", "drivers"] }),
  });
}

export interface WipeAllFleetDataResult {
  vehiclesDeleted: number;
  driversDeleted: number;
  devicesDeleted: number;
  /** Rows that failed to delete (id + a short reason), one entry per failed
   * row across all three collections. Empty on a fully clean wipe. See
   * `deleteAllSequentially`'s doc for why this can be non-empty even on a
   * healthy backend. */
  failures: { kind: "vehicle" | "driver" | "device"; id: string; reason: string }[];
}

/**
 * Deletes every item one at a time (NOT `Promise.all`) and keeps going past
 * individual failures, rather than aborting the whole batch on the first one.
 *
 * This replaced an all-at-once `Promise.all` version (2026-09-06) that a real
 * bulk wipe against production reliably broke: firing every DELETE in a
 * collection simultaneously produced a mix of HTTP 503s and bare axios
 * "Network Error"s (no response at all) -- the single backend process
 * (`entrypoint.sh` runs one uvicorn worker, no reverse proxy in front of it,
 * see docker-compose.yml) plus its Postgres connection pool visibly couldn't
 * absorb a burst of a dozen-plus concurrent write requests while the page's
 * own background polling (compliance-expiry, live vehicle options) was also
 * in flight. `Promise.all` then meant that ONE dropped connection killed the
 * entire wipe with zero rows actually confirmed deleted. One-at-a-time keeps
 * the backend's concurrent load at 1 regardless of fleet size, and catching
 * each row's own error means a single flaky row (or a genuinely undeletable
 * one) no longer blocks every row after it.
 */
async function deleteAllSequentially<T>(
  items: T[],
  kind: WipeAllFleetDataResult["failures"][number]["kind"],
  idOf: (item: T) => string,
  del: (id: string) => Promise<unknown>,
): Promise<{ deleted: number; failures: WipeAllFleetDataResult["failures"] }> {
  let deleted = 0;
  const failures: WipeAllFleetDataResult["failures"] = [];
  for (const item of items) {
    const id = idOf(item);
    try {
      await del(id);
      deleted += 1;
    } catch (err) {
      failures.push({ kind, id, reason: errorMessage(err) });
    }
  }
  return { deleted, failures };
}

export function useWipeAllFleetData() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (): Promise<WipeAllFleetDataResult> => {
      const [vehiclesPage, driversPage, devicesPage] = await Promise.all([
        apiClient.get<Page<Vehicle>>("/v1/fleet/vehicles", { params: { skip: 0, limit: LOOKUP_LIMIT } }),
        apiClient.get<Page<Driver>>("/v1/drivers", { params: { skip: 0, limit: LOOKUP_LIMIT } }),
        apiClient.get<Page<Device>>("/v1/fleet/devices", { params: { skip: 0, limit: LOOKUP_LIMIT } }),
      ]);
      // Devices first (they reference a vehicle), then vehicles, then drivers --
      // purely for a sane order to read in logs if one step fails partway
      // through; no FK actually requires this order (see module note above).
      // Each collection is deleted one row at a time -- see
      // deleteAllSequentially's doc for why this replaced a Promise.all.
      const devicesResult = await deleteAllSequentially(
        devicesPage.data.items,
        "device",
        (d) => d.id,
        (id) => apiClient.delete(`/v1/fleet/devices/${id}`),
      );
      const vehiclesResult = await deleteAllSequentially(
        vehiclesPage.data.items,
        "vehicle",
        (v) => v.id,
        (id) => apiClient.delete(`/v1/fleet/vehicles/${id}`),
      );
      const driversResult = await deleteAllSequentially(
        driversPage.data.items,
        "driver",
        (d) => d.id,
        (id) => apiClient.delete(`/v1/users/${id}`),
      );
      return {
        vehiclesDeleted: vehiclesResult.deleted,
        driversDeleted: driversResult.deleted,
        devicesDeleted: devicesResult.deleted,
        failures: [...devicesResult.failures, ...vehiclesResult.failures, ...driversResult.failures],
      };
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["fleet", "vehicles"] });
      qc.invalidateQueries({ queryKey: ["fleet", "drivers"] });
      qc.invalidateQueries({ queryKey: ["fleet", "devices"] });
    },
  });
}

// ---------------------------------------------------------------------------
// TEMPORARY force-wipe (see backend app.services.fleet_wipe's module
// docstring for full context). `useWipeAllFleetData` above (correctly)
// leaves behind exactly the drivers who still have real PSL/wallet/rating/
// compliance/tariff-change-log evidence on file -- `assert_user_deletable`
// refuses those, and it must keep refusing them for the DEFAULT wipe. This
// is a SEPARATE, owner-only, explicitly-opted-into action for a genuinely
// empty test tenant: it calls a real backend endpoint
// (`POST /v1/fleet/wipe-test-data/force`) that purges those evidence rows
// server-side -- never implemented by looping per-row deletes from the
// client, which would silently defeat assert_user_deletable from outside
// with none of its "here's exactly what's blocking" honesty. It never
// deletes AuditLog rows, under any circumstance -- see
// `audit_log_preserved` below, always true, and the backend module
// docstring for why.
// ---------------------------------------------------------------------------

export interface FleetForceWipeFailure {
  kind: "vehicle" | "device" | "driver";
  id: string;
  reason: string;
}

export interface FleetForceWipeResult {
  vehiclesDeleted: number;
  devicesDeleted: number;
  driversDeleted: number;
  /** Evidence category -> row count PERMANENTLY destroyed by this call. */
  evidenceRowsDestroyed: Record<string, number>;
  /** Always true -- see this section's module note. Kept as a real field
   * (not assumed) so the confirm/result UI reflects exactly what the
   * backend says happened, not what the frontend assumes it did. */
  auditLogPreserved: boolean;
  failures: FleetForceWipeFailure[];
}

export function useForceWipeAllFleetData() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (): Promise<FleetForceWipeResult> => {
      const { data } = await apiClient.post("/v1/fleet/wipe-test-data/force", { confirm: true });
      return {
        vehiclesDeleted: data.vehicles_deleted,
        devicesDeleted: data.devices_deleted,
        driversDeleted: data.drivers_deleted,
        evidenceRowsDestroyed: data.evidence_rows_destroyed,
        auditLogPreserved: data.audit_log_preserved,
        failures: data.failures,
      };
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["fleet", "vehicles"] });
      qc.invalidateQueries({ queryKey: ["fleet", "drivers"] });
      qc.invalidateQueries({ queryKey: ["fleet", "devices"] });
    },
  });
}
