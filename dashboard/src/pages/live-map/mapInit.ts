import mapboxgl from "mapbox-gl";
import type { PlottedVehicle } from "./mapTypes";
import {
  TRAIL_CASING_LAYER_ID,
  TRAIL_CURSOR_LAYER_ID,
  TRAIL_FAST_COLOR,
  TRAIL_LINE_LAYER_ID,
  TRAIL_MID_COLOR,
  TRAIL_SLOW_COLOR,
  TRAIL_SOURCE_ID,
  TRAIL_STOP_LAYER_ID,
} from "./trails";

/**
 * Map construction and the GL layer stack for the live fleet map: the token,
 * the default camera, the style, and every source/layer the renderer draws
 * into (geofences, unpaired devices, the history trail, on-trip routes).
 *
 * Extracted verbatim from FleetMapCanvas.tsx during the Phase 0 file split.
 * The layer bodies are unchanged, including their ordering -- which is load-
 * bearing: geofences first (so they sit under everything), then devices, then
 * the trail, then routes on top of the trail, and DOM markers above all of it
 * because mapboxgl.Marker is absolutely-positioned DOM rather than a GL layer.
 */

// Public/publishable Mapbox token — safe to ship in a client bundle (see .env.example).
// Falls back to the plain-SVG plot (PlainCanvasMap.tsx) when unset so the dashboard
// still works offline / without a maps API key.
export const MAPBOX_TOKEN = import.meta.env.VITE_MAPBOX_TOKEN;

export const MAP_STYLE_URL = "mapbox://styles/benfarid/cmtbnyhe4000e01pcgx2t51za";

/**
 * The camera the map opens on when it has nothing better to show, resolved by
 * `resolveInitialCamera` below. No city is hardcoded anywhere in this module:
 * the fallback of last resort is a whole-world view, which is honest about
 * knowing nothing rather than implying the fleet is somewhere it is not.
 */
export interface MapCamera {
  center: [number, number];
  zoom: number;
}

/** Whole-world view -- the only view this codebase is entitled to assume. */
export const WORLD_VIEW: MapCamera = { center: [0, 0], zoom: 1 };

/** Zoom used for a single known point (one vehicle, or one tablet's last locate). */
export const SINGLE_POINT_ZOOM = 13;

/** Never zoom the bounding-box fallback in further than this: a fleet parked at
 * one depot should still show its surroundings, not a rooftop. */
const MAX_FITTED_ZOOM = 14;

export const SINGLE_VEHICLE_ZOOM = SINGLE_POINT_ZOOM;

/** How far the followed vehicle must drift from the map centre before the camera
 * re-centres. Below this, GPS jitter would re-animate the camera constantly and
 * make everything else on the map unreadable. */
export const FOLLOW_RECENTRE_M = 120;

/** Great-circle metres between two points -- only used to decide whether follow
 * mode should re-centre, so the spherical-earth approximation is ample. */
export function haversineMetres(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const R = 6_371_008.8;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
}

// Color for a vehicle's live route-to-destination line -- deliberately not
// reusing statusColor's palette (green/gold/gray/red already mean
// available/on-trip/offline/duress on the marker itself) nor the geofence
// overlay's brand-accent gold, so a route line never reads as "this is the
// same signal as X" at a glance (this task's own "visually distinct from the
// geofence overlay ... and the duress pulsing ring" requirement). Not a CSS
// custom property since this is the one map feature with no existing design-
// system token to reuse -- a plain hex, same "define once, share between the
// Mapbox and plain-SVG renderers" posture as VEHICLE_ARROW_VIEWBOX_PATH.
export const ROUTE_LINE_COLOR = "#2563eb";

export const DEVICE_SOURCE_ID = "unpaired-devices";
export const DEVICE_CIRCLE_LAYER_ID = "unpaired-devices-circle";
export const DEVICE_LABEL_LAYER_ID = "unpaired-devices-label";

