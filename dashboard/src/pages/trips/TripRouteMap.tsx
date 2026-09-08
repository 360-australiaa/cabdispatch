import { useEffect, useRef, useState } from "react";
import mapboxgl from "mapbox-gl";
import "mapbox-gl/dist/mapbox-gl.css";
import { MapPinOff } from "lucide-react";

// Same public/publishable token + custom global style as the Live Map and
// Tariff Studio's toll-zone picker (pages/live-map/FleetMapCanvas.tsx,
// pages/tariffs/TollZoneMapPicker.tsx) -- falls back to a plain text summary
// below when unset so this modal never breaks without a token configured.
const MAPBOX_TOKEN = import.meta.env.VITE_MAPBOX_TOKEN;
const MAP_STYLE = "mapbox://styles/benfarid/cmtbnyhe4000e01pcgx2t51za";
const SINGLE_POINT_ZOOM = 14;
const FIT_PADDING = 48;
const FIT_MAX_ZOOM = 16;

// Plain hex, not CSS custom properties -- these paint canvas-rendered
// Mapbox GL layers, whose own color parser doesn't understand var(...)
// (same posture as FleetMapCanvas's ROUTE_LINE_COLOR constant). The map's
// own style doesn't change with the app's light/dark toggle, so a fixed
// color chosen for legibility against the map tiles is correct regardless
// of theme -- unlike the "A"/"B" marker pins below, which are plain HTML
// elements and can (and do) use real CSS custom properties.
//
// TRACE_LINE_COLOR is distinct from FleetMapCanvas's ROUTE_LINE_COLOR (a
// *live* route-to-destination line, different page/concept) -- the two are
// never shown together so there's no need to share a constant.
const TRACE_LINE_COLOR = "#2563eb";
const FALLBACK_LINE_COLOR = "#9ca3af";

export interface TripRoutePoint {
  lat: number;
  lng: number;
}

export interface TripRouteMapProps {
  /** Pickup point. Trip.start_lat/start_lng are non-nullable on the wire, but
   * this component still guards against a missing/invalid value defensively
   * rather than assuming the type -- see hasCoords below. */
  startLat: number | null | undefined;
  startLng: number | null | undefined;
  /** Drop-off point. Null on an open trip (no end recorded yet) -- see
   * Trip.end_lat/end_lng's doc comment (backend/app/models/trips.py). */
  endLat: number | null | undefined;
  endLng: number | null | undefined;
  /**
   * Real recorded GPS trace points, in chronological order, when available.
   * The dashboard's `Trip` type (hooks/useTrips.ts) has no field for this
   * today -- the backend's `TripRead` only exposes `gps_trace_ref`, an opaque
   * string set by whichever client synced the trip, with no endpoint that
   * resolves it back to real points; the raw `gps_trace` array `TripSyncItem`
   * accepts is used once, transiently, to recompute the fare/distance server-
   * side (`app.services.trips.recompute_from_trace`) and is never persisted
   * anywhere retrievable. This prop exists so the rendering below already
   * draws the real driven path the day a real trace becomes fetchable --
   * today no caller has one to pass, so it's always omitted and the map
   * degrades honestly to the straight-line stand-in below.
   */
  trace?: TripRoutePoint[] | null;
  className?: string;
}

function hasCoords(lat: number | null | undefined, lng: number | null | undefined): boolean {
  return (
    typeof lat === "number" &&
    typeof lng === "number" &&
    Number.isFinite(lat) &&
    Number.isFinite(lng) &&
    lat >= -90 &&
    lat <= 90 &&
    lng >= -180 &&
    lng <= 180
  );
}

function validTracePoints(trace: TripRoutePoint[] | null | undefined): Array<[number, number]> {
  if (!trace || trace.length < 2) return [];
  const points: Array<[number, number]> = [];
  for (const p of trace) {
    if (!hasCoords(p.lat, p.lng)) return []; // one bad point -- don't fabricate a partial path
    points.push([p.lng, p.lat]);
  }
  return points;
}

function fitToPoints(map: mapboxgl.Map, points: Array<[number, number]>) {
  if (points.length === 0) return;
  if (points.length === 1) {
    map.jumpTo({ center: points[0], zoom: SINGLE_POINT_ZOOM });
    return;
  }
  const bounds = new mapboxgl.LngLatBounds();
  for (const p of points) bounds.extend(p);
  map.fitBounds(bounds, { padding: FIT_PADDING, maxZoom: FIT_MAX_ZOOM, duration: 0 });
}

/** Builds an "A"/"B" pin marker element -- a plain DOM node (not JSX), same
 * convention FleetMapCanvas's marker-building uses because mapboxgl.Marker
 * wants a real Node. Colors are design-system tokens that don't change
 * between light/dark mode (--success/--brand-accent are only defined once,
 * in index.css's :root) so the pins read the same in both themes. */
