import { useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { FileText, Pencil, Play, Square, Trash2 } from "lucide-react";
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
import { useAuth } from "@/lib/auth";
import { useDeleteShiftMutation, useDriversLookupQuery, useShiftsQuery, useVehiclesLookupQuery } from "./api";
import { EditShiftModal } from "./EditShiftModal";
import { EndShiftModal } from "./EndShiftModal";
import { formatDateTime, formatMoney, reconciledBadgeVariant, shiftStatusBadgeVariant, shiftStatusLabel } from "./format";
import { ShiftReportModal } from "./ShiftReportModal";
import { StartShiftModal } from "./StartShiftModal";
import type { Shift } from "./types";

const PAGE_SIZE = 15;

/** Roles that can start/end/edit/delete shifts from the dashboard. Everyone
 * else (e.g. `driver`) gets the read/reconcile view only. */
const MANAGE_ROLES = new Set(["owner", "admin", "dispatcher"]);

const RECONCILED_OPTIONS = [
  { value: "", label: "All" },
  { value: "true", label: "Reconciled" },
  { value: "false", label: "Not reconciled" },
];

export default function ShiftsPage() {
  const { user } = useAuth();
  const canManage = !!user && MANAGE_ROLES.has(user.role);

  // Lets a "Shift history" link from the Fleet -> Vehicles page (or any other
  // page) deep-link straight into a pre-filtered view, e.g. /shifts?vehicle_id=<id>.
  // Read once on mount only -- this page's own filter controls own the state
  // from then on, so picking a different vehicle in the dropdown doesn't
  // fight with the URL.
  const [searchParams] = useSearchParams();

  const [page, setPage] = useState(0);
  const [driverFilter, setDriverFilter] = useState("");
  const [vehicleFilter, setVehicleFilter] = useState(() => searchParams.get("vehicle_id") ?? "");
  const [reconciledFilter, setReconciledFilter] = useState("");
  const [activeOnly, setActiveOnly] = useState(false);

  const [startOpen, setStartOpen] = useState(false);
  const [editingShift, setEditingShift] = useState<Shift | null>(null);
  const [endingShift, setEndingShift] = useState<Shift | null>(null);
  const [reportShiftId, setReportShiftId] = useState<string | null>(null);
  const [deletingShift, setDeletingShift] = useState<Shift | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const shiftsQuery = useShiftsQuery({
    limit: PAGE_SIZE,
    offset: page * PAGE_SIZE,
    driver_id: driverFilter || undefined,
    vehicle_id: vehicleFilter || undefined,
    reconciled: reconciledFilter === "" ? undefined : reconciledFilter === "true",
    active_only: activeOnly || undefined,
  });
  const driversQuery = useDriversLookupQuery();
  const vehiclesQuery = useVehiclesLookupQuery();
  const deleteMutation = useDeleteShiftMutation();

  const drivers = driversQuery.data ?? [];
  const vehicles = vehiclesQuery.data ?? [];

  const driverLabelById = useMemo(() => {
    const map = new Map<string, string>();
    for (const d of drivers) map.set(d.id, d.name);
    return map;
  }, [drivers]);

  const vehicleLabelById = useMemo(() => {
    const map = new Map<string, string>();
    for (const v of vehicles) map.set(v.id, v.rego);
    return map;
  }, [vehicles]);

  const driverFilterOptions = [
    { value: "", label: "All drivers" },
    ...drivers.map((d) => ({ value: d.id, label: d.name })),
  ];
  const vehicleFilterOptions = [
    { value: "", label: "All vehicles" },
    ...vehicles.map((v) => ({ value: v.id, label: v.rego })),
  ];

  const total = shiftsQuery.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  function resetPageAnd<T>(setter: (v: T) => void) {
    return (v: T) => {
      setter(v);
      setPage(0);
    };
  }

  const columns: TableColumn<Shift>[] = [
    {
      key: "driver_id",
      header: "Driver",
      render: (row) => driverLabelById.get(row.driver_id) ?? row.driver_id.slice(0, 8),
    },
    {
      key: "vehicle_id",
      header: "Vehicle",
      render: (row) => vehicleLabelById.get(row.vehicle_id) ?? row.vehicle_id.slice(0, 8),
    },
    {
      key: "start_at",
      header: "Started",
      render: (row) => formatDateTime(row.start_at),
      sortable: true,
      sortAccessor: (row) => row.start_at,
    },
    {
      key: "status",
      header: "Status",
      render: (row) => (
        <Badge variant={shiftStatusBadgeVariant(row)}>{shiftStatusLabel(row)}</Badge>
      ),
    },
    { key: "trips_count", header: "Trips" },
    {
      key: "cash_total",
      header: "Cash",
      render: (row) => formatMoney(row.cash_total),
      sortable: true,
      sortAccessor: (row) => Number(row.cash_total),
    },
    {
      key: "card_total",
      header: "Card",
      render: (row) => formatMoney(row.card_total),
      sortable: true,
      sortAccessor: (row) => Number(row.card_total),
    },
    {
      key: "psl_owed",
      header: "PSL owed",
      render: (row) => formatMoney(row.psl_owed),
      sortable: true,
      sortAccessor: (row) => Number(row.psl_owed),
    },
    {
      key: "reconciled",
      header: "Reconciled",
      render: (row) => (
        <Badge variant={reconciledBadgeVariant(row.reconciled)}>
          {row.reconciled ? "Yes" : "No"}
        </Badge>
      ),
    },
    {
      key: "actions",
      header: "",
      className: "text-right",
      render: (row) => (
        <div className="flex justify-end gap-1" onClick={(e) => e.stopPropagation()}>
          <Button
            variant="ghost"
            size="icon"
            title="View report"
            onClick={() => setReportShiftId(row.id)}
          >
            <FileText className="h-4 w-4" />
          </Button>
          {canManage && (
            <>
              {!row.end_at && (
                <Button
                  variant="ghost"
                  size="icon"
                  title="End shift"
                  onClick={() => setEndingShift(row)}
                >
                  <Square className="h-4 w-4" />
                </Button>
              )}
              <Button variant="ghost" size="icon" title="Edit shift" onClick={() => setEditingShift(row)}>
                <Pencil className="h-4 w-4" />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                title="Delete shift"
                onClick={() => setDeletingShift(row)}
              >
                <Trash2 className="h-4 w-4" />
              </Button>
            </>
          )}
        </div>
      ),
    },
  ];

  const filtersActive = driverFilter || vehicleFilter || reconciledFilter || activeOnly;

  return (
    <div>
      <PageHeader
        title="Shifts & Reconciliation"
        description="Driver shifts opened from the driver app, with cash/card/PSL reconciliation. Start or end a shift here only to cover the dashboard-side exception case."
        actions={
          canManage ? (
            <Button onClick={() => setStartOpen(true)}>
              <Play className="h-4 w-4" /> Start shift
            </Button>
          ) : undefined
        }
      />

      <Card className="mb-4">
        <CardContent className="flex flex-wrap items-end gap-3 pt-4">
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Driver</label>
            <Select
              className="w-44"
              options={driverFilterOptions}
              value={driverFilter}
              onChange={(e) => resetPageAnd(setDriverFilter)(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Vehicle</label>
            <Select
              className="w-36"
              options={vehicleFilterOptions}
              value={vehicleFilter}
              onChange={(e) => resetPageAnd(setVehicleFilter)(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Reconciled</label>
            <Select
              className="w-36"
              options={RECONCILED_OPTIONS}
              value={reconciledFilter}
              onChange={(e) => resetPageAnd(setReconciledFilter)(e.target.value)}
            />
          </div>
          <label className="mb-2 flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              className="h-4 w-4 rounded border-border"
              checked={activeOnly}
              onChange={(e) => resetPageAnd(setActiveOnly)(e.target.checked)}
            />
            Active shifts only
          </label>
          {filtersActive && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                setDriverFilter("");
                setVehicleFilter("");
                setReconciledFilter("");
                setActiveOnly(false);
                setPage(0);
              }}
            >
              Clear filters
            </Button>
          )}
          <span className="mb-2 ml-auto text-xs text-muted-foreground">
            {total} shift{total === 1 ? "" : "s"}
          </span>
        </CardContent>
      </Card>

      {shiftsQuery.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load shifts. Check the backend connection and try again.
        </p>
      )}

      <Table
        columns={columns}
        data={shiftsQuery.data?.items ?? []}
        rowKey={(row) => row.id}
        isLoading={shiftsQuery.isLoading}
        onRowClick={(row) => setReportShiftId(row.id)}
        emptyState="No shifts match these filters."
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

      <StartShiftModal open={startOpen} onClose={() => setStartOpen(false)} drivers={drivers} vehicles={vehicles} />

      <EditShiftModal
        shift={editingShift}
        open={editingShift != null}
        onClose={() => setEditingShift(null)}
        drivers={drivers}
        vehicles={vehicles}
      />

      <EndShiftModal shift={endingShift} open={endingShift != null} onClose={() => setEndingShift(null)} />

      <ShiftReportModal
        shiftId={reportShiftId}
        onClose={() => setReportShiftId(null)}
        onEdit={
          canManage
            ? () => {
                const shift = shiftsQuery.data?.items.find((s) => s.id === reportShiftId);
                if (shift) {
                  setEditingShift(shift);
                  setReportShiftId(null);
                }
              }
            : undefined
        }
        driverLabelById={driverLabelById}
        vehicleLabelById={vehicleLabelById}
      />

      <Modal
        open={deletingShift != null}
        onClose={() => {
          setDeletingShift(null);
          setDeleteError(null);
        }}
        title="Delete shift?"
        description="This permanently removes the shift record. This cannot be undone."
        footer={
          <>
            <Button
              variant="outline"
              onClick={() => {
                setDeletingShift(null);
                setDeleteError(null);
              }}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              disabled={deleteMutation.isPending}
              onClick={async () => {
                if (!deletingShift) return;
                setDeleteError(null);
                try {
                  await deleteMutation.mutateAsync(deletingShift.id);
                  setDeletingShift(null);
                } catch {
                  setDeleteError("Could not delete this shift. Refresh and try again.");
                }
              }}
            >
              {deleteMutation.isPending ? "Deleting…" : "Delete"}
            </Button>
          </>
        }
      >
        {deletingShift && (
          <p className="text-sm text-muted-foreground">
            Shift {deletingShift.id.slice(0, 8)} started {formatDateTime(deletingShift.start_at)} will
            be permanently removed.
          </p>
        )}
        {deleteError && <p className="mt-2 text-sm text-destructive">{deleteError}</p>}
      </Modal>
    </div>
  );
}
