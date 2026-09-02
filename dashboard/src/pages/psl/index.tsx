import { useMemo, useState } from "react";
import { Banknote, ListChecks, Pencil, Plus, Receipt, Trash2 } from "lucide-react";
import { Badge, Button, Card, CardContent, Input, Modal, PageHeader, Select, Table, type TableColumn } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import {
  useDeleteLedgerEntryMutation,
  usePSLLedgerQuery,
  useTopUpsQuery,
  type PSLLedgerEntry,
  type PSLTopUp,
} from "@/hooks/usePSLCentre";
import { useDriversLookupQuery } from "@/hooks/useTrips";

/** Mirrors `psl_ledger.py`'s write-endpoint restriction — create/update/
 * delete ledger entries and top-ups are owner/admin/dispatcher only. Without
 * this the page showed fully-enabled write controls to every role,
 * including `driver`, which then just 403'd. */
const MANAGE_ROLES = new Set(["owner", "admin", "dispatcher"]);
import { LedgerFormModal } from "./LedgerFormModal";
import { TopUpFormModal } from "./TopUpFormModal";
import { RemittanceReport } from "./RemittanceReport";
import { PAYMENT_METHOD_OPTIONS, formatDate, formatDateTime, formatMoney, formatPeriod, subtractMoney } from "./format";

// The backend caps GET /v1/psl/ledger and /v1/psl/topups at limit=200 with no
// total count (see shared/API_SUMMARY.md) — driver/period are filtered
// server-side, the capped page is then paginated client-side via <Table pageSize>.
const FETCH_LIMIT = 200;
const PAGE_SIZE = 15;

const PAYMENT_METHOD_LABELS = new Map(
  PAYMENT_METHOD_OPTIONS.map((opt) => [opt.value, opt.label]),
);

type Tab = "ledger" | "topups" | "report";

