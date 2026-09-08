import { Badge, EmptyState, ErrorBanner, Skeleton, Table, Tooltip, type TableColumn } from "@/components/ui";
import { EntityLink } from "@/components/EntityLink";
import { errorMessage, formatDateTimeShort, formatMoney } from "@/lib/format";
import { useTripsQuery, type Trip } from "@/hooks/useTrips";

export interface TripsTabProps {
  vehicleId: string;
}

/** Whether the API told us anything at all about an auto-detected toll or
 * airport pickup fee on this trip -- see `TollsTab` for the real per-road /
 * airport-vs-toll split; this column is just an honest "there's more detail
 * behind this figure" flag at the row level. */
function hasAutoTollSignal(trip: Trip): boolean {
  return Boolean(trip.auto_tolls_applied?.length || (trip.auto_tolled_roads && Object.keys(trip.auto_tolled_roads).length));
}

const COLUMNS: TableColumn<Trip>[] = [
  {
    key: "id",
    header: "Trip",
    render: (t) => <EntityLink kind="trip" id={t.id} name={t.id.slice(0, 8)} />,
  },
  { key: "start_at", header: "Start", sortable: true, sortAccessor: (t) => t.start_at, render: (t) => formatDateTimeShort(t.start_at) },
  {
    key: "end_at",
    header: "End",
    render: (t) => (t.end_at ? formatDateTimeShort(t.end_at) : "—"),
  },
  {
    key: "status",
    header: "Status",
    sortable: true,
    render: (t) => <Badge variant={t.status === "open" ? "accent" : "outline"}>{t.status}</Badge>,
  },
  { key: "payment_method", header: "Payment", render: (t) => t.payment_method },
  { key: "total", header: "Total", className: "text-right", render: (t) => formatMoney(t.total) },
  {
    key: "tolls",
    header: "Tolls & fees",
    className: "text-right",
    render: (t) => (
      <span className="inline-flex items-center gap-1.5 justify-end">
        {formatMoney(t.tolls)}
        {hasAutoTollSignal(t) && (
          <Tooltip content="Includes an auto-detected toll road/zone or airport pickup fee -- see the Tolls tab for the road-level split.">
            <Badge variant="outline" className="text-[10px]">
              auto
            </Badge>
          </Tooltip>
        )}
      </span>
    ),
  },
];

/**
 * Plan §5.2 -- this vehicle's trips, server-filtered by `vehicle_id`, with a
 * combined "Tolls & fees" column (see this module's own doc comment on why
 * a per-row airport-vs-toll split isn't shown here -- `TollsTab` does that
 * aggregation instead, where it can actually be attributed).
 */
export function TripsTab({ vehicleId }: TripsTabProps) {
  const tripsQuery = useTripsQuery({ vehicle_id: vehicleId, limit: 100 });

  if (tripsQuery.isLoading) {
    return (
      <div className="flex flex-col gap-2">
        <Skeleton className="h-8 w-full" />
        <Skeleton className="h-40 w-full" />
      </div>
    );
  }

  if (tripsQuery.isError) {
    return <ErrorBanner message={`Failed to load trips for this vehicle: ${errorMessage(tripsQuery.error)}`} />;
  }

  const trips = tripsQuery.data?.items ?? [];

  if (trips.length === 0) {
    return <EmptyState title="No trips yet" description="No trips have been recorded for this vehicle." />;
  }

  return (
    <Table columns={COLUMNS} data={trips} rowKey={(t) => t.id} pageSize={10} label="Trips for this vehicle" />
  );
}
