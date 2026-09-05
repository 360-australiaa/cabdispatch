import { useEffect, useRef } from "react";
import mapboxgl from "mapbox-gl";
import "mapbox-gl/dist/mapbox-gl.css";
import { Input } from "@/components/ui";
import { circlePolygon } from "@/lib/geoCircle";

// Same public/publishable Mapbox token pattern as the Live Map (see
// src/pages/live-map/FleetMapCanvas.tsx) — falls back to plain lat/lng
// number inputs below when unset so this form never breaks without a token.
const MAPBOX_TOKEN = import.meta.env.VITE_MAPBOX_TOKEN;

const SYDNEY_CENTER: [number, number] = [151.2093, -33.8688];
const DEFAULT_ZOOM = 10.5;
const PICKED_ZOOM = 13;

const CIRCLE_SOURCE_ID = "toll-zone-radius";
const CIRCLE_FILL_LAYER_ID = "toll-zone-radius-fill";
const CIRCLE_LINE_LAYER_ID = "toll-zone-radius-line";

interface TollZoneMapPickerProps {
  lat: number | null;
  lng: number | null;
  radiusM: number;
  onPick: (lat: number, lng: number) => void;
}

/** Click-to-set center picker for a circular toll zone. Reuses the same
 * Mapbox GL JS setup as the Live Map's fleet map; renders a plain lat/lng
 * number-input fallback when no VITE_MAPBOX_TOKEN is configured. */
export function TollZoneMapPicker({ lat, lng, radiusM, onPick }: TollZoneMapPickerProps) {
  if (MAPBOX_TOKEN) {
    return <MapboxCenterPicker lat={lat} lng={lng} radiusM={radiusM} onPick={onPick} />;
  }
  return <PlainLatLngFallback lat={lat} lng={lng} onPick={onPick} />;
}

function MapboxCenterPicker({ lat, lng, radiusM, onPick }: TollZoneMapPickerProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<mapboxgl.Map | null>(null);
  const markerRef = useRef<mapboxgl.Marker | null>(null);
  const onPickRef = useRef(onPick);
  onPickRef.current = onPick;

  // Init the map once; click anywhere to (re)place the center marker.
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;

    mapboxgl.accessToken = MAPBOX_TOKEN as string;
    const map = new mapboxgl.Map({
      container: containerRef.current,
      style: "mapbox://styles/benfarid/cmtbnyhe4000e01pcgx2t51za",
      center: lat != null && lng != null ? [lng, lat] : SYDNEY_CENTER,
      zoom: lat != null && lng != null ? PICKED_ZOOM : DEFAULT_ZOOM,
    });
    map.addControl(new mapboxgl.NavigationControl({ showCompass: false }), "top-right");
    mapRef.current = map;

    map.on("click", (e) => {
      onPickRef.current(e.lngLat.lat, e.lngLat.lng);
    });

    map.on("load", () => {
      map.resize();
      map.addSource(CIRCLE_SOURCE_ID, {
        type: "geojson",
        data: { type: "FeatureCollection", features: [] },
      });
      map.addLayer({
        id: CIRCLE_FILL_LAYER_ID,
        type: "fill",
        source: CIRCLE_SOURCE_ID,
        paint: { "fill-color": "var(--brand-accent)", "fill-opacity": 0.2 },
      });
      map.addLayer({
        id: CIRCLE_LINE_LAYER_ID,
        type: "line",
        source: CIRCLE_SOURCE_ID,
        paint: { "line-color": "var(--brand-accent)", "line-width": 2 },
      });
    });

    return () => {
      markerRef.current?.remove();
      markerRef.current = null;
      map.remove();
      mapRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- init once, see comment above
  }, []);

  // Keep the marker + radius circle in sync with the current center/radius.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    if (lat == null || lng == null) {
      markerRef.current?.remove();
      markerRef.current = null;
      if (map.getSource(CIRCLE_SOURCE_ID)) {
        (map.getSource(CIRCLE_SOURCE_ID) as mapboxgl.GeoJSONSource).setData({
          type: "FeatureCollection",
          features: [],
        });
      }
      return;
    }

    if (!markerRef.current) {
      markerRef.current = new mapboxgl.Marker({ color: "#F5A623", draggable: true })
        .setLngLat([lng, lat])
        .addTo(map);
      markerRef.current.on("dragend", () => {
        const pos = markerRef.current!.getLngLat();
        onPickRef.current(pos.lat, pos.lng);
      });
    } else {
      markerRef.current.setLngLat([lng, lat]);
    }

    const source = map.getSource(CIRCLE_SOURCE_ID) as mapboxgl.GeoJSONSource | undefined;
    if (source) {
      source.setData({
        type: "FeatureCollection",
        features: [circlePolygon(lat, lng, radiusM || 0)],
      });
    }
  }, [lat, lng, radiusM]);

  return (
    <div>
      <div ref={containerRef} className="h-[320px] w-full rounded-md border border-border" />
      <p className="mt-1.5 text-xs text-muted-foreground">
        Click (or drag the pin) to set the zone center. Radius shading updates live as you edit it below.
      </p>
    </div>
  );
}

/** No-token fallback — plain lat/lng number inputs, same bounds as the
 * backend's GeofenceCreate schema (±90 / ±180). */
function PlainLatLngFallback({ lat, lng, onPick }: Omit<TollZoneMapPickerProps, "radiusM">) {
  return (
    <div>
      <div className="grid grid-cols-2 gap-4">
        <div className="flex flex-col gap-1.5">
          <label className="text-xs font-medium text-muted-foreground">Center latitude</label>
          <Input
            type="number"
            step="any"
            min={-90}
            max={90}
            value={lat ?? ""}
            onChange={(e) => onPick(Number(e.target.value), lng ?? SYDNEY_CENTER[0])}
            placeholder="-33.8688"
            required
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <label className="text-xs font-medium text-muted-foreground">Center longitude</label>
          <Input
            type="number"
            step="any"
            min={-180}
            max={180}
            value={lng ?? ""}
            onChange={(e) => onPick(lat ?? SYDNEY_CENTER[1], Number(e.target.value))}
            placeholder="151.2093"
            required
          />
        </div>
      </div>
      <p className="mt-1.5 text-xs text-muted-foreground">
        No VITE_MAPBOX_TOKEN configured — enter the zone center manually. Set the token to pick it on a map
        instead (see .env.example).
      </p>
    </div>
  );
}
