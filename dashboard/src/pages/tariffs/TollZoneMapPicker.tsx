import { useEffect, useId, useRef } from "react";
import mapboxgl from "mapbox-gl";
import "mapbox-gl/dist/mapbox-gl.css";
import { Input } from "@/components/ui";
import { circlePolygon } from "@/lib/geoCircle";
import type { Geofence } from "@/hooks/useGeofences";

// Same public/publishable Mapbox token pattern as the Live Map (see
// src/pages/live-map/FleetMapCanvas.tsx) — falls back to plain lat/lng
// number inputs below when unset so this form never breaks without a token.
const MAPBOX_TOKEN = import.meta.env.VITE_MAPBOX_TOKEN;

const SYDNEY_CENTER: [number, number] = [151.2093, -33.8688];
/** Sydney Airport (Kingsford Smith), roughly between the T1 and T2/T3
 * precincts -- the picker's home view for airport pickup zones. */
export const SYDNEY_AIRPORT_CENTER: [number, number] = [151.1753, -33.9399];
const DEFAULT_ZOOM = 10.5;
const PICKED_ZOOM = 13;

/** The literal `--brand-accent` gold from `index.css`. Mapbox paint properties
 * cannot read CSS custom properties -- passing `var(--brand-accent)` makes
 * `addLayer` reject the layer with `color expected` and return, so the zone
 * circle silently never drew. Same fix as live-map/FleetMapCanvas.tsx. */
const ZONE_COLOR = "#f4c300";

const CIRCLE_SOURCE_ID = "toll-zone-radius";
const CIRCLE_FILL_LAYER_ID = "toll-zone-radius-fill";
const CIRCLE_LINE_LAYER_ID = "toll-zone-radius-line";

/** Sibling zones drawn faintly under the one being edited, so an operator
 * placing T2 can see where T1 already sits and avoid a bad overlap. Neutral
 * grey rather than the accent gold so they never read as the active zone. */
const OTHER_ZONES_SOURCE_ID = "toll-zone-others";
const OTHER_ZONES_FILL_LAYER_ID = "toll-zone-others-fill";
const OTHER_ZONES_LINE_LAYER_ID = "toll-zone-others-line";
const OTHER_ZONE_COLOR = "#7c8594";

/** The subset of a `Geofence` the picker needs to draw a sibling zone. */
export type PickerZone = Pick<Geofence, "id" | "name" | "center_lat" | "center_lng" | "radius_m">;

interface TollZoneMapPickerProps {
  lat: number | null;
  lng: number | null;
  radiusM: number;
  onPick: (lat: number, lng: number) => void;
  /** Where the map opens before a center is picked. `[lng, lat]`, Mapbox
   * order. Defaults to Sydney CBD. */
  defaultCenter?: [number, number];
  /** Existing zones of the same kind to draw faintly for context (never the
   * one being edited -- the caller filters that out). */
  otherZones?: PickerZone[];
}

/** Click-to-set center picker for a circular toll / airport zone. Reuses the
 * same Mapbox GL JS setup as the Live Map's fleet map; renders a plain
 * lat/lng number-input fallback when no VITE_MAPBOX_TOKEN is configured. */
export function TollZoneMapPicker({
  lat,
  lng,
  radiusM,
  onPick,
  defaultCenter = SYDNEY_CENTER,
  otherZones = [],
}: TollZoneMapPickerProps) {
  if (MAPBOX_TOKEN) {
    return (
      <MapboxCenterPicker
        lat={lat}
        lng={lng}
        radiusM={radiusM}
        onPick={onPick}
        defaultCenter={defaultCenter}
        otherZones={otherZones}
      />
    );
  }
  return (
    <PlainLatLngFallback lat={lat} lng={lng} onPick={onPick} defaultCenter={defaultCenter} otherZones={otherZones} />
  );
}