/** Grey, and only grey. The status palette (green available / gold on-trip / red
 * duress) belongs to vehicles carrying passengers; a tablet's last locate is a
 * stale snapshot and must not borrow a colour that means "live and available". */
const DEVICE_POINT_COLOR = "#94a3b8";

// GeoJSON source/layer ids for the geofence-breach overlay -- same naming
// convention as TollZoneMapPicker.tsx's own CIRCLE_SOURCE_ID/CIRCLE_*_LAYER_ID
// constants for the equivalent single-zone preview.
export const GEOFENCE_SOURCE_ID = "live-map-geofences";
/**
 * The gold both geofence layers are drawn in — the literal value of the
 * `--brand-accent` custom property in `index.css`, not a reference to it.
 *
 * They used to pass `"var(--brand-accent)"` straight to Mapbox, which cannot read
 * CSS custom properties: `addLayer` rejected both with `color expected,
 * "var(--brand-accent)" found` and returned without adding them, so the geofence
 * overlay this component documents at length has in fact never drawn a single
 * boundary on the live map. It failed as a console error rather than a thrown
 * exception, which is why nothing downstream noticed. DOM markers keep using
 * the `var()` form — that is real CSS, and works.
 */
const GEOFENCE_COLOR = "#f4c300";

export const GEOFENCE_FILL_LAYER_ID = "live-map-geofences-fill";
export const GEOFENCE_LINE_LAYER_ID = "live-map-geofences-line";

// GeoJSON source/layer ids for the on-trip route overlay -- one shared source
// (one LineString feature per routable vehicle, see useVehicleRoutes.ts),
// split into two layers by the feature's own `isFallback` property rather
// than one data-driven paint expression: Mapbox GL's `line-dasharray` is a
// camera-only paint property (it cannot key off a feature's `["get", ...]`
// value the way `line-color`/`line-width` can), so a real-vs-fallback dash
// style needs two layers filtered by that property instead, same "multiple
// layers over one source" shape as the fill+line pair just above.
export const ROUTE_SOURCE_ID = "live-map-routes";
export const ROUTE_LINE_LAYER_ID = "live-map-routes-line";
export const ROUTE_FALLBACK_LAYER_ID = "live-map-routes-fallback";

export function fitToVehicles(map: mapboxgl.Map, vehicles: PlottedVehicle[]) {
  if (vehicles.length === 0) return;
  if (vehicles.length === 1) {
    map.jumpTo({ center: [vehicles[0].lng, vehicles[0].lat], zoom: SINGLE_VEHICLE_ZOOM });
    return;
  }
  const bounds = new mapboxgl.LngLatBounds();
  for (const v of vehicles) bounds.extend([v.lng, v.lat]);
  map.fitBounds(bounds, { padding: 56, maxZoom: 14, duration: 0 });
}

/** A minimal lat/lng -- vehicles and tablet locates are both reduced to this
 * before the camera helpers below look at them, so neither helper needs to
 * know which kind of thing it is framing. */
export interface KnownPoint {
  lat: number;
  lng: number;
}

/**
 * Reads a tenant-configured default map centre out of the tenant's
 * `theme_json`, or returns null if it does not carry one.
 *
 * Written defensively against `unknown` rather than against a typed field
 * because this is arbitrary server-stored JSON: a tenant row written by an
 * older build, by the platform console, or by hand can carry anything at all
 * under `default_center`, and a malformed value must fall through to the next
 * fallback rather than hand Mapbox a NaN and blank the map. Accepted shape is
 * `[lng, lat]` -- Mapbox's own order, so what is stored is what is passed --
 * with an optional numeric `default_zoom`.
 */
