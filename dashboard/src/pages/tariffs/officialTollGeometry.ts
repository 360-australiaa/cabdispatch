/**
 * The NSW Government's own Sydney Motorways Toll Calculator publishes the real
 * polyline of every motorway segment it knows about (a "point graph" of named
 * toll/interchange points, each pair of adjacent points joined by a Google-encoded
 * polyline string). This module decodes that encoding and looks up, for a given
 * `toll_road_id` this app already tracks, the real official geometry generated
 * from it -- so `TollGantryMap.tsx` can draw the ACTUAL road shape instead of
 * `tollCorridor.ts`'s nearest-neighbour reconstruction wherever the government's
 * own data covers a road.
 *
 * `OFFICIAL_TOLL_GEOMETRY` (officialTollGeometry.data.ts) is a generated,
 * committed data file -- not fetched at runtime -- built once from two source
 * exports (polylines.json: 693 rows of {start_point, end_point, direction, type,
 * polyline}; tollpoints_data.json: 118 rows of {ref, motorway_name, latitude,
 * longitude, closest_cw, closest_ccw, ...}) by:
 *
 *   1. Mapping each tollpoints_data.json row's `motorway_name` to one of this
 *      app's own toll_road_id values by name (see NAME_TO_ROAD in the generating
 *      script) -- a name with no obvious match (Gore Hill Fwy, Southern Cross
 *      Drive, Western Distributor, Bradfield Hwy, Cahill Expressway, General
 *      Holmes Drive -- all untolled feeders with no toll_road_id of their own)
 *      is left unmapped rather than guessed. M12, Rozelle Interchange and Iron
 *      Cove Link have NO rows in the source at all -- this app's registry
 *      already prices those `toll_free`/`unpriced`, so no geometry is needed.
 *   2. Walking each ref's `closest_cw` adjacency (the source's own idea of
 *      "the next point clockwise along this road") to find the matching
 *      point-to-point polyline (preferring type `TRAVERSE`, the mainline path,
 *      over `ENTRY`/`EXIT` ramp-specific alternates) and decoding it.
 *   3. Stitching those decoded edges end-to-end into the longest chains they
 *      form. A road with ramps/forks or a hop the source simply does not cover
 *      comes out as more than one segment rather than one fabricated line --
 *      every point in every segment is real decoded geometry, never invented.
 *
 * Re-run that script (kept alongside the source export, not in this repo) to
 * regenerate officialTollGeometry.data.ts from a refreshed source; nothing here
 * calls out to the network.
 */
import { OFFICIAL_TOLL_GEOMETRY } from "./officialTollGeometry.data";
import type { RoadPoint } from "./tollCorridor";

/**
 * Decodes a Google encoded polyline string into `[latitude, longitude]` pairs.
 * Standard algorithm (the same one Google Maps/Mapbox/etc. use for encoded
 * polylines and paths) -- no dependency pulled in for this, it's ~15 lines.
 *
 * Confirmed against a known sample from the source data this session:
 * `decodePolyline("|n\`mEuw|x[yQ\`E")` -> `[[-33.75871, 151.04907], [-33.7557, 151.0481]]`.
 */
export function decodePolyline(encoded: string): [number, number][] {
  let index = 0;
  let lat = 0;
  let lng = 0;
  const coordinates: [number, number][] = [];
  const len = encoded.length;

  while (index < len) {
    let shift = 0;
    let result = 0;
    let byte: number;
    do {
      byte = encoded.charCodeAt(index++) - 63;
      result |= (byte & 0x1f) << shift;
      shift += 5;
    } while (byte >= 0x20);
    lat += result & 1 ? ~(result >> 1) : result >> 1;

    shift = 0;
    result = 0;
    do {
      byte = encoded.charCodeAt(index++) - 63;
      result |= (byte & 0x1f) << shift;
      shift += 5;
    } while (byte >= 0x20);
    lng += result & 1 ? ~(result >> 1) : result >> 1;

    coordinates.push([lat * 1e-5, lng * 1e-5]);
  }
  return coordinates;
}

/**
 * Real official geometry for a road, as one or more ordered point segments, or
 * `null` if the government source doesn't cover this road at all (the caller
 * falls back to `orderAlongRoad` reconstruction in that case -- see
 * `tollCorridor.ts`'s `resolveCorridorSegments`).
 */
export function officialGeometryFor(tollRoadId: string): readonly RoadPoint[][] | null {
  return OFFICIAL_TOLL_GEOMETRY[tollRoadId] ?? null;
}

/** Every toll_road_id the official source has real geometry for. Exported for
 * the Tariff Studio's own coverage reporting/tests, not just internal use. */
export function officialGeometryRoadIds(): string[] {
  return Object.keys(OFFICIAL_TOLL_GEOMETRY);
}
