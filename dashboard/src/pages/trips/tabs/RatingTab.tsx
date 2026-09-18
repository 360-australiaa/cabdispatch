import { Star } from "lucide-react";
import { EmptyState, ErrorBanner, Skeleton } from "@/components/ui";
import { errorMessage, formatDateTime } from "@/lib/format";
import { useRatingsQuery } from "@/pages/driver-engagement/hooks";

export interface RatingTabProps {
  tripId: string;
  driverId: string;
}

function StarRow({ value }: { value: number }) {
  return (
    <span className="inline-flex items-center gap-0.5">
      {[1, 2, 3, 4, 5].map((n) => (
        <Star
          key={n}
          className={n <= value ? "h-4 w-4 fill-brand-accent text-brand-accent" : "h-4 w-4 text-border"}
        />
      ))}
    </span>
  );
}

/**
 * Trip page Rating tab (dashboard command-centre plan §7). Asks the server
 * for this trip's rating directly: `GET /v1/ratings?trip_id=`
 * (`backend/app/api/v1/ratings.py::list_ratings`).
 *
 * Until 2026-09-19 that endpoint filtered by `driver_id`/`stars` only, so this
 * tab fetched the driver's 200 most recent ratings (the endpoint's own
 * server-side max) and matched `trip_id` in the browser. For a driver with
 * more than 200 ratings since the trip in view, the rating fell off that page
 * and the tab rendered "No rating recorded" for a trip that genuinely had one
 * -- a wrong answer shown to the operator, not a hedged one.
 * `TripRating.trip_id` is unique per trip (one rating per trip; the create
 * endpoint 409s on a repeat), so the filtered page holds at most one row and
 * an empty page now means exactly what it says.
 */
export function RatingTab({ tripId, driverId }: RatingTabProps) {
  // `driver_id` is still sent alongside `trip_id`: it costs nothing and is an
  // additional AND over the same tenant-scoped query, so a rating somehow
  // attributed to a different driver cannot surface on this driver's trip.
  const ratingsQuery = useRatingsQuery({ trip_id: tripId, driver_id: driverId, limit: 1 });

  if (ratingsQuery.isLoading) {
    return <Skeleton className="h-24 w-full" />;
  }

  if (ratingsQuery.isError) {
    return (
      <ErrorBanner
        message={`Failed to load this trip's rating (GET /v1/ratings?trip_id=): ${errorMessage(
          ratingsQuery.error,
        )}`}
      />
    );
  }

  const rating = ratingsQuery.data?.items[0];

  if (!rating) {
    return <EmptyState title="No rating recorded" description="No rating references this trip yet." />;
  }

  return (
    <div className="rounded-lg border border-border p-4">
      <div className="flex items-center justify-between">
        <StarRow value={rating.stars} />
        <span className="text-xs text-muted-foreground">{formatDateTime(rating.created_at)}</span>
      </div>
      {rating.comment && <p className="mt-3 text-sm text-foreground">{rating.comment}</p>}
    </div>
  );
}
