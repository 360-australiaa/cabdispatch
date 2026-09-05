import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import mapboxgl from "mapbox-gl";
import "mapbox-gl/dist/mapbox-gl.css";
import type { Geofence } from "@/hooks/useGeofences";
import { circlePolygon } from "@/lib/geoCircle";
import type { DuressEventRead, VehicleLiveRead } from "./types";
import { useVehicleRoutes, type RoutableVehicle, type VehicleRouteState } from "./useVehicleRoutes";
import {
  formatRelativeTime,
  formatSpeed,
  idleLabel,
  isBusyStatus,
  isStale,
  staleLabel,
  statusColor,
  type IdleInfo,
} from "./utils";

/**
 * A `VehicleLiveRead` enriched with the two purely-client-side states this
 * file (and VehicleDetailModal) surface alongside it: idle detection
 * (computeIdleInfo/usePositionHistory.ts) and geofence-breach containment
 * (geofencesContaining/utils.ts). Computed once in LiveMapPage (the only
 * place that owns both the position-history buffer and the fetched geofence
 * list) and threaded down here rather than recomputed per-consumer, so the
 * map, hover card and detail modal can never disagree about a vehicle's
 * idle/geofence state.
 */
export type VehicleMapState = VehicleLiveRead & {
  idleInfo: IdleInfo;
  insideGeofences: Geofence[];
};

interface FleetMapCanvasProps {
  vehicles: VehicleMapState[];
  duressEvents: DuressEventRead[];
  /** Every fetched geofence -- drawn as a translucent circle overlay
   * regardless of current occupancy, so a dispatcher can see the boundary
   * itself, not just a binary in/out flag on a vehicle that happens to be
   * inside one right now. */
  geofences: Geofence[];
  /** Called when a non-duress vehicle's marker/pin is clicked (a duress-active
   * one always deep-links straight to its event instead -- see
   * buildMarkerElement / PlainCanvasMap's onClick below). */
  onSelectVehicle: (vehicleId: string) => void;
}

type PlottedVehicle = VehicleMapState & { lat: number; lng: number };

const WIDTH = 900;
const HEIGHT = 460;
const PADDING = 32;

// Field-testing default (2026-08-27): Karachi, Pakistan -- swap back to Sydney CBD
// ([151.2093, -33.8688]) once Karachi field testing wraps. Only matters before any
// vehicle position has loaded -- fitToVehicles() below re-centers on real GPS the
// moment a vehicle publishes, from anywhere in the world (the custom global style
// set below isn't tied to any region).
const DEFAULT_CENTER: [number, number] = [67.0011, 24.8607];
const DEFAULT_ZOOM = 10.5;
const SINGLE_VEHICLE_ZOOM = 13;

// Public/publishable Mapbox token — safe to ship in a client bundle (see .env.example).
// Falls back to the plain-SVG plot below when unset so the dashboard still works offline
// / without a maps API key.
const MAPBOX_TOKEN = import.meta.env.VITE_MAPBOX_TOKEN;

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

interface MapDataProps {
  plotted: PlottedVehicle[];
  duressByVehicleId: Map<string, DuressEventRead>;
  geofences: Geofence[];
  /** Live route-to-destination line per routable vehicle -- see
   * useVehicleRoutes.ts. Keyed by vehicle id; a vehicle absent from this map
   * has no line drawn (not on-trip, no destination picked, or not yet in
   * `plotted`). */
  routes: Map<string, VehicleRouteState>;
  onSelectVehicle: (vehicleId: string) => void;
}

/**
 * Live fleet map. Renders a real Mapbox GL JS map (custom global style, default-region-
 * centered or fit to the fleet's bounding box) when VITE_MAPBOX_TOKEN is configured;
 * otherwise falls back to a plain-SVG lat/lng plot so the page never breaks
 * for anyone without a token set up (see PlainCanvasMap below).
 */
export function FleetMapCanvas({ vehicles, duressEvents, geofences, onSelectVehicle }: FleetMapCanvasProps) {
  const plotted = useMemo(
    () => vehicles.filter((v): v is PlottedVehicle => v.lat != null && v.lng != null),
    [vehicles],
  );

  const duressByVehicleId = useMemo(() => {
    const map = new Map<string, DuressEventRead>();
    for (const event of duressEvents) {
      if (event.status !== "resolved" && event.status !== "cancelled") {
        map.set(event.vehicle_id, event);
      }
    }
    return map;
  }, [duressEvents]);

  // Vehicles that qualify for a drawn live route right now: actively on a
  // trip (isBusyStatus -- the same "busy" rule the status pill and idle
  // detection already share, see utils.ts) AND the driver has picked a
  // destination on it AND a real current position is already in `plotted`.
  // Recomputed only from the four numbers useVehicleRoutes actually cares
  // about (see that hook's own RoutableVehicle doc), not the full vehicle
  // row, so its effect doesn't re-run on unrelated field changes (battery,
  // idleInfo, ...) that tick on the same 5s cadence.
  const routableVehicles: RoutableVehicle[] = useMemo(
    () =>
      plotted
        .filter(
          (v): v is PlottedVehicle & { planned_dest_lat: number; planned_dest_lng: number } =>
            isBusyStatus(v.live_status) && v.planned_dest_lat != null && v.planned_dest_lng != null,
        )
        .map((v) => ({ id: v.id, lat: v.lat, lng: v.lng, destLat: v.planned_dest_lat, destLng: v.planned_dest_lng })),
    [plotted],
  );
  const routes = useVehicleRoutes(routableVehicles);

  if (MAPBOX_TOKEN) {
    return (
      <MapboxFleetMap
        plotted={plotted}
        duressByVehicleId={duressByVehicleId}
        geofences={geofences}
        routes={routes}
        onSelectVehicle={onSelectVehicle}
      />
    );
  }

  return (
    <PlainCanvasMap
      plotted={plotted}
      duressByVehicleId={duressByVehicleId}
      geofences={geofences}
      routes={routes}
      onSelectVehicle={onSelectVehicle}
    />
  );
}

