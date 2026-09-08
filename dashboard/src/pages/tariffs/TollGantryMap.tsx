import { useEffect, useMemo, useRef } from "react";
import mapboxgl from "mapbox-gl";
import "mapbox-gl/dist/mapbox-gl.css";
import { Table, type TableColumn } from "@/components/ui";
import type { TollGantry, TollRoad } from "@/hooks/useTollRoads";

// Same public/publishable token pattern as the Live Map and the toll-zone
// picker (src/pages/live-map/FleetMapCanvas.tsx, TollZoneMapPicker.tsx) —
// falls back to a plain coordinate table below when unset, so the pinpoints
// are always readable even on an install with no map token configured.
const MAPBOX_TOKEN = import.meta.env.VITE_MAPBOX_TOKEN;

const SYDNEY_CENTER: [number, number] = [151.0500, -33.8300];
const DEFAULT_ZOOM = 9;

/** Minimal local GeoJSON shapes.
 *
 * This project has no `@types/geojson` dependency, so the global `GeoJSON`
 * namespace does not exist here, and mapbox-gl's own exported `GeoJSONFeature`
 * type does not expose `properties`/`geometry` to consumers. Rather than pull
 * in a types package for four fields, the two shapes actually used are
 * declared here — and the hover handler narrows through `unknown` into
 * [HoveredFeature] at the one place a feature is read back out of the map. */
interface PointFeatureCollection {
  type: "FeatureCollection";
  features: {
    type: "Feature";
    geometry: { type: "Point"; coordinates: [number, number] };
    properties: Record<string, string>;
  }[];
}

interface HoveredFeature {
  geometry: { coordinates: [number, number] };
  properties: Record<string, string> | null;
}

const SOURCE_ID = "toll-gantries";
const CIRCLE_LAYER_ID = "toll-gantries-circles";
const LINE_SOURCE_ID = "toll-corridors";
const LINE_LAYER_ID = "toll-corridor-lines";
const HALO_LAYER_ID = "toll-gantry-halo";
const LABEL_LAYER_ID = "toll-gantry-labels";

/** One colour per road, so the 141 pinpoints read as real corridors rather
 * than an undifferentiated spray of dots. Keyed by the registry's own road
 * ids; anything not listed (a future road, or the M12 stub) falls back to
 * the neutral colour below rather than disappearing. */
const ROAD_COLORS: Record<string, string> = {
  M7: "#6366f1",
  M4: "#f97316",
  M8: "#14b8a6",
  M5E: "#0ea5e9",
  M5SW: "#84cc16",
  M2: "#ec4899",
  M4M8_LINK: "#eab308",
  ROZELLE_INTERCHANGE: "#a855f7",
  CCT: "#ef4444",
  LCT: "#22c55e",
  ED: "#f43f5e",
  NORTHCONNEX: "#06b6d4",
  SHB_SHT: "#8b5cf6",
  M12: "#94a3b8",
};
const FALLBACK_COLOR = "#94a3b8";

function colorFor(roadId: string): string {
  return ROAD_COLORS[roadId] ?? FALLBACK_COLOR;
}

interface TollGantryMapProps {
  gantries: TollGantry[];
  roads: TollRoad[];
  isLoading: boolean;
  isError: boolean;
}

/**
 * Every physical toll gantry in the NSW registry, plotted.
 *
 * These coordinates are not decoration: they are exactly what the meter
 * matches a GPS fix against to decide a toll was crossed (within 150m — see
 * the Android `TOLL_GANTRY_DETECTION_RADIUS_M`). Until this existed the
 * dashboard showed a gantry's NAME and nothing else, so there was no way to
 * check that the registry's idea of where a toll point sits matches the real
 * road — the single most consequential piece of data in automatic toll
 * detection was the one piece an operator could not see.
 */
export function TollGantryMap({ gantries, roads, isLoading, isError }: TollGantryMapProps) {
  const roadNames = useMemo(() => {
    const out: Record<string, string> = {};
    for (const road of roads) out[road.id] = road.name;
    return out;
  }, [roads]);

  if (isError) {
    return (
      <p className="rounded-md border border-border p-4 text-sm text-destructive">
        Failed to load gantry coordinates. The prices above are unaffected.
      </p>
    );
  }
  if (isLoading) {
    return <p className="rounded-md border border-border p-4 text-sm text-muted-foreground">Loading gantries…</p>;
  }
  if (gantries.length === 0) {
    return (
      <p className="rounded-md border border-border p-4 text-sm text-muted-foreground">
        No gantries loaded — run <span className="font-mono">scripts/seed_toll_roads.py</span> on the server.
      </p>
    );
  }

  return (
    <div>
      {MAPBOX_TOKEN ? (
        <MapboxGantryCanvas gantries={gantries} roadNames={roadNames} />
      ) : (
        <PlainCoordinateTable gantries={gantries} roadNames={roadNames} />
      )}
      <Legend gantries={gantries} roadNames={roadNames} />
    </div>
  );
}

