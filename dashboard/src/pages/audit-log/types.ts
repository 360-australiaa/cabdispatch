/**
 * Types mirroring the backend `/v1/audit-log` schemas -- see
 * app/schemas/audit_log.py (AuditLogRead / AuditLogListResponse /
 * AuditLogVerifyResponse) for the source of truth.
 *
 * APPEND-ONLY BY DESIGN: no update / create-adjacent form-values type here --
 * the backend exposes no PATCH, PUT, or DELETE for this domain (see the
 * module docstring in app/api/v1/audit_log.py), and this dashboard page is
 * read-only.
 */

export interface AuditLogEntry {
  id: string;
  tenant_id: string;
  /** Null is possible in principle (system-attributed entries), though the
   * create endpoint always attributes to the authenticated caller today. */
  actor_user_id: string | null;
  action: string;
  entity_type: string;
  entity_id: string;
  before_json: Record<string, unknown> | null;
  after_json: Record<string, unknown> | null;
  at: string;
  /** This entry hash-chain link value. */
  hash: string;
  /** The preceding entry hash value -- the tamper-evidence chain that
   * "Verify chain integrity" on this page checks end to end. */
  previous_hash: string;
}

export interface AuditLogListResponse {
  items: AuditLogEntry[];
  total: number;
  limit: number;
  offset: number;
}

/** GET /v1/audit-log/verify -- see app.services.audit_log.verify_chain. */
export interface AuditLogVerifyResponse {
  valid: boolean;
  broken_at_id: string | null;
  checked: number;
}