// ---------------------------------------------------------------------------
// Shared vehicle-glyph + hover-card content
// ---------------------------------------------------------------------------

// A small upward-pointing arrow/car glyph (viewBox 0 0 24 24, tip at top) --
// shared (as a path shape) between the Mapbox marker (DOM/SVG, below) and the
// plain-SVG fallback's own locally-scaled copy of the same silhouette, so the
// two renderers stay in visual parity per this task's contract.
const VEHICLE_ARROW_VIEWBOX_PATH = "M12 2L19 21L12 17L5 21Z";

/** Fields shown in the vehicle hover card, computed once and rendered by
 * both the Mapbox popup (plain DOM, buildHoverCardElement) and the plain-SVG
 * fallback (JSX, PlainCanvasMap) so the two never drift out of sync. */
interface HoverCardFields {
  rego: string;
  statusLabel: string;
  speedLabel: string;
  batteryLabel: string;
  networkLabel: string;
  updatedLabel: string;
  duressActive: boolean;
  /** "Signal lost 3m ago", or null when not stale -- see utils.ts's
   * staleLabel/isStale. */
  staleLabel: string | null;
  /** "Idle 12m", or null when not idle -- see utils.ts's idleLabel/
   * computeIdleInfo. Mutually exclusive with staleLabel in practice
   * (computeIdleInfo returns not-idle for a stale vehicle), but both are
   * carried independently here rather than collapsed into one "warning"
   * field, per this task's own "don't reuse the exact same visual" rule. */
  idleLabel: string | null;
  /** Names of every geofence this vehicle is currently inside, empty when
   * outside all of them. */
  geofenceNames: string[];
}

function getHoverCardFields(vehicle: PlottedVehicle, duressEvent: DuressEventRead | undefined): HoverCardFields {
  return {
    rego: vehicle.rego,
    statusLabel: vehicle.live_status,
    speedLabel: formatSpeed(vehicle.speed_kmh),
    batteryLabel: vehicle.battery != null ? `${vehicle.battery}%` : "—",
    networkLabel: vehicle.network ?? "—",
    updatedLabel: formatRelativeTime(vehicle.position_updated_at),
    duressActive: duressEvent != null,
    staleLabel: staleLabel(vehicle.position_updated_at),
    idleLabel: idleLabel(vehicle.idleInfo),
    geofenceNames: vehicle.insideGeofences.map((g) => g.name),
  };
}

const HOVER_CARD_ROWS: Array<[label: string, key: keyof Pick<HoverCardFields, "speedLabel" | "batteryLabel" | "networkLabel" | "updatedLabel">]> = [
  ["Speed", "speedLabel"],
  ["Battery", "batteryLabel"],
  ["Network", "networkLabel"],
  ["Updated", "updatedLabel"],
];

// ---------------------------------------------------------------------------
// Mapbox GL JS rendering
// ---------------------------------------------------------------------------

function fitToVehicles(map: mapboxgl.Map, vehicles: PlottedVehicle[]) {
  if (vehicles.length === 0) return;
  if (vehicles.length === 1) {
    map.jumpTo({ center: [vehicles[0].lng, vehicles[0].lat], zoom: SINGLE_VEHICLE_ZOOM });
    return;
  }
  const bounds = new mapboxgl.LngLatBounds();
  for (const v of vehicles) bounds.extend([v.lng, v.lat]);
  map.fitBounds(bounds, { padding: 56, maxZoom: 14, duration: 0 });
}

const SVG_NS = "http://www.w3.org/2000/svg";

/**
 * Builds the marker's vehicle glyph. When `heading` is a real number the
 * glyph is a directional arrow rotated to match it; when null (vehicle
 * stationary, or the device/GPS stack never reported one) it falls back to a
 * plain dot rather than pointing the arrow "up" and letting that read as a
 * guessed/implied north heading -- see LivePosition.heading's doc comment
 * (hooks/useLiveMap.ts) for why this codebase never fabricates a direction.
 */
