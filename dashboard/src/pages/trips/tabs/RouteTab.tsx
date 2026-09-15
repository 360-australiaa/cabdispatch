import { useMemo } from "react";
import { useTripGpsTraceQuery } from "@/hooks/useTrips";
import type { Trip } from "@/hooks/useTrips";
import { blackoutStretches } from "../blackouts";
import { TripRouteMap } from "../TripRouteMap";
import { BlackoutSection } from "./BlackoutSection";

export interface RouteTabProps {
  trip: Trip;
}

/**
 * Trip page Route tab (dashboard command-centre plan §7): `TripRouteMap` +
 * `GET /v1/trips/{id}/gps-trace`, same fetch `TripDetailModal` used, only
 * enabled while this tab is actually mounted (the query's own `enabled`
 * flag, not a page-wide fetch).
 *
 * GPS blackouts (admin-panel plan §2) are drawn dashed on the map -- a
 * device segment between its own entry and exit fix, a server event between
 * the trace points at its boundaries (see `blackouts.ts`) -- and listed
 * under it by `BlackoutSection`, the same table the Fare tab shows.
 *
 * Speed colouring / stops: `TripRouteMap` draws a single solid-colour line
 * for the real trace (or a dashed straight-line stand-in) -- it has no
 * speed-graded rendering today, and the trip has no separate "stop" concept
 * beyond its pickup/drop-off points (no intermediate-stop field on `Trip`
 * or the gps-trace response). Both are left for a future pass rather than
 * fabricated here; the caption under the map already says exactly what is
 * and isn't real about what's drawn.
 */
export function RouteTab({ trip }: RouteTabProps) {
  const gpsTraceQuery = useTripGpsTraceQuery(trip.id, true);
  const trace = gpsTraceQuery.data?.points;
  const blackouts = useMemo(() => blackoutStretches(trip, trace), [trip, trace]);

  return (
    <div className="flex flex-col gap-2">
      <TripRouteMap
        startLat={trip.start_lat}
        startLng={trip.start_lng}
        endLat={trip.end_lat}
        endLng={trip.end_lng}
        trace={trace}
        blackouts={blackouts}
        className="h-96 w-full rounded-md border border-border"
      />
      {gpsTraceQuery.data && (
        <p className="text-xs text-muted-foreground">
          {gpsTraceQuery.data.point_count} recorded GPS point{gpsTraceQuery.data.point_count === 1 ? "" : "s"}.
        </p>
      )}
      <BlackoutSection trip={trip} />
    </div>
  );
}
