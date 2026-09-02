import { useMemo, useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { ShieldCheck } from "lucide-react";
import { Badge, Button, Card, CardContent, Input, Modal, PageHeader, Select, Table } from "@/components/ui";
import type { TableColumn } from "@/components/ui/Table";
import { useAuth } from "@/lib/auth";
import { listActorOptions, listAuditLogEntries, verifyAuditLogChain } from "./api";
import { actionBadgeVariant, errorMessage, formatDateTime, shortId } from "./format";
import type { AuditLogEntry, AuditLogVerifyResponse } from "./types";

const PAGE_SIZE = 25;

/** `YYYY-MM-DD` (from an `<input type="date">`) -> ISO instant, inclusive of
 * the whole local day -- same convention `pages/trips/index.tsx` uses for its
 * date-range inputs, just sent to the server here (`at_from`/`at_to` are real
 * `GET /v1/audit-log` query params, not a client-side filter). */
function dayStartIso(day: string): string {
  return new Date(`${day}T00:00:00`).toISOString();
}
function dayEndIso(day: string): string {
  return new Date(`${day}T23:59:59.999`).toISOString();
}

export default function AuditLogPage() {
  // GET /v1/audit-log itself has no role gate (any authenticated tenant user
  // may read the trail) -- only GET /v1/audit-log/verify is owner/admin only
  // server-side (`require_role("owner", "admin")`). So this page stays fully
  // visible to every role; only the "Verify chain" action is gated, same
  // "show it, gate the actions" convention as pages/tariffs/index.tsx's
  // `canWrite`.
  const { user } = useAuth();
  const canVerify = user?.role === "owner" || user?.role === "admin";

  const [page, setPage] = useState(0);
  const [entityType, setEntityType] = useState("");
  const [action, setAction] = useState("");
  const [actorUserId, setActorUserId] = useState("");
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [detailEntry, setDetailEntry] = useState<AuditLogEntry | null>(null);

  const [verifyResult, setVerifyResult] = useState<AuditLogVerifyResponse | null>(null);
  const [verifyError, setVerifyError] = useState<string | null>(null);
  const [verifying, setVerifying] = useState(false);

  const actorOptionsQuery = useQuery({
    queryKey: ["audit-log-actor-options"],
    queryFn: listActorOptions,
    staleTime: 5 * 60_000,
  });

  const actorById = useMemo(() => {
    const map = new Map<string, { name: string; email: string }>();
    for (const actor of actorOptionsQuery.data ?? []) {
      map.set(actor.id, { name: actor.name, email: actor.email });
    }
    return map;
  }, [actorOptionsQuery.data]);

  const actorFilterOptions = useMemo(
    () => [
      { value: "", label: "All actors" },
      ...(actorOptionsQuery.data ?? []).map((a) => ({ value: a.id, label: `${a.name} (${a.email})` })),
    ],
    [actorOptionsQuery.data],
  );

  function resetPageAnd<T>(setter: (v: T) => void) {
    return (v: T) => {
      setPage(0);
      setter(v);
    };
  }

  const entriesQuery = useQuery({
    queryKey: ["audit-log-entries", page, entityType, action, actorUserId, dateFrom, dateTo],
    queryFn: () =>
      listAuditLogEntries({
        limit: PAGE_SIZE,
        offset: page * PAGE_SIZE,
        entity_type: entityType || undefined,
        action: action || undefined,
        actor_user_id: actorUserId || undefined,
        at_from: dateFrom ? dayStartIso(dateFrom) : undefined,
        at_to: dateTo ? dayEndIso(dateTo) : undefined,
      }),
    placeholderData: (prev) => prev,
  });

  const total = entriesQuery.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const hasFilters = Boolean(entityType || action || actorUserId || dateFrom || dateTo);

  function clearFilters() {
    setPage(0);
    setEntityType("");
    setAction("");
    setActorUserId("");
    setDateFrom("");
    setDateTo("");
  }

  async function handleVerify() {
    setVerifying(true);
    setVerifyError(null);
    setVerifyResult(null);
    try {
      const result = await verifyAuditLogChain();
      setVerifyResult(result);
    } catch (err) {
      setVerifyError(errorMessage(err));
    } finally {
      setVerifying(false);
    }
  }

  function actorLabel(row: AuditLogEntry): string {
    if (!row.actor_user_id) return "System";
    const actor = actorById.get(row.actor_user_id);
    return actor ? actor.name : shortId(row.actor_user_id);
  }

  const columns: TableColumn<AuditLogEntry>[] = [
    {
      key: "at",
      header: "When",
      render: (row) => formatDateTime(row.at),
      sortable: true,
      sortAccessor: (row) => row.at,
    },
    {
      key: "actor",
      header: "Actor",
      render: (row) => (
        <span title={row.actor_user_id ?? undefined}>{actorLabel(row)}</span>
      ),
    },
    {
      key: "action",
      header: "Action",
      render: (row) => <Badge variant={actionBadgeVariant(row.action)}>{row.action}</Badge>,
    },
    {
      key: "entity",
      header: "Entity",
      render: (row) => (
        <span>
          <span className="font-medium">{row.entity_type}</span>{" "}
          <span className="font-mono text-xs text-muted-foreground" title={row.entity_id}>
            {shortId(row.entity_id)}
          </span>
        </span>
      ),
    },
    {
      key: "details",
      header: "",
      className: "text-right",
      render: (row) => (
        <Button variant="ghost" size="sm" onClick={() => setDetailEntry(row)}>
          Details
        </Button>
      ),
    },
  ];

  return (
    <div>
      <PageHeader
        title="Audit Log"
        description="Append-only, tamper-evident trail of every write across this tenant. Every entry is hash-chained to the one before it."
        actions={
          canVerify ? (
            <Button variant="outline" onClick={handleVerify} disabled={verifying}>
              <ShieldCheck className="h-4 w-4" /> {verifying ? "Verifying…" : "Verify chain"}
            </Button>
          ) : undefined
        }
      />

      {verifyResult && (
        <p
          className={
            verifyResult.valid
              ? "mb-4 text-sm text-success"
              : "mb-4 text-sm text-destructive"
          }
        >
          {verifyResult.valid
            ? `Chain verified — ${verifyResult.checked} entries checked, no tampering detected.`
            : `Chain broken at entry ${verifyResult.broken_at_id} (${verifyResult.checked} entries checked).`}
        </p>
      )}
      {verifyError && <p className="mb-4 text-sm text-destructive">{verifyError}</p>}

      <Card className="mb-4">
        <CardContent className="flex flex-wrap items-end gap-3 pt-4">
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Entity type</label>
            <Input
              className="w-40"
              placeholder="e.g. tariff"
              value={entityType}
              onChange={(e) => resetPageAnd(setEntityType)(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Action</label>
            <Input
              className="w-40"
              placeholder="e.g. update"
              value={action}
              onChange={(e) => resetPageAnd(setAction)(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Actor</label>
            <Select
              className="w-56"
              options={actorFilterOptions}
              value={actorUserId}
              onChange={(e) => resetPageAnd(setActorUserId)(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">From</label>
            <Input
              type="date"
              className="w-40"
              value={dateFrom}
              onChange={(e) => resetPageAnd(setDateFrom)(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">To</label>
            <Input
              type="date"
              className="w-40"
              value={dateTo}
              onChange={(e) => resetPageAnd(setDateTo)(e.target.value)}
            />
          </div>
          {hasFilters && (
            <Button variant="ghost" size="sm" onClick={clearFilters}>
              Clear filters
            </Button>
          )}
          <span className="mb-2 ml-auto text-xs text-muted-foreground">
            {total} entr{total === 1 ? "y" : "ies"}
          </span>
        </CardContent>
      </Card>

      {entriesQuery.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load the audit log. Check the backend connection and try again.
        </p>
      )}

      <Table
        columns={columns}
        data={entriesQuery.data?.items ?? []}
        rowKey={(row) => row.id}
        isLoading={entriesQuery.isLoading}
        onRowClick={(row) => setDetailEntry(row)}
        emptyState="No audit log entries match these filters."
      />

      {pageCount > 1 && (
        <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
          <span>
            Page {page + 1} of {pageCount}
          </span>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >
              Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={page >= pageCount - 1}
              onClick={() => setPage((p) => Math.min(pageCount - 1, p + 1))}
            >
              Next
            </Button>
          </div>
        </div>
      )}

      <Modal
        open={detailEntry != null}
        onClose={() => setDetailEntry(null)}
        title={detailEntry ? `${detailEntry.entity_type} — ${detailEntry.action}` : undefined}
        description={detailEntry ? formatDateTime(detailEntry.at) : undefined}
        className="max-w-2xl"
      >
        {detailEntry && (
          <div className="flex flex-col gap-3 text-sm">
            <DetailRow label="Actor">{actorLabel(detailEntry)}</DetailRow>
            <DetailRow label="Entity ID">
              <span className="font-mono text-xs">{detailEntry.entity_id}</span>
            </DetailRow>
            <DetailRow label="Hash">
              <span className="break-all font-mono text-xs">{detailEntry.hash}</span>
            </DetailRow>
            <DetailRow label="Previous hash">
              <span className="break-all font-mono text-xs">{detailEntry.previous_hash}</span>
            </DetailRow>
            {detailEntry.before_json && (
              <div>
                <p className="mb-1 text-xs font-medium text-muted-foreground">Before</p>
                <pre className="max-h-48 overflow-auto rounded-md bg-muted p-2 text-xs">
                  {JSON.stringify(detailEntry.before_json, null, 2)}
                </pre>
              </div>
            )}
            {detailEntry.after_json && (
              <div>
                <p className="mb-1 text-xs font-medium text-muted-foreground">After</p>
                <pre className="max-h-48 overflow-auto rounded-md bg-muted p-2 text-xs">
                  {JSON.stringify(detailEntry.after_json, null, 2)}
                </pre>
              </div>
            )}
          </div>
        )}
      </Modal>
    </div>
  );
}

function DetailRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-xs font-medium text-muted-foreground">{label}</span>
      <span>{children}</span>
    </div>
  );
}
