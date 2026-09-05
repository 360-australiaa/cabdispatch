import { useEffect, useRef, useState } from "react";
import { haversineMeters } from "./utils";

/**
 * A vehicle that qualifies for a drawn live route right now -- see
 * FleetMapCanvas's own filter (on-trip via `isBusyStatus`, both
 * `planned_dest_lat`/`lng` non-null, and a real current lat/lng, i.e. already
 * a member of `plotted`). Kept as its own narrow shape (rather than passing
 * `PlottedVehicle` straight through) so this hook's dependency array only
 * churns on the four numbers that actually matter to it, not on every other
 * field (battery/network/idleInfo/...) that changes on the same 5s tick.
 */
export interface RoutableVehicle {
  id: string;
  lat: number;
  lng: number;
  destLat: number;
  destLng: number;
}

/**
 * One vehicle's drawn route -- either a real routed geometry from Mapbox
 * Directions, or the straight current-position-to-destination line used
 * before the fetch resolves (or forever, if it fails / no token is
 * configured). `points` are `[lat, lng]` pairs, this app's usual coordinate
 * order (matches `RoutePoint` in the Android app's own MapboxDirections.kt --
 * flipped from Mapbox's own lng,lat convention at the same boundary that
 * fetch happens, so nothing downstream has to think about axis order).
 */
export interface VehicleRouteState {
  points: Array<[number, number]>;
  /** True while `points` is the straight-line stand-in rather than a real
   * routed geometry -- lets a renderer style the two differently (dashed vs
   * solid, see FleetMapCanvas) rather than silently presenting a guess as if
   * it were the actual road route. */
  isFallback: boolean;
}

// How far either endpoint (current position or destination) may drift from
// the pair a cached route was fetched for before it's considered stale enough
// to re-fetch. A default chosen for this task (the dashboard task brief's own
// suggested figure), not a decided product policy -- same "flag it, don't
// silently canonicalize it" convention as IDLE_RADIUS_M/IDLE_WINDOW_MS in
// utils.ts (see docs/DURESS_DEVICE_INTEGRATION.md sec 8). Large enough that
// GPS jitter and the vehicle's own forward progress along an unchanged route
// don't trigger a re-fetch on every 5s position tick (see
// LivePositionHeartbeat.kt's cadence) -- Directions is a metered, rate-limited
// API, so re-fetching on every tick would be both wasteful and risk
// rate-limiting the token every other map feature also depends on.
const ROUTE_REFETCH_THRESHOLD_M = 100;

// Same public/publishable token FleetMapCanvas already uses for the map style
// itself (see that file's own doc comment) -- Directions is a plain
// authenticated HTTPS GET the `pk.*` token is designed for, same posture as
// the Android app's MapboxDirections.kt gateway (that file's doc comment
// explains why the *Navigation SDK* isn't usable here; the plain Directions
// REST endpoint has no such restriction on either platform).
const MAPBOX_TOKEN = import.meta.env.VITE_MAPBOX_TOKEN;
const DIRECTIONS_BASE_URL = "https://api.mapbox.com/directions/v5/mapbox/driving/";

interface CacheEntry {
  fromLat: number;
  fromLng: number;
  toLat: number;
  toLng: number;
  state: VehicleRouteState;
}

function snapshotStates(cache: Map<string, CacheEntry>): Map<string, VehicleRouteState> {
  const out = new Map<string, VehicleRouteState>();
  for (const [id, entry] of cache) out.set(id, entry.state);
  return out;
}

/**
 * Fetches a real driving route between two points via the Mapbox Directions
 * API (`geometries=geojson` -- simpler to consume client-side than the
 * encoded-polyline format the Android app's MapboxDirections.kt decodes by
 * hand, and there's no equivalent size-conscious reason here to prefer the
 * compact encoding over plain GeoJSON coordinates for a single fetched line).
 * Never fabricates: any transport failure, non-2xx response, or missing/empty
 * route resolves to `null` rather than a guessed geometry -- the caller falls
 * back to the honest straight-line stand-in already seeded before this
 * promise settles.
 */