export default function PslPage() {
  const { user } = useAuth();
  const canManage = !!user && MANAGE_ROLES.has(user.role);

  const [tab, setTab] = useState<Tab>("ledger");

  const [driverFilter, setDriverFilter] = useState("");
  const [periodFilter, setPeriodFilter] = useState("");

  const [createOpen, setCreateOpen] = useState(false);
  const [topUpOpen, setTopUpOpen] = useState(false);
  const [editingEntry, setEditingEntry] = useState<PSLLedgerEntry | null>(null);
  const [deletingEntry, setDeletingEntry] = useState<PSLLedgerEntry | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const ledgerQuery = usePSLLedgerQuery({
    driver_id: driverFilter || undefined,
    period: periodFilter || undefined,
    skip: 0,
    limit: FETCH_LIMIT,
  });
  const topUpsQuery = useTopUpsQuery({
    driver_id: driverFilter || undefined,
    period: periodFilter || undefined,
    skip: 0,
    limit: FETCH_LIMIT,
  });
  const driversQuery = useDriversLookupQuery();
  const deleteMutation = useDeleteLedgerEntryMutation();

  const drivers = driversQuery.data ?? [];

  const driverLabelById = useMemo(() => {
    const map = new Map<string, string>();
    for (const d of drivers) map.set(d.id, d.name);
    return map;
  }, [drivers]);

  const driverFilterOptions = [
    { value: "", label: "All drivers" },
    ...drivers.map((d) => ({ value: d.id, label: d.name })),
  ];

  const entries = ledgerQuery.data ?? [];
  const topUps = topUpsQuery.data ?? [];

  const columns: TableColumn<PSLLedgerEntry>[] = [
    {
      key: "period",
      header: "Period",
      render: (row) => formatPeriod(row.period),
      sortable: true,
      sortAccessor: (row) => row.period,
    },
    {
      key: "driver_id",
      header: "Driver",
      render: (row) => driverLabelById.get(row.driver_id) ?? row.driver_id.slice(0, 8),
      sortable: true,
      sortAccessor: (row) => driverLabelById.get(row.driver_id) ?? row.driver_id,
    },
    {
      key: "trips_count",
      header: "Trips",
      render: (row) => row.trips_count,
      sortable: true,
      sortAccessor: (row) => row.trips_count,
    },
    {
      key: "amount_owed",
      header: "Owed",
      render: (row) => formatMoney(row.amount_owed),
      sortable: true,
      sortAccessor: (row) => Number(row.amount_owed),
    },
    {
      key: "amount_collected",
      header: "Collected",
      render: (row) => formatMoney(row.amount_collected),
      sortable: true,
      sortAccessor: (row) => Number(row.amount_collected),
    },
    {
      key: "outstanding",
      header: "Outstanding",
      render: (row) => {
        const outstanding = subtractMoney(row.amount_owed, row.amount_collected);
        return (
          <span className={Number(outstanding) > 0 ? "font-medium text-destructive" : ""}>
            {formatMoney(outstanding)}
          </span>
        );
      },
      sortable: true,
      sortAccessor: (row) => Number(subtractMoney(row.amount_owed, row.amount_collected)),
    },
    {
      key: "remitted_at",
      header: "Remitted",
      render: (row) =>
        row.remitted_at ? (
          <Badge variant="success">{formatDate(row.remitted_at)}</Badge>
        ) : (
          <Badge variant="outline">Pending</Badge>
        ),
      sortable: true,
      sortAccessor: (row) => (row.remitted_at ? 1 : 0),
    },
    ...(canManage
      ? [
          {
            key: "actions",
            header: "",
            className: "text-right",
            render: (row: PSLLedgerEntry) => (
              <div className="flex justify-end gap-1">
                <Button
                  variant="ghost"
                  size="icon"
                  title="Edit entry"
                  onClick={(e) => {
                    e.stopPropagation();
                    setEditingEntry(row);
                  }}
                >
                  <Pencil className="h-4 w-4" />
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  title="Delete entry"
                  onClick={(e) => {
                    e.stopPropagation();
                    setDeletingEntry(row);
                  }}
                >
                  <Trash2 className="h-4 w-4 text-destructive" />
                </Button>
              </div>
            ),
          } satisfies TableColumn<PSLLedgerEntry>,
        ]
      : []),
  ];

  const topUpColumns: TableColumn<PSLTopUp>[] = [
    {
      key: "period",
      header: "Period",
      render: (row) => formatPeriod(row.period),
      sortable: true,
      sortAccessor: (row) => row.period,
    },
    {
      key: "driver_id",
      header: "Driver",
      render: (row) => driverLabelById.get(row.driver_id) ?? row.driver_id.slice(0, 8),
      sortable: true,
      sortAccessor: (row) => driverLabelById.get(row.driver_id) ?? row.driver_id,
    },
    {
      key: "amount",
      header: "Amount",
      render: (row) => formatMoney(row.amount),
      sortable: true,
      sortAccessor: (row) => Number(row.amount),
    },
    {
      key: "payment_method",
      header: "Payment method",
      render: (row) => PAYMENT_METHOD_LABELS.get(row.payment_method) ?? row.payment_method,
      sortable: true,
      sortAccessor: (row) => row.payment_method,
    },
    {
      key: "status",
      header: "Status",
      render: (row) => <Badge variant="outline">{row.status}</Badge>,
      sortable: true,
      sortAccessor: (row) => row.status,
    },
    {
      key: "created_at",
      header: "Recorded",
      render: (row) => formatDateTime(row.created_at),
      sortable: true,
      sortAccessor: (row) => row.created_at,
    },
  ];

  const tableKey = [driverFilter, periodFilter].join("|");

  return (
    <div>
      <PageHeader
        title="PSL Centre"
        description="Passenger Service Levy accrual ledger, driver top-ups, and monthly remittance reporting."
        actions={
          canManage ? (
            <>
              <Button variant="outline" onClick={() => setTopUpOpen(true)}>
                <Banknote className="h-4 w-4" /> Record top-up
              </Button>
              <Button onClick={() => setCreateOpen(true)}>
                <Plus className="h-4 w-4" /> New ledger entry
              </Button>
            </>
          ) : undefined
        }
      />

      <div className="mb-4 inline-flex rounded-md border border-border bg-muted p-1">
        <button
          type="button"
          onClick={() => setTab("ledger")}
          className={
            "inline-flex items-center gap-1.5 rounded px-3 py-1.5 text-sm font-medium transition-colors " +
            (tab === "ledger"
              ? "bg-card text-foreground shadow-sm"
              : "text-muted-foreground hover:text-foreground")
          }
        >
          <ListChecks className="h-4 w-4" /> Ledger
        </button>
        <button
          type="button"
          onClick={() => setTab("topups")}
          className={
            "inline-flex items-center gap-1.5 rounded px-3 py-1.5 text-sm font-medium transition-colors " +
            (tab === "topups"
              ? "bg-card text-foreground shadow-sm"
              : "text-muted-foreground hover:text-foreground")
          }
        >
          <Receipt className="h-4 w-4" /> Top-ups
        </button>
        <button
          type="button"
          onClick={() => setTab("report")}
          className={
            "inline-flex items-center gap-1.5 rounded px-3 py-1.5 text-sm font-medium transition-colors " +
            (tab === "report"
              ? "bg-card text-foreground shadow-sm"
              : "text-muted-foreground hover:text-foreground")
          }
        >
          <Banknote className="h-4 w-4" /> Remittance report
        </button>
      </div>

      {(tab === "ledger" || tab === "topups") && (
        <>
          <Card className="mb-4">
            <CardContent className="flex flex-wrap items-end gap-3 pt-4">
              <div className="flex flex-col gap-1.5">
                <label className="text-xs font-medium text-muted-foreground">Driver</label>
                <Select
                  className="w-48"
                  options={driverFilterOptions}
                  value={driverFilter}
                  onChange={(e) => setDriverFilter(e.target.value)}
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <label className="text-xs font-medium text-muted-foreground">Period</label>
                <Input
                  type="month"
                  className="w-40"
                  value={periodFilter}
                  onChange={(e) => setPeriodFilter(e.target.value)}
                />
              </div>
              {(driverFilter || periodFilter) && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    setDriverFilter("");
                    setPeriodFilter("");
                  }}
                >
                  Clear filters
                </Button>
              )}
            </CardContent>
          </Card>

          {tab === "ledger" ? (
            <>
              {ledgerQuery.isError && (
                <p className="mb-3 text-sm text-destructive">
                  Failed to load the PSL ledger. Check the backend connection and try again.
                </p>
              )}

              {entries.length === FETCH_LIMIT && (
                <p className="mb-3 text-xs text-muted-foreground">
                  Showing the first {FETCH_LIMIT} matching entries — narrow the filters to see
                  more.
                </p>
              )}

              <Table
                key={tableKey}
                columns={columns}
                data={entries}
                rowKey={(row) => row.id}
                isLoading={ledgerQuery.isLoading}
                emptyState="No PSL ledger entries match these filters."
                pageSize={PAGE_SIZE}
              />
            </>
          ) : (
            <>
              {topUpsQuery.isError && (
                <p className="mb-3 text-sm text-destructive">
                  Failed to load top-ups. Check the backend connection and try again.
                </p>
              )}

              {topUps.length === FETCH_LIMIT && (
                <p className="mb-3 text-xs text-muted-foreground">
                  Showing the first {FETCH_LIMIT} matching top-ups — narrow the filters to see
                  more.
                </p>
              )}

              <Table
                key={tableKey}
                columns={topUpColumns}
                data={topUps}
                rowKey={(row) => row.id}
                isLoading={topUpsQuery.isLoading}
                emptyState="No top-ups recorded for these filters."
                pageSize={PAGE_SIZE}
              />
            </>
          )}
        </>
      )}

      {tab === "report" && <RemittanceReport />}

      <LedgerFormModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        mode="create"
        drivers={drivers}
      />

      <LedgerFormModal
        open={editingEntry != null}
        onClose={() => setEditingEntry(null)}
        mode="edit"
        entry={editingEntry ?? undefined}
        drivers={drivers}
      />

      <TopUpFormModal
        open={topUpOpen}
        onClose={() => setTopUpOpen(false)}
        drivers={drivers}
        defaultDriverId={driverFilter || undefined}
        defaultPeriod={periodFilter || undefined}
      />

      <Modal
        open={deletingEntry != null}
        onClose={() => {
          setDeletingEntry(null);
          setDeleteError(null);
        }}
        title="Delete ledger entry?"
        description="This permanently removes the PSL accrual record for this driver/period."
        footer={
          <>
            <Button
              variant="outline"
              onClick={() => {
                setDeletingEntry(null);
                setDeleteError(null);
              }}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              disabled={deleteMutation.isPending}
              onClick={async () => {
                if (!deletingEntry) return;
                setDeleteError(null);
                try {
                  await deleteMutation.mutateAsync(deletingEntry.id);
                  setDeletingEntry(null);
                } catch {
                  setDeleteError("Could not delete this entry. Refresh and try again.");
                }
              }}
            >
              {deleteMutation.isPending ? "Deleting…" : "Delete"}
            </Button>
          </>
        }
      >
        {deletingEntry && (
          <p className="text-sm text-muted-foreground">
            {formatPeriod(deletingEntry.period)} entry for{" "}
            {driverLabelById.get(deletingEntry.driver_id) ?? deletingEntry.driver_id.slice(0, 8)} will
            be permanently removed.
          </p>
        )}
        {deleteError && <p className="mt-2 text-sm text-destructive">{deleteError}</p>}
      </Modal>
    </div>
  );
}
