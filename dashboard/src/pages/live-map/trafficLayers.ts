import type mapboxgl from "mapbox-gl";
import { hazardCategoryLabel, hazardColor, hazardGlyph, type TrafficCamera, type TrafficHazard } from "./trafficTypes";

/**
 * The GL layer stack and popup content for the live-traffic overlay: real
 * Transport for NSW camera locations and hazards (incidents/roadworks/
 * closures/floods/fires), drawn on top of the existing live fleet map.
 *
 * Split out of FleetMapCanvas.tsx the same way `mapInit.ts` (map/layer
 * construction) and `markers.ts` (marker DOM + popup content) already are --
 * one circle+symbol source/layer pair per feed, GeoJSON-driven like the
 * geofence/device/trail/route layers `mapInit.ts` installs, rather than one
 * `mapboxgl.Marker` DOM node per camera/hazard: `TollGantryMap.tsx`'s own doc
 * comment on its 141-gantry circle layer already found that DOM markers
 * visibly stutter at this kind of count, and a metro-wide camera feed is
 * easily that many points.
 */

interface PointFeature<P> {
  type: "Feature";
  geometry: { type: "Point"; coordinates: [number, number] };
  properties: P;
}
interface PointFeatureCollection<P> {
  type: "FeatureCollection";
  features: PointFeature<P>[];
}

export interface CameraFeatureProps {
  id: string;
  name: string;
  direction: string;
  region: string;
  image_url: string;
}
export type CameraFeatureCollection = PointFeatureCollection<CameraFeatureProps>;

export interface HazardFeatureProps {
  id: string;
  category: string;
  /** Always a display string on the feature -- a null feed `headline` is
   * resolved to a category label here (same fallback buildHazardPopupHtml
   * applies), since Mapbox text-field expressions cannot render `null`. */
  headline: string;
  closure_type: string;
  direction: string;
  speed_limit: number | null;
  expected_delay_minutes: number | null;
  ended: boolean;
  glyph: string;
  color: string;
}
export type HazardFeatureCollection = PointFeatureCollection<HazardFeatureProps>;

const EMPTY_FEATURE_COLLECTION = { type: "FeatureCollection" as const, features: [] };

export const TRAFFIC_CAMERA_SOURCE_ID = "live-traffic-cameras";
export const TRAFFIC_CAMERA_LAYER_ID = "live-traffic-cameras-circle";
export const TRAFFIC_CAMERA_LABEL_LAYER_ID = "live-traffic-cameras-label";
export const TRAFFIC_HAZARD_SOURCE_ID = "live-traffic-hazards";
export const TRAFFIC_HAZARD_LAYER_ID = "live-traffic-hazards-circle";
export const TRAFFIC_HAZARD_LABEL_LAYER_ID = "live-traffic-hazards-label";

/** Sky blue -- distinct from every hazard colour below, from the vehicle
 * status palette, and from the geofence/route/trail colours already on this
 * map (see GEOFENCE_COLOR/ROUTE_LINE_COLOR in mapInit.ts). */
const CAMERA_COLOR = "#38bdf8";

/** Turns fetched cameras into the GeoJSON source data the circle+label
 * layers read. Exported (rather than inlined in the sync effect) so its
 * output -- which is exactly what the map would render as markers -- can be
 * asserted on directly in a test, the same "test the pure function, not a
 * WebGL canvas jsdom cannot create" posture `TollGantryMap.test.ts` already
 * takes for `corridorCollection`. */
export function buildCameraFeatureCollection(cameras: TrafficCamera[]): CameraFeatureCollection {
  return {
    type: "FeatureCollection",
    features: cameras.map((c) => ({
      type: "Feature",
      geometry: { type: "Point", coordinates: [c.longitude, c.latitude] },
      properties: {
        id: c.id,
        name: c.name,
        direction: c.direction ?? "",
        region: c.region ?? "",
        image_url: c.image_url,
      },
    })),
  };
}

/** Same job as buildCameraFeatureCollection, for hazards -- also carries the
 * resolved glyph/colour so the style layers below can read them straight off
 * the feature with a plain `["get", ...]` rather than a data-driven
 * expression keyed on `category` (which would need updating every time a new
 * TfNSW category shows up; hazardGlyph/hazardColor already handle that). */
