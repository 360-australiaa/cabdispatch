import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Star } from "lucide-react";
import {
  Badge,
  Card,
  CardContent,
  PageHeader,
  Pagination,
  Select,
  Spinner,
  Table,
  type TableColumn,
} from "@/components/ui";
import apiClient from "@/lib/apiClient";
import { useAuth } from "@/lib/auth";
import { useDriverOptionsQuery, type Page, type RatingStars, type TripRating } from "./hooks";
import { formatDateTime } from "./format";

const PAGE_SIZE = 15;

function Stars({ value }: { value: number }) {
  const rounded = Math.round(value);
  return (
    <span className="inline-flex items-center gap-0.5" title={`${value.toFixed(2)} / 5`}>
      {[1, 2, 3, 4, 5].map((n) => (
        <Star
          key={n}
          className={n <= rounded ? "h-3.5 w-3.5 fill-brand-accent text-brand-accent" : "h-3.5 w-3.5 text-border"}
        />
      ))}
    </span>
  );
}

const STAR_FILTER_OPTIONS = [
  { value: "", label: "All star ratings" },
  { value: "5", label: "5 stars" },
  { value: "4", label: "4 stars" },
  { value: "3", label: "3 stars" },
  { value: "2", label: "2 stars" },
  { value: "1", label: "1 star" },
];

// --- Summary aggregate (GET /v1/ratings/summary) ----------------------------
//
// A real SQL aggregate (COUNT/AVG/GROUP BY, backend/app/api/v1/ratings.py)
// over EVERY rating row matching the tenant (+ optional driver_id) filter --
// not derived client-side from whatever page of GET /v1/ratings happened to
// be loaded, which used to silently under-count a fleet with more than 200
// ratings. Colocated here rather than in ./hooks.ts, which this page does
// not own (see docs/plans/2026-09-08-global-meter-program.md's D12 row) --
// only RatingsPage.tsx itself is this workstream's to edit.

interface RatingsSummaryDriverLine {
  driver_id: string;
  count: number;
  average: number;
}

interface RatingsSummary {
  total: number;
  average: number | null;
  distribution: Record<string, number>;
  by_driver: RatingsSummaryDriverLine[];
}

function useRatingsSummaryQuery(driverId: string | null) {
  return useQuery({
    queryKey: ["ratings-summary", driverId],
    queryFn: async () => {
      const res = await apiClient.get<RatingsSummary>("/v1/ratings/summary", {
        params: driverId ? { driver_id: driverId } : undefined,
      });
      return res.data;
    },
    placeholderData: (prev) => prev,
  });
}

// --- Row-level list (GET /v1/ratings) --------------------------------------
//
// A local query, not `useRatingsQuery` from `./hooks.ts` (this page does not
// own that file), so the `stars` filter -- which needs to run server-side
// alongside real `skip`/`limit` paging for `total` to stay honest -- can be
// wired here without touching a file outside this workstream's ownership.

interface RatingsListFilters {
  driverId?: string;
  stars?: number;
  skip: number;
  limit: number;
}

function useRatingsListQuery(filters: RatingsListFilters) {
  return useQuery({
    queryKey: ["ratings-list", filters],
    queryFn: async () => {
      const res = await apiClient.get<Page<TripRating>>("/v1/ratings", {
        params: {
          driver_id: filters.driverId || undefined,
          stars: filters.stars,
          skip: filters.skip,
          limit: filters.limit,
        },
      });
      return res.data;
    },
    placeholderData: (prev) => prev,
  });
}

/**
 * Ratings — owner/admin view of the passenger's post-trip 1-5 star rating
 * (`GET /v1/ratings`, captured by the driver tablet's Close & Pay rating
 * step). This was a real gap: the backend route has existed since the
 * driver-engagement pass that also shipped Wallet/Announcements/Incentives,
 * but no dashboard page ever read it -- a dispatcher had no way to see which
 * drivers were being rated poorly, or read a passenger's written comment,
 * without querying the API directly. Mirrors `WalletPage.tsx`'s shape
 * (owner/admin-gated, read-only from here).
 *
 * The fleet average, star distribution and per-driver leaderboard come from
 * `GET /v1/ratings/summary` -- a real aggregate over every matching row --
 * while the row-level table below pages through `GET /v1/ratings` with a
 * real server-side `skip`/`limit`/`stars` filter and real `total` (D12
 * server-side pagination), not a single capped fetch filtered/paginated
 * client-side the way this page used to work.
 */
