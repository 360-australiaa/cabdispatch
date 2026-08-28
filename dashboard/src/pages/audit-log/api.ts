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
