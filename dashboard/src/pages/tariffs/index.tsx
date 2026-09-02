import { useState } from "react";
import { History, MapPinned, Pencil, Plus, Receipt, Trash2 } from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  Modal,
  PageHeader,
  Select,
  Table,
  type TableColumn,
} from "@/components/ui";
import { cn } from "@/lib/utils";
import { useAuth } from "@/lib/auth";
import {
  useDeleteTariffMutation,
  useTariffsQuery,
  type Region,
  type Tariff,
} from "@/hooks/useTariffStudio";
import { ChangeLogModal } from "./ChangeLogModal";
import { TariffFormModal } from "./TariffFormModal";
import { TariffSuggestPanel } from "./TariffSuggestPanel";
import { TollZonesPanel } from "./TollZonesPanel";
import { extractErrorMessage, formatDateTime, formatMoney, REGION_OPTIONS, regionBadgeVariant } from "./format";

const PAGE_SIZE = 15;

const REGION_FILTER_OPTIONS = [{ value: "", label: "All regions" }, ...REGION_OPTIONS];
const BOOKED_FILTER_OPTIONS = [
  { value: "", label: "All tariffs" },
  { value: "true", label: "Booked only" },
  { value: "false", label: "Rank / hail only" },
];

type TariffStudioTab = "tariffs" | "toll-zones";

const TABS: { key: TariffStudioTab; label: string; icon: typeof Receipt }[] = [
  { key: "tariffs", label: "Rate cards", icon: Receipt },
  { key: "toll-zones", label: "Toll Zones", icon: MapPinned },
];

