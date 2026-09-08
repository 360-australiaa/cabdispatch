import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import type {
  Driver,
  DriverComplianceRead,
  DriverComplianceUpdate,
  FatigueAlert,
  Page,
} from "../types";
import { LOOKUP_LIMIT, PAGE_LIMIT } from "./constants";
import { POLL, pollingQueryOptions } from "@/lib/pollIntervals";

/**
 * Drivers: the read-only live-status rollup, driver creation, the compliance
 * dates that only the general user endpoint carries, fatigue alerts, and
 * driver photos.
 *
 * Split out of `pages/fleet/api.ts` (804 lines) in Phase 0. Every function
 * here is the original, unchanged; `api/index.ts` re-exports all of them so no
 * call site's import path changed.
 */

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

/** `DELETE /v1/users/{id}`. Also the per-row delete the bulk wipe
 * (api/wipe.ts) loops over -- see that module's own note for why deleting a
 * driver can legitimately be refused by the backend. */
export function useDeleteDriver() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/v1/users/${id}`);
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
 * useComplianceExpiry, api/vehicles.ts) so the Fleet & Drivers banner reflects
 * the new date immediately. */
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
    ...pollingQueryOptions(POLL.AMBIENT),
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