function buildVehicleGlyph(color: string, size: number, heading: number | null): SVGSVGElement {
  const svg = document.createElementNS(SVG_NS, "svg") as SVGSVGElement;
  svg.setAttribute("width", String(size));
  svg.setAttribute("height", String(size));
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.style.display = "block";
  svg.style.filter = "drop-shadow(0 1px 2px rgba(0,0,0,0.45))";
  svg.style.pointerEvents = "none";

  if (heading != null) {
    svg.style.transform = `rotate(${heading}deg)`;
    svg.style.transformOrigin = "50% 50%";
    const path = document.createElementNS(SVG_NS, "path");
    path.setAttribute("d", VEHICLE_ARROW_VIEWBOX_PATH);
    path.setAttribute("fill", color);
    path.setAttribute("stroke", "var(--card)");
    path.setAttribute("stroke-width", "1.5");
    path.setAttribute("stroke-linejoin", "round");
    svg.appendChild(path);
  } else {
    const circle = document.createElementNS(SVG_NS, "circle");
    circle.setAttribute("cx", "12");
    circle.setAttribute("cy", "12");
    circle.setAttribute("r", "7");
    circle.setAttribute("fill", color);
    circle.setAttribute("stroke", "var(--card)");
    circle.setAttribute("stroke-width", "2");
    svg.appendChild(circle);
  }
  return svg;
}

/** (Re)renders a marker's icon + duress ring + rego label into already-built
 * container elements, so an existing marker can be refreshed in place on new
 * vehicle data instead of being torn down and recreated -- tearing down would
 * also kill any in-flight position tween and the open hover popup (see the
 * marker-sync effect in MapboxFleetMap). */
function renderMarkerContent(
  iconWrap: HTMLDivElement,
  labelEl: HTMLSpanElement,
  vehicle: PlottedVehicle,
  duressEvent: DuressEventRead | undefined,
) {
  const stale = isStale(vehicle.position_updated_at);
  const idle = !stale && vehicle.idleInfo.idle;
  const inGeofence = vehicle.insideGeofences.length > 0;

  const size = duressEvent ? 22 : 18;
  iconWrap.replaceChildren();
  iconWrap.style.width = `${size}px`;
  iconWrap.style.height = `${size}px`;
  // Reduced opacity is the "lost signal" visual (on top of the dashed ring
  // below) -- distinct from idle, which stays full-opacity since the vehicle
  // is still reporting fine, it's just not moving.
  iconWrap.style.opacity = stale ? "0.5" : "1";

  if (duressEvent) {
    // Pulsing ring around duress vehicles — same "red pin" treatment as the
    // plain-canvas fallback's animated <circle>.
    const ring = document.createElement("div");
    ring.className = "animate-ping";
    ring.style.position = "absolute";
    ring.style.inset = "-8px";
    ring.style.borderRadius = "9999px";
    ring.style.backgroundColor = "var(--destructive)";
    ring.style.opacity = "0.45";
    ring.style.pointerEvents = "none";
    iconWrap.appendChild(ring);
  }

  // Stale ("lost signal") vs. idle ("online but parked") each get their own
  // static outline -- a dashed muted ring for stale, a dotted amber ring for
  // idle, deliberately different dash patterns AND colors so a dispatcher can
  // tell the two apart at a glance rather than both reading as one generic
  // "something's wrong" flag (see utils.ts's isStale/computeIdleInfo docs).
  // Neither is animated -- a continuously-moving decoration here caused real
  // user distress earlier and was fully reverted (see this file's history);
  // these are static outlines, not motion.
  if (stale || idle) {
    const ring = document.createElement("div");
    ring.style.position = "absolute";
    ring.style.inset = "-5px";
    ring.style.borderRadius = "9999px";
    ring.style.pointerEvents = "none";
    ring.style.borderStyle = stale ? "dashed" : "dotted";
    ring.style.borderWidth = "2px";
    ring.style.borderColor = stale ? "var(--muted-foreground)" : "var(--warning, #d97706)";
    iconWrap.appendChild(ring);
  }

  const color = duressEvent ? "var(--destructive)" : statusColor(vehicle.live_status);
  iconWrap.appendChild(buildVehicleGlyph(color, size, vehicle.heading));

  if (inGeofence) {
    // Small corner badge for "inside a geofence" -- deliberately a different
    // shape/position (a small dot at the glyph's corner) than the duress
    // ring (large, pulsing, centered) and the stale/idle rings (surround the
    // whole glyph), so all three can be shown at once without visually
    // merging into one signal.
    const badge = document.createElement("div");
    badge.title = `Inside ${vehicle.insideGeofences.map((g) => g.name).join(", ")}`;
    badge.style.position = "absolute";
    badge.style.top = "-3px";
    badge.style.right = "-3px";
    badge.style.width = "8px";
    badge.style.height = "8px";
    badge.style.borderRadius = "9999px";
    badge.style.backgroundColor = "var(--brand-accent)";
    badge.style.border = "1.5px solid var(--card)";
    badge.style.pointerEvents = "none";
    iconWrap.appendChild(badge);
  }

  labelEl.textContent = vehicle.rego;
  labelEl.style.bottom = `${size + 6}px`;
}

/** Builds the (empty) marker DOM shell once per vehicle -- icon container +
 * rego label -- content is filled in by renderMarkerContent above, separately,
 * so later updates don't need to recreate this shell (and therefore don't
 * disturb the mapboxgl.Marker bound to it or any listeners attached to it). */