function MapboxGantryCanvas({
  gantries,
  roadNames,
}: {
  gantries: TollGantry[];
  roadNames: Record<string, string>;
}) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<mapboxgl.Map | null>(null);
  // Read inside map event handlers, which are registered once and would
  // otherwise close over the first render's values forever.
  const dataRef = useRef({ gantries, roadNames });
  dataRef.current = { gantries, roadNames };

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;

    mapboxgl.accessToken = MAPBOX_TOKEN as string;
    const map = new mapboxgl.Map({
      container: containerRef.current,
      // navigation-night, not the custom near-black brand style (owner, 2026-09-08: "the toll
      // map is very black, I can't see the entry or exit point"). This is the one map whose
      // whole job is to show WHERE on a real road a charge fires, so the road network has to be
      // readable underneath the gantries. Still dark, still on-theme.
      style: "mapbox://styles/mapbox/navigation-night-v1",
      center: SYDNEY_CENTER,
      zoom: DEFAULT_ZOOM,
    });
    map.addControl(new mapboxgl.NavigationControl({ showCompass: false }), "top-right");
    mapRef.current = map;

    ensurePopupStyles();
    const popup = new mapboxgl.Popup({ closeButton: false, closeOnClick: false, className: "toll-popup", offset: 14 });

    map.on("load", () => {
      map.addSource(SOURCE_ID, {
        type: "geojson",
        data: featureCollection(dataRef.current.gantries, dataRef.current.roadNames),
      });
      // One circle layer over a GeoJSON source rather than 141 DOM Markers:
      // markers are individually positioned on every frame of a pan/zoom,
      // which at this count visibly stutters; a circle layer is drawn by the
      // GPU in one pass.
      // Corridor lines FIRST (so they draw under the points): one LineString per road through
      // its gantries in registry order. A toll road is a route, not a scatter of dots, and the
      // owner could not tell entry from exit when it was only dots on black.
      map.addSource(LINE_SOURCE_ID, { type: "geojson", data: corridorCollection(dataRef.current.gantries) });
      map.addLayer({
        id: LINE_LAYER_ID,
        type: "line",
        source: LINE_SOURCE_ID,
        layout: { "line-cap": "round", "line-join": "round" },
        paint: {
          "line-color": ["get", "color"],
          "line-width": ["interpolate", ["linear"], ["zoom"], 8, 2, 12, 4, 16, 7],
          "line-opacity": 0.85,
        },
      });
      // Soft halo under each gantry so the points read against roads of any colour.
      map.addLayer({
        id: HALO_LAYER_ID,
        type: "circle",
        source: SOURCE_ID,
        paint: {
          "circle-radius": ["interpolate", ["linear"], ["zoom"], 8, 7, 12, 11, 16, 18],
          "circle-color": ["get", "color"],
          "circle-opacity": 0.28,
          "circle-blur": 0.6,
        },
      });
      map.addLayer({
        id: CIRCLE_LAYER_ID,
        type: "circle",
        source: SOURCE_ID,
        paint: {
          "circle-radius": ["interpolate", ["linear"], ["zoom"], 8, 4, 12, 7, 16, 11],
          "circle-color": ["get", "color"],
          "circle-stroke-width": 2,
          "circle-stroke-color": "#ffffff",
        },
      });
      // Gantry names on the map itself from zoom 11, so entry/exit ramps are legible without
      // hovering every point.
      map.addLayer({
        id: LABEL_LAYER_ID,
        type: "symbol",
        source: SOURCE_ID,
        minzoom: 11,
        layout: {
          "text-field": ["get", "shortLabel"],
          "text-size": ["interpolate", ["linear"], ["zoom"], 11, 10, 15, 13],
          "text-offset": [0, 1.4],
          "text-anchor": "top",
          "text-font": ["DIN Pro Medium", "Arial Unicode MS Regular"],
          "text-optional": true,
        },
        paint: {
          "text-color": "#f5f7fb",
          "text-halo-color": "#080710",
          "text-halo-width": 1.4,
        },
      });

      // Fit to the real data rather than a hardcoded Sydney box, so a future
      // road outside the current extent is never silently off-screen.
      const bounds = new mapboxgl.LngLatBounds();
      for (const g of dataRef.current.gantries) bounds.extend([g.longitude, g.latitude]);
      if (!bounds.isEmpty()) map.fitBounds(bounds, { padding: 48, maxZoom: 13, duration: 0 });
    });

    map.on("mouseenter", CIRCLE_LAYER_ID, (e) => {
      map.getCanvas().style.cursor = "pointer";
      const feature = e.features?.[0] as unknown as HoveredFeature | undefined;
      const props = feature?.properties;
      if (!feature || !props) return;
      const [lng, lat] = feature.geometry.coordinates;
      popup
        .setLngLat([lng, lat])
        .setHTML(
          `<div class="toll-popup-body">
             <div class="toll-popup-road" style="color:${escapeHtml(props.color ?? "#fff")}">${escapeHtml(props.roadName ?? "")}</div>
             <div class="toll-popup-title">${escapeHtml(props.location ?? "")}</div>
             <div class="toll-popup-meta">${escapeHtml(props.kind ?? "Toll point")}</div>
             <div class="toll-popup-coords">${Number(lat).toFixed(5)}, ${Number(lng).toFixed(5)}</div>
           </div>`,
        )
        .addTo(map);
    });
    map.on("mouseleave", CIRCLE_LAYER_ID, () => {
      map.getCanvas().style.cursor = "";
      popup.remove();
    });

    return () => {
      popup.remove();
      map.remove();
      mapRef.current = null;
    };
  }, []);

  // Keep the plotted points in step with a refetch without rebuilding the map.
  useEffect(() => {
    const map = mapRef.current;
    const source = map?.getSource(SOURCE_ID) as mapboxgl.GeoJSONSource | undefined;
    source?.setData(featureCollection(gantries, roadNames));
    const lines = map?.getSource(LINE_SOURCE_ID) as mapboxgl.GeoJSONSource | undefined;
    lines?.setData(corridorCollection(gantries));
  }, [gantries, roadNames]);

  return (
    <div>
      <div ref={containerRef} className="h-[480px] w-full overflow-hidden rounded-md border border-border" />
      <TollLegend roadNames={roadNames} gantries={gantries} />
    </div>
  );
}