export default function TariffsPage() {
  // Create/edit/delete a Fares-Order-regulated rate card is now owner/admin
  // gated server-side (backend/app/api/v1/tariffs.py) — mirrors the same
  // `canWrite` pattern already used by the sibling Zones page. Change-log
  // (read-only) stays visible to every role.
  const { user } = useAuth();
  const canWrite = user?.role === "owner" || user?.role === "admin";

  const [tab, setTab] = useState<TariffStudioTab>("tariffs");
  const [regionFilter, setRegionFilter] = useState<Region | "">("");
  const [bookedFilter, setBookedFilter] = useState<"" | "true" | "false">("");
  const [page, setPage] = useState(0);

  const [createOpen, setCreateOpen] = useState(false);
  const [editingTariff, setEditingTariff] = useState<Tariff | null>(null);
  const [logTariff, setLogTariff] = useState<Tariff | null>(null);
  const [deletingTariff, setDeletingTariff] = useState<Tariff | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const tariffsQuery = useTariffsQuery({
    region: regionFilter,
    booked: bookedFilter === "" ? "" : bookedFilter === "true",
    skip: page * PAGE_SIZE,
    limit: PAGE_SIZE,
  });
  const deleteMutation = useDeleteTariffMutation();

  const tariffs = tariffsQuery.data?.items ?? [];
  const total = tariffsQuery.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  function resetPageAnd<T>(setter: (v: T) => void) {
    return (v: T) => {
      setPage(0);
      setter(v);
    };
  }

  const columns: TableColumn<Tariff>[] = [
    { key: "name", header: "Name", render: (row) => <span className="font-medium">{row.name}</span> },
    {
      key: "region",
      header: "Region",
      render: (row) => <Badge variant={regionBadgeVariant(row.region)}>{row.region}</Badge>,
    },
    {
      key: "booked",
      header: "Booked",
      render: (row) => (row.booked ? <Badge variant="accent">Booked</Badge> : <Badge variant="outline">Rank / hail</Badge>),
    },
    {
      key: "effective_from",
      header: "Effective from",
      render: (row) => formatDateTime(row.effective_from),
    },
    {
      key: "effective_to",
      header: "Effective to",
      render: (row) => (row.effective_to ? formatDateTime(row.effective_to) : <span className="text-muted-foreground">Open-ended</span>),
    },
    { key: "flag_fall", header: "Flag fall", render: (row) => formatMoney(row.flag_fall) },
    { key: "dist_rate_1", header: "Dist. rate 1", render: (row) => formatMoney(row.dist_rate_1) },
    {
      key: "actions",
      header: "",
      className: "text-right",
      render: (row) => (
        <div className="flex justify-end gap-1">
          <Button variant="ghost" size="icon" title="Change log" onClick={() => setLogTariff(row)}>
            <History className="h-4 w-4" />
          </Button>
          {canWrite && (
            <>
              <Button variant="ghost" size="icon" title="Edit" onClick={() => setEditingTariff(row)}>
                <Pencil className="h-4 w-4" />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                title="Delete"
                onClick={() => {
                  setDeleteError(null);
                  setDeletingTariff(row);
                }}
              >
                <Trash2 className="h-4 w-4 text-destructive" />
              </Button>
            </>
          )}
        </div>
      ),
    },
  ];

  return (
    <div>
      <PageHeader
        title="Tariff Studio"
        description="Effective-dated rate cards and toll zones. Rank/hail urban & country tariffs are validated against the NSW Fares Order reference on save."
        actions={
          tab === "tariffs" && canWrite ? (
            <Button onClick={() => setCreateOpen(true)}>
              <Plus className="h-4 w-4" /> New tariff
            </Button>
          ) : undefined
        }
      />

      <div className="mb-6 flex gap-1 border-b border-border">
        {TABS.map(({ key, label, icon: Icon }) => (
          <button
            key={key}
            type="button"
            onClick={() => setTab(key)}
            className={cn(
              "flex items-center gap-2 border-b-2 px-4 py-2 text-sm font-medium transition-colors",
              tab === key
                ? "border-brand-primary text-brand-primary"
                : "border-transparent text-muted-foreground hover:text-foreground",
            )}
          >
            <Icon className="h-4 w-4" />
            {label}
          </button>
        ))}
      </div>

      {tab === "toll-zones" && <TollZonesPanel />}

      {tab === "tariffs" && (
        <>
      <TariffSuggestPanel />

      <Card className="mb-4">
        <CardContent className="flex flex-wrap items-end gap-3 pt-4">
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Region</label>
            <Select
              className="w-40"
              options={REGION_FILTER_OPTIONS}
              value={regionFilter}
              onChange={(e) => resetPageAnd(setRegionFilter)(e.target.value as Region | "")}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Type</label>
            <Select
              className="w-44"
              options={BOOKED_FILTER_OPTIONS}
              value={bookedFilter}
              onChange={(e) => resetPageAnd(setBookedFilter)(e.target.value as "" | "true" | "false")}
            />
          </div>
          {(regionFilter || bookedFilter) && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                setPage(0);
                setRegionFilter("");
                setBookedFilter("");
              }}
            >
              Clear filters
            </Button>
          )}
        </CardContent>
      </Card>

      {tariffsQuery.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load tariffs. Check the backend connection and try again.
        </p>
      )}

      <Table
        columns={columns}
        data={tariffs}
        rowKey={(row) => row.id}
        isLoading={tariffsQuery.isLoading}
        emptyState="No tariffs match these filters."
      />

      {pageCount > 1 && (
        <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
          <span>
            Page {page + 1} of {pageCount} ({total} tariffs)
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
        </>
      )}

      <TariffFormModal open={createOpen} onClose={() => setCreateOpen(false)} mode="create" />

      <TariffFormModal
        open={editingTariff != null}
        onClose={() => setEditingTariff(null)}
        mode="edit"
        tariff={editingTariff ?? undefined}
      />

      <ChangeLogModal open={logTariff != null} onClose={() => setLogTariff(null)} tariff={logTariff} />

      <Modal
        open={deletingTariff != null}
        onClose={() => {
          setDeletingTariff(null);
          setDeleteError(null);
        }}
        title="Delete tariff?"
        description="This permanently removes the rate card. The change log for it will no longer be reachable through this UI."
        footer={
          <>
            <Button
              variant="outline"
              onClick={() => {
                setDeletingTariff(null);
                setDeleteError(null);
              }}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              disabled={deleteMutation.isPending}
              onClick={async () => {
                if (!deletingTariff) return;
                setDeleteError(null);
                try {
                  await deleteMutation.mutateAsync(deletingTariff.id);
                  setDeletingTariff(null);
                } catch (err) {
                  setDeleteError(extractErrorMessage(err));
                }
              }}
            >
              {deleteMutation.isPending ? "Deleting…" : "Delete"}
            </Button>
          </>
        }
      >
        {deletingTariff && (
          <p className="text-sm text-muted-foreground">
            <span className="font-medium text-foreground">{deletingTariff.name}</span> ({deletingTariff.region}) will
            be permanently removed.
          </p>
        )}
        {deleteError && <p className="mt-2 text-sm text-destructive">{deleteError}</p>}
      </Modal>
    </div>
  );
}
