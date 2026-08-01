import { useMemo, useState } from "react";
import { AlertTriangle, Plus, Search } from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  Input,
  Modal,
  PageHeader,
  Select,
  Table,
  type TableColumn,
} from "@/components/ui";
import {
  useDeleteTripMutation,
  useDriversLookupQuery,
  useTariffsLookupQuery,
  useTripsQuery,
  useVehiclesLookupQuery,
  type Trip,
  type TripStatus,
  type TripType,
} from "@/hooks/useTrips";
import { TripDetailModal } from "./TripDetailModal";
import { TripFormModal } from "./TripFormModal";
import {
  formatDateTime,
  formatMoney,
  statusBadgeVariant,
  TRIP_TYPE_LABELS,
  TRIP_TYPE_OPTIONS,
} from "./format";

// The backend has no date-range query param on GET /v1/trips (see
// shared/API_SUMMARY.md) — status/type/vehicle/driver are filtered
// server-side, then a single capped fetch is filtered client-side by date
// and free text, and paginated client-side via <Table pageSize>.
const FETCH_LIMIT = 200;
const PAGE_SIZE = 15;

const STATUS_OPTIONS = [
  { value: "", label: "All statuses" },
  { value: "open", label: "Open" },
  { value: "closed", label: "Closed" },
];

const TYPE_OPTIONS = [{ value: "", label: "All types" }, ...TRIP_TYPE_OPTIONS];