function buildMarkerShell(): { el: HTMLDivElement; iconWrap: HTMLDivElement; labelEl: HTMLSpanElement } {
  const el = document.createElement("div");
  el.style.position = "relative";
  el.style.cursor = "pointer";

  const iconWrap = document.createElement("div");
  iconWrap.style.position = "relative";
  el.appendChild(iconWrap);

  const labelEl = document.createElement("span");
  labelEl.style.position = "absolute";
  labelEl.style.left = "50%";
  labelEl.style.transform = "translateX(-50%)";
  labelEl.style.fontSize = "10px";
  labelEl.style.fontWeight = "500";
  labelEl.style.color = "#fff";
  labelEl.style.textShadow = "0 1px 2px rgba(0,0,0,0.8)";
  labelEl.style.whiteSpace = "nowrap";
  labelEl.style.pointerEvents = "none";
  el.appendChild(labelEl);

  return { el, iconWrap, labelEl };
}

/** Styled hover-card content for the Mapbox popup -- theme-aware via the same
 * CSS custom properties the rest of the dashboard uses (index.css), so it
 * reads correctly in both light and dark mode without hardcoding a palette.
 * Built as a plain DOM node (not JSX) to match this file's existing
 * marker-building convention and because mapboxgl.Popup#setDOMContent wants
 * a real Node, not a React tree. */
function buildHoverCardElement(vehicle: PlottedVehicle, duressEvent: DuressEventRead | undefined): HTMLDivElement {
  const fields = getHoverCardFields(vehicle, duressEvent);

  const card = document.createElement("div");
  card.style.background = "var(--card)";
  card.style.color = "var(--card-foreground)";
  card.style.border = "1px solid var(--border)";
  card.style.borderRadius = "8px";
  card.style.padding = "8px 10px";
  card.style.fontSize = "12px";
  card.style.lineHeight = "1.6";
  card.style.minWidth = "150px";

  const title = document.createElement("div");
  title.style.fontWeight = "600";
  title.style.marginBottom = "2px";
  title.textContent = fields.rego;
  card.appendChild(title);

  const statusRow = document.createElement("div");
  statusRow.style.display = "flex";
  statusRow.style.justifyContent = "space-between";
  statusRow.style.gap = "12px";
  const statusLabel = document.createElement("span");
  statusLabel.style.color = "var(--muted-foreground)";
  statusLabel.textContent = "Status";
  const statusValue = document.createElement("span");
  statusValue.textContent = fields.duressActive ? "Duress active" : fields.statusLabel;
  if (fields.duressActive) statusValue.style.color = "var(--destructive)";
  statusRow.append(statusLabel, statusValue);
  card.appendChild(statusRow);

  for (const [label, key] of HOVER_CARD_ROWS) {
    const row = document.createElement("div");
    row.style.display = "flex";
    row.style.justifyContent = "space-between";
    row.style.gap = "12px";
    const labelSpan = document.createElement("span");
    labelSpan.style.color = "var(--muted-foreground)";
    labelSpan.textContent = label;
    const valueSpan = document.createElement("span");
    valueSpan.textContent = fields[key];
    row.append(labelSpan, valueSpan);
    card.appendChild(row);
  }

  // Stale/idle/geofence call-outs -- each only rendered when it applies,
  // styled as a standalone line rather than another label/value row since
  // these are alerts, not routine telemetry fields.
  if (fields.staleLabel) {
    const line = document.createElement("div");
    line.style.marginTop = "4px";
    line.style.color = "var(--muted-foreground)";
    line.textContent = fields.staleLabel;
    card.appendChild(line);
  }
  if (fields.idleLabel) {
    const line = document.createElement("div");
    line.style.marginTop = "4px";
    line.style.color = "var(--warning, #d97706)";
    line.textContent = fields.idleLabel;
    card.appendChild(line);
  }
  if (fields.geofenceNames.length > 0) {
    const line = document.createElement("div");
    line.style.marginTop = "4px";
    line.style.color = "var(--brand-accent)";
    line.textContent = `Inside ${fields.geofenceNames.join(", ")}`;
    card.appendChild(line);
  }

  return card;
}

// Strips Mapbox's own default popup chrome (white background, box-shadow,
// pointed tip) so our theme-aware card (buildHoverCardElement above) is the
// only thing rendered -- injected once, scoped to the `.vehicle-hover-popup`
// className passed to every mapboxgl.Popup this component creates.
const POPUP_STYLE_ID = "fleet-map-vehicle-popup-style";

function ensurePopupStyleInjected() {
  if (document.getElementById(POPUP_STYLE_ID)) return;
  const style = document.createElement("style");
  style.id = POPUP_STYLE_ID;
  style.textContent = `
    .vehicle-hover-popup .mapboxgl-popup-content {
      background: transparent;
      box-shadow: none;
      padding: 0;
    }
    .vehicle-hover-popup .mapboxgl-popup-tip {
      display: none;
    }
  `;
  document.head.appendChild(style);
}

/** How long a marker glides between two reported positions. Heartbeats can
 * arrive as often as every 5s now (this same run drops the backend/Android
 * cadence from 30s to 5s -- see LivePositionHeartbeat.kt), so a several-second
 * linear glide reads as continuous motion instead of a teleport, without
 * outrunning the next real update. This is tweening REAL, data-driven
 * position changes, not decorative animation -- see this file's own history. */
const POSITION_TWEEN_MS = 3500;

