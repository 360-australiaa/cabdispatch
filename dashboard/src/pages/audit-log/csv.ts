/**
 * CSV export for the audit log.
 *
 * There is no server-side export endpoint for this domain (unlike
 * `/v1/reports/nsw-ptp-export`) and D11 does not add one -- backend query
 * params belong to D12/B11. Instead this pages through the existing
 * `GET /v1/audit-log` list endpoint at its server-enforced maximum
 * (`limit<=200`, see `backend/app/api/v1/audit_log.py:76`) using the same
 * filters the screen has applied, so the export always matches what the
 * operator is looking at rather than only the current 25-row page.
 *
 * `MAX_EXPORT_ROWS` caps how far it will page before giving up and saying so
 * in the file -- an audit trail with tens of thousands of matching rows
 * should have its filters narrowed, not silently truncated without a word.
 */
import apiClient from "@/lib/apiClient";
import type { AuditLogEntry, AuditLogListResponse } from "./types";
import type { AuditLogFilters } from "./api";
import { classifyEntry, type DiffField } from "./diff";

const FETCH_PAGE_SIZE = 200; // server-enforced max, see module doc above
const MAX_EXPORT_ROWS = 5000;

function csvEscape(value: unknown): string {
  if (value === null || value === undefined) return "";
  const s = typeof value === "object" ? JSON.stringify(value) : String(value);
  if (/[",\n\r]/.test(s)) return `"${s.replace(/"/g, '""')}"`;
  return s;
}

function formatChanges(changes: DiffField[]): string {
  return changes
    .map((c) => `${c.field}: ${csvValue(c.from)} -> ${csvValue(c.to)}`)
    .join("; ");
}

function csvValue(v: unknown): string {
  if (v === null || v === undefined) return String.fromCharCode(8212); // em-dash, same "missing" convention as the screen
  return typeof v === "object" ? JSON.stringify(v) : String(v);
}

function statusLabel(status: ReturnType<typeof classifyEntry>["status"]): string {
  switch (status) {
    case "create":
      return "Created";
    case "delete":
      return "Deleted";
    case "update":
      return "Changed";
    case "before-not-recorded":
      return "Prior state not recorded -- cannot be diffed";
  }
}

/** Fetches every entry matching `filters`, paging at the server's max page
 * size, up to `MAX_EXPORT_ROWS`. Returns the rows plus whether the fetch was
 * cut off before reaching the server's reported total. */
async function fetchAllForExport(
  filters: AuditLogFilters,
): Promise<{ rows: AuditLogEntry[]; total: number; truncated: boolean }> {
  let offset = 0;
  let total = Infinity;
  const rows: AuditLogEntry[] = [];
  while (offset < total && rows.length < MAX_EXPORT_ROWS) {
    const { data } = await apiClient.get<AuditLogListResponse>("/v1/audit-log", {
      params: { limit: FETCH_PAGE_SIZE, offset, ...filters },
    });
    total = data.total;
    rows.push(...data.items);
    if (data.items.length === 0) break; // defensive: avoid an infinite loop on a stalled backend
    offset += FETCH_PAGE_SIZE;
  }
  return { rows, total: Number.isFinite(total) ? total : rows.length, truncated: rows.length < total };
}

function describeFilters(filters: AuditLogFilters): string[] {
  const lines: string[] = [];
  lines.push(`Action filter: ${filters.action ?? "(all)"}`);
  lines.push(`Entity type filter: ${filters.entity_type ?? "(all)"}`);
  lines.push(`Entity id filter: ${filters.entity_id ?? "(all)"}`);
  lines.push(`Date range: ${filters.at_from ?? "(no lower bound)"} to ${filters.at_to ?? "(no upper bound)"}`);
  return lines;
}

const CSV_COLUMNS = [
  "id",
  "at",
  "action",
  "entity_type",
  "entity_id",
  "actor_user_id",
  "diff_status",
  "field_changes",
  "hash",
  "previous_hash",
];

/** Builds the export as a plain string. Exposed separately from the download
 * trigger so it can be unit-tested without touching the DOM/Blob APIs. */
export function buildAuditLogCsv(
  rows: AuditLogEntry[],
  filters: AuditLogFilters,
  meta: { total: number; truncated: boolean; exportedAt: string },
): string {
  const lines: string[] = [];
  // A reader who receives this file second-hand (a regulator, an accountant)
  // has no access to the screen it came from -- the export has to state its
  // own scope, or a filtered subset can be mistaken for the whole record.
  lines.push(`# Cab Dispatch audit-log export`);
  lines.push(`# Exported: ${meta.exportedAt}`);
  for (const l of describeFilters(filters)) lines.push(`# ${l}`);
  lines.push(`# Rows in this file: ${rows.length} of ${meta.total} matching entries`);
  if (meta.truncated) {
    lines.push(
      `# WARNING: this export was truncated at ${rows.length} rows. Narrow the filters (date range or action) and export again to get the rest.`,
    );
  }
  lines.push(CSV_COLUMNS.join(","));
  for (const row of rows) {
    const diff = classifyEntry(row);
    lines.push(
      [
        row.id,
        row.at,
        row.action,
        row.entity_type,
        row.entity_id,
        row.actor_user_id ?? "",
        statusLabel(diff.status),
        formatChanges(diff.changes),
        row.hash,
        row.previous_hash,
      ]
        .map(csvEscape)
        .join(","),
    );
  }
  return lines.join("\r\n") + "\r\n";
}

/** Fetches the full filtered set (up to the cap above) and triggers a
 * browser download -- same throwaway-object-URL pattern as
 * `downloadNswPtpExport` (`hooks/useReports.ts`). */
export async function downloadAuditLogCsv(filters: AuditLogFilters): Promise<{ truncated: boolean }> {
  const { rows, total, truncated } = await fetchAllForExport(filters);
  const csv = buildAuditLogCsv(rows, filters, { total, truncated, exportedAt: new Date().toISOString() });
  const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `audit-log-export_${new Date().toISOString().slice(0, 10)}.csv`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
  return { truncated };
}
