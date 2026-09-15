import type {
  BlackoutReconciliationFlag,
  DeviceGpsBlackoutSegment,
  GpsBlackoutEvent,
  Trip,
  TripGpsTracePoint,
} from "@/hooks/useTrips";

/**
 * Pure readers over a trip's two GPS-blackout accounts, shared by the trip
 * page's blackout table (`tabs/BlackoutSection.tsx`) and the route map's
 * dashed blackout stretches (`TripRouteMap.tsx`).
 *
 * Two accounts, never merged: `gps_blackout_events` is what the SERVER
 * derived from the raw trace, `device_gps_blackout_segments` is what the
 * METER itself recorded (see both fields' docs in hooks/useTrips.ts). This
 * module flattens each into one row shape so a table can show them side by
 * side, labelled by source -- it does not pair them up, dedupe them, or
 * prefer one over the other. A disagreement is the thing worth seeing.
 */

export type BlackoutSource = "device" | "server";

/** One row of the blackout table, whichever account it came from. Every
 * figure the device did not report is null, never zero. */
export interface BlackoutRow {
  key: string;
  source: BlackoutSource;
  startedAt: string;
  endedAt: string;
  elapsedS: number;
  resolution: string | null;
  /** Device: `billed_distance_km`. Server: `matched_km` (null when no
   * corridor explained the gap and nothing extra was billed). */
  billedKm: string | null;
  estimatedKm: string | null;
  referenceKm: string | null;
  correctionKm: string | null;
  referenceSource: string | null;
  confidence: string | null;
  zuptCount: number | null;
  corridorRoadId: string | null;
  entryWasMoving: boolean | null;
  /** `[lng, lat]` in Mapbox order, null when the account carries no point. */
  entry: [number, number] | null;
  exit: [number, number] | null;
}

/** A stretch to draw dashed on the route map. */
export interface BlackoutStretch {
  key: string;
  source: BlackoutSource;
  from: [number, number];
  to: [number, number];
  label: string;
}

function elapsedSeconds(startIso: string, endIso: string): number {
  const start = new Date(startIso).getTime();
  const end = new Date(endIso).getTime();
  if (Number.isNaN(start) || Number.isNaN(end)) return 0;
  return Math.max(0, Math.round((end - start) / 1000));
}

function finiteLngLat(lat: unknown, lng: unknown): [number, number] | null {
  if (typeof lat !== "number" || typeof lng !== "number") return null;
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null;
  return [lng, lat];
}

export function deviceSegmentRow(segment: DeviceGpsBlackoutSegment, index: number): BlackoutRow {
  return {
    key: `device-${segment.client_uuid || index}-${segment.started_at}`,
    source: "device",
    startedAt: segment.started_at,
    endedAt: segment.ended_at,
    elapsedS: elapsedSeconds(segment.started_at, segment.ended_at),
    resolution: segment.resolution ?? null,
    billedKm: segment.billed_distance_km ?? null,
    estimatedKm: segment.estimated_distance_km ?? null,
    referenceKm: segment.reference_distance_km ?? null,
    correctionKm: segment.correction_km ?? null,
    referenceSource: segment.reference_source ?? null,
    confidence: segment.confidence ?? null,
    zuptCount: segment.zupt_count ?? null,
    corridorRoadId: segment.corridor_road_id ?? null,
    entryWasMoving: segment.entry_was_moving ?? null,
    entry: finiteLngLat(segment.entry_lat, segment.entry_lng),
    exit: finiteLngLat(segment.exit_lat, segment.exit_lng),
  };
}

export function serverEventRow(event: GpsBlackoutEvent, index: number): BlackoutRow {
  return {
    key: `server-${event.start}-${index}`,
    source: "server",
    startedAt: event.start,
    endedAt: event.end,
    elapsedS: event.elapsed_s,
    resolution: event.resolution ?? null,
    billedKm: event.matched_km,
    estimatedKm: null,
    referenceKm: null,
    correctionKm: null,
    referenceSource: null,
    confidence: null,
    zuptCount: null,
    corridorRoadId: null,
    entryWasMoving: null,
    entry: null,
    exit: null,
  };
}

/** Both accounts as rows: the device's first, then the server's, each in
 * time order. Empty when the trip carries neither. */
