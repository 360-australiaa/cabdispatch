import { useState } from "react";
import { EntityLink, type EntityLinkKind } from "@/components/EntityLink";
import { Badge, EmptyState, Pagination, Table, type TableColumn } from "@/components/ui";
import { errorMessage, formatDateTimeSeconds } from "@/lib/format";
import { PAGE_LIMIT, useAuditLogQuery } from "@/pages/audit-log/api";
import type { AuditLogEntry } from "@/pages/audit-log/types";

const LINKABLE_KINDS = new Set<EntityLinkKind>(["driver", "vehicle", "device", "trip", "shift"]);

function subjectCell(entry: AuditLogEntry) {
  if (LINKABLE_KINDS.has(entry.entity_type as EntityLinkKind)) {
    return (
      <EntityLink kind={entry.entity_type as EntityLinkKind} id={entry.entity_id} name={entry.entity_id.slice(0, 8)} />
    );
  }
  return `${entry.entity_type} ${entry.entity_id.slice(0, 8)}`;
}

/**
 * Driver page Activity tab (dashboard command-centre plan §4.8):
 * `GET /v1/audit-log?subject_id=` -- the alias added to `list_audit_log_entries`
 * (`backend/app/api/v1/audit_log.py`) so this page doesn't need to know the
 * table's own `entity_type`/`entity_id` naming. Any authenticated user may
 * read this endpoint (no role gate server-side), so there is no forbidden
 * state to code around here, only "the request failed" / "nothing yet".
 */
export function ActivityTab({ driverId }: { driverId: string }) {
  const [offset, setOffset] = useState(0);
  const query = useAuditLogQuery(offset, { subject_id: driverId });

  const entries = query.data?.items ?? [];
  const total = query.data?.total ?? 0;
  const page = Math.floor(offset / PAGE_LIMIT);
  const pageCount = Math.max(1, Math.ceil(total / PAGE_LIMIT));

  const columns: TableColumn<AuditLogEntry>[] = [
    { key: "at", header: "When", render: (e) => formatDateTimeSeconds(e.at) },
    { key: "action", header: "Action", render: (e) => <Badge variant="outline">{e.action}</Badge> },
    { key: "entity", header: "Entity", render: subjectCell },
    { key: "actor_user_id", header: "Actor", render: (e) => e.actor_user_id?.slice(0, 8) ?? "—" },
  ];

  if (query.isError) {
    return (
      <EmptyState
        title="Couldn't load activity"
        description={`GET /v1/audit-log?subject_id= failed: ${errorMessage(query.error)}`}
      />
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <Table
        columns={columns}
        data={entries}
        rowKey={(e) => e.id}
        isLoading={query.isLoading}
        label="Activity"
        emptyState={<EmptyState title="No activity" description="No audit-log entries reference this driver yet." />}
      />
      {total > PAGE_LIMIT && (
        <Pagination
          page={page}
          pageCount={pageCount}
          onPageChange={(p) => setOffset(p * PAGE_LIMIT)}
          summary={
            <>
              {offset + 1}–{Math.min(total, offset + PAGE_LIMIT)} of {total}
            </>
          }
        />
      )}
    </div>
  );
}
