/** Mirrors backend `app/schemas/audit_log.py` + `app/models/audit_log.py`.
 *
 * APPEND-ONLY: there is no update/delete on this domain anywhere in the
 * backend (see `app/api/v1/audit_log.py`'s module docstring) — this page is
 * read-only by construction, there is no edit/delete UI to build here. */
export interface AuditLogEntry {
  id: string;
  tenant_id: string;
  actor_user_id: string | null;
  action: string;
  entity_type: string;
  entity_id: string;
  before_json: Record<string, unknown> | null;
  after_json: Record<string, unknown> | null;
  at: string;
  hash: string;
  previous_hash: string;
}

export interface AuditLogListResponse {
  items: AuditLogEntry[];
  total: number;
  limit: number;
  offset: number;
}

/** Query params accepted by `GET /v1/audit-log` — every one of these (and
 * only these) is a real server-side filter; nothing here is a client-side-only
 * no-op. See `app/api/v1/audit_log.py::list_audit_log_entries`. */
export interface AuditLogListParams {
  limit?: number;
  offset?: number;
  entity_type?: string;
  entity_id?: string;
  actor_user_id?: string;
  action?: string;
  /** Inclusive lower bound on `at`, ISO 8601. */
  at_from?: string;
  /** Inclusive upper bound on `at`, ISO 8601. */
  at_to?: string;
}

/** Response of `GET /v1/audit-log/verify` — owner/admin only. Walks the
 * calling tenant's full hash chain to confirm tamper-evidence linkage is
 * intact end to end. */
export interface AuditLogVerifyResponse {
  valid: boolean;
  broken_at_id: string | null;
  checked: number;
}

/** Lightweight actor lookup option — see `listActorOptions` in `./api.ts`. */
export interface AuditLogActorOption {
  id: string;
  name: string;
  email: string;
}
