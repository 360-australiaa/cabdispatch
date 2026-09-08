import { useState } from "react";
import { EntityLink } from "@/components/EntityLink";
import { Badge, EmptyState, Pagination, Table, type TableColumn } from "@/components/ui";
import { useTripsQuery, type Trip } from "@/hooks/useTrips";
import { errorMessage, formatDateTime, formatDistance, formatMoney } from "@/lib/format";
import { statusBadgeVariant, TRIP_TYPE_LABELS } from "@/pages/trips/format";

const PAGE_SIZE = 15;

/**
 * Driver page Trips tab (dashboard command-centre plan §4.3): server-paged
 * trips for this driver, via the real `driver_id` filter `GET /v1/trips`
 * already supports (`@/hooks/useTrips`'s `useTripsQuery`, the same hook the
 * Trips page uses). `total` is a real server count, so paging is genuine
 * server-side paging, not a client slice over a capped fetch.
 */
export function TripsTab({ driverId }: { driverId: string }) {
  const [skip, setSkip] = useState(0);
  const tripsQuery = useTripsQuery({ driver_id: driverId, skip, limit: PAGE_SIZE });

  const trips = tripsQuery.data?.items ?? [];
  const total = tripsQuery.data?.total ?? 0;
  const page = Math.floor(skip / PAGE_SIZE);
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const columns: TableColumn<Trip>[] = [
    {
      key: "id",
      header: "Trip",
      render: (t) => <EntityLink kind="trip" id={t.id} name={t.id.slice(0, 8)} />,
    },
    { key: "start_at", header: "Started", render: (t) => formatDateTime(t.start_at) },
    { key: "type", header: "Type", render: (t) => TRIP_TYPE_LABELS[t.type] ?? t.type },
    {
      key: "status",
      header: "Status",
      render: (t) => <Badge variant={statusBadgeVariant(t.status)}>{t.status}</Badge>,
    },
    { key: "distance_m", header: "Distance", render: (t) => formatDistance(t.distance_m) },
    { key: "total", header: "Total", className: "text-right", render: (t) => formatMoney(t.total) },
    {
      key: "flagged_for_review",
      header: "Flagged",
      render: (t) => (t.flagged_for_review ? <Badge variant="destructive">Flagged</Badge> : "—"),
    },
  ];

  if (tripsQuery.isError) {
    return (
      <EmptyState
        title="Couldn't load trips"
        description={`GET /v1/trips?driver_id= failed: ${errorMessage(tripsQuery.error)}`}
      />
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <Table
        columns={columns}
        data={trips}
        rowKey={(t) => t.id}
        isLoading={tripsQuery.isLoading}
        label="Trips"
        emptyState={<EmptyState title="No trips" description="This driver has no recorded trips yet." />}
      />
      {total > PAGE_SIZE && (
        <Pagination
          page={page}
          pageCount={pageCount}
          onPageChange={(p) => setSkip(p * PAGE_SIZE)}
          summary={
            <>
              {skip + 1}–{Math.min(total, skip + PAGE_SIZE)} of {total} (page {page + 1} of {pageCount})
            </>
          }
        />
      )}
    </div>
  );
}
