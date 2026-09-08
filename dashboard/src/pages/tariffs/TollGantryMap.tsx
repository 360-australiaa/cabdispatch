import { useEffect, useMemo, useRef } from "react";
import mapboxgl from "mapbox-gl";
import "mapbox-gl/dist/mapbox-gl.css";
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
      style: "mapbox://styles/benfarid/cmtbnyhe4000e01pcgx2t51za",
      center: SYDNEY_CENTER,
      zoom: DEFAULT_ZOOM,
    });
    map.addControl(new mapboxgl.NavigationControl({ showCompass: false }), "top-right");
    mapRef.current = map;

    const popup = new mapboxgl.Popup({ closeButton: false, closeOnClick: false });

    map.on("load", () => {
      map.addSource(SOURCE_ID, {
        type: "geojson",
        data: featureCollection(dataRef.current.gantries, dataRef.current.roadNames),
      });
      // One circle layer over a GeoJSON source rather than 141 DOM Markers:
      // markers are individually positioned on every frame of a pan/zoom,
      // which at this count visibly stutters; a circle layer is drawn by the
      // GPU in one pass.
      map.addLayer({
        id: CIRCLE_LAYER_ID,
        type: "circle",
        source: SOURCE_ID,
        paint: {
          "circle-radius": ["interpolate", ["linear"], ["zoom"], 8, 3, 12, 5, 16, 8],
          "circle-color": ["get", "color"],
          "circle-stroke-width": 1,
          "circle-stroke-color": "#0b0b10",
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
          `<div style="font-size:12px;line-height:1.4">
             <div style="font-weight:600">${escapeHtml(props.location ?? "")}</div>
             <div style="opacity:.75">${escapeHtml(props.roadName ?? "")}</div>
             <div style="opacity:.75">${Number(lat).toFixed(5)}, ${Number(lng).toFixed(5)}</div>
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
  }, [gantries, roadNames]);

  return <div ref={containerRef} className="h-[420px] w-full overflow-hidden rounded-md border border-border" />;
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
        roadName: roadNames[g.toll_road_id] ?? g.toll_road_id,
        color: colorFor(g.toll_road_id),
      },
    })),
  };
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
    <div className="max-h-[420px] overflow-y-auto rounded-md border border-border">
      <table className="w-full text-xs">
        <thead className="sticky top-0 bg-card">
          <tr className="text-left text-muted-foreground">
            <th className="px-3 py-2 font-medium">Road</th>
            <th className="px-3 py-2 font-medium">Gantry</th>
            <th className="px-3 py-2 font-medium">Latitude</th>
            <th className="px-3 py-2 font-medium">Longitude</th>
          </tr>
        </thead>
        <tbody>
          {gantries.map((g) => (
            <tr key={g.id} className="border-t border-border/50">
              <td className="px-3 py-1">{roadNames[g.toll_road_id] ?? g.toll_road_id}</td>
              <td className="px-3 py-1">{g.location}</td>
              <td className="px-3 py-1 font-mono">{g.latitude.toFixed(5)}</td>
              <td className="px-3 py-1 font-mono">{g.longitude.toFixed(5)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
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
