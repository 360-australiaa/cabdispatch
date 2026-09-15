import { useMemo } from "react";
import { AlertTriangle, CheckCircle2 } from "lucide-react";
import { Badge, EmptyState, ErrorBanner, Skeleton } from "@/components/ui";
import { errorMessage, formatMoney, formatPercent } from "@/lib/format";
import { useGeofencesQuery } from "@/hooks/useGeofences";
import { useTollRoadsQuery } from "@/hooks/useTollRoads";
import type { Trip } from "@/hooks/useTrips";
import { BlackoutSection } from "./BlackoutSection";

/** The server's own tolerance on the device-vs-recomputed check (backend
 * `compute_variance_pct` / the sync path's 1% rule). Shown, not enforced. */
const VARIANCE_THRESHOLD_LABEL = "1.00%";

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
 * The "Fare check" panel (admin-panel plan §2) puts the two totals the
 * variance check compares next to each other, with the figure, the
 * threshold and the flag reason -- previously the page said only "Failed"
 * or "Flagged". The device's own total is NOT on `TripRead` today (see
 * `Trip.device_total`'s doc in hooks/useTrips.ts), so that row says "not
 * stored" rather than back-computing a figure the unsigned variance cannot
 * give.
 *
 * The GPS-blackout audit trail (both accounts, inertial figures, the
 * reconciliation flags) is `BlackoutSection`, rendered here and on the
 * Route tab under the map that draws the same stretches dashed.
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

  const showVarianceWarning = !trip.max_fare_check_passed;
  const reconciliationFlags = trip.blackout_reconciliation ?? [];
  const unpricedRoadIds = trip.unpriced_toll_road_ids ?? [];

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
              {trip.variance_pct != null ? ` (threshold ${VARIANCE_THRESHOLD_LABEL})` : ""}. Review before
              issuing a receipt.
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
        <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Fare check</p>
        <FareRow label="Server total (fare of record)" value={formatMoney(trip.total)} />
        <FareRow
          label="Device total"
          value={trip.device_total != null ? formatMoney(trip.device_total) : "Not stored on this trip"}
          muted={trip.device_total == null}
        />
        <FareRow
          label="Variance"
          value={
            trip.variance_pct != null
              ? `${formatPercent(trip.variance_pct)} (threshold ${VARIANCE_THRESHOLD_LABEL})`
              : "n/a — no device total was checked"
          }
          muted={trip.variance_pct == null}
        />
        <div className="flex items-center justify-between py-1 text-sm">
          <span className="text-foreground">Result</span>
          {trip.status !== "closed" ? (
            <span className="text-muted-foreground">Pending — trip still open</span>
          ) : trip.max_fare_check_passed ? (
            <Badge variant="success">Passed</Badge>
          ) : (
            <Badge variant="destructive">Failed</Badge>
          )}
        </div>
        <div className="flex items-start justify-between gap-4 py-1 text-sm">
          <span className="shrink-0 text-foreground">Review flag</span>
          <span className={trip.flagged_for_review ? "text-right text-destructive" : "text-right text-muted-foreground"}>
            {trip.flagged_for_review ? "Flagged for review" : "Not flagged"}
            {reconciliationFlags.length > 0 &&
              ` · ${reconciliationFlags.length} blackout reconciliation flag${reconciliationFlags.length === 1 ? "" : "s"}`}
          </span>
        </div>
        {trip.review_notes && (
          <div className="mt-1 rounded-md border border-border bg-muted/40 p-2 text-xs text-foreground">
            <span className="font-medium text-muted-foreground">Reason / notes: </span>
            <span className="whitespace-pre-wrap">{trip.review_notes}</span>
          </div>
        )}
      </div>

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
        {/* Roads the trip genuinely crossed that the registry holds no price
            for (the Rozelle Interchange case): shown by name, never with a
            guessed amount, so a dispatcher knows a manual toll may be missing. */}
        {unpricedRoadIds.length > 0 && (
          <div className="mt-3 rounded-md border border-warning/60 bg-warning/10 p-2 text-xs">
            <p className="font-semibold text-warning">Crossed but not priced — a manual toll may be missing</p>
            <ul className="mt-1 list-disc pl-4 text-foreground">
              {unpricedRoadIds.map((id) => (
                <li key={id}>
                  {roadNameById.get(id) ?? id}
                  {!roadNameById.has(id) && <span className="text-muted-foreground"> (id not in the registry)</span>}
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>

      <BlackoutSection trip={trip} roadNameById={roadNameById} />
    </div>
  );
}