export function buildHazardFeatureCollection(hazards: TrafficHazard[]): HazardFeatureCollection {
  return {
    type: "FeatureCollection",
    features: hazards.map((h) => ({
      type: "Feature",
      geometry: { type: "Point", coordinates: [h.longitude, h.latitude] },
      properties: {
        id: h.id,
        category: h.category,
        headline: h.headline ?? hazardCategoryLabel(h.category),
        closure_type: h.closure_type ?? "",
        direction: h.direction ?? "",
        speed_limit: h.speed_limit,
        expected_delay_minutes: h.expected_delay_minutes,
        ended: h.ended,
        glyph: hazardGlyph(h.category),
        color: hazardColor(h.category),
      },
    })),
  };
}

/**
 * Adds both sources and all four layers, empty, once. Call from the map's
 * own `load` handler, same convention as `installMapLayers` (mapInit.ts).
 *
 * Hazards are added after cameras so a hazard marker draws on top of a
 * camera that happens to sit at the same spot -- a closure is the more
 * urgent thing to see first.
 */
export function installTrafficLayers(map: mapboxgl.Map) {
  map.addSource(TRAFFIC_CAMERA_SOURCE_ID, { type: "geojson", data: EMPTY_FEATURE_COLLECTION });
  map.addLayer({
    id: TRAFFIC_CAMERA_LAYER_ID,
    type: "circle",
    source: TRAFFIC_CAMERA_SOURCE_ID,
    paint: {
      "circle-radius": 7,
      "circle-color": CAMERA_COLOR,
      "circle-opacity": 0.85,
      "circle-stroke-width": 2,
      "circle-stroke-color": "#0b0b10",
    },
  });
  map.addLayer({
    id: TRAFFIC_CAMERA_LABEL_LAYER_ID,
    type: "symbol",
    source: TRAFFIC_CAMERA_SOURCE_ID,
    layout: {
      // A literal glyph, not a `["get", ...]` expression -- every camera
      // marker is the same small camera icon; only hazards vary by category.
      "text-field": "📷",
      "text-size": 10,
      "text-allow-overlap": true,
    },
  });

  map.addSource(TRAFFIC_HAZARD_SOURCE_ID, { type: "geojson", data: EMPTY_FEATURE_COLLECTION });
  map.addLayer({
    id: TRAFFIC_HAZARD_LAYER_ID,
    type: "circle",
    source: TRAFFIC_HAZARD_SOURCE_ID,
    paint: {
      "circle-radius": ["case", ["get", "ended"], 5, 8],
      "circle-color": ["get", "color"],
      // Cleared hazards stay visible but fade -- same "say why it's still
      // there rather than vanish with no explanation" posture this map
      // already applies to a stale vehicle position (see markers.ts's
      // renderMarkerContent: reduced opacity, not deletion).
      "circle-opacity": ["case", ["get", "ended"], 0.35, 0.85],
      "circle-stroke-width": 2,
      "circle-stroke-color": "#0b0b10",
    },
  });
  map.addLayer({
    id: TRAFFIC_HAZARD_LABEL_LAYER_ID,
    type: "symbol",
    source: TRAFFIC_HAZARD_SOURCE_ID,
    layout: {
      "text-field": ["get", "glyph"],
      "text-size": 13,
      "text-allow-overlap": true,
    },
  });
}

const TRAFFIC_LAYER_IDS = [
  TRAFFIC_CAMERA_LAYER_ID,
  TRAFFIC_CAMERA_LABEL_LAYER_ID,
  TRAFFIC_HAZARD_LAYER_ID,
  TRAFFIC_HAZARD_LABEL_LAYER_ID,
];

/** Flips all four layers' visibility together -- the toggle this overlay
 * offers is one switch ("Live traffic"), not four, so cameras and hazards
 * always show/hide as a unit. A style-layout toggle rather than tearing the
 * layers down: instant, and the GeoJSON sources stay populated underneath so
 * turning it back on doesn't need a refetch. */
export function setTrafficLayersVisibility(map: mapboxgl.Map, visible: boolean) {
  const visibility = visible ? "visible" : "none";
  for (const id of TRAFFIC_LAYER_IDS) {
    if (map.getLayer(id)) map.setLayoutProperty(id, "visibility", visibility);
  }
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c] as string,
  );
}

/**
 * How often the popup re-keys the camera `<img>`'s `src` while it stays
 * open, purely to make the same live-refreshing snapshot URL actually look
 * live in the UI. This is NOT polling the endpoint -- no request is made
 * beyond the browser refetching that one image -- and nothing runs while the
 * popup is closed (see FleetMapCanvas's `stopCameraRefresh`, called from the
 * popup's own `close` event).
 */
