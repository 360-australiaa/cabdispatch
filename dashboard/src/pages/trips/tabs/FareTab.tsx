import { useMemo } from "react";
import { AlertTriangle, CheckCircle2 } from "lucide-react";
import { EmptyState, ErrorBanner, Skeleton } from "@/components/ui";
import { errorMessage, formatDurationSeconds, formatMoney, formatPercent, formatTimeShort } from "@/lib/format";
import { useGeofencesQuery } from "@/hooks/useGeofences";
import { useTollRoadsQuery } from "@/hooks/useTollRoads";
import type { Trip } from "@/hooks/useTrips";

export interface FareTabProps {
  trip: Trip;
}

function FareRow({ label, value, muted }: { label: string; value: string; muted?: boolean }) {
  return (
    <div className="flex items-center justify-between py-1 text-sm">
      <span className={muted ? "text-muted-foreground" : "text-foreground"}>{label}</span>
      <span className={muted ? "text-muted-foreground" : "font-medium text-foreground"}>{value}</span>
    </div>
  );
}

/**
 * Trip page Fare tab (dashboard command-centre plan §7): the same
 * flag-fall/distance/waiting/peak/tolls/PSL/extras/subtotal/surcharge/
 * total/GST/variance breakdown `TripDetailModal` already showed, plus (new)
 * tolls itemised by road and an airport-fee line when the trip's
 * `auto_tolled_roads`/`auto_tolls_applied` fields carry them.
 *
 * The road/airport breakdown reuses the exact reading the Vehicle page's
 * Tolls tab worked out (`pages/vehicles/tabs/TollsTab.tsx`) rather than
 * re-deriving it: `auto_tolled_roads` is an exact {road id: amount} map,
 * `auto_tolls_applied` is a list of geofence ids (ad hoc toll circles AND
 * the airport pickup fee share this mechanism) cross-referenced against
 * `GET /v1/geofences` for its `kind`/`toll_amount`. Whatever remains of
 * `trip.tolls` after subtracting both is real money the API cannot
 * attribute to a road -- shown as its own honest "Other / unitemised" line,
 * never folded silently into a road it may not belong to.
 *
 * Also renders `trip.gps_blackout_events` (only when the trip actually has
 * any -- the overwhelming majority don't) so a disputed tunnel fare has an
 * answer on this same page: exactly when the meter lost GPS, for how long,
 * and whether a known toll-road corridor explained the gap (real distance
 * billed) or not (nothing extra billed for it). See
 * backend/app/models/trips.py::Trip.gps_blackout_events's own doc comment.
 */