/** "Entry ramp · eastbound" from the registry's own ramp/direction fields, or "Toll point". */
function gantryKind(g: TollGantry): string {
  const parts: string[] = [];
  if (g.ramp) parts.push(g.ramp);
  if (g.direction) parts.push(g.direction);
  return parts.length ? parts.join(" · ") : "Toll point";
}

function featureCollection(
  gantries: TollGantry[],
  roadNames: Record<string, string>,
): PointFeatureCollection {
  return {
    type: "FeatureCollection",
    features: gantries.map((g) => ({
      type: "Feature",
      geometry: { type: "Point", coordinates: [g.longitude, g.latitude] },
      properties: {
        id: g.id,
        location: g.location,
        // Map label: the gantry's own name minus the road prefix the CSV repeats on every row
        // ("CCT Mainline East" -> "Mainline East"), so labels stay short at zoom 11.
        shortLabel: g.location.replace(/^[A-Z0-9]+\s+/, ""),
        kind: gantryKind(g),
        roadName: roadNames[g.toll_road_id] ?? g.toll_road_id,
        color: colorFor(g.toll_road_id),
      },
    })),
  };
}

/** One LineString per road, through its gantries in registry order. Roads with a single gantry
 * draw no line -- there is no corridor to show. */
interface LineFeature {
  type: "Feature";
  geometry: { type: "LineString"; coordinates: [number, number][] };
  properties: { roadId: string; color: string };
}
interface LineFeatureCollection {
  type: "FeatureCollection";
  features: LineFeature[];
}

function corridorCollection(gantries: TollGantry[]): LineFeatureCollection {
  const byRoad = new Map<string, TollGantry[]>();
  for (const g of gantries) {
    const list = byRoad.get(g.toll_road_id) ?? [];
    list.push(g);
    byRoad.set(g.toll_road_id, list);
  }
  const features: LineFeature[] = [];
  for (const [roadId, list] of byRoad) {
    if (list.length < 2) continue;
    features.push({
      type: "Feature",
      geometry: { type: "LineString", coordinates: list.map((g) => [g.longitude, g.latitude] as [number, number]) },
      properties: { roadId, color: colorFor(roadId) },
    });
  }
  return { type: "FeatureCollection", features };
}

/** Road colour key under the map, with the gantry count per road -- the owner's "which line is
 * which" question answered without hovering. */
