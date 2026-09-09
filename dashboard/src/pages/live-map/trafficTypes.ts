/**
 * Shapes shared by the live-traffic data hooks (`useLiveTrafficCameras.ts`,
 * `useLiveTrafficHazards.ts`) and the Mapbox layer/popup builders
 * (`trafficLayers.ts`).
 *
 * Coded directly against the real contract for two tenant-authenticated
 * endpoints ingesting Transport for NSW's public, no-API-key "Live Traffic
 * NSW" feeds (real camera snapshot URLs; real incidents/roadworks/closures):
 *
 *   GET /v1/traffic/cameras?bbox=<minLng,minLat,maxLng,maxLat>
 *     -> Page<TrafficCameraRead>
 *   GET /v1/traffic/hazards?bbox=...&category=...&active_only=true
 *     -> Page<TrafficHazardRead>
 *
 * `backend/app/api/v1/traffic.py` did not exist in this worktree when this
 * was first written -- these shapes were coded ahead against the plan's
 * stated field names, MSW-mocked. The backend agent's router/schemas
 * (`backend/app/schemas/traffic.py`) landed in this same worktree while this
 * was in progress, and this file was updated to match exactly one real
 * difference from the originally-stated plan: the camera's display field is
 * `name`, not `title` (see `TrafficCameraRead` in that schema module).
 * Everything else matched (including `headline` on a hazard genuinely being
 * nullable, which this already modelled). Only this file needed the edit --
 * the layer builders and FleetMapCanvas consume `TrafficCamera`/
 * `TrafficHazard` by name, not by guessing the wire shape.
 */

export interface TrafficCamera {
  id: string;
  name: string;
  latitude: number;
  longitude: number;
  direction: string | null;
  /** A live-refreshing JPEG snapshot URL -- not a video stream. See
   * trafficLayers.ts's CAMERA_IMAGE_REFRESH_MS for how the popup keeps this
   * looking live without polling the endpoint itself. */
  image_url: string;
  region: string | null;
}

/** TfNSW's own hazard categories -- the real six from
 * `backend/app/models/traffic.py`'s `TRAFFIC_HAZARD_CATEGORIES` (`incident`,
 * `roadwork`, `flood`, `fire`, `alpine`, `majorevent`). Kept open (`| string`)
 * rather than a closed union anyway: an uncovered category must still plot
 * with a sane fallback glyph/colour (see hazardGlyph/hazardColor below)
 * rather than the whole hazard disappearing or the app throwing on a feed
 * value nobody enumerated yet -- `alpine`/`majorevent` get exactly that
 * fallback treatment in v1 rather than their own glyph. */
export type TrafficHazardCategory = "incident" | "roadwork" | "flood" | "fire" | "alpine" | "majorevent" | string;

export interface TrafficHazard {
  id: string;
  category: TrafficHazardCategory;
  latitude: number;
  longitude: number;
  /** Nullable on the real feed -- not every hazard row carries one. The
   * popup falls back to a category-derived label rather than showing
   * nothing at all (see buildHazardPopupHtml in trafficLayers.ts). */
  headline: string | null;
  closure_type: string | null;
  direction: string | null;
  speed_limit: number | null;
  expected_delay_minutes: number | null;
  /** True once TfNSW has marked the hazard cleared. Still returned (not
   * dropped) so a dispatcher can see something *just* cleared rather than
   * having it vanish with no explanation -- drawn fainter, see
   * trafficLayers.ts's circle-opacity case expression. */
  ended: boolean;
}

/** A Mapbox-order bounding box -- [minLng, minLat, maxLng, maxLat], the same
 * lng-before-lat convention `mapInit.ts`'s `MapCamera`/`parseTenantCamera`
 * already use everywhere Mapbox is the eventual consumer. */
export interface TrafficBBox {
  minLng: number;
  minLat: number;
  maxLng: number;
  maxLat: number;
}

/** Serializes a bbox exactly as the contract's query param wants it. */
export function bboxParam(bbox: TrafficBBox): string {
  return `${bbox.minLng},${bbox.minLat},${bbox.maxLng},${bbox.maxLat}`;
}

/** A structural subset of `mapboxgl.LngLatBounds` -- typed narrowly so the
 * hooks/tests that build a bbox from a map's current viewport don't need to
 * import `mapbox-gl` itself just for this one conversion. */
export interface LngLatBoundsLike {
  getWest(): number;
  getSouth(): number;
  getEast(): number;
  getNorth(): number;
}

export function bboxFromLngLatBounds(bounds: LngLatBoundsLike): TrafficBBox {
  return {
    minLng: bounds.getWest(),
    minLat: bounds.getSouth(),
    maxLng: bounds.getEast(),
    maxLat: bounds.getNorth(),
  };
}

/** One glyph/colour pair per known category, each visually distinct from the
 * others AND from the vehicle-status palette (green/gold/gray/red already
 * mean available/on-trip/offline/duress -- see markers.ts's statusColor) and
 * from the toll-corridor colours (TollGantryMap.tsx's ROAD_COLORS), so a
 * hazard marker never reads as a different kind of signal this map already
 * uses. */
const HAZARD_GLYPH: Record<string, string> = {
  incident: "⚠",
  roadwork: "🚧",
  flood: "🌊",
  fire: "🔥",
};
const HAZARD_COLOR: Record<string, string> = {
  incident: "#ef4444",
  roadwork: "#f59e0b",
  flood: "#0ea5e9",
  fire: "#f97316",
};
const DEFAULT_HAZARD_GLYPH = "•";
const DEFAULT_HAZARD_COLOR = "#94a3b8";

/** Glyph for a hazard's category, or a plain dot for a category the feed
 * returns that nobody has named a glyph for yet. */
export function hazardGlyph(category: string): string {
  return HAZARD_GLYPH[category] ?? DEFAULT_HAZARD_GLYPH;
}

/** Colour for a hazard's category, or a neutral grey for an unrecognised one
 * -- same "never disappear on unmapped input" posture as hazardGlyph. */
export function hazardColor(category: string): string {
  return HAZARD_COLOR[category] ?? DEFAULT_HAZARD_COLOR;
}

const HAZARD_CATEGORY_LABEL: Record<string, string> = {
  incident: "Incident",
  roadwork: "Roadworks",
  flood: "Flood",
  fire: "Fire",
  alpine: "Alpine conditions",
  majorevent: "Major event",
};

/** Human label for a category, used as the popup title when the real feed's
 * own `headline` is null (it is nullable on the wire -- see
 * `TrafficHazard.headline`'s doc above) -- a dispatcher still sees SOMETHING
 * naming what the marker is, rather than a blank title. */
export function hazardCategoryLabel(category: string): string {
  return HAZARD_CATEGORY_LABEL[category] ?? "Traffic hazard";
}