export function FareTab({ trip }: FareTabProps) {
  const tollRoadsQuery = useTollRoadsQuery();
  const geofencesQuery = useGeofencesQuery({ limit: 200 });

  const roadNameById = useMemo(() => {
    const map = new Map<string, string>();
    for (const road of tollRoadsQuery.data ?? []) {
      map.set(road.id, road.name);
      for (const point of road.toll_points) map.set(point.id, `${road.name} — ${point.name}`);
    }
    return map;
  }, [tollRoadsQuery.data]);

  const geofenceById = useMemo(() => {
    const map = new Map<string, { name: string; kind: string; toll_amount: string | null }>();
    for (const g of geofencesQuery.data?.items ?? []) map.set(g.id, g);
    return map;
  }, [geofencesQuery.data]);

  const tollBreakdown = useMemo(() => {
    const roads: Array<{ name: string; amount: number }> = [];
    let airportTotal = 0;
    let attributed = 0;

    for (const [roadId, amountStr] of Object.entries(trip.auto_tolled_roads ?? {})) {
      const amount = Number(amountStr) || 0;
      roads.push({ name: roadNameById.get(roadId) ?? roadId, amount });
      attributed += amount;
    }
    for (const geofenceId of trip.auto_tolls_applied ?? []) {
      const g = geofenceById.get(geofenceId);
      if (!g || g.toll_amount == null) continue;
      const amount = Number(g.toll_amount) || 0;
      if (g.kind === "airport") {
        airportTotal += amount;
      } else {
        roads.push({ name: g.name, amount });
      }
      attributed += amount;
    }

    const tripTolls = Number(trip.tolls) || 0;
    const other = Math.max(0, tripTolls - attributed);
    return { roads, airportTotal, other };
  }, [trip.auto_tolled_roads, trip.auto_tolls_applied, trip.tolls, roadNameById, geofenceById]);

  const variancePctNum = trip.variance_pct != null ? Number(trip.variance_pct) : null;
  const showVarianceWarning = !trip.max_fare_check_passed;

  const registryError = tollRoadsQuery.isError || geofencesQuery.isError;

  return (
    <div className="flex flex-col gap-4">
      {showVarianceWarning && (
        <div className="flex items-start gap-2 rounded-md border border-destructive bg-destructive/10 px-3 py-2 text-sm text-destructive">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <div>
            <p className="font-semibold">Max fare check failed</p>
            <p>
              Device-reported total differs from the recomputed fare by {formatPercent(trip.variance_pct)}
              {variancePctNum != null ? " (threshold 1.00%)" : ""}. Review before issuing a receipt.
            </p>
          </div>
        </div>
      )}
      {!showVarianceWarning && trip.variance_pct != null && (
        <div className="flex items-center gap-2 rounded-md border border-success bg-success/10 px-3 py-2 text-sm text-success">
          <CheckCircle2 className="h-4 w-4 shrink-0" />
          <span>Max fare check passed — variance {formatPercent(trip.variance_pct)}.</span>
        </div>
      )}

      <div className="rounded-lg border border-border p-3">
        <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          Fare breakdown
        </p>
        <FareRow label="Flag fall" value={formatMoney(trip.flag_fall)} />
        <FareRow label="Distance" value={formatMoney(trip.dist_amount)} />
        <FareRow label="Waiting" value={formatMoney(trip.wait_amount)} />
        <FareRow label="Peak" value={formatMoney(trip.peak_amount)} />
        <FareRow label="Tolls" value={formatMoney(trip.tolls)} />
        <FareRow label="PSL" value={formatMoney(trip.psl)} />
        <FareRow label="Extras" value={formatMoney(trip.extras)} />
        <FareRow label="Subtotal" value={formatMoney(trip.subtotal)} muted />
        <FareRow label="Surcharge" value={formatMoney(trip.surcharge)} />
        <div className="my-1 border-t border-border" />
        <FareRow label="Total" value={formatMoney(trip.total)} />
        <FareRow label="GST component" value={formatMoney(trip.gst_component)} muted />
        <FareRow
          label="Variance vs device total"
          value={trip.variance_pct != null ? formatPercent(trip.variance_pct) : "n/a"}
          muted
        />
      </div>

      <div className="rounded-lg border border-border p-3">
        <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          Tolls itemised by road
        </p>
        {registryError ? (
          <ErrorBanner
            message={`Failed to load the toll-road/geofence registry: ${errorMessage(
              tollRoadsQuery.error ?? geofencesQuery.error,
            )}`}
          />
        ) : tollRoadsQuery.isLoading || geofencesQuery.isLoading ? (
          <Skeleton className="h-16 w-full" />
        ) : Number(trip.tolls) === 0 ? (
          <p className="text-sm text-muted-foreground">No tolls or airport fees on this trip.</p>
        ) : (
          <div className="flex flex-col gap-1">
            {tollBreakdown.roads.length === 0 && tollBreakdown.airportTotal === 0 ? (
              <EmptyState
                title="No auto-detected tolls"
                description="This trip's tolls total is not broken down by road -- likely entered manually or synced from the tablet's own offline ledger."
              />
            ) : (
              tollBreakdown.roads.map((r) => <FareRow key={r.name} label={r.name} value={formatMoney(r.amount)} />)
            )}
            {tollBreakdown.airportTotal > 0 && (
              <FareRow label="Airport access fee" value={formatMoney(tollBreakdown.airportTotal)} />
            )}
            {tollBreakdown.other > 0 && (
              <FareRow label="Other / unitemised" value={formatMoney(tollBreakdown.other)} muted />
            )}
          </div>
        )}
      </div>

      {(trip.gps_blackout_events?.length ?? 0) > 0 && (
        <div className="rounded-lg border border-border p-3">
          <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            GPS blackouts
          </p>
          <div className="flex flex-col gap-2">
            {trip.gps_blackout_events!.map((event, i) => (
              <div key={`${event.start}-${i}`} className="flex items-center justify-between text-sm">
                <span className="text-foreground">
                  {formatTimeShort(event.start)} – {formatTimeShort(event.end)}
                  <span className="text-muted-foreground"> ({formatDurationSeconds(event.elapsed_s)})</span>
                </span>
                <span className={event.matched_km != null ? "font-medium text-foreground" : "text-muted-foreground"}>
                  {event.matched_km != null
                    ? `${Number(event.matched_km).toFixed(2)} km via known corridor`
                    : "No known corridor — billed $0 for this gap"}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