export default function RatingsPage() {
  const { user } = useAuth();
  const canAccess = user?.role === "owner" || user?.role === "admin";

  const [driverId, setDriverId] = useState<string>("");
  const [starsFilter, setStarsFilter] = useState<string>("");
  const [page, setPage] = useState(0);

  const driversQuery = useDriverOptionsQuery();
  const drivers = useMemo(() => driversQuery.data ?? [], [driversQuery.data]);
  const driverNameById = useMemo(() => {
    const map = new Map<string, string>();
    for (const d of drivers) map.set(d.id, d.name);
    return map;
  }, [drivers]);

  const summaryQuery = useRatingsSummaryQuery(canAccess ? driverId || null : null);
  const summary = summaryQuery.data;

  const ratingsQuery = useRatingsListQuery({
    driverId: canAccess ? driverId || undefined : undefined,
    stars: starsFilter ? Number(starsFilter) : undefined,
    skip: page * PAGE_SIZE,
    limit: PAGE_SIZE,
  });

  const rows = ratingsQuery.data?.items ?? [];
  const total = ratingsQuery.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const overallAverage = summary?.average ?? null;
  const distribution: Record<RatingStars, number> = {
    1: summary?.distribution?.["1"] ?? 0,
    2: summary?.distribution?.["2"] ?? 0,
    3: summary?.distribution?.["3"] ?? 0,
    4: summary?.distribution?.["4"] ?? 0,
    5: summary?.distribution?.["5"] ?? 0,
  };
  const distributionTotal = summary?.total ?? 0;

  // Worst-first, same as the backend's own sort -- lets an operator spot a
  // driver trending low without clicking through every driver one at a time.
  // Only meaningful when not already filtered to a single driver.
  const leaderboard = driverId ? [] : (summary?.by_driver ?? []);

  const driverOptions = [
    { value: "", label: driversQuery.isLoading ? "Loading drivers…" : "All drivers" },
    ...drivers.map((d) => ({ value: d.id, label: d.driver_code ? `${d.name} (${d.driver_code})` : d.name })),
  ];

  const columns: TableColumn<TripRating>[] = [
    { key: "created_at", header: "When", render: (row) => formatDateTime(row.created_at) },
    {
      key: "driver_id",
      header: "Driver",
      render: (row) => driverNameById.get(row.driver_id) ?? `${row.driver_id.slice(0, 8)}…`,
    },
    { key: "stars", header: "Rating", render: (row) => <Stars value={row.stars} /> },
    {
      key: "comment",
      header: "Comment",
      render: (row) =>
        row.comment ? (
          <span className="line-clamp-2 max-w-md">{row.comment}</span>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
    {
      key: "trip_id",
      header: "Trip",
      render: (row) => (
        // No /trips/:id route exists in this app -- every entity link here is
        // the query-param deep-link convention Trips already supports
        // (`?search=`, matched against trip.id -- see pages/trips/index.tsx).
        <Link
          to={`/trips?search=${row.trip_id}`}
          className="font-mono text-xs text-brand-primary dark:text-brand-accent underline-offset-2 hover:underline"
          title={row.trip_id}
        >
          {row.trip_id.slice(0, 8)}…
        </Link>
      ),
    },
  ];

  if (!canAccess) {
    return (
      <div>
        <PageHeader title="Ratings" description="Passenger star ratings for every driver in your fleet." />
        <Card>
          <CardContent className="pt-4 text-sm text-muted-foreground">
            Ratings are visible to owner and admin roles only.
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title="Ratings"
        description="Passenger 1-5 star ratings captured on the driver tablet at the end of Close & Pay, with the written comment if the passenger left one."
      />

      <div className="mb-4 grid gap-4 sm:grid-cols-3">
        <Card>
          <CardContent className="pt-4">
            <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
              {driverId ? "Driver average" : "Fleet average"}
            </p>
            <p className="mt-1 flex items-center gap-2 text-2xl font-semibold tabular-nums">
              {overallAverage == null ? "—" : overallAverage.toFixed(2)}
              {overallAverage != null && <Stars value={overallAverage} />}
            </p>
            <p className="mt-1 text-xs text-muted-foreground">
              {summaryQuery.isLoading ? (
                <Spinner size="sm" label="Loading ratings" />
              ) : (
                `From all ${distributionTotal} rating${distributionTotal === 1 ? "" : "s"}`
              )}
            </p>
          </CardContent>
        </Card>
        <Card className="sm:col-span-2">
          <CardContent className="pt-4">
            <p className="mb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">Distribution</p>
            <div className="flex flex-col gap-1">
              {([5, 4, 3, 2, 1] as RatingStars[]).map((star) => {
                const count = distribution[star];
                const pct = distributionTotal ? (count / distributionTotal) * 100 : 0;
                return (
                  <div key={star} className="flex items-center gap-2 text-xs">
                    <span className="w-3 shrink-0 text-muted-foreground">{star}</span>
                    <Star className="h-3 w-3 shrink-0 fill-brand-accent text-brand-accent" />
                    <div className="h-2 flex-1 overflow-hidden rounded-full bg-muted">
                      <div className="h-full bg-brand-accent" style={{ width: `${pct}%` }} />
                    </div>
                    <span className="w-6 shrink-0 text-right text-muted-foreground">{count}</span>
                  </div>
                );
              })}
            </div>
          </CardContent>
        </Card>
      </div>

      {!driverId && leaderboard.length > 1 && (
        <Card className="mb-4">
          <CardContent className="pt-4">
            <p className="mb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
              Drivers to watch (lowest average first)
            </p>
            <div className="flex flex-wrap gap-2">
              {leaderboard.slice(0, 6).map((d) => (
                <button
                  key={d.driver_id}
                  type="button"
                  onClick={() => {
                    setDriverId(d.driver_id);
                    setPage(0);
                  }}
                  className="flex items-center gap-2 rounded-md border border-border bg-muted/40 px-2.5 py-1.5 text-xs hover:bg-muted"
                >
                  <span className="font-medium text-foreground">
                    {driverNameById.get(d.driver_id) ?? `${d.driver_id.slice(0, 8)}…`}
                  </span>
                  <Badge variant={d.average < 3 ? "destructive" : d.average < 4 ? "accent" : "success"}>
                    {d.average.toFixed(1)}★
                  </Badge>
                  <span className="text-muted-foreground">({d.count})</span>
                </button>
              ))}
            </div>
          </CardContent>
        </Card>
      )}

      <Card className="mb-4">
        <CardContent className="flex flex-wrap items-end gap-3 pt-4">
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Driver</label>
            <Select
              className="w-64"
              options={driverOptions}
              value={driverId}
              onChange={(e) => {
                setDriverId(e.target.value);
                setPage(0);
              }}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Stars</label>
            <Select
              className="w-44"
              options={STAR_FILTER_OPTIONS}
              value={starsFilter}
              onChange={(e) => {
                setStarsFilter(e.target.value);
                setPage(0);
              }}
            />
          </div>
          <span className="mb-2 ml-auto text-xs text-muted-foreground">
            {rows.length} of {total} rating{total === 1 ? "" : "s"}
          </span>
        </CardContent>
      </Card>

      {ratingsQuery.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load ratings. Check the backend connection and try again.
        </p>
      )}

      <Table
        columns={columns}
        data={rows}
        rowKey={(row) => row.id}
        isLoading={ratingsQuery.isLoading}
        emptyState="No ratings match these filters."
        label="Ratings"
      />
      <Pagination
        page={page}
        pageCount={pageCount}
        onPageChange={setPage}
        summary={`${total} rating${total === 1 ? "" : "s"} — page ${page + 1} of ${pageCount}`}
        label="Ratings pagination"
      />
    </div>
  );
}