export const CAMERA_IMAGE_REFRESH_MS = 15_000;

/**
 * Popup content for a clicked camera: title, direction, then the live JPEG
 * itself, cache-busted with `refreshKey` so the browser actually refetches it
 * rather than serving the first frame out of its own cache for as long as the
 * popup stays open. Returns an HTML string, same `setHTML` convention (and
 * the same manual `escapeHtml`, for the same reason -- no `@types/geojson`-
 * style dependency pulled in for one popup) `TollGantryMap.tsx` already uses
 * for its own gantry popup.
 */
export function buildCameraPopupHtml(camera: TrafficCamera, refreshKey: number): string {
  const separator = camera.image_url.includes("?") ? "&" : "?";
  const src = `${camera.image_url}${separator}_t=${refreshKey}`;
  return `
    <div class="traffic-popup-body">
      <div class="traffic-popup-title">${escapeHtml(camera.name)}</div>
      ${camera.direction ? `<div class="traffic-popup-meta">${escapeHtml(camera.direction)}</div>` : ""}
      <img class="traffic-camera-img" src="${escapeHtml(src)}" alt="${escapeHtml(camera.name)}" />
      ${camera.region ? `<div class="traffic-popup-region">${escapeHtml(camera.region)}</div>` : ""}
    </div>`;
}

/** Popup content for a clicked hazard: headline first, then whichever of
 * closure type / direction / speed limit / expected delay the feed actually
 * supplied -- each line only rendered when present, same "only show what
 * applies" rule the vehicle hover card (markers.ts) already follows for its
 * stale/idle/geofence call-outs. */
export function buildHazardPopupHtml(hazard: TrafficHazard): string {
  const title = hazard.headline ?? hazardCategoryLabel(hazard.category);
  const lines: string[] = [`<div class="traffic-popup-title">${escapeHtml(title)}</div>`];
  if (hazard.closure_type) {
    lines.push(`<div class="traffic-popup-meta">${escapeHtml(hazard.closure_type)}</div>`);
  }
  if (hazard.direction) {
    lines.push(`<div class="traffic-popup-meta">${escapeHtml(hazard.direction)}</div>`);
  }
  if (hazard.speed_limit != null) {
    lines.push(`<div class="traffic-popup-meta">Speed limit ${hazard.speed_limit} km/h</div>`);
  }
  if (hazard.expected_delay_minutes != null) {
    lines.push(`<div class="traffic-popup-meta">Delay ~${hazard.expected_delay_minutes} min</div>`);
  }
  if (hazard.ended) {
    lines.push(`<div class="traffic-popup-ended">Cleared</div>`);
  }
  return `<div class="traffic-popup-body">${lines.join("")}</div>`;
}

const TRAFFIC_POPUP_STYLE_ID = "live-traffic-popup-style";

/** Injected once. Same dark-card treatment `TollGantryMap.tsx`'s
 * `ensurePopupStyles` and `markers.ts`'s `ensurePopupStyleInjected` already
 * use -- matched here rather than reusing either directly, since neither is
 * exported for cross-file use and this popup's body classes are its own. */
export function ensureTrafficPopupStyleInjected() {
  if (typeof document === "undefined" || document.getElementById(TRAFFIC_POPUP_STYLE_ID)) return;
  const style = document.createElement("style");
  style.id = TRAFFIC_POPUP_STYLE_ID;
  style.textContent = `
    .traffic-popup .mapboxgl-popup-content { background:#161524; color:#f5f7fb; border:1px solid #26243c; border-radius:10px; padding:10px 12px; box-shadow:0 8px 24px rgba(0,0,0,.45); }
    .traffic-popup .mapboxgl-popup-tip { border-top-color:#161524; border-bottom-color:#161524; }
    .traffic-popup-body { font-size:12px; line-height:1.45; min-width:160px; max-width:240px; }
    .traffic-popup-title { font-size:13px; font-weight:600; }
    .traffic-popup-meta { color:#93aad1; margin-top:2px; }
    .traffic-popup-region { color:#8a90a8; margin-top:4px; font-size:11px; }
    .traffic-popup-ended { color:#8a90a8; margin-top:4px; font-style:italic; }
    .traffic-camera-img { display:block; width:100%; max-width:220px; margin-top:6px; border-radius:6px; }
  `;
  document.head.appendChild(style);
}
