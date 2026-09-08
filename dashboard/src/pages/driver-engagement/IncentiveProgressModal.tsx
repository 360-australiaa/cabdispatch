import { useState } from "react";
import { Modal, Select } from "@/components/ui";
import { cn } from "@/lib/utils";
import { useDriverOptionsQuery, useIncentiveProgressQuery, type Incentive } from "./hooks";

export interface IncentiveProgressModalProps {
  open: boolean;
  onClose: () => void;
  incentive: Incentive | null;
}

/** "How is this driver doing against this incentive?" — picked from the
 * driver dropdown, computed live. There is no owner/admin endpoint that
 * runs the driver tablet's own progress calculation
 * (`incentive_progress_for_driver`) for an arbitrary driver, so this counts
 * the driver's closed trips inside the incentive window itself from
 * `GET /v1/trips` (see `useIncentiveProgressQuery`'s doc comment) and says
 * so, plus flags when the trip page fetched might not cover the whole
 * window rather than silently under-counting. */
export function IncentiveProgressModal({ open, onClose, incentive }: IncentiveProgressModalProps) {
  const [driverId, setDriverId] = useState<string>("");
  const driversQuery = useDriverOptionsQuery();
  const drivers = driversQuery.data ?? [];

  const progressQuery = useIncentiveProgressQuery(driverId || null, incentive);
  const progress = progressQuery.data;

  const driverOptions = [
    { value: "", label: driversQuery.isLoading ? "Loading drivers…" : "Select a driver" },
    ...drivers.map((d) => ({
      value: d.id,
      label: d.driver_code ? `${d.name} (${d.driver_code})` : d.name,
    })),
  ];

  return (
    <Modal
      open={open}
      onClose={() => {
        setDriverId("");
        onClose();
      }}
      title={incentive ? `Progress — ${incentive.title}` : "Progress"}
      description="Counted live from this driver's closed trips inside the incentive's window — nothing here is stored."
    >
      <div className="flex flex-col gap-1.5">
        <label className="text-xs font-medium text-muted-foreground" htmlFor="incentive-progress-driver">
          Driver
        </label>
        <Select
          id="incentive-progress-driver"
          options={driverOptions}
          value={driverId}
          onChange={(e) => setDriverId(e.target.value)}
          disabled={driversQuery.isLoading}
        />
      </div>

      {driverId && incentive && (
        <div className="mt-4">
          {progressQuery.isLoading && <p className="text-sm text-muted-foreground">Loading…</p>}
          {progressQuery.isError && (
            <p className="text-sm text-destructive">Failed to load this driver's trips.</p>
          )}
          {progress && (
            <>
              <div className="flex items-baseline justify-between">
                <p className="text-sm font-medium">
                  {progress.completedTrips} of {progress.targetTrips} trips
                </p>
                <p className="text-sm text-muted-foreground">{progress.progressPct}%</p>
              </div>
              <div className="mt-2 h-2 w-full overflow-hidden rounded-full bg-muted">
                <div
                  className={cn("h-full rounded-full", progress.achieved ? "bg-success" : "bg-primary")}
                  style={{ width: `${progress.progressPct}%` }}
                />
              </div>
              <p className="mt-2 text-sm text-muted-foreground">
                {progress.achieved
                  ? "Target reached."
                  : `${progress.remainingTrips} trip${progress.remainingTrips === 1 ? "" : "s"} to go.`}
              </p>
              {progress.maybeIncomplete && (
                <p className="mt-2 text-xs text-amber-600">
                  This driver has more closed trips than the 200 most recent fetched here — if some of
                  the missing ones fall inside the incentive window, the count above is a floor, not
                  the exact total.
                </p>
              )}
            </>
          )}
        </div>
      )}

      {!driverId && (
        <p className="mt-4 text-sm text-muted-foreground">Pick a driver to see their progress.</p>
      )}
    </Modal>
  );
}
