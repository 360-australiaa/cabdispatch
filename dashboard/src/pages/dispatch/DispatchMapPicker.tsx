import { useEffect, useId, useRef, useState } from "react";
import mapboxgl from "mapbox-gl";
import "mapbox-gl/dist/mapbox-gl.css";
import { Button, Input } from "@/components/ui";

// Same public/publishable Mapbox token pattern as Live Map and the tariffs
// map pickers (`pages/tariffs/TollZoneMapPicker.tsx`) — falls back to plain
// lat/lng number inputs below when unset so this form never breaks without
// a token.
const MAPBOX_TOKEN = import.meta.env.VITE_MAPBOX_TOKEN;

const SYDNEY_CENTER: [number, number] = [151.2093, -33.8688];
const DEFAULT_ZOOM = 10.5;
const PICKED_ZOOM = 13;

const PICKUP_COLOR = "#2e7d32";
const DROPOFF_COLOR = "#c62828";

export type PickTarget = "pickup" | "dropoff";

interface LatLng {
  lat: number;
  lng: number;
}

interface DispatchMapPickerProps {
  pickup: LatLng | null;
  dropoff: LatLng | null;
  /** Which pin the next map click (or manual entry) moves. */
  active: PickTarget;
  onActiveChange: (target: PickTarget) => void;
  onPick: (target: PickTarget, lat: number, lng: number) => void;
}

/**
 * Click-to-set map picker for a job's pickup and drop-off points. Reuses the
 * same Mapbox GL JS setup as the Live Map and the tariffs toll-zone picker;
 * renders a plain lat/lng number-input fallback for both points when no
 * `VITE_MAPBOX_TOKEN` is configured, so dispatch never breaks without one.
 */
export function DispatchMapPicker(props: DispatchMapPickerProps) {
  if (MAPBOX_TOKEN) {
    return <MapboxTwoPinPicker {...props} />;
  }
  return <PlainLatLngFallback {...props} />;
}

function MapboxTwoPinPicker({ pickup, dropoff, active, onActiveChange, onPick }: DispatchMapPickerProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<mapboxgl.Map | null>(null);
  const pickupMarkerRef = useRef<mapboxgl.Marker | null>(null);
  const dropoffMarkerRef = useRef<mapboxgl.Marker | null>(null);
  const activeRef = useRef(active);
  const onPickRef = useRef(onPick);

  // Refs are only ever read from event handlers below, never during render,
  // so updating them in an effect (rather than inline in the render body,
  // which `react-hooks/refs` flags) is correct here.
  useEffect(() => {
    activeRef.current = active;
    onPickRef.current = onPick;
  }, [active, onPick]);

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;

    mapboxgl.accessToken = MAPBOX_TOKEN as string;
    const start = pickup ?? dropoff;
    const map = new mapboxgl.Map({
      container: containerRef.current,
      style: "mapbox://styles/benfarid/cmtbnyhe4000e01pcgx2t51za",
      center: start ? [start.lng, start.lat] : SYDNEY_CENTER,
      zoom: start ? PICKED_ZOOM : DEFAULT_ZOOM,
    });
    map.addControl(new mapboxgl.NavigationControl({ showCompass: false }), "top-right");
    mapRef.current = map;

    map.on("click", (e) => {
      onPickRef.current(activeRef.current, e.lngLat.lat, e.lngLat.lng);
    });
    map.on("load", () => map.resize());

    return () => {
      pickupMarkerRef.current?.remove();
      dropoffMarkerRef.current?.remove();
      pickupMarkerRef.current = null;
      dropoffMarkerRef.current = null;
      map.remove();
      mapRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- init once, see comment above
  }, []);

  useEffect(() => {
    syncMarker(mapRef.current, pickupMarkerRef, pickup, PICKUP_COLOR, (lat, lng) =>
      onPickRef.current("pickup", lat, lng),
    );
  }, [pickup]);

  useEffect(() => {
    syncMarker(mapRef.current, dropoffMarkerRef, dropoff, DROPOFF_COLOR, (lat, lng) =>
      onPickRef.current("dropoff", lat, lng),
    );
  }, [dropoff]);

  return (
    <div>
      <div className="mb-2 flex gap-2">
        <Button
          type="button"
          size="sm"
          variant={active === "pickup" ? "primary" : "outline"}
          onClick={() => onActiveChange("pickup")}
        >
          Placing: pickup
        </Button>
        <Button
          type="button"
          size="sm"
          variant={active === "dropoff" ? "primary" : "outline"}
          onClick={() => onActiveChange("dropoff")}
        >
          Placing: drop-off
        </Button>
      </div>
      <div ref={containerRef} className="h-[320px] w-full rounded-md border border-border" />
      <p className="mt-1.5 text-xs text-muted-foreground">
        Click the map (or drag a pin) to set the {active === "pickup" ? "pickup" : "drop-off"} point.
      </p>
    </div>
  );
}

