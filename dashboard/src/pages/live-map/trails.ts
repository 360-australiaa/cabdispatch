import type { TrailPoint } from "./mapTypes";

/**
 * The selected vehicle's history trail: its GeoJSON shapes, its source/layer
 * ids, its speed ramp, and the feature builder that turns recorded positions
 * into something Mapbox can draw.
 *
 * Extracted verbatim from FleetMapCanvas.tsx during the Phase 0 file split.
 * Nothing here changed -- the constants and buildTrailFeatures are the same
 * code, moved so the trail's rules live in one place instead of interleaved
 * with marker DOM and map construction.
 */

/** The trail's own GeoJSON shapes. Declared here rather than reaching for the
 * `GeoJSON` namespace, which this project has no @types dependency for -- the same
 * choice TollGantryMap made for its PointFeatureCollection. */
export interface TrailFeature {
  type: "Feature";
  properties: Record<string, string | number>;
  geometry:
    | { type: "LineString"; coordinates: [number, number][] }
    | { type: "Point"; coordinates: [number, number] };
}

export interface TrailFeatureCollection {
  type: "FeatureCollection";
  features: TrailFeature[];
}

export const TRAIL_SOURCE_ID = "vehicle-trail";
export const TRAIL_CASING_LAYER_ID = "vehicle-trail-casing";
export const TRAIL_LINE_LAYER_ID = "vehicle-trail-line";
export const TRAIL_STOP_LAYER_ID = "vehicle-trail-stops";
export const TRAIL_CURSOR_LAYER_ID = "vehicle-trail-cursor";

// Speed ramp for the trail. Deliberately not the status palette (green/gold/grey
// already mean available/on-trip/offline on the markers) -- a trail segment's
// colour is about how fast the car was going, not what it was doing.
export const TRAIL_SLOW_COLOR = "#f97316";
export const TRAIL_MID_COLOR = "#a855f7";
export const TRAIL_FAST_COLOR = "#22d3ee";

/** A gap longer than this splits the trail into separate LineStrings rather than
 * drawing a straight line across it. Without the split, a tablet that was off
 * for six hours gets a confident line through the middle of the city it never
 * drove. */
const TRAIL_GAP_MS = 10 * 60 * 1000;

/** Sitting below this speed for at least [STOP_MIN_MS] earns a stop marker. */
const STOP_SPEED_KMH = 3;
const STOP_MIN_MS = 3 * 60 * 1000;

/**
 * The trail as GeoJSON: line segments, stop markers, and the scrubber ghost.
 *
 * Split on time gaps rather than drawn as one polyline -- see TRAIL_GAP_MS. The
 * `speed` property on each segment is the speed at its START point, which is what
 * the line-colour interpolation reads.
 */
export function buildTrailFeatures(trail: TrailPoint[], cursor: number | null): TrailFeatureCollection {
  const features: TrailFeature[] = [];
  if (trail.length >= 2) {
    for (let i = 0; i < trail.length - 1; i += 1) {
      const a = trail[i];
      const b = trail[i + 1];
      const gap = Date.parse(b.recordedAt) - Date.parse(a.recordedAt);
      if (!Number.isFinite(gap) || gap > TRAIL_GAP_MS) continue;
      features.push({
        type: "Feature",
        properties: { speed: a.speedKmh ?? 0 },
        geometry: { type: "LineString", coordinates: [[a.lng, a.lat], [b.lng, b.lat]] },
      });
    }
  }

  // Stops: runs of consecutive near-stationary points lasting long enough to be
  // a real stop rather than a traffic light.
  let runStart: number | null = null;
  for (let i = 0; i <= trail.length; i += 1) {
    const stationary = i < trail.length && (trail[i].speedKmh ?? 0) <= STOP_SPEED_KMH;
    if (stationary && runStart === null) runStart = i;
    if (!stationary && runStart !== null) {
      const from = trail[runStart];
      const to = trail[i - 1];
      const heldMs = Date.parse(to.recordedAt) - Date.parse(from.recordedAt);
      if (Number.isFinite(heldMs) && heldMs >= STOP_MIN_MS) {
        features.push({
          type: "Feature",
          properties: { kind: "stop", minutes: Math.round(heldMs / 60000) },
          geometry: { type: "Point", coordinates: [from.lng, from.lat] },
        });
      }
      runStart = null;
    }
  }

  if (cursor != null && trail[cursor]) {
    features.push({
      type: "Feature",
      properties: { kind: "cursor" },
      geometry: { type: "Point", coordinates: [trail[cursor].lng, trail[cursor].lat] },
    });
  }

  return { type: "FeatureCollection", features };
}