export function blackoutRows(trip: Pick<Trip, "gps_blackout_events" | "device_gps_blackout_segments">): BlackoutRow[] {
  const device = (trip.device_gps_blackout_segments ?? []).map(deviceSegmentRow);
  const server = (trip.gps_blackout_events ?? []).map(serverEventRow);
  const byStart = (a: BlackoutRow, b: BlackoutRow) => a.startedAt.localeCompare(b.startedAt);
  return [...device.sort(byStart), ...server.sort(byStart)];
}

/** How far apart (seconds) a trace point may be from a server event's
 * boundary and still stand in for it on the map. The server derives an event
 * from a gap between two consecutive trace points, so the boundary IS a
 * trace timestamp -- the tolerance only absorbs millisecond rounding. */
export const TRACE_MATCH_TOLERANCE_S = 5;

function nearestTracePoint(points: TripGpsTracePoint[], iso: string): TripGpsTracePoint | null {
  const target = new Date(iso).getTime();
  if (Number.isNaN(target) || points.length === 0) return null;
  let best: TripGpsTracePoint | null = null;
  let bestDelta = Infinity;
  for (const p of points) {
    const t = new Date(p.ts).getTime();
    if (Number.isNaN(t)) continue;
    const delta = Math.abs(t - target);
    if (delta < bestDelta) {
      bestDelta = delta;
      best = p;
    }
  }
  return best && bestDelta <= TRACE_MATCH_TOLERANCE_S * 1000 ? best : null;
}

/**
 * The stretches to draw dashed on the route map. A device segment carries
 * its own entry/exit fix. A server event carries only timestamps, so it is
 * located against the recorded trace: the point at its `start` and the point
 * at its `end`. An event with no matching trace points is simply not drawn
 * -- the table still lists it -- rather than placed by guesswork.
 */
export function blackoutStretches(
  trip: Pick<Trip, "gps_blackout_events" | "device_gps_blackout_segments">,
  trace: TripGpsTracePoint[] | null | undefined,
): BlackoutStretch[] {
  const stretches: BlackoutStretch[] = [];
  for (const row of blackoutRows(trip)) {
    let from = row.entry;
    let to = row.exit;
    if (row.source === "server" && trace && trace.length > 0) {
      const start = nearestTracePoint(trace, row.startedAt);
      const end = nearestTracePoint(trace, row.endedAt);
      from = start ? finiteLngLat(start.lat, start.lng) : null;
      to = end ? finiteLngLat(end.lat, end.lng) : null;
    }
    if (!from || !to) continue;
    stretches.push({
      key: row.key,
      source: row.source,
      from,
      to,
      label: `${row.source === "device" ? "Meter" : "Server"}: ${describeResolution(row.resolution)}, ${row.elapsedS}s`,
    });
  }
  return stretches;
}

/** Human label for a `BlackoutResolution` value. An unknown value is echoed
 * back rather than hidden -- see the type's own doc. */
export function describeResolution(resolution: string | null | undefined): string {
  switch (resolution) {
    case "NONE":
      return "No known road — nothing extra billed";
    case "CORRIDOR":
      return "Known corridor — real distance billed";
    case "STATIONARY":
      return "Stationary at entry — waiting only";
    case "INERTIAL":
      return "Inertial dead-reckoning";
    case "UNCALIBRATED":
      return "Inertial, uncalibrated — billed as ticked";
    case "STOPPED":
      return "Driver-paused";
    case null:
    case undefined:
    case "":
      return "Not labelled";
    default:
      return resolution;
  }
}

const FLAG_LABELS: Record<string, string> = {
  device_segment_missing_on_server: "The meter reported a blackout the server's trace does not show",
  server_gap_missing_on_device: "The server's trace has a gap the meter never reported",
  corridor_distance_mismatch: "Billed corridor distance disagrees with the registry corridor",
  inertial_correction_large: "Inertial estimate needed a large correction at reacquisition",
  stopped_seconds_mismatch: "Driver-paused seconds disagree between meter and server",
};

/** Human label for a reconciliation flag's `type`; unknown kinds are shown
 * as their raw type so a new backend check is never silently dropped. */
export function describeReconciliationFlag(flag: BlackoutReconciliationFlag): string {
  return FLAG_LABELS[flag.type] ?? flag.type;
}
