import { useMemo } from "react";
import { EntityLink } from "@/components/EntityLink";
import { Badge, EmptyState, ErrorBanner, Skeleton, Table, type TableColumn } from "@/components/ui";
import { errorMessage, formatDateTime, formatDistance, formatMoney } from "@/lib/format";
import { useTripsQuery, type Trip } from "@/hooks/useTrips";
import { statusBadgeVariant, TRIP_TYPE_LABELS } from "@/pages/trips/format";
import type { Shift } from "../types";

export interface TripsTabProps {
  shift: Shift;
}

/**
 * Shift page Trips tab (dashboard command-centre plan §7). `GET /v1/trips`
 * has no `shift_id` query param (checked: `TripListFilters` only takes
 * status/type/vehicle_id/driver_id/flagged_for_review/start_from/start_to/
 * end_from/end_to/skip/limit) -- so this filters server-side by the real
 * `driver_id` + a `start_from`/`start_to` window bounded by the shift's own
 * start/end (an open shift's window runs to "now"), then narrows to an
 * exact match with a client-side `trip.shift_id === shift.id` check. Every
 * trip does carry a real `shift_id` field, so the narrowing is exact, not a
 * guess -- it only exists because the server-side filter set doesn't reach
 * that far yet.
 */
export function TripsTab({ shift }: TripsTabProps) {
  const tripsQuery = useTripsQuery({
    driver_id: shift.driver_id,
    start_from: shift.start_at,
    start_to: shift.end_at ?? new Date().toISOString(),
    limit: 200,
  });

  const trips = useMemo(
    () => (tripsQuery.data?.items ?? []).filter((t) => t.shift_id === shift.id),
    [tripsQuery.data, shift.id],
  );

  if (tripsQuery.isLoading) {
    return <Skeleton className="h-40 w-full" />;
  }

  if (tripsQuery.isError) {
    return (
      <ErrorBanner
        message={`Failed to load trips for this shift (GET /v1/trips?driver_id=): ${errorMessage(
          tripsQuery.error,
        )}`}
      />
    );
  }

  const columns: TableColumn<Trip>[] = [
    { key: "id", header: "Trip", render: (t) => <EntityLink kind="trip" id={t.id} name={t.id.slice(0, 8)} /> },
    { key: "start_at", header: "Started", render: (t) => formatDateTime(t.start_at) },
    { key: "type", header: "Type", render: (t) => TRIP_TYPE_LABELS[t.type] ?? t.type },
    { key: "status", header: "Status", render: (t) => <Badge variant={statusBadgeVariant(t.status)}>{t.status}</Badge> },
    { key: "distance_m", header: "Distance", render: (t) => formatDistance(t.distance_m) },
    { key: "total", header: "Total", className: "text-right", render: (t) => formatMoney(t.total) },
  ];

  return (
    <Table
      columns={columns}
      data={trips}
      rowKey={(t) => t.id}
      label="Trips this shift"
      emptyState={<EmptyState title="No trips" description="No trips are recorded against this shift." />}
    />
  );
}
