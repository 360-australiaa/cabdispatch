import { useState } from "react";
import { ShieldAlert, ShieldCheck, ShieldQuestion } from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  Input,
  Modal,
  PageHeader,
  Table,
  type TableColumn,
} from "@/components/ui";
import { useAuditLogQuery, useVerifyAuditLogChain, PAGE_LIMIT, type AuditLogFilters } from "./api";
import type { AuditLogEntry } from "./types";

const EM_DASH = String.fromCharCode(8212);

function formatDateTime(value: string | null | undefined): string {
  if (!value) return EM_DASH;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleString("en-AU", {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

function formatJsonValue(value: unknown): string {
  if (value === null || value === undefined) return EM_DASH;
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

/** Diffs a generic before/after snapshot pair into {field, from, to} rows.
 * Unlike a single-entity change log (see tariffs/ChangeLogModal.tsx), this
 * page covers every entity type in the system, so field labels are just the
 * raw JSON keys -- there is no fixed per-domain label map to draw on here. */
function diffJson(
  before: Record<string, unknown> | null,
  after: Record<string, unknown> | null,
): { field: string; from: unknown; to: unknown }[] {
  const keys = new Set<string>([
    ...(before ? Object.keys(before) : []),
    ...(after ? Object.keys(after) : []),
  ]);
  const rows: { field: string; from: unknown; to: unknown }[] = [];
  for (const key of keys) {
    const fromVal = before ? before[key] : undefined;
    const toVal = after ? after[key] : undefined;
    if (String(fromVal) !== String(toVal)) {
      rows.push({ field: key, from: fromVal, to: toVal });
    }
  }
  return rows.sort((a, b) => a.field.localeCompare(b.field));
}

/** Read-only audit trail for the whole tenant (GET /v1/audit-log), plus a
 * prominent tamper-evidence check (GET /v1/audit-log/verify). No
 * create/edit/delete affordances anywhere here -- the backend exposes none
 * for this domain, by design (see app/api/v1/audit_log.py). */
export default function AuditLogPage() {
  const [actionFilter, setActionFilter] = useState("");
  const [entityTypeFilter, setEntityTypeFilter] = useState("");
  const [entityIdFilter, setEntityIdFilter] = useState("");
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [offset, setOffset] = useState(0);
  const [detailEntry, setDetailEntry] = useState<AuditLogEntry | null>(null);

  const filters: AuditLogFilters = {
    action: actionFilter.trim() || undefined,
    entity_type: entityTypeFilter.trim() || undefined,
    entity_id: entityIdFilter.trim() || undefined,
    at_from: dateFrom ? new Date(dateFrom + "T00:00:00").toISOString() : undefined,
    at_to: dateTo ? new Date(dateTo + "T23:59:59.999").toISOString() : undefined,
  };
  const hasFilters =
    Boolean(actionFilter || entityTypeFilter || entityIdFilter || dateFrom || dateTo);

  const auditQuery = useAuditLogQuery(offset, filters);
  const verifyMutation = useVerifyAuditLogChain();

  const items = auditQuery.data?.items ?? [];
  const total = auditQuery.data?.total ?? 0;
  const currentPage = Math.floor(offset / PAGE_LIMIT) + 1;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_LIMIT));

  function resetOffset() {
    setOffset(0);
  }

  function clearFilters() {
    setActionFilter("");
    setEntityTypeFilter("");
    setEntityIdFilter("");
    setDateFrom("");
    setDateTo("");
    setOffset(0);
  }

  const columns: TableColumn<AuditLogEntry>[] = [
    {
      key: "at",
      header: "When",
      render: (row) => formatDateTime(row.at),
    },
    {
      key: "action",
      header: "Action",
      render: (row) => <Badge variant="outline">{row.action}</Badge>,
    },
    {
      key: "entity_type",
      header: "Entity type",
      render: (row) => row.entity_type,
    },
    {
      key: "entity_id",
      header: "Entity id",
      render: (row) => (
        <span className="font-mono text-xs text-muted-foreground">
          {row.entity_id.slice(0, 8)}
        </span>
      ),
    },
    {
      key: "actor_user_id",
      header: "Actor",
      render: (row) => (
        <span className="font-mono text-xs text-muted-foreground">
          {row.actor_user_id ? row.actor_user_id.slice(0, 8) : EM_DASH}
        </span>
      ),
    },
    {
      key: "actions",
      header: "",
      className: "text-right",
      render: (row) => (
        <Button
          variant="ghost"
          size="sm"
          onClick={(e) => {
            e.stopPropagation();
            setDetailEntry(row);
          }}
        >
          View diff
        </Button>
      ),
    },
  ];

  const detailChanges = detailEntry
    ? diffJson(detailEntry.before_json, detailEntry.after_json)
    : [];
  const detailIsCreate = detailEntry != null && detailEntry.before_json == null;
  const detailTitle = detailEntry
    ? detailEntry.action + " " + EM_DASH + " " + detailEntry.entity_type
    : undefined;

  return (
    <div>
      <PageHeader
        title="Audit Log"
        description="Immutable, append-only trail of every recorded change across the tenant. Nothing here can be edited or removed."
      />

      {/* The whole point of this page -- kept large, colored, and above the
       * filters/table so it can never read as a secondary action. */}
      <Card
        className={
          "mb-6 border-2 " +
          (verifyMutation.data?.valid === false
            ? "border-destructive"
            : verifyMutation.data?.valid === true
              ? "border-success"
              : "border-border")
        }
      >
        <CardContent className="flex flex-wrap items-center justify-between gap-4 pt-4">
          <div className="flex items-center gap-3">
            {verifyMutation.data?.valid === false ? (
              <ShieldAlert className="h-8 w-8 shrink-0 text-destructive" />
            ) : verifyMutation.data?.valid === true ? (
              <ShieldCheck className="h-8 w-8 shrink-0 text-success" />
            ) : (
              <ShieldQuestion className="h-8 w-8 shrink-0 text-muted-foreground" />
            )}
            <div>
              <p className="text-sm font-semibold text-foreground">Hash-chain integrity</p>
              {verifyMutation.data ? (
                verifyMutation.data.valid ? (
                  <p className="text-sm text-success">
                    Chain intact -- {verifyMutation.data.checked} entries checked.
                  </p>
                ) : (
                  <p className="text-sm font-medium text-destructive">
                    Tampering detected at entry {verifyMutation.data.broken_at_id ?? "unknown"}.
                  </p>
                )
              ) : verifyMutation.isError ? (
                <p className="text-sm text-destructive">
                  Could not run the check (admin or owner role required). Try again.
                </p>
              ) : (
                <p className="text-sm text-muted-foreground">
                  Recomputes every entry hash value from its stored fields plus the previous
                  entry hash value, confirming no row was edited or removed at the database
                  layer.
                </p>
              )}
            </div>
          </div>
          <Button
            variant={verifyMutation.data?.valid === false ? "destructive" : "primary"}
            disabled={verifyMutation.isPending}
            onClick={() => verifyMutation.mutate()}
          >
            {verifyMutation.isPending ? "Verifying..." : "Verify chain integrity"}
          </Button>
        </CardContent>
      </Card>

      <Card className="mb-4">
        <CardContent className="flex flex-wrap items-end gap-3 pt-4">
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Action</label>
            <Input
              className="w-40"
              placeholder="e.g. update"
              value={actionFilter}
              onChange={(e) => {
                setActionFilter(e.target.value);
                resetOffset();
              }}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Entity type</label>
            <Input
              className="w-40"
              placeholder="e.g. trip"
              value={entityTypeFilter}
              onChange={(e) => {
                setEntityTypeFilter(e.target.value);
                resetOffset();
              }}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Entity id</label>
            <Input
              className="w-44"
              placeholder="exact id"
              value={entityIdFilter}
              onChange={(e) => {
                setEntityIdFilter(e.target.value);
                resetOffset();
              }}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">From</label>
            <Input
              type="date"
              className="w-40"
              value={dateFrom}
              onChange={(e) => {
                setDateFrom(e.target.value);
                resetOffset();
              }}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">To</label>
            <Input
              type="date"
              className="w-40"
              value={dateTo}
              onChange={(e) => {
                setDateTo(e.target.value);
                resetOffset();
              }}
            />
          </div>
          {hasFilters && (
            <Button variant="ghost" size="sm" onClick={clearFilters}>
              Clear filters
            </Button>
          )}
        </CardContent>
      </Card>

      {auditQuery.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load the audit log. Check the backend connection and try again.
        </p>
      )}

      <Table
        columns={columns}
        data={items}
        rowKey={(row) => row.id}
        isLoading={auditQuery.isLoading}
        onRowClick={(row) => setDetailEntry(row)}
        emptyState="No audit log entries match these filters."
      />

      {total > 0 && (
        <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
          <span>
            Page {currentPage} of {pageCount} ({total} total entries)
          </span>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={offset === 0}
              onClick={() => setOffset((o) => Math.max(0, o - PAGE_LIMIT))}
            >
              Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={offset + PAGE_LIMIT >= total}
              onClick={() => setOffset((o) => o + PAGE_LIMIT)}
            >
              Next
            </Button>
          </div>
        </div>
      )}

      <Modal
        open={detailEntry != null}
        onClose={() => setDetailEntry(null)}
        title={detailTitle}
        description="Immutable audit entry. Nothing here can be edited or removed."
        className="max-w-2xl"
      >
        {detailEntry && (
          <div className="flex flex-col gap-4">
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant={detailIsCreate ? "success" : "default"}>
                {detailIsCreate ? "Created" : "Changed"}
              </Badge>
              <span className="text-xs text-muted-foreground">
                {formatDateTime(detailEntry.at)}
              </span>
              <span className="text-xs text-muted-foreground">
                Entity: {detailEntry.entity_type} / {detailEntry.entity_id}
              </span>
              <span className="text-xs text-muted-foreground">
                Actor: {detailEntry.actor_user_id ?? EM_DASH}
              </span>
            </div>

            <div className="max-h-[45vh] overflow-y-auto rounded-md border border-border">
              <table className="w-full text-xs">
                <thead className="bg-muted text-muted-foreground">
                  <tr>
                    <th className="px-3 py-2 text-left font-medium">Field</th>
                    {!detailIsCreate && (
                      <th className="px-3 py-2 text-left font-medium">Before</th>
                    )}
                    <th className="px-3 py-2 text-left font-medium">After</th>
                  </tr>
                </thead>
                <tbody>
                  {detailChanges.length === 0 ? (
                    <tr>
                      <td
                        colSpan={detailIsCreate ? 2 : 3}
                        className="px-3 py-4 text-center text-muted-foreground"
                      >
                        No field-level changes recorded on this entry.
                      </td>
                    </tr>
                  ) : (
                    detailChanges.map(({ field, from, to }) => (
                      <tr key={field} className="border-t border-border/60">
                        <td className="px-3 py-1.5 font-medium text-foreground">{field}</td>
                        {!detailIsCreate && (
                          <td className="px-3 py-1.5 text-muted-foreground line-through">
                            {formatJsonValue(from)}
                          </td>
                        )}
                        <td className="px-3 py-1.5 text-foreground">{formatJsonValue(to)}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>

            <div className="flex flex-col gap-1 text-xs text-muted-foreground">
              <span className="font-mono break-all">hash: {detailEntry.hash}</span>
              <span className="font-mono break-all">previous_hash: {detailEntry.previous_hash}</span>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
