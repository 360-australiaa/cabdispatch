import { useState } from "react";
import { Star } from "lucide-react";
import { EntityLink } from "@/components/EntityLink";
import { Card, CardContent, EmptyState, Pagination, Table, type TableColumn } from "@/components/ui";
import { errorMessage } from "@/lib/format";
import { useRatingsQuery, type TripRating } from "@/pages/driver-engagement/hooks";
import { formatDateTime } from "@/pages/driver-engagement/format";
import { isForbidden, useDriverRatingsSummary } from "../api";

const PAGE_SIZE = 15;

function StarRow({ value }: { value: number }) {
  return (
    <span className="inline-flex items-center gap-0.5">
      {[1, 2, 3, 4, 5].map((n) => (
        <Star
          key={n}
          className={n <= value ? "h-3.5 w-3.5 fill-brand-accent text-brand-accent" : "h-3.5 w-3.5 text-border"}
        />
      ))}
    </span>
  );
}

/**
 * Driver page Ratings tab (dashboard command-centre plan §4.5): the
 * summary aggregate plus the row-level list, both real and driver-scoped
 * (`GET /v1/ratings/summary?driver_id=`, `GET /v1/ratings?driver_id=`) --
 * both owner/admin only server-side, so a dispatcher sees an honest "not
 * available for your role" instead of a crash rather than a hidden tab,
 * since the tab itself is still real for the roles that can see it.
 */
export function RatingsTab({ driverId }: { driverId: string }) {
  const [skip, setSkip] = useState(0);
  const summaryQuery = useDriverRatingsSummary(driverId);
  const ratingsQuery = useRatingsQuery({ driver_id: driverId, skip, limit: PAGE_SIZE });

  const rows = ratingsQuery.data?.items ?? [];
  const total = ratingsQuery.data?.total ?? 0;
  const page = Math.floor(skip / PAGE_SIZE);
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const forbidden = isForbidden(summaryQuery.error) || isForbidden(ratingsQuery.error);

  const columns: TableColumn<TripRating>[] = [
    { key: "created_at", header: "When", render: (r) => formatDateTime(r.created_at) },
    { key: "stars", header: "Stars", render: (r) => <StarRow value={r.stars} /> },
    { key: "comment", header: "Comment", render: (r) => r.comment ?? "—" },
    {
      key: "trip_id",
      header: "Trip",
      render: (r) => <EntityLink kind="trip" id={r.trip_id} name={r.trip_id.slice(0, 8)} />,
    },
  ];

  if (forbidden) {
    return (
      <EmptyState
        title="Not available for your role"
        description="GET /v1/ratings and /v1/ratings/summary are owner/admin only."
      />
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardContent className="pt-4">
          {summaryQuery.isLoading ? (
            <p className="text-sm text-muted-foreground">Loading…</p>
          ) : summaryQuery.isError ? (
            <p className="text-sm text-destructive">Failed to load rating summary.</p>
          ) : summaryQuery.data && summaryQuery.data.total > 0 ? (
            <div className="flex items-center gap-4">
              <span className="text-2xl font-semibold text-foreground">
                {summaryQuery.data.average?.toFixed(2) ?? "—"}
              </span>
              <div className="flex flex-col gap-1 text-xs text-muted-foreground">
                {[5, 4, 3, 2, 1].map((stars) => {
                  const count = summaryQuery.data?.distribution[String(stars)] ?? 0;
                  const pct = summaryQuery.data && summaryQuery.data.total > 0 ? (count / summaryQuery.data.total) * 100 : 0;
                  return (
                    <div key={stars} className="flex items-center gap-2">
                      <span className="w-8">{stars}★</span>
                      <div className="h-1.5 w-32 overflow-hidden rounded-full bg-muted">
                        <div className="h-full rounded-full bg-brand-accent" style={{ width: `${pct}%` }} />
                      </div>
                      <span className="w-8 text-right">{count}</span>
                    </div>
                  );
                })}
              </div>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">No ratings yet.</p>
          )}
        </CardContent>
      </Card>

      {ratingsQuery.isError ? (
        <EmptyState
          title="Couldn't load ratings"
          description={`GET /v1/ratings?driver_id= failed: ${errorMessage(ratingsQuery.error)}`}
        />
      ) : (
        <div className="flex flex-col gap-3">
          <Table
            columns={columns}
            data={rows}
            rowKey={(r) => r.id}
            isLoading={ratingsQuery.isLoading}
            label="Ratings"
            emptyState={<EmptyState title="No ratings" description="This driver has no ratings yet." />}
          />
          {total > PAGE_SIZE && (
            <Pagination
              page={page}
              pageCount={pageCount}
              onPageChange={(p) => setSkip(p * PAGE_SIZE)}
              summary={
                <>
                  {skip + 1}–{Math.min(total, skip + PAGE_SIZE)} of {total}
                </>
              }
            />
          )}
        </div>
      )}
    </div>
  );
}
