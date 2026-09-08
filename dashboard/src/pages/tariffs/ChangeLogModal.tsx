import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { History } from "lucide-react";
import { Badge, Modal, Spinner, Table, type TableColumn } from "@/components/ui";
import { useTariffChangeLogQuery, type Tariff } from "@/hooks/useTariffStudio";
// Cross-page reuse of the actor lookup Audit Log already built (same
// "first 100 users" cap as GET /v1/users itself) -- this modal used to show
// `entry.actor_user_id` as a raw truncated UUID even though Audit Log
// already solved resolving this exact field to a human name.
import { listActorOptions } from "@/pages/audit-log/api";
import { formatDateTime, RATE_FIELD_META } from "./format";

export interface ChangeLogModalProps {
  open: boolean;
  onClose: () => void;
  tariff: Tariff | null;
}

const FIELD_LABELS: Record<string, string> = {
  id: "ID",
  tenant_id: "Tenant",
  name: "Name",
  region: "Region",
  effective_from: "Effective from",
  effective_to: "Effective to",
  booked: "Booked",
  ...Object.fromEntries(Object.entries(RATE_FIELD_META).map(([k, v]) => [k, v.label])),
};

function fieldLabel(key: string): string {
  return FIELD_LABELS[key] ?? key;
}

function formatSnapshotValue(key: string, value: unknown): string {
  if (value === null || value === undefined) return "—";
  if (key === "booked") return value ? "Yes" : "No";
  if (key === "effective_from" || key === "effective_to") return formatDateTime(String(value));
  return String(value);
}

/** Diffs an immutable change-log entry's before/after snapshots into a list
 * of {field, from, to}. `before` is null on the entry created alongside a
 * new tariff — every field is shown as "set" in that case. */
function diffEntry(before: Record<string, unknown> | null, after: Record<string, unknown>) {
  const keys = Object.keys(after).filter((k) => k !== "id" && k !== "tenant_id");
  const changed: { field: string; from: unknown; to: unknown }[] = [];
  for (const k of keys) {
    const beforeVal = before ? before[k] : undefined;
    const afterVal = after[k];
    if (before === null || String(beforeVal) !== String(afterVal)) {
      changed.push({ field: k, from: before === null ? undefined : beforeVal, to: afterVal });
    }
  }
  return changed;
}

type FieldChange = { field: string; from: unknown; to: unknown };

/** The "was" column only exists on an update: a create entry has no `before`
 * snapshot, so a "Was" column there would be a column of em-dashes. The
 * column set is therefore built per entry rather than declared once. */
function changeColumns(isCreate: boolean): TableColumn<FieldChange>[] {
  const field: TableColumn<FieldChange> = {
    key: "field",
    header: "Field",
    className: "font-medium text-foreground",
    render: (c) => fieldLabel(c.field),
  };
  const to: TableColumn<FieldChange> = {
    key: "to",
    header: "Now",
    render: (c) => formatSnapshotValue(c.field, c.to),
  };
  if (isCreate) return [field, to];
  return [
    field,
    {
      key: "from",
      header: "Was",
      className: "text-muted-foreground line-through",
      render: (c) => formatSnapshotValue(c.field, c.from),
    },
    to,
  ];
}

/** Read-only, append-only view of a tariff's change log (GET
 * /v1/tariffs/{id}/change-log). No edit/delete affordances anywhere here —
 * the backend exposes none, by design. */
export function ChangeLogModal({ open, onClose, tariff }: ChangeLogModalProps) {
  const logQuery = useTariffChangeLogQuery(tariff?.id ?? null, { limit: 100 });
  const entries = logQuery.data?.items ?? [];

  const actorOptionsQuery = useQuery({
    queryKey: ["audit-log-actor-options"],
    queryFn: listActorOptions,
    staleTime: 5 * 60_000,
  });
  const actorNameById = useMemo(() => {
    const map = new Map<string, string>();
    for (const actor of actorOptionsQuery.data ?? []) map.set(actor.id, actor.name);
    return map;
  }, [actorOptionsQuery.data]);

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={
        <span className="inline-flex items-center gap-2">
          <History className="h-4 w-4" /> Change log — {tariff?.name}
        </span>
      }
      description="Immutable, append-only audit trail. Every create and update produces one entry; nothing here can be edited or removed."
      className="max-w-2xl"
    >
      <div className="max-h-[60vh] overflow-y-auto pr-1">
        {logQuery.isLoading && (
          <p className="py-6 text-center text-sm text-muted-foreground">
            <Spinner size="sm" label="Loading change log" />
          </p>
        )}
        {logQuery.isError && (
          <p className="py-6 text-center text-sm text-destructive">Failed to load the change log.</p>
        )}
        {!logQuery.isLoading && !logQuery.isError && entries.length === 0 && (
          <p className="py-6 text-center text-sm text-muted-foreground">No change log entries yet.</p>
        )}
        <ul className="flex flex-col gap-3">
          {entries.map((entry) => {
            const changes = diffEntry(entry.before_json, entry.after_json);
            const isCreate = entry.before_json === null;
            return (
              <li key={entry.id} className="rounded-md border border-border p-3">
                <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                  <div className="flex items-center gap-2">
                    <Badge variant={isCreate ? "success" : "default"}>{isCreate ? "Created" : "Updated"}</Badge>
                    <span className="text-xs text-muted-foreground">{formatDateTime(entry.at)}</span>
                  </div>
                  <span className="text-xs text-muted-foreground" title={entry.actor_user_id}>
                    Actor: {actorNameById.get(entry.actor_user_id) ?? `${entry.actor_user_id.slice(0, 8)}…`}
                  </span>
                </div>
                <Table
                  columns={changeColumns(isCreate)}
                  data={changes}
                  rowKey={(c) => c.field}
                  label={`Changes in the ${isCreate ? "create" : "update"} entry at ${formatDateTime(entry.at)}`}
                />
              </li>
            );
          })}
        </ul>
      </div>
    </Modal>
  );
}
