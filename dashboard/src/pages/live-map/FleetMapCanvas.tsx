import { useEffect, useMemo, useRef } from "react";
import { useNavigate, type NavigateFunction } from "react-router-dom";
import mapboxgl from "mapbox-gl";
import "mapbox-gl/dist/mapbox-gl.css";
import type { DuressEventRead, VehicleLiveRead } from "./types";
import { statusColor } from "./utils";

interface FleetMapCanvasProps {
  vehicles: VehicleLiveRead[];
  duressEvents: DuressEventRead[];
}

type PlottedVehicle = VehicleLiveRead & { lat: number; lng: number };

const WIDTH = 900;
const HEIGHT = 460;
const PADDING = 32;

const SYDNEY_CENTER: [number, number] = [151.2093, -33.8688];
const DEFAULT_ZOOM = 10.5;
const SINGLE_VEHICLE_ZOOM = 13;

// Public/publishable Mapbox token — safe to ship in a client bundle (see .env.example).
// Falls back to the plain-SVG plot below when unset so the dashboard still works offline
// / without a maps API key.
const MAPBOX_TOKEN = import.meta.env.VITE_MAPBOX_TOKEN;

interface MapDataProps {
  plotted: PlottedVehicle[];
  duressByVehicleId: Map<string, DuressEventRead>;
}

/**
 * Live fleet map. Renders a real Mapbox GL JS map (dark style, Sydney-centered
 * or fit to the fleet's bounding box) when VITE_MAPBOX_TOKEN is configured;
 * otherwise falls back to a plain-SVG lat/lng plot so the page never breaks
 * for anyone without a token set up (see PlainCanvasMap below).
 */
export function FleetMapCanvas({ vehicles, duressEvents }: FleetMapCanvasProps) {
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

  if (MAPBOX_TOKEN) {
    return <MapboxFleetMap plotted={plotted} duressByVehicleId={duressByVehicleId} />;
  }

  return <PlainCanvasMap plotted={plotted} duressByVehicleId={duressByVehicleId} />;
}

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

function buildMarkerElement(
  vehicle: PlottedVehicle,
  duressEvent: DuressEventRead | undefined,
  navigate: NavigateFunction,
): HTMLDivElement {
  const size = duressEvent ? 16 : 12;
  const color = duressEvent ? "var(--destructive)" : statusColor(vehicle.live_status);

  const el = document.createElement("div");
  el.style.position = "relative";
  el.style.width = `${size}px`;
  el.style.height = `${size}px`;
  el.style.borderRadius = "9999px";
  el.style.background = color;
  el.style.border = "2px solid var(--card)";
  el.style.boxShadow = "0 1px 3px rgba(0,0,0,0.45)";
  el.title = duressEvent
    ? `${vehicle.rego} — active duress event`
    : `${vehicle.rego} (${vehicle.live_status})`;

  if (duressEvent) {
    el.style.cursor = "pointer";
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
    el.appendChild(ring);
    el.addEventListener("click", () => navigate(`/duress?event=${duressEvent.id}`));
  }

  const label = document.createElement("span");
  label.textContent = vehicle.rego;
  label.style.position = "absolute";
  label.style.bottom = `${size + 6}px`;
  label.style.left = "50%";
  label.style.transform = "translateX(-50%)";
  label.style.fontSize = "10px";
  label.style.fontWeight = "500";
  label.style.color = "#fff";
  label.style.textShadow = "0 1px 2px rgba(0,0,0,0.8)";
  label.style.whiteSpace = "nowrap";
  label.style.pointerEvents = "none";
  el.appendChild(label);

  return el;
}

