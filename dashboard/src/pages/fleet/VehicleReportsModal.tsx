import { useState } from "react";
import { Card, CardContent, Input, Modal } from "@/components/ui";
import { defaultReportRange, formatAud, toDateInputValue } from "@/hooks/useReports";
import { useVehicleLifetimeTotals, useVehiclePilotReport } from "./api";
import { errorMessage, formatDateTime } from "./format";
import type { Vehicle } from "./types";

type ReportsTab = "lifetime" | "pilot";

function stat(value: string | null | undefined) {
  return value == null ? "—" : formatAud(value);
}

function pct(value: string | null | undefined) {
  return value == null ? "—" : value + "%";
}

export function VehicleReportsModal({
  vehicle,
  onClose,
}: {
  vehicle: Vehicle | null;
  onClose: () => void;
}) {
  const [tab, setTab] = useState<ReportsTab>("lifetime");
  const initial = defaultReportRange();
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);

  const rangeValid = Boolean(from && to) && from <= to;
  const range = rangeValid ? { from, to } : { from: "", to: "" };

  const totalsQuery = useVehicleLifetimeTotals(vehicle ? vehicle.id : null);
  const pilotQuery = useVehiclePilotReport(vehicle ? vehicle.id : null, range);

  return (
    <Modal
      open={vehicle !== null}
      onClose={onClose}
      title={vehicle ? "Operations reports — " + vehicle.rego : "Operations reports"}
      className="max-w-2xl"
    >
      <div className="mb-4 flex gap-1 border-b border-border">
        <button
          type="button"
          onClick={() => setTab("lifetime")}
          className={
            "border-b-2 px-3 py-2 text-sm font-medium transition-colors " +
            (tab === "lifetime"
              ? "border-brand-primary text-brand-primary"
              : "border-transparent text-muted-foreground hover:text-foreground")
          }
        >
          Lifetime totals
        </button>
        <button
          type="button"
          onClick={() => setTab("pilot")}
          className={
            "border-b-2 px-3 py-2 text-sm font-medium transition-colors " +
            (tab === "pilot"
              ? "border-brand-primary text-brand-primary"
              : "border-transparent text-muted-foreground hover:text-foreground")
          }
        >
          Pilot report
        </button>
      </div>

      {tab === "lifetime" && (
        <div>
          <p className="mb-3 text-xs text-muted-foreground">
            All-time cumulative totals across every closed trip ever recorded for this
            vehicle, mirroring the register a physical taxi meter keeps.
          </p>
          {totalsQuery.isError ? (
            <p className="text-sm text-destructive">
              Failed to load lifetime totals: {errorMessage(totalsQuery.error)}
            </p>
          ) : totalsQuery.isLoading || !totalsQuery.data ? (
            <p className="text-sm text-muted-foreground">Loading…</p>
          ) : (
            <>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                <StatTile label="Trips" value={String(totalsQuery.data.trip_count)} />
                <StatTile label="Total fares" value={stat(totalsQuery.data.total_fares)} />
                <StatTile label="Total PSL" value={stat(totalsQuery.data.total_psl)} />
                <StatTile label="Total tolls" value={stat(totalsQuery.data.total_tolls)} />
                <StatTile
                  label="Total tips"
                  value={
                    totalsQuery.data.total_tips == null
                      ? "Not tracked"
                      : stat(totalsQuery.data.total_tips)
                  }
                />
                <StatTile label="Total km" value={totalsQuery.data.total_km + " km"} />
              </div>
              <p className="mt-3 text-xs text-muted-foreground">
                Generated {formatDateTime(totalsQuery.data.generated_at)}
              </p>
            </>
          )}
        </div>
      )}

      {tab === "pilot" && (
        <div>
          <div className="mb-3 flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1.5">
              <label className="text-xs font-medium text-muted-foreground" htmlFor="pilot-from">
                From
              </label>
              <Input
                id="pilot-from"
                type="date"
                className="w-40"
                value={from}
                max={to || toDateInputValue(new Date())}
                onChange={(e) => setFrom(e.target.value)}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-xs font-medium text-muted-foreground" htmlFor="pilot-to">
                To
              </label>
              <Input
                id="pilot-to"
                type="date"
                className="w-40"
                value={to}
                min={from || undefined}
                onChange={(e) => setTo(e.target.value)}
              />
            </div>
          </div>
          {!rangeValid && from && to && (
            <p className="mb-3 text-xs text-destructive">From must be on or before To.</p>
          )}

          {pilotQuery.isError ? (
            <p className="text-sm text-destructive">
              Failed to load the pilot report: {errorMessage(pilotQuery.error)}
            </p>
          ) : pilotQuery.isLoading || !pilotQuery.data ? (
            <p className="text-sm text-muted-foreground">Loading…</p>
          ) : (
            <>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                <StatTile label="Trips in range" value={String(pilotQuery.data.trip_count)} />
                <StatTile
                  label="Avg fare-accuracy variance"
                  value={pct(pilotQuery.data.avg_fare_accuracy_variance_pct)}
                />
                <StatTile
                  label="Device uptime (est.)"
                  value={pct(pilotQuery.data.device_uptime_estimate_pct)}
                />
                <StatTile
                  label="Duress test activations"
                  value={
                    pilotQuery.data.duress_test_activation_count == null
                      ? "Not tracked"
                      : String(pilotQuery.data.duress_test_activation_count)
                  }
                />
                <StatTile
                  label="Duress events (all)"
                  value={String(pilotQuery.data.duress_event_count_total)}
                />
                <StatTile
                  label="Flagged for review"
                  value={String(pilotQuery.data.flagged_for_review_count)}
                />
              </div>
              <p className="mt-3 text-xs text-muted-foreground">
                {pilotQuery.data.from_date} to {pilotQuery.data.to_date}, generated{" "}
                {formatDateTime(pilotQuery.data.generated_at)}
              </p>
            </>
          )}
        </div>
      )}
    </Modal>
  );
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
