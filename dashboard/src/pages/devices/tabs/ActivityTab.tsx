import { Table, type TableColumn } from "@/components/ui";
import type { AuditLogEntry } from "@/pages/audit-log/types";
import { formatDateTime } from "@/pages/fleet/format";

export interface ActivityTabProps {
  entries: AuditLogEntry[];
  isLoading: boolean;
}

const COLUMNS: TableColumn<AuditLogEntry>[] = [
  { key: "at", header: "When", render: (e) => formatDateTime(e.at) },
  { key: "action", header: "Action", render: (e) => e.action },
  { key: "actor_user_id", header: "By", render: (e) => e.actor_user_id ?? "System" },
];

/**
 * `/devices/:id?tab=activity` -- `GET /v1/audit-log?subject_id=` filtered to
 * this device (dashboard command-centre plan §6.5). Whether this tab exists
 * at all is decided by the parent (`DevicePage`) from the same query's
 * error state -- see its own comment for why -- so this component only ever
 * has to render success/loading/empty, never a failure.
 */
export function ActivityTab({ entries, isLoading }: ActivityTabProps) {
  return (
    <Table
      columns={COLUMNS}
      data={entries}
      rowKey={(e) => e.id}
      isLoading={isLoading}
      emptyState="No audit-log entries recorded for this device yet."
    />
  );
}
