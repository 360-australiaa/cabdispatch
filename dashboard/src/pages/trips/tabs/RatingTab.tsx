import { Star } from "lucide-react";
import { EmptyState, ErrorBanner, Skeleton } from "@/components/ui";
import { errorMessage, formatDateTime } from "@/lib/format";
import { useRatingsQuery } from "@/pages/driver-engagement/hooks";

export interface RatingTabProps {
  tripId: string;
  driverId: string;
}

const RATINGS_FETCH_LIMIT = 200;

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
 * Trip page Rating tab (dashboard command-centre plan §7). `GET /v1/ratings`
 * (`backend/app/api/v1/ratings.py::list_ratings`) filters by `driver_id` and
 * `stars` only -- there is no `trip_id` query param, and `TripRating` does
 * carry a real `trip_id` field (set on create), so rather than omitting this
 * tab outright this fetches the driver's most recent ratings (capped at 200,
 * the endpoint's server-side max) and finds the one row whose `trip_id`
 * matches this trip -- a real, driver-scoped server query with an honest
 * client-side match, not a guessed or fabricated figure. A driver with more
 * than 200 ratings since this trip could in principle fall outside that
 * page; the empty state below says so rather than silently claiming "no
 * rating" when the query simply didn't reach far enough back.
 */
export function RatingTab({ tripId, driverId }: RatingTabProps) {
  const ratingsQuery = useRatingsQuery({ driver_id: driverId, limit: RATINGS_FETCH_LIMIT });

  if (ratingsQuery.isLoading) {
    return <Skeleton className="h-24 w-full" />;
  }

  if (ratingsQuery.isError) {
    return (
      <ErrorBanner
        message={`Failed to load ratings for this driver (GET /v1/ratings?driver_id=): ${errorMessage(
          ratingsQuery.error,
        )}`}
      />
    );
  }

  const rating = ratingsQuery.data?.items.find((r) => r.trip_id === tripId);

  if (!rating) {
    const total = ratingsQuery.data?.total ?? 0;
    return (
      <EmptyState
        title="No rating recorded"
        description={
          total > RATINGS_FETCH_LIMIT
            ? `This driver has ${total} ratings on record -- more than the ${RATINGS_FETCH_LIMIT} most recent this tab checks (GET /v1/ratings has no trip_id filter). This trip's rating may exist further back.`
            : "No rating references this trip yet."
        }
      />
    );
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