function syncMarker(
  map: mapboxgl.Map | null,
  markerRef: React.MutableRefObject<mapboxgl.Marker | null>,
  point: LatLng | null,
  color: string,
  onDrag: (lat: number, lng: number) => void,
) {
  if (!map) return;

  if (point == null) {
    markerRef.current?.remove();
    markerRef.current = null;
    return;
  }

  if (!markerRef.current) {
    markerRef.current = new mapboxgl.Marker({ color, draggable: true })
      .setLngLat([point.lng, point.lat])
      .addTo(map);
    markerRef.current.on("dragend", () => {
      const pos = markerRef.current!.getLngLat();
      onDrag(pos.lat, pos.lng);
    });
  } else {
    markerRef.current.setLngLat([point.lng, point.lat]);
  }
}

/** No-token fallback — plain lat/lng number inputs for both points, same
 * bounds as the backend's JobCreate schema (±90 / ±180). */
function PlainLatLngFallback({ pickup, dropoff, onPick }: DispatchMapPickerProps) {
  const idPrefix = useId();
  const [pickupLat, setPickupLat] = useState(pickup?.lat.toString() ?? "");
  const [pickupLng, setPickupLng] = useState(pickup?.lng.toString() ?? "");
  const [dropoffLat, setDropoffLat] = useState(dropoff?.lat.toString() ?? "");
  const [dropoffLng, setDropoffLng] = useState(dropoff?.lng.toString() ?? "");

  function commit(target: PickTarget, latStr: string, lngStr: string) {
    const lat = Number.parseFloat(latStr);
    const lng = Number.parseFloat(lngStr);
    if (!Number.isNaN(lat) && !Number.isNaN(lng)) onPick(target, lat, lng);
  }

  return (
    <div>
      <p className="mb-2 text-xs text-muted-foreground">
        No VITE_MAPBOX_TOKEN configured — enter each point's coordinates manually. Set the token to pick
        them on a map instead (see .env.example).
      </p>
      <div className="grid grid-cols-2 gap-4">
        <div className="flex flex-col gap-1.5">
          <label htmlFor={`${idPrefix}-pickup-lat`} className="text-xs font-medium text-muted-foreground">
            Pickup latitude
          </label>
          <Input
            id={`${idPrefix}-pickup-lat`}
            type="number"
            step="any"
            min={-90}
            max={90}
            value={pickupLat}
            onChange={(e) => {
              setPickupLat(e.target.value);
              commit("pickup", e.target.value, pickupLng);
            }}
            placeholder="-33.8688"
            required
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <label htmlFor={`${idPrefix}-pickup-lng`} className="text-xs font-medium text-muted-foreground">
            Pickup longitude
          </label>
          <Input
            id={`${idPrefix}-pickup-lng`}
            type="number"
            step="any"
            min={-180}
            max={180}
            value={pickupLng}
            onChange={(e) => {
              setPickupLng(e.target.value);
              commit("pickup", pickupLat, e.target.value);
            }}
            placeholder="151.2093"
            required
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <label htmlFor={`${idPrefix}-dropoff-lat`} className="text-xs font-medium text-muted-foreground">
            Drop-off latitude
          </label>
          <Input
            id={`${idPrefix}-dropoff-lat`}
            type="number"
            step="any"
            min={-90}
            max={90}
            value={dropoffLat}
            onChange={(e) => {
              setDropoffLat(e.target.value);
              commit("dropoff", e.target.value, dropoffLng);
            }}
            placeholder="-33.9399"
            required
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <label htmlFor={`${idPrefix}-dropoff-lng`} className="text-xs font-medium text-muted-foreground">
            Drop-off longitude
          </label>
          <Input
            id={`${idPrefix}-dropoff-lng`}
            type="number"
            step="any"
            min={-180}
            max={180}
            value={dropoffLng}
            onChange={(e) => {
              setDropoffLng(e.target.value);
              commit("dropoff", dropoffLat, e.target.value);
            }}
            placeholder="151.1753"
            required
          />
        </div>
      </div>
    </div>
  );
}
