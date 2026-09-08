import { useState } from "react";
import { Card, CardContent, Input, SkeletonText, Tabs, type TabItem } from "@/components/ui";
import { defaultReportRange, formatAud, toDateInputValue } from "@/hooks/useReports";
import { errorMessage, formatDateTimeShort } from "@/lib/format";
import { useVehicleLifetimeTotals, useVehiclePilotReport } from "@/pages/fleet/api";
import type { Vehicle } from "@/pages/fleet/types";

export interface ReportsTabProps {
  vehicle: Vehicle;
}

type ReportsSubTab = "lifetime" | "pilot";

const SUB_TABS: TabItem<ReportsSubTab>[] = [
  { value: "lifetime", label: "Lifetime totals" },
  { value: "pilot", label: "Pilot report" },
];

function stat(value: string | null | undefined) {
  return value == null ? "—" : formatAud(value);
}

function pct(value: string | null | undefined) {
  return value == null ? "—" : value + "%";
}

function StatTile({ label, value }: { label: string; value: string }) {
  return (
    <Card>
      <CardContent className="pt-4">
        <p className="text-xs text-muted-foreground">{label}</p>
        <p className="mt-1 text-lg font-semibold">{value}</p>
      </CardContent>
    </Card>
  );
}

/**
 * Plan §5.5 -- `VehicleReportsModal`'s two report tabs, ported in as-is
 * (same `useVehicleLifetimeTotals`/`useVehiclePilotReport` hooks, same
 * fields, same "not tracked" honesty for the null lifetime-tips/duress-test
 * fields) as inline tab content rather than a separate `Modal`.
 *
 * `DateRangePicker` (plan F3) has not landed yet as of this pass -- the
 * plain `<input type="date">` pair `VehicleReportsModal` already used is
 * kept rather than blocking on that foundation workstream, per the plan's
 * own fallback instruction.
 */
export function ReportsTab({ vehicle }: ReportsTabProps) {
  const [tab, setTab] = useState<ReportsSubTab>("lifetime");
  const initial = defaultReportRange();
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);

  const rangeValid = Boolean(from && to) && from <= to;
  const range = rangeValid ? { from, to } : { from: "", to: "" };

  const totalsQuery = useVehicleLifetimeTotals(vehicle.id);
  const pilotQuery = useVehiclePilotReport(vehicle.id, range);

  return (
    <div>
      <Tabs items={SUB_TABS} value={tab} onChange={setTab} variant="underline" label="Vehicle report sections" className="mb-4" />

      {tab === "lifetime" && (
        <div>
          <p className="mb-3 text-xs text-muted-foreground">
            All-time cumulative totals across every closed trip ever recorded for this vehicle,
            mirroring the register a physical taxi meter keeps.
          </p>
          {totalsQuery.isError ? (
            <p className="text-sm text-destructive">Failed to load lifetime totals: {errorMessage(totalsQuery.error)}</p>
          ) : totalsQuery.isLoading || !totalsQuery.data ? (
            <SkeletonText lines={4} />
          ) : (
            <>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                <StatTile label="Trips" value={String(totalsQuery.data.trip_count)} />
                <StatTile label="Total fares" value={stat(totalsQuery.data.total_fares)} />
                <StatTile label="Total PSL" value={stat(totalsQuery.data.total_psl)} />
                <StatTile label="Total tolls" value={stat(totalsQuery.data.total_tolls)} />
                <StatTile
                  label="Total tips"
                  value={totalsQuery.data.total_tips == null ? "Not tracked" : stat(totalsQuery.data.total_tips)}
                />
                <StatTile label="Total km" value={totalsQuery.data.total_km + " km"} />
              </div>
              <p className="mt-3 text-xs text-muted-foreground">Generated {formatDateTimeShort(totalsQuery.data.generated_at)}</p>
            </>
          )}
        </div>
      )}

      {tab === "pilot" && (
        <div>
          <div className="mb-3 flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1.5">
              <label className="text-xs font-medium text-muted-foreground" htmlFor="vp-pilot-from">
                From
              </label>
              <Input
                id="vp-pilot-from"
                type="date"
                className="w-40"
                value={from}
                max={to || toDateInputValue(new Date())}
                onChange={(e) => setFrom(e.target.value)}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-xs font-medium text-muted-foreground" htmlFor="vp-pilot-to">
                To
              </label>
              <Input id="vp-pilot-to" type="date" className="w-40" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
            </div>
          </div>
          {!rangeValid && from && to && <p className="mb-3 text-xs text-destructive">From must be on or before To.</p>}

          {pilotQuery.isError ? (
            <p className="text-sm text-destructive">Failed to load the pilot report: {errorMessage(pilotQuery.error)}</p>
          ) : pilotQuery.isLoading || !pilotQuery.data ? (
            <SkeletonText lines={4} />
          ) : (
            <>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                <StatTile label="Trips in range" value={String(pilotQuery.data.trip_count)} />
                <StatTile label="Avg fare-accuracy variance" value={pct(pilotQuery.data.avg_fare_accuracy_variance_pct)} />
                <StatTile label="Device uptime (est.)" value={pct(pilotQuery.data.device_uptime_estimate_pct)} />
                <StatTile
                  label="Duress test activations"
                  value={pilotQuery.data.duress_test_activation_count == null ? "Not tracked" : String(pilotQuery.data.duress_test_activation_count)}
                />
                <StatTile label="Duress events (all)" value={String(pilotQuery.data.duress_event_count_total)} />
                <StatTile label="Flagged for review" value={String(pilotQuery.data.flagged_for_review_count)} />
              </div>
              <p className="mt-3 text-xs text-muted-foreground">
                {pilotQuery.data.from_date} to {pilotQuery.data.to_date}, generated {formatDateTimeShort(pilotQuery.data.generated_at)}
              </p>
            </>
          )}
        </div>
      )}
    </div>
  );
}
