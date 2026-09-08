import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import mapboxgl from "mapbox-gl";
import "mapbox-gl/dist/mapbox-gl.css";
import { circlePolygon } from "@/lib/geoCircle";
import { useTenantQuery } from "@/hooks/useWhite-labelSettings";
import type { DuressEventRead } from "./types";
import { useVehicleRoutes, type RoutableVehicle } from "./useVehicleRoutes";
import { isBusyStatus } from "./utils";
import type { DevicePoint, MapDataProps, PlottedVehicle, TrailPoint, VehicleMapState } from "./mapTypes";
import {
  DEVICE_SOURCE_ID,
  FOLLOW_RECENTRE_M,
  GEOFENCE_SOURCE_ID,
  MAPBOX_TOKEN,
  ROUTE_SOURCE_ID,
  SINGLE_VEHICLE_ZOOM,
  createFleetMap,
  fitToVehicles,
  haversineMetres,
  installMapLayers,
  resolveInitialCamera,
  type CameraSource,
} from "./mapInit";
import {
  buildHoverCardElement,
  buildMarkerShell,
  ensurePopupStyleInjected,
  renderMarkerContent,
  stopTween,
  tweenMarkerTo,
  type MarkerEntry,
} from "./markers";
import { TRAIL_SOURCE_ID, buildTrailFeatures } from "./trails";
import { PlainCanvasMap } from "./PlainCanvasMap";

/**
 * The live fleet map: picks a renderer, owns the Mapbox instance, and keeps
 * every source and marker in sync with live vehicle data.
 *
 * The pieces this used to hold inline were split out in Phase 0 with no
 * behaviour change: map construction and the GL layer stack to `mapInit.ts`,
 * marker DOM/hover card/tween to `markers.ts`, the history trail to
 * `trails.ts`, the no-token SVG fallback to `PlainCanvasMap.tsx`, and the
 * shapes all four share to `mapTypes.ts`.
 */

// Re-exported so every existing import site (live-map/index.tsx,
// FleetLocateList, TrailControls, VehicleDetailModal) keeps working against
// this module rather than having to learn where each piece moved to.
export type { DevicePoint, TrailPoint, VehicleMapState } from "./mapTypes";
export { ROUTE_LINE_COLOR } from "./mapInit";

interface FleetMapCanvasProps {
  vehicles: VehicleMapState[];
  duressEvents: DuressEventRead[];
  /** Every fetched geofence -- drawn as a translucent circle overlay
   * regardless of current occupancy, so a dispatcher can see the boundary
   * itself, not just a binary in/out flag on a vehicle that happens to be
   * inside one right now. */
  geofences: MapDataProps["geofences"];
  /** Called when a non-duress vehicle's marker/pin is clicked (a duress-active
   * one always deep-links straight to its event instead -- see the marker
   * click handler / PlainCanvasMap's onClick). */
  onSelectVehicle: (vehicleId: string) => void;
  /** The vehicle the operator is looking at: gets a ring on the map, and the
   * camera flies to it when it changes. */
  selectedVehicleId?: string | null;
  /** Keep the camera on the selected vehicle as new positions arrive. */
  follow?: boolean;
  /** Fired when the operator pans while following, so the caller can drop out
   * of follow rather than fight them for the camera. */
  onFollowInterrupted?: () => void;
  /** Where the selected vehicle has been, oldest first. Drawn as a line behind
   * the markers; empty or absent draws nothing. */
  trail?: TrailPoint[];
  /** Index into [trail] the scrubber is parked on, or null for "live". Renders a
   * ghost marker at that point so an operator can step back through the drive. */
  trailCursor?: number | null;
  /** Tablets that answer to no vehicle, at wherever they last reported from. */
  devicePoints?: DevicePoint[];
  /** The device the operator is looking at, if the selection is a tablet rather
   * than a vehicle: gets a ring, and the camera flies to it when it changes. */
  selectedDeviceId?: string | null;
  /** Called when a tablet's own point is clicked. */
  onSelectDevice?: (deviceId: string) => void;
}

/**
 * Live fleet map. Renders a real Mapbox GL JS map (custom global style, opened on
 * the tenant's configured default centre, else the fleet's own last-known bounding
 * box, else a world view -- see resolveInitialCamera) when VITE_MAPBOX_TOKEN is configured;
 * otherwise falls back to a plain-SVG lat/lng plot so the page never breaks
 * for anyone without a token set up (see PlainCanvasMap.tsx).
 */