// GeoJSON source/layer ids for the geofence-breach overlay -- same naming
// convention as TollZoneMapPicker.tsx's own CIRCLE_SOURCE_ID/CIRCLE_*_LAYER_ID
// constants for the equivalent single-zone preview.
const GEOFENCE_SOURCE_ID = "live-map-geofences";
const GEOFENCE_FILL_LAYER_ID = "live-map-geofences-fill";
const GEOFENCE_LINE_LAYER_ID = "live-map-geofences-line";

// GeoJSON source/layer ids for the on-trip route overlay -- one shared source
// (one LineString feature per routable vehicle, see useVehicleRoutes.ts),
// split into two layers by the feature's own `isFallback` property rather
// than one data-driven paint expression: Mapbox GL's `line-dasharray` is a
// camera-only paint property (it cannot key off a feature's `["get", ...]`
// value the way `line-color`/`line-width` can), so a real-vs-fallback dash
// style needs two layers filtered by that property instead, same "multiple
// layers over one source" shape as the fill+line pair just above.
const ROUTE_SOURCE_ID = "live-map-routes";
const ROUTE_LINE_LAYER_ID = "live-map-routes-line";
const ROUTE_FALLBACK_LAYER_ID = "live-map-routes-fallback";

interface MarkerEntry {
  marker: mapboxgl.Marker;
  popup: mapboxgl.Popup;
  el: HTMLDivElement;
  iconWrap: HTMLDivElement;
  labelEl: HTMLSpanElement;
  vehicle: PlottedVehicle;
  duressEvent: DuressEventRead | undefined;
  rafId: number | null;
}

function stopTween(entry: MarkerEntry) {
  if (entry.rafId != null) {
    cancelAnimationFrame(entry.rafId);
    entry.rafId = null;
  }
}

/** Glides a marker (and its popup, if currently open) from its current
 * lngLat to `to` over POSITION_TWEEN_MS. Cancels any tween already in flight
 * for this marker first, so a newer position arriving mid-glide replaces the
 * old target cleanly instead of racing it. */
function tweenMarkerTo(entry: MarkerEntry, to: [number, number]) {
  stopTween(entry);
  const from = entry.marker.getLngLat();
  const fromLng = from.lng;
  const fromLat = from.lat;
  const start = performance.now();

  const step = (now: number) => {
    const t = Math.min(1, (now - start) / POSITION_TWEEN_MS);
    const lng = fromLng + (to[0] - fromLng) * t;
    const lat = fromLat + (to[1] - fromLat) * t;
    entry.marker.setLngLat([lng, lat]);
    if (entry.popup.isOpen()) {
      entry.popup.setLngLat([lng, lat]);
    }
    entry.rafId = t < 1 ? requestAnimationFrame(step) : null;
  };
  entry.rafId = requestAnimationFrame(step);
}