export function parseTenantCamera(theme: unknown): MapCamera | null {
  if (!theme || typeof theme !== "object") return null;
  const raw = (theme as Record<string, unknown>).default_center;
  if (!Array.isArray(raw) || raw.length !== 2) return null;
  const [lng, lat] = raw;
  if (typeof lng !== "number" || typeof lat !== "number") return null;
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return null;
  if (lng < -180 || lng > 180 || lat < -90 || lat > 90) return null;
  const rawZoom = (theme as Record<string, unknown>).default_zoom;
  const zoom =
    typeof rawZoom === "number" && Number.isFinite(rawZoom) && rawZoom >= 0 && rawZoom <= 22
      ? rawZoom
      : SINGLE_POINT_ZOOM - 2;
  return { center: [lng, lat], zoom };
}

/**
 * The camera that frames every point the fleet is known to have reported
 * from, or null when the fleet has never reported a position at all.
 *
 * This duplicates none of `fitToVehicles`' work -- that one drives a live map
 * through `fitBounds`, this one computes a camera *before* a map exists, for
 * the constructor. The zoom comes from the longitudinal span against the 360
 * degrees a full world spans at zoom 0, which is the same doubling-per-level
 * relationship Mapbox uses; it is deliberately approximate, since anything it
 * frames is immediately refined by `fitToVehicles` on the map's `load`.
 */
export function cameraForPoints(points: KnownPoint[]): MapCamera | null {
  if (points.length === 0) return null;
  let minLat = Infinity;
  let maxLat = -Infinity;
  let minLng = Infinity;
  let maxLng = -Infinity;
  for (const p of points) {
    minLat = Math.min(minLat, p.lat);
    maxLat = Math.max(maxLat, p.lat);
    minLng = Math.min(minLng, p.lng);
    maxLng = Math.max(maxLng, p.lng);
  }
  const center: [number, number] = [(minLng + maxLng) / 2, (minLat + maxLat) / 2];
  const span = Math.max(maxLng - minLng, maxLat - minLat);
  // A single point, or a fleet parked close enough together that the span
  // rounds to nothing, has no meaningful extent to fit -- frame it directly.
  if (span < 1e-4) return { center, zoom: SINGLE_POINT_ZOOM };
  const zoom = Math.log2(360 / span) - 0.5;
  return {
    center,
    zoom: Math.min(MAX_FITTED_ZOOM, Math.max(WORLD_VIEW.zoom, zoom)),
  };
}

/** Which of the three fallbacks the opening camera came from. Surfaced to the
 * user in the empty-state caption, so the map never shows a region without
 * saying where that region came from. */
export type CameraSource = "tenant" | "fleet" | "world";

/**
 * The opening camera, in the order of preference this product owes a tenant:
 * their own configured default centre, else wherever their own fleet was last
 * seen, else the whole world. There is no fourth option and no hardcoded city
 * -- an installation in any country gets a view that is either configured or
 * derived from its own data.
 *
 * Note this only decides where the map *opens*. Once it loads, `fitToVehicles`
 * still frames whatever vehicles are actually live, so a tenant with a
 * configured centre and vehicles on the road still gets its fleet framed.
 */
export function resolveInitialCamera(
  theme: unknown,
  points: KnownPoint[],
): { camera: MapCamera; source: CameraSource } {
  const tenant = parseTenantCamera(theme);
  if (tenant) return { camera: tenant, source: "tenant" };
  const fleet = cameraForPoints(points);
  if (fleet) return { camera: fleet, source: "fleet" };
  return { camera: WORLD_VIEW, source: "world" };
}

/** Constructs the map itself, on the camera resolved by `resolveInitialCamera`,
 * with the nav control attached. Sources and layers are added separately, on
 * `load`, by installMapLayers below. */
export function createFleetMap(container: HTMLDivElement, camera: MapCamera): mapboxgl.Map {
  mapboxgl.accessToken = MAPBOX_TOKEN as string;
  const map = new mapboxgl.Map({
    container,
    style: MAP_STYLE_URL,
    center: camera.center,
    zoom: camera.zoom,
  });
  map.addControl(new mapboxgl.NavigationControl({ showCompass: false }), "top-right");
  return map;
}