function buildPinElement(label: "A" | "B", background: string, foreground: string): HTMLDivElement {
  const el = document.createElement("div");
  el.style.width = "26px";
  el.style.height = "26px";
  el.style.borderRadius = "9999px";
  el.style.backgroundColor = background;
  el.style.color = foreground;
  el.style.border = "2px solid #fff";
  el.style.boxShadow = "0 1px 3px rgba(0,0,0,0.5)";
  el.style.display = "flex";
  el.style.alignItems = "center";
  el.style.justifyContent = "center";
  el.style.fontSize = "12px";
  el.style.fontWeight = "700";
  el.style.fontFamily = "Inter, ui-sans-serif, system-ui, sans-serif";
  el.style.cursor = "default";
  el.textContent = label;
  el.title = label === "A" ? "Pickup" : "Drop-off";
  return el;
}

const ROUTE_SOURCE_ID = "trip-route-line";
// Two separate static-paint layers (filtered by a "kind" property on the
// shared source) rather than one data-driven layer -- line-dasharray isn't a
// data-driven-capable paint property in Mapbox GL's style spec, so a single
// layer can't switch between solid/dashed per-feature via an expression.
// Only one of the two ever has data in practice (a trip either has a real
// trace or falls back to the straight line, never both), but keeping them as
// two plain layers means every paint value here is a static, valid color/
// dasharray -- no expression to get wrong.
const TRACE_LAYER_ID = "trip-route-trace-layer";
const FALLBACK_LAYER_ID = "trip-route-fallback-layer";

/** One-line honest caption for whatever's actually drawn -- always visible
 * under the map so a straight reference line is never mistaken for a real
 * route, and a missing endpoint always reads as "not recorded", not as an
 * error or a silent gap. */
function captionFor(hasStart: boolean, hasEnd: boolean, hasRealTrace: boolean): string {
  if (hasStart && hasEnd) {
    return hasRealTrace
      ? "Solid line shows the GPS path actually recorded for this trip."
      : "Dashed line is a straight reference between pickup and drop-off — no GPS trace was recorded for this trip.";
  }
  if (hasStart) return "Drop-off location not recorded for this trip.";
  return "Pickup location not recorded for this trip.";
}

/** Trip detail map snapshot: an "A" pin at pickup, a "B" pin at drop-off, the
 * real driven path when one is available, and an honest degrade (plus
 * caption explaining exactly what's on screen) for every other case. Falls
 * back to a plain text summary when no VITE_MAPBOX_TOKEN is configured, same
 * posture as TollZoneMapPicker. */
export function TripRouteMap({ startLat, startLng, endLat, endLng, trace, className }: TripRouteMapProps) {
  const hasStart = hasCoords(startLat, startLng);
  const hasEnd = hasCoords(endLat, endLng);

  if (!hasStart && !hasEnd) {
    return <TripRouteMapEmpty className={className} />;
  }

  const hasRealTrace = validTracePoints(trace).length >= 2;

  return (
    <div className="space-y-1.5">
      {MAPBOX_TOKEN ? (
        <MapboxTripRouteMap
          startLat={startLat}
          startLng={startLng}
          endLat={endLat}
          endLng={endLng}
          trace={trace}
          className={className}
        />
      ) : (
        <PlainCoordsFallback
          startLat={startLat}
          startLng={startLng}
          endLat={endLat}
          endLng={endLng}
          className={className}
        />
      )}
      <p className="text-xs text-muted-foreground">{captionFor(hasStart, hasEnd, hasRealTrace)}</p>
    </div>
  );
}

