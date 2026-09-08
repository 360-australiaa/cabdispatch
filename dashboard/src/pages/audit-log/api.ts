import { useMutation, useQuery } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import type { AuditLogListResponse, AuditLogVerifyResponse } from "./types";

/** Matches the limit cap the backend enforces (Query(..., le=200)) on
 * GET /v1/audit-log. */
export const PAGE_LIMIT = 25;

/** Every query param GET /v1/audit-log actually accepts, beyond limit/offset
 * (see app/api/v1/audit_log.py::list_audit_log_entries). */
export interface AuditLogFilters {
  entity_type?: string;
  entity_id?: string;
  /** Alias for `entity_id` a caller can pass without knowing this table's
   * own `entity_type`/`entity_id` naming -- added for the driver/vehicle
   * detail page's Activity tab (dashboard command-centre plan §4/§5). Same
   * `AuditLog.entity_id` column as `entity_id` above; see
   * `backend/app/api/v1/audit_log.py::list_audit_log_entries`. */
  subject_id?: string;
  actor_user_id?: string;
  action?: string;
  at_from?: string;
  at_to?: string;
}

/** Server-paginated list -- offset advances a page at a time via PAGE_LIMIT. */
export function useAuditLogQuery(offset: number, filters: AuditLogFilters) {
  return useQuery({
    queryKey: ["audit-log", "list", offset, filters],
    queryFn: async () => {
      const { data } = await apiClient.get<AuditLogListResponse>("/v1/audit-log", {
        params: { limit: PAGE_LIMIT, offset, ...filters },
      });
      return data;
    },
    placeholderData: (prev) => prev,
  });
}

/** GET /v1/audit-log/verify -- admin/owner-only, enforced server-side.
 * Triggered on demand from a button rather than fetched automatically,
 * since it walks the full hash chain for the tenant on every call. */
export function useVerifyAuditLogChain() {
  return useMutation({
    mutationFn: async () => {
      const { data } = await apiClient.get<AuditLogVerifyResponse>("/v1/audit-log/verify");
      return data;
    },
  });
}

/** One staff member, for resolving an entry's raw `actor_user_id` to a human
 * name. Declared here rather than in `./types` because the audit-log response
 * itself never carries it -- it comes from `GET /v1/users`. */
export interface AuditLogActorOption {
  id: string;
  name: string;
  email: string;
}

/** Lightweight, unpaginated-ish (first 100 — the same server-side cap as
 * `GET /v1/users` itself) staff lookup for resolving `actor_user_id` to a
 * human name/email — the audit-log response only carries the raw id. Same
 * pattern as `pages/duress/api.ts`'s `listVehicleOptionsForDeviceLink`.
 *
 * Restored during the Phase 0 dashboard scaffold: the merge this work is based
 * on dropped this function while keeping its only caller
 * (`pages/tariffs/ChangeLogModal.tsx`), so `tsc -b` failed on the base commit
 * itself. Body is the original, unchanged.
 */
export async function listActorOptions(): Promise<AuditLogActorOption[]> {
  const res = await apiClient.get<{ items: AuditLogActorOption[] }>("/v1/users", {
    params: { skip: 0, limit: 100 },
  });
  return res.data.items;
}
