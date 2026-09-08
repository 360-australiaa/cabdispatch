import mapboxgl from "mapbox-gl";
import type { DuressEventRead } from "./types";
import type { PlottedVehicle } from "./mapTypes";
import { formatRelativeTime, formatSpeed, idleLabel, isStale, staleLabel, statusColor } from "./utils";

/**
 * Everything the Mapbox renderer builds by hand in the DOM: the vehicle glyph,
 * the marker shell and its content, the theme-aware hover card, the popup
 * style shim, and the position tween.
 *
 * Extracted verbatim from FleetMapCanvas.tsx during the Phase 0 file split.
 * The hover-card *fields* (getHoverCardFields / HOVER_CARD_ROWS) live here too
 * even though the plain-SVG fallback renders them as JSX, because that is
 * exactly the point of them: one computation, two renderers, so the Mapbox
 * popup and the fallback card can never disagree.
 */

// A small upward-pointing arrow/car glyph (viewBox 0 0 24 24, tip at top) --
// shared (as a path shape) between the Mapbox marker (DOM/SVG, below) and the
// plain-SVG fallback's own locally-scaled copy of the same silhouette, so the
// two renderers stay in visual parity.
export const VEHICLE_ARROW_VIEWBOX_PATH = "M12 2L19 21L12 17L5 21Z";

/** Fields shown in the vehicle hover card, computed once and rendered by
 * both the Mapbox popup (plain DOM, buildHoverCardElement) and the plain-SVG
 * fallback (JSX, PlainCanvasMap) so the two never drift out of sync. */
export interface HoverCardFields {
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

export function getHoverCardFields(
  vehicle: PlottedVehicle,
  duressEvent: DuressEventRead | undefined,
): HoverCardFields {
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

export const HOVER_CARD_ROWS: Array<
  [label: string, key: keyof Pick<HoverCardFields, "speedLabel" | "batteryLabel" | "networkLabel" | "updatedLabel">]
> = [
  ["Speed", "speedLabel"],
  ["Battery", "batteryLabel"],
  ["Network", "networkLabel"],
  ["Updated", "updatedLabel"],
];

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
export function renderMarkerContent(
  iconWrap: HTMLDivElement,
  labelEl: HTMLSpanElement,
  vehicle: PlottedVehicle,
  duressEvent: DuressEventRead | undefined,
  selected: boolean,
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

  if (selected) {
    // A static halo behind the glyph, never animated.
    //
    // The obvious thing here is a pulse, and this codebase already reverted
    // decorative marker animation once on real user feedback -- see the
    // POSITION_TWEEN comment below: only data-driven motion is acceptable on
    // this map. A ring that simply sits there says "this is the one you picked"
    // just as clearly and does not compete with the duress pulse, which is the
    // one animation on this map that must never be mistaken for anything else.
    const ring = document.createElement("div");
    ring.style.position = "absolute";
    ring.style.inset = "-9px";
    ring.style.borderRadius = "999px";
    ring.style.border = "2px solid var(--brand-accent)";
    ring.style.boxShadow = "0 0 0 3px rgba(0,0,0,0.35)";
    ring.style.pointerEvents = "none";
    iconWrap.appendChild(ring);
  }

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
export function buildMarkerShell(): { el: HTMLDivElement; iconWrap: HTMLDivElement; labelEl: HTMLSpanElement } {
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
export function buildHoverCardElement(
  vehicle: PlottedVehicle,
  duressEvent: DuressEventRead | undefined,
): HTMLDivElement {
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

export function ensurePopupStyleInjected() {
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

export interface MarkerEntry {
  marker: mapboxgl.Marker;
  popup: mapboxgl.Popup;
  el: HTMLDivElement;
  iconWrap: HTMLDivElement;
  labelEl: HTMLSpanElement;
  vehicle: PlottedVehicle;
  duressEvent: DuressEventRead | undefined;
  rafId: number | null;
}

export function stopTween(entry: MarkerEntry) {
  if (entry.rafId != null) {
    cancelAnimationFrame(entry.rafId);
    entry.rafId = null;
  }
}

/** Glides a marker (and its popup, if currently open) from its current
 * lngLat to `to` over POSITION_TWEEN_MS. Cancels any tween already in flight
 * for this marker first, so a newer position arriving mid-glide replaces the
 * old target cleanly instead of racing it. */
export function tweenMarkerTo(entry: MarkerEntry, to: [number, number]) {
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