function MapboxFleetMap({ plotted, duressByVehicleId, geofences, routes, onSelectVehicle }: MapDataProps) {
  const navigate = useNavigate();
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<mapboxgl.Map | null>(null);
  const markersRef = useRef<Map<string, MarkerEntry>>(new Map());
  const [styleLoaded, setStyleLoaded] = useState(false);

  // Init the map once. Initial framing (Sydney vs. fleet bounding box) uses
  // whatever `plotted` this component mounted with — by the time it renders,
  // the parent's vehicles query has already resolved (see LiveMapPage).
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;

    ensurePopupStyleInjected();

    mapboxgl.accessToken = MAPBOX_TOKEN as string;
    const map = new mapboxgl.Map({
      container: containerRef.current,
      style: "mapbox://styles/benfarid/cmtbnyhe4000e01pcgx2t51za",
      center: DEFAULT_CENTER,
      zoom: DEFAULT_ZOOM,
    });
    map.addControl(new mapboxgl.NavigationControl({ showCompass: false }), "top-right");
    mapRef.current = map;

    map.on("load", () => {
      map.resize();
      fitToVehicles(map, plotted);

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
        paint: { "fill-color": "var(--brand-accent)", "fill-opacity": 0.12 },
      });
      map.addLayer({
        id: GEOFENCE_LINE_LAYER_ID,
        type: "line",
        source: GEOFENCE_SOURCE_ID,
        paint: { "line-color": "var(--brand-accent)", "line-width": 1.5, "line-dasharray": [2, 2] },
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
      setStyleLoaded(true);
    });

    return () => {
      markersRef.current.forEach((entry) => {
        stopTween(entry);
        entry.popup.remove();
        entry.marker.remove();
      });
      markersRef.current.clear();
      map.remove();
      mapRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- init once; see comment above
  }, []);

  // Keep the geofence overlay in sync with the fetched list -- this is a
  // separate, much-less-frequent update than the marker-sync effect below
  // (geofences rarely change, see useGeofences.ts's long staleTime), so it's
  // kept as its own effect rather than folded into that one.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !styleLoaded) return;
    const source = map.getSource(GEOFENCE_SOURCE_ID) as mapboxgl.GeoJSONSource | undefined;
    if (!source) return;
    source.setData({
      type: "FeatureCollection",
      features: geofences.map((g) => circlePolygon(g.center_lat, g.center_lng, g.radius_m)),
    });
  }, [geofences, styleLoaded]);

  // Keep the route overlay in sync with useVehicleRoutes' own cache -- a
  // separate effect from the marker-sync one below since it's keyed on a
  // different value (`routes`, not `plotted`) and updates the GL source
  // rather than any marker DOM, same separation-of-concerns as the geofence
  // effect just above.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !styleLoaded) return;
    const source = map.getSource(ROUTE_SOURCE_ID) as mapboxgl.GeoJSONSource | undefined;
    if (!source) return;
    source.setData({
      type: "FeatureCollection",
      features: Array.from(routes.entries()).map(([vehicleId, state]) => ({
        type: "Feature",
        properties: { vehicleId, isFallback: state.isFallback },
        geometry: {
          type: "LineString",
          coordinates: state.points.map(([lat, lng]) => [lng, lat]),
        },
      })),
    });
  }, [routes, styleLoaded]);

  // Keep markers in sync with live vehicle positions and duress state.
  // Existing markers are updated in place (icon/label/popup content, and a
  // tweened position) rather than torn down and recreated on every tick --
  // recreating would snap positions instantly and drop any open hover popup,
  // defeating both the smooth-motion and hover-card requirements below.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    const seen = new Set<string>();

    for (const vehicle of plotted) {
      seen.add(vehicle.id);
      const duressEvent = duressByVehicleId.get(vehicle.id);
      const existing = markersRef.current.get(vehicle.id);

      if (!existing) {
        const { el, iconWrap, labelEl } = buildMarkerShell();
        renderMarkerContent(iconWrap, labelEl, vehicle, duressEvent);
        const marker = new mapboxgl.Marker({ element: el, anchor: "center" })
          .setLngLat([vehicle.lng, vehicle.lat])
          .addTo(map);
        const popup = new mapboxgl.Popup({
          closeButton: false,
          closeOnClick: false,
          offset: 18,
          className: "vehicle-hover-popup",
        });

        const entry: MarkerEntry = {
          marker,
          popup,
          el,
          iconWrap,
          labelEl,
          vehicle,
          duressEvent,
          rafId: null,
        };

        // Every marker is clickable: a duress-active vehicle deep-links
        // straight to its open event (existing, highest-priority behavior);
        // any other vehicle opens the vehicle detail panel instead. Reads
        // `entry.vehicle`/`entry.duressEvent` (mutated on later updates
        // below) rather than closing over the vehicle/duressEvent above, so
        // one listener stays correct for the marker's whole lifetime.
        el.addEventListener("click", () => {
          if (entry.duressEvent) navigate(`/duress?event=${entry.duressEvent.id}`);
          else onSelectVehicle(entry.vehicle.id);
        });
        el.addEventListener("mouseenter", () => {
          popup.setDOMContent(buildHoverCardElement(entry.vehicle, entry.duressEvent));
          popup.setLngLat(marker.getLngLat()).addTo(map);
        });
        el.addEventListener("mouseleave", () => popup.remove());

        markersRef.current.set(vehicle.id, entry);
        continue;
      }

      existing.vehicle = vehicle;
      existing.duressEvent = duressEvent;
      renderMarkerContent(existing.iconWrap, existing.labelEl, vehicle, duressEvent);
      if (existing.popup.isOpen()) {
        existing.popup.setDOMContent(buildHoverCardElement(vehicle, duressEvent));
      }

      const current = existing.marker.getLngLat();
      const target: [number, number] = [vehicle.lng, vehicle.lat];
      if (current.lng !== target[0] || current.lat !== target[1]) {
        tweenMarkerTo(existing, target);
      }
    }

    for (const [id, entry] of markersRef.current) {
      if (seen.has(id)) continue;
      stopTween(entry);
      entry.popup.remove();
      entry.marker.remove();
      markersRef.current.delete(id);
    }
  }, [plotted, duressByVehicleId, navigate, onSelectVehicle]);

  return (
    <div className="relative">
      <div ref={containerRef} className="h-[460px] w-full rounded-md border border-border" />
      {plotted.length === 0 && (
        <div className="pointer-events-none absolute left-3 top-3 rounded-md bg-card/90 px-3 py-1.5 text-xs text-muted-foreground shadow">
          No live vehicle positions yet — showing the default region (Karachi, currently, for field
          testing). Positions appear here once a device publishes via POST /v1/fleet/positions.
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Plain-SVG fallback — no Mapbox token configured
// ---------------------------------------------------------------------------

// Same arrow silhouette as VEHICLE_ARROW_VIEWBOX_PATH above, re-expressed in
// this renderer's own local coordinate space (each vehicle's <g> is already
// translated to its (x, y), so this path is centered on its own origin
// instead of a 0-24 viewBox) -- keeps the two renderers' glyphs matching
// without forcing an extra nested <svg>/viewBox indirection in plain SVG.
const VEHICLE_ARROW_LOCAL_PATH = "M0,-8 L6,8 L0,4 L-6,8 Z";

/**
 * Plain-SVG lat/lng plot, used when no Mapbox token is configured. Vehicles
 * are projected into a local bounding box (not real map tiles), colored by
 * live status. Every vehicle pin is clickable: one with an open duress event
 * is drawn oversized in red and routes to `/duress?event=<id>` (the "red
 * pin" requirement — since a duress row itself has no lat/lng, its pin
 * position is its vehicle's last-known position); any other vehicle opens
 * the vehicle detail panel instead.
 */
function PlainCanvasMap({ plotted, duressByVehicleId, geofences, routes, onSelectVehicle }: MapDataProps) {
  const navigate = useNavigate();
  // No Mapbox Popup infra exists in this fallback -- track the hovered
  // vehicle id ourselves and render the same theme-aware hover card as a
  // plain absolutely-positioned div synced to its projected (x, y).
  const [hoveredId, setHoveredId] = useState<string | null>(null);

  const bounds = useMemo(() => {
    if (plotted.length === 0) return null;
    let minLat = Infinity;
    let maxLat = -Infinity;
    let minLng = Infinity;
    let maxLng = -Infinity;
    for (const v of plotted) {
      minLat = Math.min(minLat, v.lat);
      maxLat = Math.max(maxLat, v.lat);
      minLng = Math.min(minLng, v.lng);
      maxLng = Math.max(maxLng, v.lng);
    }
    // Pad degenerate ranges (single vehicle, or a fleet parked at one depot)
    // so markers don't collapse onto the viewport edge.
    const latSpan = maxLat - minLat || 0.01;
    const lngSpan = maxLng - minLng || 0.01;
    return {
      minLat: minLat - latSpan * 0.15,
      maxLat: maxLat + latSpan * 0.15,
      minLng: minLng - lngSpan * 0.15,
      maxLng: maxLng + lngSpan * 0.15,
    };
  }, [plotted]);

  function project(lat: number, lng: number): [number, number] {
    if (!bounds) return [WIDTH / 2, HEIGHT / 2];
    const x = PADDING + ((lng - bounds.minLng) / (bounds.maxLng - bounds.minLng)) * (WIDTH - PADDING * 2);
    const y = PADDING + ((bounds.maxLat - lat) / (bounds.maxLat - bounds.minLat)) * (HEIGHT - PADDING * 2);
    return [x, y];
  }

  // Approximate pixel radius for a geofence circle in this local projection --
  // converts meters to degrees latitude (111,320 m/deg, same constant as
  // TollZoneMapPicker.tsx's circlePolygon) then to pixels via this bounding
  // box's own vertical scale. Only the vertical (lat) scale is used, same
  // simplifying assumption as project() above (which doesn't lng-compress by
  // cos(lat) either) -- fine for "roughly where the boundary is" at this
  // fallback's non-tile-based zoom, not for a geodesically exact circle.
  function projectedRadiusPx(radiusM: number): number {
    if (!bounds) return 0;
    const metersPerDegLat = 111_320;
    const yScale = (HEIGHT - PADDING * 2) / (bounds.maxLat - bounds.minLat);
    return (radiusM / metersPerDegLat) * yScale;
  }

  if (plotted.length === 0) {
    return (
      <div className="flex h-[460px] flex-col items-center justify-center gap-1 rounded-md border border-dashed border-border text-center text-sm text-muted-foreground">
        <p>No live vehicle positions yet.</p>
        <p className="text-xs">Positions appear here once a device publishes via POST /v1/fleet/positions.</p>
      </div>
    );
  }

  const hoveredVehicle = hoveredId ? plotted.find((v) => v.id === hoveredId) : undefined;
  const hoveredDuress = hoveredVehicle ? duressByVehicleId.get(hoveredVehicle.id) : undefined;
  const hoveredPos = hoveredVehicle ? project(hoveredVehicle.lat, hoveredVehicle.lng) : null;
  const hoveredFields = hoveredVehicle ? getHoverCardFields(hoveredVehicle, hoveredDuress) : null;

  return (
    <div className="relative">
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        className="w-full rounded-md border border-border bg-muted/40"
        role="img"
        aria-label="Fleet live map"
      >
        {Array.from({ length: 6 }).map((_, i) => (
          <line
            key={`v-${i}`}
            x1={(WIDTH / 5) * i}
            y1={0}
            x2={(WIDTH / 5) * i}
            y2={HEIGHT}
            stroke="var(--border)"
            strokeWidth={1}
          />
        ))}
        {Array.from({ length: 4 }).map((_, i) => (
          <line
            key={`h-${i}`}
            x1={0}
            y1={(HEIGHT / 3) * i}
            x2={WIDTH}
            y2={(HEIGHT / 3) * i}
            stroke="var(--border)"
            strokeWidth={1}
          />
        ))}

        {/* Geofence-breach overlay circles, drawn beneath every vehicle so a
            dispatcher can see the boundary itself -- same brand-accent/gold
            treatment as the Mapbox renderer's fill+line layers above, and as
            TollZoneMapPicker.tsx's own zone-radius preview. */}
        {geofences.map((g) => {
          const [cx, cy] = project(g.center_lat, g.center_lng);
          const r = projectedRadiusPx(g.radius_m);
          if (r <= 0) return null;
          return (
            <circle
              key={g.id}
              cx={cx}
              cy={cy}
              r={r}
              fill="var(--brand-accent)"
              fillOpacity={0.12}
              stroke="var(--brand-accent)"
              strokeWidth={1.5}
              strokeDasharray="4 3"
            />
          );
        })}

        {/* On-trip route-to-destination lines -- drawn beneath the vehicle
            <g>s below (same "route under markers, never over them" ordering
            as the Mapbox renderer's GL layers vs. its DOM markers) and in a
            color (ROUTE_LINE_COLOR) that appears nowhere else on this map, so
            it never reads as the same signal as a geofence boundary or the
            duress ring. Dashed = still the straight-line stand-in (Directions
            fetch pending or failed); solid = a real routed geometry -- see
            useVehicleRoutes.ts's own VehicleRouteState.isFallback doc. */}
        {Array.from(routes.entries()).map(([vehicleId, state]) => (
          <polyline
            key={`route-${vehicleId}`}
            points={state.points.map(([lat, lng]) => project(lat, lng).join(",")).join(" ")}
            fill="none"
            stroke={ROUTE_LINE_COLOR}
            strokeWidth={2.5}
            strokeOpacity={state.isFallback ? 0.55 : 0.85}
            strokeDasharray={state.isFallback ? "5 3" : undefined}
          />
        ))}

        {plotted.map((v) => {
          const [x, y] = project(v.lat, v.lng);
          const duressEvent = duressByVehicleId.get(v.id);
          const stale = isStale(v.position_updated_at);
          const idle = !stale && v.idleInfo.idle;
          const inGeofence = v.insideGeofences.length > 0;
          const color = duressEvent ? "var(--destructive)" : statusColor(v.live_status);
          const scale = duressEvent ? 1.3 : 1;
          return (
            <g
              key={v.id}
              transform={`translate(${x}, ${y})`}
              className="cursor-pointer"
              opacity={stale ? 0.5 : 1}
              onClick={() => (duressEvent ? navigate(`/duress?event=${duressEvent.id}`) : onSelectVehicle(v.id))}
              onMouseEnter={() => setHoveredId(v.id)}
              onMouseLeave={() => setHoveredId((id) => (id === v.id ? null : id))}
            >
              {duressEvent && (
                <circle r={13} fill="none" stroke="var(--destructive)" strokeWidth={2} opacity={0.5}>
                  <animate attributeName="r" values="9;15;9" dur="1.6s" repeatCount="indefinite" />
                  <animate attributeName="opacity" values="0.6;0.1;0.6" dur="1.6s" repeatCount="indefinite" />
                </circle>
              )}
              {/* Static (non-animated) stale/idle outline -- same dashed-vs-
                  dotted, muted-vs-amber distinction as the Mapbox renderer's
                  renderMarkerContent, so the two never disagree visually. */}
              {(stale || idle) && (
                <circle
                  r={11}
                  fill="none"
                  stroke={stale ? "var(--muted-foreground)" : "var(--warning, #d97706)"}
                  strokeWidth={2}
                  strokeDasharray={stale ? "3 2" : "1 2"}
                />
              )}
              {v.heading != null ? (
                <path
                  d={VEHICLE_ARROW_LOCAL_PATH}
                  transform={`scale(${scale}) rotate(${v.heading})`}
                  fill={color}
                  stroke="var(--card)"
                  strokeWidth={1.5}
                  strokeLinejoin="round"
                />
              ) : (
                // heading == null -- vehicle stationary or never reported one.
                // Same neutral-dot fallback as the Mapbox marker (buildVehicleGlyph)
                // rather than guessing a direction.
                <circle r={duressEvent ? 8 : 6} fill={color} stroke="var(--card)" strokeWidth={2} />
              )}
              {inGeofence && (
                <circle cx={7} cy={-7} r={3} fill="var(--brand-accent)" stroke="var(--card)" strokeWidth={1} />
              )}
              <text y={-14} textAnchor="middle" fontSize={10} style={{ fill: "var(--foreground)" }}>
                {v.rego}
              </text>
            </g>
          );
        })}
      </svg>

      {hoveredVehicle && hoveredPos && hoveredFields && (
        <div
          className="pointer-events-none absolute z-10 rounded-lg border px-2.5 py-2 text-xs shadow-lg"
          style={{
            left: `${(hoveredPos[0] / WIDTH) * 100}%`,
            top: `${(hoveredPos[1] / HEIGHT) * 100}%`,
            transform: "translate(-50%, calc(-100% - 16px))",
            background: "var(--card)",
            color: "var(--card-foreground)",
            borderColor: "var(--border)",
            lineHeight: 1.6,
            minWidth: "150px",
          }}
        >
          <div className="mb-0.5 font-semibold">{hoveredFields.rego}</div>
          <div className="flex justify-between gap-3">
            <span style={{ color: "var(--muted-foreground)" }}>Status</span>
            <span style={hoveredFields.duressActive ? { color: "var(--destructive)" } : undefined}>
              {hoveredFields.duressActive ? "Duress active" : hoveredFields.statusLabel}
            </span>
          </div>
          {HOVER_CARD_ROWS.map(([label, key]) => (
            <div key={label} className="flex justify-between gap-3">
              <span style={{ color: "var(--muted-foreground)" }}>{label}</span>
              <span>{hoveredFields[key]}</span>
            </div>
          ))}
          {hoveredFields.staleLabel && (
            <div className="mt-1" style={{ color: "var(--muted-foreground)" }}>
              {hoveredFields.staleLabel}
            </div>
          )}
          {hoveredFields.idleLabel && (
            <div className="mt-1" style={{ color: "var(--warning, #d97706)" }}>
              {hoveredFields.idleLabel}
            </div>
          )}
          {hoveredFields.geofenceNames.length > 0 && (
            <div className="mt-1" style={{ color: "var(--brand-accent)" }}>
              Inside {hoveredFields.geofenceNames.join(", ")}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