/**
 * Adds every source and layer the live map draws into, in the order they must
 * be stacked. Call once, from the map's own `load` handler.
 *
 * `onSelectDevice` is taken as an argument rather than closed over by the
 * caller for the same reason the original code read it through a ref: this
 * handler is registered once for the map's whole lifetime, so it must not
 * capture a stale callback -- pass a stable indirection (a ref read), not the
 * current render's prop.
 */
export function installMapLayers(map: mapboxgl.Map, onSelectDevice: (deviceId: string) => void) {
  // Geofence-breach overlay -- one shared GeoJSON source of every
  // fetched geofence's circle, drawn beneath the vehicle markers (added
  // before any marker exists) so a dispatcher can see the boundary
  // itself, not just a per-vehicle in/out badge. Same fill/line
  // treatment (brand-accent gold, low fill opacity) as the Tariff
  // Studio Toll Zones picker (TollZoneMapPicker.tsx) uses for the same
  // geometry, for visual consistency across the app.
  map.addSource(GEOFENCE_SOURCE_ID, {
    type: "geojson",
    data: { type: "FeatureCollection", features: [] },
  });
  map.addLayer({
    id: GEOFENCE_FILL_LAYER_ID,
    type: "fill",
    source: GEOFENCE_SOURCE_ID,
    paint: { "fill-color": GEOFENCE_COLOR, "fill-opacity": 0.12 },
  });
  map.addLayer({
    id: GEOFENCE_LINE_LAYER_ID,
    type: "line",
    source: GEOFENCE_SOURCE_ID,
    paint: { "line-color": GEOFENCE_COLOR, "line-width": 1.5, "line-dasharray": [2, 2] },
  });

  // Unpaired tablets, from their own last locate response. Added before the
  // trail and the vehicle markers so a real vehicle always draws on top of a
  // stale device fix that happens to sit in the same street.
  map.addSource(DEVICE_SOURCE_ID, {
    type: "geojson",
    data: { type: "FeatureCollection", features: [] },
  });
  map.addLayer({
    id: DEVICE_CIRCLE_LAYER_ID,
    type: "circle",
    source: DEVICE_SOURCE_ID,
    paint: {
      "circle-radius": ["case", ["get", "selected"], 9, 6],
      // Hollow, not filled: the fleet's own markers are solid discs, and a
      // tablet's last-known position is a weaker claim than a live vehicle
      // position. It should read as an outline, not a car.
      "circle-color": "#0b0b10",
      "circle-opacity": 0.85,
      "circle-stroke-width": ["case", ["get", "selected"], 3, 2],
      "circle-stroke-color": DEVICE_POINT_COLOR,
    },
  });
  map.addLayer({
    id: DEVICE_LABEL_LAYER_ID,
    type: "symbol",
    source: DEVICE_SOURCE_ID,
    layout: {
      "text-field": ["get", "label"],
      "text-size": 11,
      "text-offset": [0, 1.4],
      "text-anchor": "top",
      "text-allow-overlap": false,
    },
    paint: {
      "text-color": DEVICE_POINT_COLOR,
      "text-halo-color": "#0b0b10",
      "text-halo-width": 1.5,
    },
  });
  map.on("click", DEVICE_CIRCLE_LAYER_ID, (e) => {
    const feature = e.features?.[0] as { properties?: Record<string, unknown> } | undefined;
    const id = feature?.properties?.id;
    if (typeof id === "string") onSelectDevice(id);
  });
  map.on("mouseenter", DEVICE_CIRCLE_LAYER_ID, () => {
    map.getCanvas().style.cursor = "pointer";
  });
  map.on("mouseleave", DEVICE_CIRCLE_LAYER_ID, () => {
    map.getCanvas().style.cursor = "";
  });

  // History trail -- where the selected vehicle has actually been, as a real
  // line on the real map. The durable 72h history has always been served by
  // GET /v1/vehicles/{id}/position-history but was only ever rendered as a
  // static SVG inside a modal, which could not be compared against anything
  // else on the map. Added before the route overlay so a live route draws
  // over the historical trail rather than under it.
  map.addSource(TRAIL_SOURCE_ID, {
    type: "geojson",
    data: { type: "FeatureCollection", features: [] },
  });
  // A dark casing under the coloured line. Checked on the deployed map: a plain
  // 3px line at 85% opacity is genuinely hard to pick out against this dark
  // style over a grey street grid -- it was there and rendering, and still took
  // a paint-property probe to see. The casing is what every routing map does,
  // and it costs one more line layer.
  map.addLayer({
    id: TRAIL_CASING_LAYER_ID,
    type: "line",
    source: TRAIL_SOURCE_ID,
    filter: ["==", ["geometry-type"], "LineString"],
    layout: { "line-cap": "round", "line-join": "round" },
    paint: {
      "line-color": "#0b0b10",
      "line-width": ["interpolate", ["linear"], ["zoom"], 10, 5, 16, 9],
      "line-opacity": 0.9,
    },
  });
  map.addLayer({
    id: TRAIL_LINE_LAYER_ID,
    type: "line",
    source: TRAIL_SOURCE_ID,
    filter: ["==", ["geometry-type"], "LineString"],
    layout: { "line-cap": "round", "line-join": "round" },
    paint: {
      // Coloured by the speed on each segment, so a glance at the trail
      // shows where the vehicle was crawling and where it was moving --
      // which is most of what anyone asks a history trail.
      "line-color": [
        "interpolate", ["linear"], ["get", "speed"],
        0, TRAIL_SLOW_COLOR,
        30, TRAIL_MID_COLOR,
        70, TRAIL_FAST_COLOR,
      ],
      // Widens with zoom so the trail stays readable both when it is a whole
      // shift across a city and when it is one street.
      "line-width": ["interpolate", ["linear"], ["zoom"], 10, 3, 16, 6],
      "line-opacity": 1,
    },
  });
  map.addLayer({
    id: TRAIL_STOP_LAYER_ID,
    type: "circle",
    source: TRAIL_SOURCE_ID,
    filter: ["==", ["get", "kind"], "stop"],
    paint: {
      "circle-radius": 5,
      "circle-color": TRAIL_SLOW_COLOR,
      "circle-stroke-width": 2,
      "circle-stroke-color": "#0b0b10",
    },
  });
  map.addLayer({
    id: TRAIL_CURSOR_LAYER_ID,
    type: "circle",
    source: TRAIL_SOURCE_ID,
    filter: ["==", ["get", "kind"], "cursor"],
    paint: {
      "circle-radius": 7,
      "circle-color": "#ffffff",
      "circle-stroke-width": 3,
      "circle-stroke-color": TRAIL_FAST_COLOR,
    },
  });

  // On-trip route overlay -- added after the geofence layers (so it
  // draws on top of that translucent fill) but, like every other GL
  // layer here, still beneath the vehicle markers themselves: markers
  // are separate absolutely-positioned DOM elements (mapboxgl.Marker),
  // not part of this layer stack, so they always render above any line
  // this source draws without needing an explicit z-order fight (this
  // task's own "do not let it obscure the vehicle markers" requirement).
  map.addSource(ROUTE_SOURCE_ID, {
    type: "geojson",
    data: { type: "FeatureCollection", features: [] },
  });
  map.addLayer({
    id: ROUTE_FALLBACK_LAYER_ID,
    type: "line",
    source: ROUTE_SOURCE_ID,
    filter: ["==", ["get", "isFallback"], true],
    paint: { "line-color": ROUTE_LINE_COLOR, "line-width": 3, "line-opacity": 0.55, "line-dasharray": [2, 2] },
  });
  map.addLayer({
    id: ROUTE_LINE_LAYER_ID,
    type: "line",
    source: ROUTE_SOURCE_ID,
    filter: ["==", ["get", "isFallback"], false],
    paint: { "line-color": ROUTE_LINE_COLOR, "line-width": 3, "line-opacity": 0.8 },
  });
}
