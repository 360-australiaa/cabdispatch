import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { Star } from "lucide-react";
import { Badge, Card, CardContent, PageHeader, Select, Table, type TableColumn } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { useDriverOptionsQuery, useRatingsQuery, type RatingStars, type TripRating } from "./hooks";
import { formatDateTime } from "./format";

// Matches the backend's `GET /v1/ratings` cap (`limit: int = Query(default=50, ge=1, le=200)`,
// app/api/v1/ratings.py) -- fetching the max page gives the best client-side
// average/histogram without a second round trip. No fleet-wide "average
// rating per driver" endpoint exists server-side (only a single-driver one,
// wired to /v1/me/rating for the driver's own tablet), so both the overall
// summary and the per-driver leaderboard below are computed from this same
// loaded set, same "derive the rollup from the list you already fetched"
// convention `pages/psl/RemittanceReport.tsx` uses.
const FETCH_LIMIT = 200;

function average(ratings: TripRating[]): number | null {
  if (ratings.length === 0) return null;
  return ratings.reduce((sum, r) => sum + r.stars, 0) / ratings.length;
}

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

/**
 * Ratings — owner/admin view of the passenger's post-trip 1-5 star rating
 * (`GET /v1/ratings`, captured by the driver tablet's Close & Pay rating
 * step). This was a real gap: the backend route has existed since the
 * driver-engagement pass that also shipped Wallet/Announcements/Incentives,
 * but no dashboard page ever read it -- a dispatcher had no way to see which
 * drivers were being rated poorly, or read a passenger's written comment,
 * without querying the API directly. Mirrors `WalletPage.tsx`'s shape
 * (owner/admin-gated, read-only from here).
 */
export default function RatingsPage() {
  const { user } = useAuth();
  const canAccess = user?.role === "owner" || user?.role === "admin";

  const [driverId, setDriverId] = useState<string>("");
  const [starsFilter, setStarsFilter] = useState<string>("");

  const driversQuery = useDriverOptionsQuery();
  const drivers = useMemo(() => driversQuery.data ?? [], [driversQuery.data]);
  const driverNameById = useMemo(() => {
    const map = new Map<string, string>();
    for (const d of drivers) map.set(d.id, d.name);
    return map;
  }, [drivers]);

  const ratingsQuery = useRatingsQuery({
    driver_id: canAccess ? driverId || undefined : undefined,
    skip: 0,
    limit: FETCH_LIMIT,
  });

  const allRatings = ratingsQuery.data?.items ?? [];
  const rows = starsFilter ? allRatings.filter((r) => String(r.stars) === starsFilter) : allRatings;
  const total = ratingsQuery.data?.total ?? 0;

  const overallAverage = average(allRatings);
  const distribution = useMemo(() => {
    const counts: Record<RatingStars, number> = { 1: 0, 2: 0, 3: 0, 4: 0, 5: 0 };
    for (const r of allRatings) counts[r.stars]++;
    return counts;
  }, [allRatings]);

  // Per-driver leaderboard, only meaningful when not already filtered to a
  // single driver -- lets an operator spot a driver trending low without
  // clicking through every driver one at a time.
  const leaderboard = useMemo(() => {
    if (driverId) return [];
    const byDriver = new Map<string, TripRating[]>();
    for (const r of allRatings) {
      const list = byDriver.get(r.driver_id) ?? [];
      list.push(r);
      byDriver.set(r.driver_id, list);
    }
    return Array.from(byDriver.entries())
      .map(([id, ratings]) => ({
        driverId: id,
        name: driverNameById.get(id) ?? `${id.slice(0, 8)}…`,
        count: ratings.length,
        average: average(ratings) ?? 0,
      }))
      .sort((a, b) => a.average - b.average); // worst-first -- the list worth acting on
  }, [allRatings, driverId, driverNameById]);

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
          className="font-mono text-xs text-brand-primary underline-offset-2 hover:underline"
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
              {ratingsQuery.isLoading ? "Loading…" : `From ${allRatings.length} of ${total} rating${total === 1 ? "" : "s"}`}
            </p>
          </CardContent>
        </Card>
        <Card className="sm:col-span-2">
          <CardContent className="pt-4">
            <p className="mb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">Distribution</p>
            <div className="flex flex-col gap-1">
              {([5, 4, 3, 2, 1] as RatingStars[]).map((star) => {
                const count = distribution[star];
                const pct = allRatings.length ? (count / allRatings.length) * 100 : 0;
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
                  key={d.driverId}
                  type="button"
                  onClick={() => setDriverId(d.driverId)}
                  className="flex items-center gap-2 rounded-md border border-border bg-muted/40 px-2.5 py-1.5 text-xs hover:bg-muted"
                >
                  <span className="font-medium text-foreground">{d.name}</span>
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
            <Select className="w-64" options={driverOptions} value={driverId} onChange={(e) => setDriverId(e.target.value)} />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Stars</label>
            <Select className="w-44" options={STAR_FILTER_OPTIONS} value={starsFilter} onChange={(e) => setStarsFilter(e.target.value)} />
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

      {total > FETCH_LIMIT && (
        <p className="mb-3 text-xs text-muted-foreground">
          Showing the latest {FETCH_LIMIT} of {total} ratings — filter to a driver to see more of theirs.
        </p>
      )}

      <Table
        columns={columns}
        data={rows}
        rowKey={(row) => row.id}
        isLoading={ratingsQuery.isLoading}
        emptyState="No ratings match these filters."
        pageSize={15}
      />
    </div>
  );
}
