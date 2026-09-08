import { Card, CardContent } from "@/components/ui";
import { useIncentiveProgressQuery, type Incentive } from "@/pages/driver-engagement/hooks";

/**
 * One active incentive's progress bar for a single driver, computed via
 * `useIncentiveProgressQuery` -- the same client-side "count this driver's
 * closed trips inside the incentive's window" derivation the Driver
 * Wallets/Incentives pages already use (there is no owner/admin endpoint
 * that runs the driver tablet's own `GET /v1/me/incentives` logic for an
 * arbitrary driver id). `maybeIncomplete` is surfaced honestly rather than
 * silently treating a floor as an exact count.
 */
export function IncentiveProgressRow({ driverId, incentive }: { driverId: string; incentive: Incentive }) {
  const progressQuery = useIncentiveProgressQuery(driverId, incentive);
  const progress = progressQuery.data;

  return (
    <Card>
      <CardContent className="pt-4">
        <div className="flex items-center justify-between">
          <p className="text-sm font-medium text-foreground">{incentive.title}</p>
          <p className="text-sm text-muted-foreground">
            {progressQuery.isLoading || !progress
              ? "…"
              : `${progress.completedTrips} / ${progress.targetTrips} trips`}
          </p>
        </div>
        {progress && (
          <>
            <div className="mt-2 h-2 w-full overflow-hidden rounded-full bg-muted">
              <div
                className="h-full rounded-full bg-brand-accent"
                style={{ width: `${Math.min(100, progress.progressPct)}%` }}
              />
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
              {progress.achieved
                ? `Target reached — ${incentive.reward_aud} AUD`
                : `${progress.remainingTrips} trip${progress.remainingTrips === 1 ? "" : "s"} to go`}
              {progress.maybeIncomplete && " (based on the most recent trips fetched — may undercount)"}
            </p>
          </>
        )}
      </CardContent>
    </Card>
  );
}
