import { useState } from "react";
import { Modal, Select, Table, type TableColumn } from "@/components/ui";
import { cn } from "@/lib/utils";
import {
  useDriverOptionsQuery,
  useIncentiveProgressListQuery,
  useIncentiveProgressQuery,
  type Incentive,
  type IncentiveProgressRow,
} from "./hooks";
import { formatMoney } from "./format";

export interface IncentiveProgressModalProps {
  open: boolean;
  onClose: () => void;
  incentive: Incentive | null;
}

/** "How is every driver doing against this incentive?" -- one row per
 * driver from `GET /v1/incentives/{id}/progress`, the same
 * `incentive_progress_for_driver` count the driver tablet shows, run
 * server-side for the whole tenant (admin plan §4). The driver dropdown
 * narrows the table to one driver.
 *
 * On an older backend without that route (404) this drops to the previous
 * behaviour: pick one driver, and the count is reproduced client-side from
 * their closed trips (`useIncentiveProgressQuery`) with its own honesty
 * flag about the 200-trip page possibly not covering the whole window. */
export function IncentiveProgressModal({ open, onClose, incentive }: IncentiveProgressModalProps) {
  const [driverId, setDriverId] = useState<string>("");
  const driversQuery = useDriverOptionsQuery();
  const drivers = driversQuery.data ?? [];

  const listQuery = useIncentiveProgressListQuery(open && incentive ? incentive.id : null);
  const serverMissing = listQuery.data?.missing === true;

  const driverOptions = [
    {
      value: "",
      label: driversQuery.isLoading ? "Loading drivers…" : serverMissing ? "Select a driver" : "All drivers",
    },
    ...drivers.map((d) => ({
      value: d.id,
      label: d.driver_code ? `${d.name} (${d.driver_code})` : d.name,
    })),
  ];

  const rows = (listQuery.data?.items ?? []).filter((row) => !driverId || row.driver_id === driverId);

  const columns: TableColumn<IncentiveProgressRow>[] = [
    { key: "driver_name", header: "Driver", render: (row) => <span className="font-medium">{row.driver_name}</span> },
    {
      key: "completed_trips",
      header: "Progress",
      render: (row) => {
        const pct = row.target_trips > 0 ? Math.min(100, Math.floor((row.completed_trips * 100) / row.target_trips)) : 0;
        const achieved = row.completed_trips >= row.target_trips;
        return (
          <div className="min-w-[10rem]">
            <div className="flex items-baseline justify-between text-xs">
              <span>
                {row.completed_trips} of {row.target_trips} trips
              </span>
              <span className="text-muted-foreground">{pct}%</span>
            </div>
            <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-muted">
              <div className={cn("h-full rounded-full", achieved ? "bg-success" : "bg-primary")} style={{ width: `${pct}%` }} />
            </div>
          </div>
        );
      },
      sortable: true,
      sortAccessor: (row) => (row.target_trips > 0 ? row.completed_trips / row.target_trips : 0),
    },
    {
      key: "earned",
      header: "Earned",
      className: "text-right",
      render: (row) => (
        <span className={cn("font-mono", Number(row.earned) > 0 ? "text-success" : "text-muted-foreground")}>
          {formatMoney(row.earned)}
        </span>
      ),
      sortable: true,
      sortAccessor: (row) => Number(row.earned),
    },
  ];

  return (
    <Modal
      open={open}
      onClose={() => {
        setDriverId("");
        onClose();
      }}
      title={incentive ? `Progress — ${incentive.title}` : "Progress"}
      description="Counted live from each driver's closed trips inside the incentive's window — nothing here is stored."
      className="max-w-2xl"
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

      {listQuery.isError && (
        <p className="mt-4 text-sm text-destructive">Failed to load progress for this incentive.</p>
      )}

      {!serverMissing && !listQuery.isError && (
        <div className="mt-4">
          <Table
            columns={columns}
            data={rows}
            rowKey={(row) => row.driver_id}
            isLoading={listQuery.isLoading}
            emptyState={driverId ? "No progress row for this driver." : "No drivers have started on this incentive yet."}
            label="Incentive progress"
          />
        </div>
      )}

      {serverMissing && incentive && (
        <FallbackDriverProgress driverId={driverId} incentive={incentive} />
      )}
    </Modal>
  );
}

/** The pre-`/progress` path, unchanged: one driver at a time, computed in
 * the browser from `GET /v1/trips`. Only rendered when the server route is
 * missing. */
function FallbackDriverProgress({ driverId, incentive }: { driverId: string; incentive: Incentive }) {
  const progressQuery = useIncentiveProgressQuery(driverId || null, incentive);
  const progress = progressQuery.data;

  if (!driverId) {
    return <p className="mt-4 text-sm text-muted-foreground">Pick a driver to see their progress.</p>;
  }

  return (
    <div className="mt-4">
      {progressQuery.isLoading && <p className="text-sm text-muted-foreground">Loading…</p>}
      {progressQuery.isError && <p className="text-sm text-destructive">Failed to load this driver's trips.</p>}
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
              This driver has more closed trips than the 200 most recent fetched here — if some of the
              missing ones fall inside the incentive window, the count above is a floor, not the exact
              total.
            </p>
          )}
        </>
      )}
    </div>
  );
}