export function FleetMapCanvas({
  vehicles,
  duressEvents,
  geofences,
  onSelectVehicle,
  selectedVehicleId = null,
  follow = false,
  onFollowInterrupted,
  trail = [],
  trailCursor = null,
  devicePoints = [],
  selectedDeviceId = null,
  onSelectDevice = () => {},
}: FleetMapCanvasProps) {
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
        selectedVehicleId={selectedVehicleId}
        follow={follow}
        onFollowInterrupted={onFollowInterrupted}
        trail={trail}
        trailCursor={trailCursor}
        devicePoints={devicePoints}
        selectedDeviceId={selectedDeviceId}
        onSelectDevice={onSelectDevice}
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
      // The no-token fallback has no camera to fly, so follow is meaningless there;
      // it still takes the selection so a picked vehicle is marked on the SVG plot.
      selectedVehicleId={selectedVehicleId}
      follow={false}
      trail={[]}
      trailCursor={null}
      // The SVG fallback plots vehicles from a fitted bounding box and has no
      // layer machinery to add a second kind of point to. Unpaired tablets are
      // still fully listed and locatable beside it -- they just are not drawn
      // here -- rather than being half-drawn in a plot that cannot label them.
      devicePoints={[]}
      selectedDeviceId={null}
      onSelectDevice={() => {}}
      onSelectVehicle={onSelectVehicle}
    />
  );
}

// ---------------------------------------------------------------------------
// Mapbox GL JS rendering
// ---------------------------------------------------------------------------

/** What the empty state says about the region it is showing, per fallback.
 * Each one names where the view came from -- a map showing somewhere the
 * operator did not choose must say why it is showing it. */
const EMPTY_VIEW_CAPTION: Record<CameraSource, string> = {
  tenant: "showing your configured default map area.",
  fleet: "showing the area this fleet last reported from.",
  world: "showing a world view until a device reports a position.",
};

