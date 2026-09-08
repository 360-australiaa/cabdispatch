import { EmptyState, ErrorBanner, Skeleton, Table, type TableColumn } from "@/components/ui";
import { EntityLink } from "@/components/EntityLink";
import { errorMessage, formatDateTimeShort, formatKm, formatMoney } from "@/lib/format";
import { useVehicleShiftHistoryQuery } from "@/pages/live-map/useVehicleShiftHistory";
import type { VehicleShiftHistoryItem } from "@/pages/live-map/types";

export interface ShiftsTabProps {
  vehicleId: string;
}

function formatDuration(startAt: string, endAt: string | null): string {
  const start = new Date(startAt).getTime();
  if (Number.isNaN(start)) return "—";
  const end = endAt ? new Date(endAt).getTime() : Date.now();
  const minutes = Math.max(0, Math.round((end - start) / 60_000));
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  return `${hours}h ${mins}m${endAt ? "" : " (ongoing)"}`;
}

const COLUMNS: TableColumn<VehicleShiftHistoryItem>[] = [
  {
    key: "shift_id",
    header: "Shift",
    render: (row) => <EntityLink kind="shift" id={row.shift_id} name={row.shift_id.slice(0, 8)} />,
  },
  {
    key: "driver_name",
    header: "Driver",
    render: (row) =>
      row.driver_name ? <EntityLink kind="driver" id={row.driver_id} name={row.driver_name} /> : row.driver_id,
  },
  {
    key: "start_at",
    header: "Start",
    sortable: true,
    sortAccessor: (row) => new Date(row.start_at).getTime(),
    render: (row) => formatDateTimeShort(row.start_at),
  },
  { key: "end_at", header: "End", render: (row) => (row.end_at ? formatDateTimeShort(row.end_at) : "—") },
  { key: "duration", header: "Duration", render: (row) => formatDuration(row.start_at, row.end_at) },
  { key: "distance_km", header: "Distance", className: "text-right", render: (row) => formatKm(row.distance_km) },
  { key: "fare_total", header: "Fares", className: "text-right", render: (row) => formatMoney(row.fare_total) },
];

/** Plan §5.3 -- `GET /v1/fleet/vehicles/{id}/shift-history`, reusing the
 * exact hook `VehicleDetailModal` already calls, with driver/shift
 * `EntityLink`s and the distance/fare columns the endpoint already returns
 * but that modal never showed. */
export function ShiftsTab({ vehicleId }: ShiftsTabProps) {
  const shiftHistoryQuery = useVehicleShiftHistoryQuery(vehicleId);

  if (shiftHistoryQuery.isLoading) {
    return (
      <div className="flex flex-col gap-2">
        <Skeleton className="h-8 w-full" />
        <Skeleton className="h-40 w-full" />
      </div>
    );
  }

  if (shiftHistoryQuery.isError) {
    return <ErrorBanner message={`Failed to load shift history: ${errorMessage(shiftHistoryQuery.error)}`} />;
  }

  const items = shiftHistoryQuery.data?.items ?? [];
  if (items.length === 0) {
    return <EmptyState title="No shifts yet" description="No past shifts recorded for this vehicle." />;
  }

  return <Table columns={COLUMNS} data={items} rowKey={(row) => row.shift_id} pageSize={10} label="Shift history for this vehicle" />;
}
