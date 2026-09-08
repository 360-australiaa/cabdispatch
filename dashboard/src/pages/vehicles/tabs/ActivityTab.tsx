import { EmptyState, ErrorBanner, Skeleton, Table, type TableColumn } from "@/components/ui";
import { errorMessage, formatDateTimeShort, truncateId } from "@/lib/format";
import { useAuditLogQuery } from "@/pages/audit-log/api";
import type { AuditLogEntry } from "@/pages/audit-log/types";

export interface ActivityTabProps {
  vehicleId: string;
}

const COLUMNS: TableColumn<AuditLogEntry>[] = [
  { key: "at", header: "When", sortable: true, render: (e) => formatDateTimeShort(e.at) },
  { key: "action", header: "Action" },
  { key: "entity_type", header: "Record" },
  { key: "actor_user_id", header: "By", render: (e) => (e.actor_user_id ? truncateId(e.actor_user_id) : "System") },
];

/**
 * Plan §5.8 -- `GET /v1/audit-log?subject_id=`. The backend's `subject_id`
 * param is an alias for the same `entity_id` filter `AuditLogFilters`
 * already exposes (see `app/api/v1/audit_log.py::list_audit_log_entries`'s
 * own doc comment: both are ANDed against the same column), so this uses
 * `entity_id` directly rather than adding an equivalent second filter name
 * -- there is nothing to fall back from defensively here, this endpoint
 * accepts the filter today.
 *
 * HONEST-EMPTY FINDING: as of this pass, no vehicle create/update/delete/
 * pairing-code route anywhere in the backend calls `record_audit(...)` for
 * `entity_type="vehicle"` (confirmed against `app/api/v1/fleet.py` and
 * `app/services/fleet.py` -- every `record_audit(` call in this codebase
 * uses `entity_type="device"` or another domain's own type, never
 * "vehicle"). This tab will therefore reliably render empty today; it is
 * wired up correctly and will start showing real rows the moment vehicle
 * mutations are audited, so it is kept (not omitted) with an empty state
 * that says so honestly rather than a generic "no activity".
 */
export function ActivityTab({ vehicleId }: ActivityTabProps) {
  const auditQuery = useAuditLogQuery(0, { entity_id: vehicleId });

  if (auditQuery.isLoading) {
    return (
      <div className="flex flex-col gap-2">
        <Skeleton className="h-8 w-full" />
        <Skeleton className="h-32 w-full" />
      </div>
    );
  }

  if (auditQuery.isError) {
    return <ErrorBanner message={`Failed to load the audit log: ${errorMessage(auditQuery.error)}`} />;
  }

  const items = auditQuery.data?.items ?? [];
  if (items.length === 0) {
    return (
      <EmptyState
        title="No audit-log entries"
        description="Nothing recorded here yet -- vehicle edits, status changes and deletions are not currently written to the audit trail by the backend, so this tab will stay empty until that lands."
      />
    );
  }

  return <Table columns={COLUMNS} data={items} rowKey={(e) => e.id} pageSize={10} label="Audit log entries for this vehicle" />;
}
