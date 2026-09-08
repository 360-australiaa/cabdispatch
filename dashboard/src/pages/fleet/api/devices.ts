import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import type { Device, DeviceFormValues, DeviceRotateSecretResponse, Page } from "../types";
import { LOOKUP_LIMIT, PAGE_LIMIT } from "./constants";
import { POLL, pollingQueryOptions, whileActive } from "@/lib/pollIntervals";

/**
 * Devices: full CRUD plus the remote kiosk-lock / force-update / restart /
 * locate commands against /v1/fleet/devices.
 *
 * Split out of `pages/fleet/api.ts` (804 lines) in Phase 0. Every function
 * here is the original, unchanged; `api/index.ts` re-exports all of them so no
 * call site's import path changed.
 *
 * `useDeviceDetailQuery`, `useRotateDeviceSecret` and `useSetDeviceRevoked`
 * below are new (dashboard command-centre plan, §6 device page) -- added
 * here rather than in `pages/devices/` so the device page's mutations live
 * next to, and invalidate the same query keys as, every other Device
 * mutation in this file.
 */

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
    // Poll only while a locate is outstanding. There is no push channel for a
    // device's answer, so without this the "Waiting..." badge sat there until the
    // operator refreshed by hand -- they pressed Locate, the tablet answered
    // within a minute, and the page never said so. Idle fleets still make no
    // requests, which is why this is conditional rather than a flat interval.
    ...pollingQueryOptions(
      whileActive(
        POLL.PENDING_ACTION,
        (query: { state: { data?: { items?: Device[] } } }) =>
          !!query.state.data?.items?.some((d) => d.locate_requested || d.reboot_requested),
      ),
    ),
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

/**
 * One device, by id -- `GET /v1/fleet/devices/{id}` (a real single-item
 * endpoint that already existed but had no dashboard caller; the device
 * list/CRUD pages all fetched the whole page and found their row in it).
 * Backs `/devices/:deviceId` (dashboard command-centre plan §6). Polls only
 * while a command this device is waiting on is outstanding -- same
 * conditional-poll reasoning as `useDevices` above, scoped to one row
 * instead of the whole list.
 */
export function useDeviceDetailQuery(deviceId: string | null) {
  return useQuery({
    queryKey: ["fleet", "devices", "detail", deviceId],
    queryFn: async () => {
      const { data } = await apiClient.get<Device>(`/v1/fleet/devices/${deviceId}`);
      return data;
    },
    enabled: deviceId != null,
    ...pollingQueryOptions(
      whileActive(
        POLL.PENDING_ACTION,
        (query: { state: { data?: Device } }) =>
          !!query.state.data && (query.state.data.locate_requested || query.state.data.reboot_requested),
      ),
    ),
  });
}

/**
 * Admin-only. Mints a fresh device secret and returns it in plaintext exactly
 * once (`POST /v1/fleet/devices/{id}/rotate-secret`) -- the endpoint has
 * existed since the device domain landed but nothing in the dashboard ever
 * called it (found while building the device page). NOT a re-pair: the
 * device's `vehicle_id`/`paired_at` are untouched, this only invalidates the
 * credential currently on the tablet. The caller must show `device_secret`
 * to the operator once and never log or persist it.
 */
export function useRotateDeviceSecret() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const { data } = await apiClient.post<DeviceRotateSecretResponse>(
        `/v1/fleet/devices/${id}/rotate-secret`,
      );
      return data;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["fleet", "devices"] }),
  });
}

/**
 * Admin-only. Sets/clears `Device.revoked` (`PATCH /v1/fleet/devices/{id}`)
 * -- the backend's real, reversible "retire this tablet" flag (see
 * `DeviceUpdate.revoked`'s own doc: a revoked device's heartbeat 404s, and
 * re-pairing with a fresh code clears the flag). Deliberately separate from
 * `useDeleteDevice`: that is a permanent, irreversible unregister (`DELETE`,
 * "This can't be undone" in `DevicesPanel`'s own confirm copy) and the two
 * must not be presented as the same action -- see the device page's own
 * Revoke/Delete actions for which is which.
 */
export function useSetDeviceRevoked() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, revoked }: { id: string; revoked: boolean }) => {
      const { data } = await apiClient.patch<Device>(`/v1/fleet/devices/${id}`, { revoked });
      return data;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["fleet", "devices"] }),
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
 * `deleteAllSequentially`'s doc (api/wipe.ts) for why a burst of concurrent
 * writes against this backend isn't safe to assume will all land; a flagged
 * count that undercounts because a couple of calls got dropped is honest, a
 * bulk action that throws and flags none of them because ONE call dropped is
 * not.
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

/**
 * Queues a restart of the meter APP on a tablet — not an OS reboot.
 *
 * Rebooting Android needs Device-Owner provisioning this fleet does not have. Restarting the
 * meter's own process is both possible and what an operator pressing this actually wants ("the
 * meter is stuck, restart it"), and the tablet acknowledges once it has, which clears the flag.
 * The endpoint keeps its historical `/reboot` path.
 */
export function useRestartApp() {
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
