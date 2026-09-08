import type { Geofence } from "@/hooks/useGeofences";
import type { VehicleRouteState } from "./useVehicleRoutes";
import type { DuressEventRead, VehicleLiveRead } from "./types";
import type { IdleInfo } from "./utils";

/**
 * The shapes shared by the live map's two renderers (the Mapbox one in
 * FleetMapCanvas.tsx and the no-token SVG fallback in PlainCanvasMap.tsx) and
 * by the marker/trail helpers they both pull from.
 *
 * Extracted from FleetMapCanvas.tsx during the Phase 0 file split -- these
 * types were declared there and used by every one of the pieces that file was
 * broken into, so leaving them behind would have made every extracted module
 * import from the component it is meant to serve. No shape changed.
 */

/**
 * A `VehicleLiveRead` enriched with the two purely-client-side states the map
 * (and VehicleDetailModal) surface alongside it: idle detection
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

/**
 * One tablet plotted in its own right, from its last locate response.
 *
 * A device and a vehicle are not the same thing, and the map only ever knew about
 * the second. A tablet bound to no vehicle -- brand new, or left over after its car
 * was retired -- had no way of appearing at all, so "locate that tablet" silently
 * found nothing. These are drawn distinctly (a hollow grey ring, never the status
 * palette) because a device's last locate is a snapshot from whenever an operator
 * last asked, not a live position, and must not be read as one.
 */
export interface DevicePoint {
  id: string;
  label: string;
  lat: number;
  lng: number;
  /** When the tablet actually answered the locate. Shown, not hidden -- a fix from
   * three days ago is still worth having, as long as nobody thinks it is current. */
  locatedAt: string | null;
}

/** One recorded position for the history trail. A trimmed
 * `PositionHistoryItem` -- the canvas needs no more than this, and taking the
 * narrower type keeps it independent of the history endpoint's shape. */
export interface TrailPoint {
  lat: number;
  lng: number;
  speedKmh: number | null;
  recordedAt: string;
}

/** A vehicle that actually has a position, and can therefore be drawn. */
export type PlottedVehicle = VehicleMapState & { lat: number; lng: number };

/** Everything either renderer needs. Both `MapboxFleetMap` and
 * `PlainCanvasMap` take exactly this, so FleetMapCanvas can pick between them
 * without reshaping anything. */
export interface MapDataProps {
  plotted: PlottedVehicle[];
  duressByVehicleId: Map<string, DuressEventRead>;
  geofences: Geofence[];
  /** Live route-to-destination line per routable vehicle -- see
   * useVehicleRoutes.ts. Keyed by vehicle id; a vehicle absent from this map
   * has no line drawn (not on-trip, no destination picked, or not yet in
   * `plotted`). */
  routes: Map<string, VehicleRouteState>;
  onSelectVehicle: (vehicleId: string) => void;
  selectedVehicleId: string | null;
  follow: boolean;
  onFollowInterrupted?: () => void;
  trail: TrailPoint[];
  trailCursor: number | null;
  devicePoints: DevicePoint[];
  selectedDeviceId: string | null;
  onSelectDevice: (deviceId: string) => void;
}