function MapboxFleetMap({ plotted, duressByVehicleId }: MapDataProps) {
  const navigate = useNavigate();
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<mapboxgl.Map | null>(null);
  const markersRef = useRef<mapboxgl.Marker[]>([]);

  // Init the map once. Initial framing (Sydney vs. fleet bounding box) uses
  // whatever `plotted` this component mounted with — by the time it renders,
  // the parent's vehicles query has already resolved (see LiveMapPage).
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;

    mapboxgl.accessToken = MAPBOX_TOKEN as string;
    const map = new mapboxgl.Map({
      container: containerRef.current,
      style: "mapbox://styles/mapbox/dark-v11",
      center: SYDNEY_CENTER,
      zoom: DEFAULT_ZOOM,
    });
    map.addControl(new mapboxgl.NavigationControl({ showCompass: false }), "top-right");
    mapRef.current = map;

    map.on("load", () => {
      map.resize();
      fitToVehicles(map, plotted);
    });

    return () => {
      markersRef.current.forEach((m) => m.remove());
      markersRef.current = [];
      map.remove();
      mapRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- init once; see comment above
  }, []);

  // Keep markers in sync with live vehicle positions and duress state.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    markersRef.current.forEach((m) => m.remove());
    markersRef.current = plotted.map((v) => {
      const duressEvent = duressByVehicleId.get(v.id);
      const el = buildMarkerElement(v, duressEvent, navigate);
      return new mapboxgl.Marker({ element: el, anchor: "center" }).setLngLat([v.lng, v.lat]).addTo(map);
    });
  }, [plotted, duressByVehicleId, navigate]);

  return (
    <div className="relative">
      <div ref={containerRef} className="h-[460px] w-full rounded-md border border-border" />
      {plotted.length === 0 && (
        <div className="pointer-events-none absolute left-3 top-3 rounded-md bg-card/90 px-3 py-1.5 text-xs text-muted-foreground shadow">
          No live vehicle positions yet — showing Sydney. Positions appear here once a device
          publishes via POST /v1/fleet/positions.
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Plain-SVG fallback — no Mapbox token configured
// ---------------------------------------------------------------------------

/**
 * Plain-SVG lat/lng plot, used when no Mapbox token is configured. Vehicles
 * are projected into a local bounding box (not real map tiles), colored by
 * live status, and any vehicle with an open duress event is drawn oversized
 * in red with a click target that routes to `/duress?event=<id>` (the "red
 * pin" requirement — since a duress row itself has no lat/lng, its pin
 * position is its vehicle's last-known position).
 */
function PlainCanvasMap({ plotted, duressByVehicleId }: MapDataProps) {
  const navigate = useNavigate();

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

  if (plotted.length === 0) {
    return (
      <div className="flex h-[460px] flex-col items-center justify-center gap-1 rounded-md border border-dashed border-border text-center text-sm text-muted-foreground">
        <p>No live vehicle positions yet.</p>
        <p className="text-xs">Positions appear here once a device publishes via POST /v1/fleet/positions.</p>
      </div>
    );
  }

  return (
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

      {plotted.map((v) => {
        const [x, y] = project(v.lat, v.lng);
        const duressEvent = duressByVehicleId.get(v.id);
        const color = duressEvent ? "var(--destructive)" : statusColor(v.live_status);
        return (
          <g
            key={v.id}
            transform={`translate(${x}, ${y})`}
            className={duressEvent ? "cursor-pointer" : undefined}
            onClick={duressEvent ? () => navigate(`/duress?event=${duressEvent.id}`) : undefined}
          >
            {duressEvent && (
              <circle r={13} fill="none" stroke="var(--destructive)" strokeWidth={2} opacity={0.5}>
                <animate attributeName="r" values="9;15;9" dur="1.6s" repeatCount="indefinite" />
                <animate attributeName="opacity" values="0.6;0.1;0.6" dur="1.6s" repeatCount="indefinite" />
              </circle>
            )}
            <circle r={duressEvent ? 8 : 6} fill={color} stroke="var(--card)" strokeWidth={2} />
            <text y={-14} textAnchor="middle" fontSize={10} style={{ fill: "var(--foreground)" }}>
              {v.rego}
            </text>
          </g>
        );
      })}
    </svg>
  );
}
