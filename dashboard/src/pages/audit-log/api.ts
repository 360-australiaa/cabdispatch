import apiClient from "@/lib/apiClient";
import type {
  AuditLogActorOption,
  AuditLogListParams,
  AuditLogListResponse,
  AuditLogVerifyResponse,
} from "./types";

/** `GET /v1/audit-log` — tenant-scoped, any authenticated user (no role gate
 * on read; see `app/api/v1/audit_log.py`). Newest first, server-side. */
export async function listAuditLogEntries(
  params: AuditLogListParams,
): Promise<AuditLogListResponse> {
  const res = await apiClient.get<AuditLogListResponse>("/v1/audit-log", { params });
  return res.data;
}

/** `GET /v1/audit-log/verify` — owner/admin only server-side (`require_role`);
 * a non-owner/admin caller gets a 403 from the backend, which the page
 * surfaces inline rather than hiding the button entirely (see index.tsx). */
export async function verifyAuditLogChain(): Promise<AuditLogVerifyResponse> {
  const res = await apiClient.get<AuditLogVerifyResponse>("/v1/audit-log/verify");
  return res.data;
}

/** Lightweight, unpaginated-ish (first 100 — same server-side cap as
 * `GET /v1/users` itself) staff lookup for the "Actor" filter dropdown and
 * for resolving `actor_user_id` to a human name/email in the table — the
 * audit-log response only carries the raw id. Same pattern as
 * `pages/duress/api.ts`'s `listVehicleOptionsForDeviceLink`. */
export async function listActorOptions(): Promise<AuditLogActorOption[]> {
  const res = await apiClient.get<{ items: AuditLogActorOption[] }>("/v1/users", {
    params: { skip: 0, limit: 100 },
  });
  return res.data.items;
}