async function fetchDirectionsRoute(
  fromLat: number,
  fromLng: number,
  toLat: number,
  toLng: number,
): Promise<Array<[number, number]> | null> {
  try {
    const url =
      `${DIRECTIONS_BASE_URL}${fromLng},${fromLat};${toLng},${toLat}` +
      `?geometries=geojson&overview=full&access_token=${MAPBOX_TOKEN}`;
    const res = await fetch(url);
    if (!res.ok) return null;
    const body = (await res.json()) as {
      routes?: Array<{ geometry?: { coordinates?: unknown } }>;
    };
    const coords = body.routes?.[0]?.geometry?.coordinates;
    if (!Array.isArray(coords) || coords.length < 2) return null;
    // Mapbox coordinate order is [lng, lat] -- flipped here at the boundary,
    // same convention as the Android app's decodePolyline (MapboxDirections.kt).
    return coords.map((c) => [c[1], c[0]] as [number, number]);
  } catch {
    return null;
  }
}

/**
 * Live-route overlay data for FleetMapCanvas -- one entry per currently
 * routable vehicle (see `RoutableVehicle`'s own doc), each a real Directions
 * route when the fetch has resolved or a straight-line fallback otherwise.
 *
 * Routes are cached per vehicle id and only re-fetched once either endpoint
 * has moved more than `ROUTE_REFETCH_THRESHOLD_M`, *not* on every position
 * tick -- Directions is a metered API and this dashboard's own position
 * stream already ticks every 5s (see LivePositionHeartbeat.kt), so refetching
 * on every tick would be wasteful and risk rate-limiting the same token every
 * other map feature depends on. A vehicle that drops out of the routable set
 * (trip closed, destination cleared, no longer on-trip) has its cache entry
 * dropped on the very next effect run, so its line disappears rather than
 * lingering stale on the map.
 */
export function useVehicleRoutes(vehicles: RoutableVehicle[]): Map<string, VehicleRouteState> {
  const cacheRef = useRef<Map<string, CacheEntry>>(new Map());
  const [routes, setRoutes] = useState<Map<string, VehicleRouteState>>(new Map());

  useEffect(() => {
    let cancelled = false;
    const seen = new Set<string>();
    let changed = false;

    for (const v of vehicles) {
      seen.add(v.id);
      const existing = cacheRef.current.get(v.id);
      const staleEnough =
        !existing ||
        haversineMeters(existing.fromLat, existing.fromLng, v.lat, v.lng) > ROUTE_REFETCH_THRESHOLD_M ||
        haversineMeters(existing.toLat, existing.toLng, v.destLat, v.destLng) > ROUTE_REFETCH_THRESHOLD_M;
      if (!staleEnough) continue;

      // Seed the honest straight-line stand-in immediately (before the fetch
      // even starts) so a consumer always has something real to draw the
      // instant a vehicle qualifies -- see this hook's own "never re-fetch on
      // every tick" doc above for why the *fetch* is throttled, which is
      // orthogonal to always having *a* line to show.
      const entry: CacheEntry = {
        fromLat: v.lat,
        fromLng: v.lng,
        toLat: v.destLat,
        toLng: v.destLng,
        state: {
          points: [
            [v.lat, v.lng],
            [v.destLat, v.destLng],
          ],
          isFallback: true,
        },
      };
      cacheRef.current.set(v.id, entry);
      changed = true;

      if (MAPBOX_TOKEN) {
        fetchDirectionsRoute(v.lat, v.lng, v.destLat, v.destLng).then((points) => {
          if (cancelled || points == null) return;
          // Only apply if this exact fetch's cache entry is still the live
          // one for this vehicle -- a later effect run may already have
          // superseded it with a newer endpoint pair (and its own fetch) by
          // the time this one resolves.
          if (cacheRef.current.get(v.id) !== entry) return;
          cacheRef.current.set(v.id, { ...entry, state: { points, isFallback: false } });
          setRoutes(snapshotStates(cacheRef.current));
        });
      }
      // No token configured: stay on the straight-line fallback indefinitely,
      // same posture as FleetMapCanvas's own PlainCanvasMap fallback for the
      // whole map when VITE_MAPBOX_TOKEN is unset.
    }

    for (const id of Array.from(cacheRef.current.keys())) {
      if (!seen.has(id)) {
        cacheRef.current.delete(id);
        changed = true;
      }
    }

    if (changed) setRoutes(snapshotStates(cacheRef.current));

    return () => {
      cancelled = true;
    };
  }, [vehicles]);

  return routes;
}