function TollLegend({ roadNames, gantries }: { roadNames: Record<string, string>; gantries: TollGantry[] }) {
  const counts = new Map<string, number>();
  for (const g of gantries) counts.set(g.toll_road_id, (counts.get(g.toll_road_id) ?? 0) + 1);
  const roads = [...counts.keys()].sort((a, b) => (roadNames[a] ?? a).localeCompare(roadNames[b] ?? b));
  return (
    <ul className="mt-3 flex flex-wrap gap-x-4 gap-y-2 text-xs text-muted-foreground" aria-label="Toll road colour key">
      {roads.map((id) => (
        <li key={id} className="flex items-center gap-2">
          <span className="inline-block h-2.5 w-2.5 rounded-full ring-2 ring-white/70" style={{ background: colorFor(id) }} aria-hidden />
          <span className="text-foreground">{roadNames[id] ?? id}</span>
          <span className="font-mono">{counts.get(id)}</span>
        </li>
      ))}
    </ul>
  );
}

/** Injected once: mapbox's default popup is a white card with dark text, unreadable on a dark
 * map and off-theme. Scoped to the toll map's own class so nothing else changes. */
function ensurePopupStyles() {
  if (typeof document === "undefined" || document.getElementById("toll-popup-styles")) return;
  const style = document.createElement("style");
  style.id = "toll-popup-styles";
  style.textContent = `
    .toll-popup .mapboxgl-popup-content { background:#161524; color:#f5f7fb; border:1px solid #26243c; border-radius:10px; padding:10px 12px; box-shadow:0 8px 24px rgba(0,0,0,.45); }
    .toll-popup .mapboxgl-popup-tip { border-top-color:#161524; border-bottom-color:#161524; }
    .toll-popup-body { font-size:12px; line-height:1.45; min-width:180px; }
    .toll-popup-road { font-size:10px; font-weight:700; letter-spacing:.08em; text-transform:uppercase; }
    .toll-popup-title { font-size:13px; font-weight:600; margin-top:2px; }
    .toll-popup-meta { color:#93aad1; margin-top:2px; }
    .toll-popup-coords { color:#8a90a8; font-family:ui-monospace,monospace; font-size:11px; margin-top:4px; }
  `;
  document.head.appendChild(style);
}

/** Shown when no map token is configured. The coordinates themselves are the
 * point of this panel, so they stay readable rather than the whole section
 * collapsing to "map unavailable". */
function PlainCoordinateTable({
  gantries,
  roadNames,
}: {
  gantries: TollGantry[];
  roadNames: Record<string, string>;
}) {
  return (
    <div className="max-h-[420px] overflow-y-auto">
      <Table
        columns={coordinateColumns(roadNames)}
        data={gantries}
        rowKey={(g) => g.id}
        stickyHeader
        label="Toll gantry coordinates"
      />
    </div>
  );
}

/** Road and gantry are sortable: an operator hunting one specific gantry in
 * 141 rows is exactly who needs to reorder them, and the kit Table gives that
 * (keyboard-accessible) for free. Built per render because the road-name
 * lookup is a prop. */
function coordinateColumns(roadNames: Record<string, string>): TableColumn<TollGantry>[] {
  const roadName = (g: TollGantry) => roadNames[g.toll_road_id] ?? g.toll_road_id;
  return [
    { key: "road", header: "Road", sortable: true, sortAccessor: roadName, render: roadName },
    { key: "location", header: "Gantry", sortable: true, sortAccessor: (g) => g.location, render: (g) => g.location },
    {
      key: "latitude",
      header: "Latitude",
      className: "font-mono",
      render: (g) => g.latitude.toFixed(5),
    },
    {
      key: "longitude",
      header: "Longitude",
      className: "font-mono",
      render: (g) => g.longitude.toFixed(5),
    },
  ];
}

function Legend({ gantries, roadNames }: { gantries: TollGantry[]; roadNames: Record<string, string> }) {
  const counts = useMemo(() => {
    const out = new Map<string, number>();
    for (const g of gantries) out.set(g.toll_road_id, (out.get(g.toll_road_id) ?? 0) + 1);
    return [...out.entries()].sort((a, b) => b[1] - a[1]);
  }, [gantries]);

  return (
    <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
      {counts.map(([roadId, count]) => (
        <span key={roadId} className="flex items-center gap-1.5">
          <span
            className="inline-block h-2.5 w-2.5 rounded-full"
            style={{ backgroundColor: colorFor(roadId) }}
            aria-hidden
          />
          {roadNames[roadId] ?? roadId} ({count})
        </span>
      ))}
      <span className="font-medium">{gantries.length} gantries total</span>
    </div>
  );
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c] as string,
  );
}