function MapboxCenterPicker({
  lat,
  lng,
  radiusM,
  onPick,
  defaultCenter = SYDNEY_CENTER,
  otherZones = [],
}: TollZoneMapPickerProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<mapboxgl.Map | null>(null);
  const markerRef = useRef<mapboxgl.Marker | null>(null);
  const onPickRef = useRef(onPick);
  onPickRef.current = onPick;
  // Read by the one-shot "load" handler below; kept current from the sync
  // effect further down rather than assigned during render.
  const otherZonesRef = useRef(otherZones);

  // Init the map once; click anywhere to (re)place the center marker.
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;

    mapboxgl.accessToken = MAPBOX_TOKEN as string;
    const map = new mapboxgl.Map({
      container: containerRef.current,
      style: "mapbox://styles/benfarid/cmtbnyhe4000e01pcgx2t51za",
      center: lat != null && lng != null ? [lng, lat] : defaultCenter,
      zoom: lat != null && lng != null ? PICKED_ZOOM : DEFAULT_ZOOM,
    });
    map.addControl(new mapboxgl.NavigationControl({ showCompass: false }), "top-right");
    mapRef.current = map;

    map.on("click", (e) => {
      onPickRef.current(e.lngLat.lat, e.lngLat.lng);
    });

    map.on("load", () => {
      map.resize();
      // Sibling zones go in first so the active zone's layers sit on top.
      map.addSource(OTHER_ZONES_SOURCE_ID, {
        type: "geojson",
        data: otherZonesCollection(otherZonesRef.current),
      });
      map.addLayer({
        id: OTHER_ZONES_FILL_LAYER_ID,
        type: "fill",
        source: OTHER_ZONES_SOURCE_ID,
        paint: { "fill-color": OTHER_ZONE_COLOR, "fill-opacity": 0.08 },
      });
      map.addLayer({
        id: OTHER_ZONES_LINE_LAYER_ID,
        type: "line",
        source: OTHER_ZONES_SOURCE_ID,
        paint: { "line-color": OTHER_ZONE_COLOR, "line-width": 1, "line-dasharray": [2, 2], "line-opacity": 0.6 },
      });
      map.addSource(CIRCLE_SOURCE_ID, {
        type: "geojson",
        data: { type: "FeatureCollection", features: [] },
      });
      map.addLayer({
        id: CIRCLE_FILL_LAYER_ID,
        type: "fill",
        source: CIRCLE_SOURCE_ID,
        paint: { "fill-color": ZONE_COLOR, "fill-opacity": 0.2 },
      });
      map.addLayer({
        id: CIRCLE_LINE_LAYER_ID,
        type: "line",
        source: CIRCLE_SOURCE_ID,
        paint: { "line-color": ZONE_COLOR, "line-width": 2 },
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

  // Redraw the faint sibling zones whenever the list changes (e.g. after a
  // background refetch adds a terminal someone else just created).
  useEffect(() => {
    otherZonesRef.current = otherZones;
    const map = mapRef.current;
    if (!map) return;
    const source = map.getSource(OTHER_ZONES_SOURCE_ID) as mapboxgl.GeoJSONSource | undefined;
    source?.setData(otherZonesCollection(otherZones));
  }, [otherZones]);

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

function otherZonesCollection(zones: PickerZone[]) {
  return {
    type: "FeatureCollection" as const,
    features: zones.map((z) => circlePolygon(z.center_lat, z.center_lng, z.radius_m)),
  };
}

/** No-token fallback — plain lat/lng number inputs, same bounds as the
 * backend's GeofenceCreate schema (±90 / ±180). */
function PlainLatLngFallback({
  lat,
  lng,
  onPick,
  defaultCenter = SYDNEY_CENTER,
  otherZones = [],
}: Omit<TollZoneMapPickerProps, "radiusM">) {
  const [defaultLng, defaultLat] = defaultCenter;
  const id = useId();
  return (
    <div>
      <div className="grid grid-cols-2 gap-4">
        <div className="flex flex-col gap-1.5">
          <label className="text-xs font-medium text-muted-foreground" htmlFor={`${id}-lat`}>
            Center latitude
          </label>
          <Input
            id={`${id}-lat`}
            type="number"
            step="any"
            min={-90}
            max={90}
            value={lat ?? ""}
            onChange={(e) => onPick(Number(e.target.value), lng ?? defaultLng)}
            placeholder={String(defaultLat)}
            required
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <label className="text-xs font-medium text-muted-foreground" htmlFor={`${id}-lng`}>
            Center longitude
          </label>
          <Input
            id={`${id}-lng`}
            type="number"
            step="any"
            min={-180}
            max={180}
            value={lng ?? ""}
            onChange={(e) => onPick(lat ?? defaultLat, Number(e.target.value))}
            placeholder={String(defaultLng)}
            required
          />
        </div>
      </div>
      <p className="mt-1.5 text-xs text-muted-foreground">
        No VITE_MAPBOX_TOKEN configured — enter the zone center manually. Set the token to pick it on a map
        instead (see .env.example).
      </p>
      {otherZones.length > 0 && (
        <p className="mt-1.5 text-xs text-muted-foreground">
          Other zones already placed:{" "}
          {otherZones
            .map((z) => `${z.name} (${z.center_lat.toFixed(4)}, ${z.center_lng.toFixed(4)}, ${z.radius_m} m)`)
            .join("; ")}
        </p>
      )}
    </div>
  );
}