export default function TripsPage() {
  const [statusFilter, setStatusFilter] = useState<TripStatus | "">("");
  const [typeFilter, setTypeFilter] = useState<TripType | "">("");
  const [vehicleFilter, setVehicleFilter] = useState("");
  const [driverFilter, setDriverFilter] = useState("");
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [search, setSearch] = useState("");

  const [createOpen, setCreateOpen] = useState(false);
  const [editingTrip, setEditingTrip] = useState<Trip | null>(null);
  const [detailTrip, setDetailTrip] = useState<Trip | null>(null);
  const [deletingTrip, setDeletingTrip] = useState<Trip | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const tripsQuery = useTripsQuery({
    status: statusFilter || undefined,
    type: typeFilter || undefined,
    vehicle_id: vehicleFilter || undefined,
    driver_id: driverFilter || undefined,
    skip: 0,
    limit: FETCH_LIMIT,
  });
  const vehiclesQuery = useVehiclesLookupQuery();
  const driversQuery = useDriversLookupQuery();
  const tariffsQuery = useTariffsLookupQuery();
  const deleteMutation = useDeleteTripMutation();

  const vehicles = vehiclesQuery.data ?? [];
  const drivers = driversQuery.data ?? [];
  const tariffs = tariffsQuery.data ?? [];

  const vehicleLabelById = useMemo(() => {
    const map = new Map<string, string>();
    for (const v of vehicles) map.set(v.id, v.rego);
    return map;
  }, [vehicles]);

  const driverLabelById = useMemo(() => {
    const map = new Map<string, string>();
    for (const d of drivers) map.set(d.id, d.name);
    return map;
  }, [drivers]);

  const vehicleFilterOptions = [
    { value: "", label: "All vehicles" },
    ...vehicles.map((v) => ({ value: v.id, label: v.rego })),
  ];
  const driverFilterOptions = [
    { value: "", label: "All drivers" },
    ...drivers.map((d) => ({ value: d.id, label: d.name })),
  ];

  const filteredTrips = useMemo(() => {
    const items = tripsQuery.data?.items ?? [];
    const from = dateFrom ? new Date(`${dateFrom}T00:00:00`) : null;
    const to = dateTo ? new Date(`${dateTo}T23:59:59.999`) : null;
    const needle = search.trim().toLowerCase();

    return items.filter((trip) => {
      const startedAt = new Date(trip.start_at);
      if (from && startedAt < from) return false;
      if (to && startedAt > to) return false;

      if (needle) {
        const haystack = [
          trip.id,
          trip.receipt_ref ?? "",
          trip.payment_method,
          vehicleLabelById.get(trip.vehicle_id) ?? "",
          driverLabelById.get(trip.driver_id) ?? "",
        ]
          .join(" ")
          .toLowerCase();
        if (!haystack.includes(needle)) return false;
      }
      return true;
    });
  }, [tripsQuery.data, dateFrom, dateTo, search, vehicleLabelById, driverLabelById]);

  const columns: TableColumn<Trip>[] = [
    {
      key: "start_at",
      header: "Started",
      render: (row) => formatDateTime(row.start_at),
      sortable: true,
      sortAccessor: (row) => row.start_at,
    },
    {
      key: "vehicle_id",
      header: "Vehicle",
      render: (row) => vehicleLabelById.get(row.vehicle_id) ?? row.vehicle_id.slice(0, 8),
    },
    {
      key: "driver_id",
      header: "Driver",
      render: (row) => driverLabelById.get(row.driver_id) ?? row.driver_id.slice(0, 8),
    },
    {
      key: "type",
      header: "Type",
      render: (row) => TRIP_TYPE_LABELS[row.type],
    },
    {
      key: "status",
      header: "Status",
      render: (row) => <Badge variant={statusBadgeVariant(row.status)}>{row.status}</Badge>,
      sortable: true,
      sortAccessor: (row) => row.status,
    },
    {
      key: "total",
      header: "Total",
      render: (row) => (
        <span className="font-medium">{row.status === "closed" ? formatMoney(row.total) : "—"}</span>
      ),
      sortable: true,
      sortAccessor: (row) => Number(row.total),
    },
    {
      key: "fare_check",
      header: "Fare check",
      render: (row) =>
        row.status === "closed" && !row.max_fare_check_passed ? (
          <Badge variant="destructive" className="inline-flex items-center gap-1">
            <AlertTriangle className="h-3 w-3" /> Failed
          </Badge>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
  ];

  // Forces <Table> to remount (resetting its internal page/sort state)
  // whenever a filter changes, so stale pagination can't hide matches.
  const tableKey = [statusFilter, typeFilter, vehicleFilter, driverFilter, dateFrom, dateTo, search].join(
    "|",
  );

  return (
    <div>
      <PageHeader
        title="Trips"
        description="Searchable ledger of meter runs across the fleet. Open a row for the full fare breakdown and max-fare-check result."
        actions={
          <Button onClick={() => setCreateOpen(true)}>
            <Plus className="h-4 w-4" /> New trip
          </Button>
        }
      />

      <Card className="mb-4">
        <CardContent className="flex flex-wrap items-end gap-3 pt-4">
          <div className="flex min-w-[200px] flex-1 flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Search</label>
            <div className="relative">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                className="pl-8"
                placeholder="Trip id, receipt, vehicle, driver…"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
            </div>
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Status</label>
            <Select
              className="w-36"
              options={STATUS_OPTIONS}
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value as TripStatus | "")}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Type</label>
            <Select
              className="w-40"
              options={TYPE_OPTIONS}
              value={typeFilter}
              onChange={(e) => setTypeFilter(e.target.value as TripType | "")}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Vehicle</label>
            <Select
              className="w-36"
              options={vehicleFilterOptions}
              value={vehicleFilter}
              onChange={(e) => setVehicleFilter(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Driver</label>
            <Select
              className="w-36"
              options={driverFilterOptions}
              value={driverFilter}
              onChange={(e) => setDriverFilter(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">From</label>
            <Input
              type="date"
              className="w-40"
              value={dateFrom}
              onChange={(e) => setDateFrom(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">To</label>
            <Input
              type="date"
              className="w-40"
              value={dateTo}
              onChange={(e) => setDateTo(e.target.value)}
            />
          </div>
          {(statusFilter || typeFilter || vehicleFilter || driverFilter || dateFrom || dateTo || search) && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                setStatusFilter("");
                setTypeFilter("");
                setVehicleFilter("");
                setDriverFilter("");
                setDateFrom("");
                setDateTo("");
                setSearch("");
              }}
            >
              Clear filters
            </Button>
          )}
        </CardContent>
      </Card>

      {tripsQuery.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load trips. Check the backend connection and try again.
        </p>
      )}

      {tripsQuery.data && tripsQuery.data.total > FETCH_LIMIT && (
        <p className="mb-3 text-xs text-muted-foreground">
          Showing the latest {FETCH_LIMIT} of {tripsQuery.data.total} matching trips — narrow the
          filters to see more.
        </p>
      )}

      <Table
        key={tableKey}
        columns={columns}
        data={filteredTrips}
        rowKey={(row) => row.id}
        isLoading={tripsQuery.isLoading}
        onRowClick={(row) => setDetailTrip(row)}
        emptyState="No trips match these filters."
        pageSize={PAGE_SIZE}
      />

      <TripFormModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        mode="create"
        vehicles={vehicles}
        drivers={drivers}
        tariffs={tariffs}
      />

      <TripFormModal
        open={editingTrip != null}
        onClose={() => setEditingTrip(null)}
        mode="edit"
        trip={editingTrip ?? undefined}
        vehicles={vehicles}
        drivers={drivers}
        tariffs={tariffs}
      />

      <TripDetailModal
        open={detailTrip != null}
        onClose={() => setDetailTrip(null)}
        trip={detailTrip}
        vehicleLabel={
          detailTrip ? (vehicleLabelById.get(detailTrip.vehicle_id) ?? detailTrip.vehicle_id) : ""
        }
        driverLabel={
          detailTrip ? (driverLabelById.get(detailTrip.driver_id) ?? detailTrip.driver_id) : ""
        }
        onEdit={() => {
          if (detailTrip) {
            setEditingTrip(detailTrip);
            setDetailTrip(null);
          }
        }}
        onDelete={() => {
          if (detailTrip) {
            setDeletingTrip(detailTrip);
            setDetailTrip(null);
          }
        }}
      />

      <Modal
        open={deletingTrip != null}
        onClose={() => {
          setDeletingTrip(null);
          setDeleteError(null);
        }}
        title="Delete trip?"
        description="This only works while the trip is still open — closed trips are financial records and cannot be deleted."
        footer={
          <>
            <Button
              variant="outline"
              onClick={() => {
                setDeletingTrip(null);
                setDeleteError(null);
              }}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              disabled={deleteMutation.isPending}
              onClick={async () => {
                if (!deletingTrip) return;
                setDeleteError(null);
                try {
                  await deleteMutation.mutateAsync(deletingTrip.id);
                  setDeletingTrip(null);
                } catch {
                  setDeleteError(
                    "Could not delete this trip — it may have just been closed. Refresh and try again.",
                  );
                }
              }}
            >
              {deleteMutation.isPending ? "Deleting…" : "Delete"}
            </Button>
          </>
        }
      >
        {deletingTrip && (
          <p className="text-sm text-muted-foreground">
            Trip {deletingTrip.id.slice(0, 8)} started {formatDateTime(deletingTrip.start_at)} will be
            permanently removed.
          </p>
        )}
        {deleteError && <p className="mt-2 text-sm text-destructive">{deleteError}</p>}
      </Modal>
    </div>
  );
}
