import { useMemo } from "react";
import { Card, CardContent, EmptyState, ErrorBanner, Skeleton, Table, type TableColumn } from "@/components/ui";
import { errorMessage, formatMoney } from "@/lib/format";
import { useTripsQuery } from "@/hooks/useTrips";
import { useGeofencesQuery } from "@/hooks/useGeofences";
import { useTollRoadsQuery } from "@/hooks/useTollRoads";

export interface TollsTabProps {
  vehicleId: string;
}

interface RoadTotal {
  name: string;
  amount: number;
  tripCount: number;
}

/**
 * Plan §5.6 -- tolls charged on this vehicle's trips, grouped by road, with
 * airport access fees listed separately where the data actually supports
 * telling them apart.
 *
 * DATA-AVAILABILITY FINDING (see this pass's report): a trip's `tolls`
 * field is one combined dollar amount -- there is no per-trip field that
 * separately itemises "toll" vs "airport fee". Two things ARE real,
 * attributable breakdowns, though, and this tab uses both instead of
 * inventing a split the API doesn't hand over directly:
 *
 *  - `Trip.auto_tolled_roads` (real NSW toll-registry charges) is a
 *    {road/point id: dollar amount} map -- an exact per-road figure,
 *    resolved to a name via `GET /v1/toll-roads`.
 *  - `Trip.auto_tolls_applied` (ad hoc-geofence charges: tenant toll
 *    circles AND the Sydney Airport pickup fee, both use the same
 *    mechanism) is a list of geofence ids with no amount of its own;
 *    cross-referenced against `GET /v1/geofences` (which carries `kind`
 *    and `toll_amount`) it resolves to an exact dollar figure AND tells
 *    an airport-kind zone apart from an ad hoc toll circle.
 *
 * Whatever is left of a trip's `tolls` after subtracting both of the above
 * is real money that cannot be attributed to a specific road or the
 * airport fee from what the API returns today -- most commonly a trip
 * synced from the tablet's own offline ledger, which carries no breakdown
 * at all (see `apply_airport_access_fee_at_start`'s docstring in
 * `app/services/trips.py`). That remainder is shown as its own honestly-
 * labelled "Other / unitemised" line rather than folded silently into a
 * road it may not belong to.
 */
export function TollsTab({ vehicleId }: TollsTabProps) {
  const tripsQuery = useTripsQuery({ vehicle_id: vehicleId, limit: 100 });
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

  const summary = useMemo(() => {
    const roadTotals = new Map<string, RoadTotal>();
    let airportTotal = 0;
    let airportTripCount = 0;
    let adHocTollTotal = 0;
    let otherTotal = 0;
    let grandTotal = 0;

    const bump = (name: string, amount: number) => {
      const existing = roadTotals.get(name);
      if (existing) {
        existing.amount += amount;
        existing.tripCount += 1;
      } else {
        roadTotals.set(name, { name, amount, tripCount: 1 });
      }
    };

    for (const trip of tripsQuery.data?.items ?? []) {
      const tripTolls = Number(trip.tolls) || 0;
      grandTotal += tripTolls;
      let attributed = 0;
      let tripHasAirport = false;

      for (const [roadId, amountStr] of Object.entries(trip.auto_tolled_roads ?? {})) {
        const amount = Number(amountStr) || 0;
        bump(roadNameById.get(roadId) ?? roadId, amount);
        attributed += amount;
      }

      for (const geofenceId of trip.auto_tolls_applied ?? []) {
        const g = geofenceById.get(geofenceId);
        if (!g || g.toll_amount == null) continue;
        const amount = Number(g.toll_amount) || 0;
        if (g.kind === "airport") {
          airportTotal += amount;
          tripHasAirport = true;
        } else {
          adHocTollTotal += amount;
          bump(g.name, amount);
        }
        attributed += amount;
      }
      if (tripHasAirport) airportTripCount += 1;

      otherTotal += Math.max(0, tripTolls - attributed);
    }

    return {
      roadTotals: Array.from(roadTotals.values()).sort((a, b) => b.amount - a.amount),
      airportTotal,
      airportTripCount,
      adHocTollTotal,
      otherTotal,
      grandTotal,
    };
  }, [tripsQuery.data, roadNameById, geofenceById]);

  if (tripsQuery.isLoading || tollRoadsQuery.isLoading || geofencesQuery.isLoading) {
    return (
      <div className="flex flex-col gap-2">
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-40 w-full" />
      </div>
    );
  }

  if (tripsQuery.isError) {
    return <ErrorBanner message={`Failed to load trips for this vehicle: ${errorMessage(tripsQuery.error)}`} />;
  }
  if (tollRoadsQuery.isError || geofencesQuery.isError) {
    return (
      <ErrorBanner
        message={`Failed to load the toll-road/geofence registry: ${errorMessage(tollRoadsQuery.error ?? geofencesQuery.error)}`}
      />
    );
  }

  if ((tripsQuery.data?.items.length ?? 0) === 0) {
    return <EmptyState title="No trips yet" description="No trips have been recorded for this vehicle, so there are no tolls to show." />;
  }

  const columns: TableColumn<RoadTotal>[] = [
    { key: "name", header: "Road / zone" },
    { key: "tripCount", header: "Trips", className: "text-right" },
    { key: "amount", header: "Total", className: "text-right", render: (r) => formatMoney(r.amount) },
  ];

  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Card>
          <CardContent className="pt-4">
            <p className="text-xs text-muted-foreground">Tolls & fees total</p>
            <p className="mt-1 text-lg font-semibold">{formatMoney(summary.grandTotal)}</p>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-4">
            <p className="text-xs text-muted-foreground">Airport access fees</p>
            <p className="mt-1 text-lg font-semibold">{formatMoney(summary.airportTotal)}</p>
            <p className="mt-1 text-xs text-muted-foreground">
              {summary.airportTripCount} trip{summary.airportTripCount === 1 ? "" : "s"}
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-4">
            <p className="text-xs text-muted-foreground">Ad hoc toll zones</p>
            <p className="mt-1 text-lg font-semibold">{formatMoney(summary.adHocTollTotal)}</p>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-4">
            <p className="text-xs text-muted-foreground">Other / unitemised</p>
            <p className="mt-1 text-lg font-semibold">{formatMoney(summary.otherTotal)}</p>
          </CardContent>
        </Card>
      </div>

      <p className="text-xs text-muted-foreground">
        "Other / unitemised" is real money that shows up in a trip's tolls total but that the API
        does not break down by road -- most often a toll entered manually or synced from the
        tablet's own offline ledger. It is not further split here rather than guessing which road it
        belongs to.
      </p>

      <div className="rounded-lg border border-border p-3">
        <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Tolls by road / zone</p>
        {summary.roadTotals.length === 0 ? (
          <EmptyState
            title="No auto-detected tolls"
            description="No auto-detected toll-road or toll-zone charges on this vehicle's trips -- see the totals above for the combined figure."
          />
        ) : (
          <Table columns={columns} data={summary.roadTotals} rowKey={(r) => r.name} pageSize={10} label="Tolls grouped by road" />
        )}
      </div>
    </div>
  );
}