function MapboxTripRouteMap({ startLat, startLng, endLat, endLng, trace, className }: TripRouteMapProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<mapboxgl.Map | null>(null);
  const startMarkerRef = useRef<mapboxgl.Marker | null>(null);
  const endMarkerRef = useRef<mapboxgl.Marker | null>(null);
  const [styleLoaded, setStyleLoaded] = useState(false);

  const hasStart = hasCoords(startLat, startLng);
  const hasEnd = hasCoords(endLat, endLng);

  // Init the map once.
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;

    mapboxgl.accessToken = MAPBOX_TOKEN as string;
    const initialPoints: Array<[number, number]> = [];
    if (hasCoords(startLat, startLng)) initialPoints.push([startLng as number, startLat as number]);
    if (hasCoords(endLat, endLng)) initialPoints.push([endLng as number, endLat as number]);

    const map = new mapboxgl.Map({
      container: containerRef.current,
      style: MAP_STYLE,
      center: initialPoints[0] ?? [151.2093, -33.8688],
      zoom: initialPoints.length > 0 ? SINGLE_POINT_ZOOM : 10,
    });
    map.addControl(new mapboxgl.NavigationControl({ showCompass: false }), "top-right");
    mapRef.current = map;

    map.on("load", () => {
      map.resize();
      map.addSource(ROUTE_SOURCE_ID, {
        type: "geojson",
        data: { type: "FeatureCollection", features: [] },
      });
      map.addLayer({
        id: TRACE_LAYER_ID,
        type: "line",
        source: ROUTE_SOURCE_ID,
        filter: ["==", ["get", "kind"], "trace"],
        layout: { "line-join": "round", "line-cap": "round" },
        paint: { "line-color": TRACE_LINE_COLOR, "line-width": 3 },
      });
      map.addLayer({
        id: FALLBACK_LAYER_ID,
        type: "line",
        source: ROUTE_SOURCE_ID,
        filter: ["==", ["get", "kind"], "fallback"],
        layout: { "line-join": "round", "line-cap": "round" },
        paint: { "line-color": FALLBACK_LINE_COLOR, "line-width": 2, "line-dasharray": [2, 2] },
      });
      setStyleLoaded(true);
      fitToPoints(map, initialPoints.length > 0 ? initialPoints : []);
    });

    return () => {
      startMarkerRef.current?.remove();
      startMarkerRef.current = null;
      endMarkerRef.current?.remove();
      endMarkerRef.current = null;
      map.remove();
      mapRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- init once, synced below
  }, []);

  // Keep markers/line/bounds in sync with the current trip's points.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !styleLoaded) return;

    if (hasStart) {
      if (!startMarkerRef.current) {
        startMarkerRef.current = new mapboxgl.Marker({
          element: buildPinElement("A", "var(--success)", "var(--success-foreground)"),
          anchor: "center",
        })
          .setLngLat([startLng as number, startLat as number])
          .addTo(map);
      } else {
        startMarkerRef.current.setLngLat([startLng as number, startLat as number]);
      }
    } else {
      startMarkerRef.current?.remove();
      startMarkerRef.current = null;
    }

    if (hasEnd) {
      if (!endMarkerRef.current) {
        endMarkerRef.current = new mapboxgl.Marker({
          element: buildPinElement("B", "var(--brand-accent)", "var(--brand-accent-foreground)"),
          anchor: "center",
        })
          .setLngLat([endLng as number, endLat as number])
          .addTo(map);
      } else {
        endMarkerRef.current.setLngLat([endLng as number, endLat as number]);
      }
    } else {
      endMarkerRef.current?.remove();
      endMarkerRef.current = null;
    }

    const tracePoints = validTracePoints(trace);
    const source = map.getSource(ROUTE_SOURCE_ID) as mapboxgl.GeoJSONSource | undefined;
    if (source) {
      const features = [];
      if (tracePoints.length >= 2) {
        // A real recorded path -- solid line (TRACE_LAYER_ID).
        features.push({
          type: "Feature" as const,
          geometry: { type: "LineString" as const, coordinates: tracePoints },
          properties: { kind: "trace" },
        });
      } else if (hasStart && hasEnd) {
        // No trace recorded -- an honestly-labeled straight reference line
        // (FALLBACK_LAYER_ID, dashed), never presented as the driven route --
        // see TripDetailModal's caption text, rendered alongside this map.
        features.push({
          type: "Feature" as const,
          geometry: {
            type: "LineString" as const,
            coordinates: [
              [startLng as number, startLat as number],
              [endLng as number, endLat as number],
            ],
          },
          properties: { kind: "fallback" },
        });
      }
      source.setData({ type: "FeatureCollection", features });
    }

    const boundsPoints: Array<[number, number]> = [];
    if (tracePoints.length >= 2) {
      boundsPoints.push(...tracePoints);
    } else {
      if (hasStart) boundsPoints.push([startLng as number, startLat as number]);
      if (hasEnd) boundsPoints.push([endLng as number, endLat as number]);
    }
    fitToPoints(map, boundsPoints);
  }, [hasStart, hasEnd, startLat, startLng, endLat, endLng, trace, styleLoaded]);

  return (
    <div
      ref={containerRef}
      className={className ?? "h-64 w-full rounded-md border border-border"}
    />
  );
}

/** No-token fallback -- plain, honest text summary of whichever coordinates
 * exist. Never renders a map centered on a fabricated point. */
function PlainCoordsFallback({ startLat, startLng, endLat, endLng, className }: TripRouteMapProps) {
  const hasStart = hasCoords(startLat, startLng);
  const hasEnd = hasCoords(endLat, endLng);

  return (
    <div
      className={
        className ?? "flex h-64 w-full flex-col justify-center gap-2 rounded-md border border-dashed border-border p-4"
      }
    >
      <p className="text-xs text-muted-foreground">
        No VITE_MAPBOX_TOKEN configured -- showing coordinates only.
      </p>
      <p className="text-sm text-foreground">
        <span className="font-semibold">A · Pickup:</span>{" "}
        {hasStart ? `${(startLat as number).toFixed(5)}, ${(startLng as number).toFixed(5)}` : "not recorded"}
      </p>
      <p className="text-sm text-foreground">
        <span className="font-semibold">B · Drop-off:</span>{" "}
        {hasEnd ? `${(endLat as number).toFixed(5)}, ${(endLng as number).toFixed(5)}` : "not recorded"}
      </p>
    </div>
  );
}

/** Honest empty state for a trip with no usable coordinates at all -- never a
 * map centered on 0,0 ("null island"), never fabricated coordinates. */
export function TripRouteMapEmpty({ className }: { className?: string }) {
  return (
    <div
      className={
        className ??
        "flex h-64 w-full flex-col items-center justify-center gap-2 rounded-md border border-dashed border-border p-4 text-center"
      }
    >
      <MapPinOff className="h-6 w-6 text-muted-foreground" />
      <p className="text-sm text-muted-foreground">No location recorded for this trip.</p>
    </div>
  );
}