function MapboxFleetMap({
  plotted,
  duressByVehicleId,
  geofences,
  routes,
  onSelectVehicle,
  selectedVehicleId,
  follow,
  onFollowInterrupted,
  trail,
  trailCursor,
  devicePoints,
  selectedDeviceId,
  onSelectDevice,
}: MapDataProps) {
  const navigate = useNavigate();
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<mapboxgl.Map | null>(null);

  // The click handler is registered once, on map load, so it must not close over
  // a stale `onSelectDevice`. Same reason the vehicle markers keep reading
  // `entry.vehicle`/`entry.duressEvent` rather than closing over a render's values.
  const onSelectDeviceRef = useRef(onSelectDevice);
  onSelectDeviceRef.current = onSelectDevice;

  const markersRef = useRef<Map<string, MarkerEntry>>(new Map());
  const [styleLoaded, setStyleLoaded] = useState(false);

  // The tenant's own record carries the configured default map centre (in
  // `theme_json`, alongside the branding this dashboard already reads from
  // there). It is the same cached react-query entry the app shell and sidebar
  // already hold, so in practice this resolves synchronously; the init effect
  // below still waits for it to settle rather than opening on a world view and
  // jumping a moment later.
  const tenantQuery = useTenantQuery();
  const tenantSettled = !tenantQuery.isLoading;
  const tenantTheme = tenantQuery.data?.theme_json;

  // Which fallback the opening camera came from, so the empty state can say so
  // rather than naming a region the code has no business asserting.
  const [cameraSource, setCameraSource] = useState<CameraSource | null>(null);

  // Init the map once, as soon as the tenant record has settled. Opening camera
  // is the tenant's configured centre, else the bounding box of every position
  // this fleet is known to have reported from (live vehicles and tablets' last
  // locates alike), else a world view — see resolveInitialCamera. `plotted` and
  // `devicePoints` are read as they were at mount, which by then is whatever the
  // parent's queries had already resolved (see LiveMapPage).
  useEffect(() => {
    if (!containerRef.current || mapRef.current || !tenantSettled) return;

    ensurePopupStyleInjected();

    const { camera, source } = resolveInitialCamera(tenantTheme, [...plotted, ...devicePoints]);
    setCameraSource(source);
    const map = createFleetMap(containerRef.current, camera);
    mapRef.current = map;

    // Mapbox measures its canvas once and never notices the container changing
    // underneath it, so collapsing the nav rail -- or the vehicle sheet opening
    // and squeezing the page -- left the map rendered at its old width with the
    // right-hand strip blank and every click offset from what it hit. One
    // observer fixes all of those cases; `map.resize()` is a no-op when the size
    // has not actually changed.
    const resizeObserver = new ResizeObserver(() => map.resize());
    resizeObserver.observe(containerRef.current);

    map.on("load", () => {
      map.resize();
      fitToVehicles(map, plotted);
      installMapLayers(map, (id) => onSelectDeviceRef.current(id));
      setStyleLoaded(true);
    });

    return () => {
      markersRef.current.forEach((entry) => {
        stopTween(entry);
        entry.popup.remove();
        entry.marker.remove();
      });
      markersRef.current.clear();
      resizeObserver.disconnect();
      map.remove();
      mapRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- init once, after the tenant record settles; see comment above
  }, [tenantSettled]);

  // Keep the unpaired-device points in sync with the fetched list -- this is a
  // separate, much-less-frequent update than the marker-sync effect below,
  // so it's kept as its own effect rather than folded into that one.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !styleLoaded) return;
    const source = map.getSource(DEVICE_SOURCE_ID) as mapboxgl.GeoJSONSource | undefined;
    if (!source) return;
    source.setData({
      type: "FeatureCollection",
      features: devicePoints.map((d) => ({
        type: "Feature",
        properties: { id: d.id, label: d.label, selected: d.id === selectedDeviceId },
        geometry: { type: "Point", coordinates: [d.lng, d.lat] },
      })),
    });
  }, [devicePoints, selectedDeviceId, styleLoaded]);

  // Fly to a selected tablet the same way a selected vehicle is flown to -- the
  // whole point of listing them is that "locate" reaches them too.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !selectedDeviceId) return;
    const point = devicePoints.find((d) => d.id === selectedDeviceId);
    if (!point) return;
    map.flyTo({ center: [point.lng, point.lat], zoom: Math.max(map.getZoom(), SINGLE_VEHICLE_ZOOM) });
    // Keyed on the id alone: a device's last locate does not move on its own, and
    // re-flying on every poll would fight an operator reading the map.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedDeviceId]);

  // Feed the trail source: one LineString per run of points, a stop marker
  // wherever the vehicle sat still, and the scrubber's ghost.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !styleLoaded) return;
    const source = map.getSource(TRAIL_SOURCE_ID) as mapboxgl.GeoJSONSource | undefined;
    if (!source) return;
    source.setData(buildTrailFeatures(trail, trailCursor));
  }, [trail, trailCursor, styleLoaded]);

  // Fly to the selected vehicle when the selection changes.
  //
  // Deliberately keyed on the id alone, not on its position: a flyTo per
  // position update would fight the 3.5s marker tween and re-animate the camera
  // every few seconds. Following a moving vehicle is the separate effect below,
  // which eases gently instead.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !selectedVehicleId) return;
    const target = plotted.find((v) => v.id === selectedVehicleId);
    if (!target) return;
    map.flyTo({
      center: [target.lng, target.lat],
      zoom: Math.max(map.getZoom(), SINGLE_VEHICLE_ZOOM),
      duration: 900,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedVehicleId]);

  // Follow mode: keep the camera on the vehicle as fresh positions arrive.
  //
  // easeTo rather than flyTo, and only when the vehicle has actually moved
  // beyond FOLLOW_RECENTRE_M -- a camera that re-animates on every GPS jitter is
  // unusable for reading anything else on the map. The threshold matches the
  // order of magnitude the marker tween already smooths over.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !follow || !selectedVehicleId) return;
    const target = plotted.find((v) => v.id === selectedVehicleId);
    if (!target) return;
    const centre = map.getCenter();
    const movedM = haversineMetres(centre.lat, centre.lng, target.lat, target.lng);
    if (movedM < FOLLOW_RECENTRE_M) return;
    map.easeTo({ center: [target.lng, target.lat], duration: 1200 });
  }, [follow, selectedVehicleId, plotted]);

  // A manual pan while following hands the camera back to the operator.
  //
  // Without this, dragging the map would be undone by the next position frame,
  // which reads as the map being broken rather than as a mode being on. Same
  // idiom the tablet's own follow-cam uses (MeterBackdropMap's followSuspended).
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !follow || !onFollowInterrupted) return;
    const onDragStart = () => onFollowInterrupted();
    map.on("dragstart", onDragStart);
    return () => {
      map.off("dragstart", onDragStart);
    };
  }, [follow, onFollowInterrupted]);

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
        renderMarkerContent(iconWrap, labelEl, vehicle, duressEvent, vehicle.id === selectedVehicleId);
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
      renderMarkerContent(existing.iconWrap, existing.labelEl, vehicle, duressEvent, vehicle.id === selectedVehicleId);
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
    // selectedVehicleId is a dependency because the ring is drawn by
    // renderMarkerContent: without it, picking a different vehicle would leave the
    // halo on the old one until its next position update.
  }, [plotted, duressByVehicleId, navigate, onSelectVehicle, selectedVehicleId]);

  return (
    <div className="relative">
      <div ref={containerRef} className="h-[460px] w-full rounded-md border border-border" />
      {plotted.length === 0 && (
        <div className="pointer-events-none absolute left-3 top-3 rounded-md bg-card/90 px-3 py-1.5 text-xs text-muted-foreground shadow">
          No live vehicle positions yet — {EMPTY_VIEW_CAPTION[cameraSource ?? "world"]} Positions
          appear here once a device publishes via POST /v1/fleet/positions.
        </div>
      )}
    </div>
  );
}
