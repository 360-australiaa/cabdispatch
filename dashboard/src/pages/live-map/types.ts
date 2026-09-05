/**
 * Local TS mirrors of the backend Live Ops / Duress schemas this page
 * consumes (see backend/app/schemas/live_ops.py, backend/app/schemas/duress.py,
 * shared/openapi.json). This domain has no dashboard-owned CRUD entity of its
 * own — Live Ops is an explicitly read-only rollup joined from sibling
 * domains (fleet/trips/shifts/users), plus an ephemeral position pub/sub
 * pipeline — so these are read shapes, not a full resource model.
 */

export interface Page<T> {
  items: T[];
  total: number;
  skip: number;
  limit: number;
}

export type PositionSource = "live" | "trip" | "none";

/** One row of `GET /v1/vehicles`. */
export interface VehicleLiveRead {
  id: string;
  tenant_id: string;
  rego: string;
  vehicle_class: string;
  /** Vehicle.status from the fleet domain: active | maintenance | suspended | retired. */
  vehicle_status: string;
  device_id: string | null;
  device_last_seen_at: string | null;
  /** 0-100 tablet battery pct -- freshest of a live position publish or a
   * plain device heartbeat, whichever reported last. Null if never reported. */
  battery: number | null;
  /** e.g. "wifi" / "4g" / "offline" -- same source/freshness rule as battery. */
  network: string | null;
  lat: number | null;
  lng: number | null;
  /** km/h from the vehicle's last live position publish -- a property of
   * that specific fix, not a slowly-changing property of the tablet, so
   * (unlike battery/network) there is no Device-row fallback for this: null
   * means "not known" (never reported, or GPS didn't surface one), never
   * "stationary" -- see formatSpeed in utils.ts, and PositionRead.speed_kmh's
   * own doc (backend/app/schemas/live_ops.py) for the full rationale. */
  speed_kmh: number | null;
  /** 0-360 compass degrees from the freshest position publish, null when
   * unknown (vehicle stationary, or GPS never reported one). Never fabricate
   * a heading when null -- render an un-rotated/neutral marker instead
   * (see FleetMapCanvas.buildMarkerElement). */
  heading: number | null;
  /** Best-known live status, e.g. available | on_trip | offline | break. */
  live_status: string;
  position_updated_at: string | null;
  position_source: PositionSource;
  current_trip_id: string | null;
  /** Driver on this vehicle's currently-open shift, if any -- "who has this
   * vehicle checked out right now". Always derived live from the shift
   * domain, never a cached pointer. */
  current_driver_id: string | null;
  current_driver_name: string | null;
  current_shift_id: string | null;
  current_shift_start_at: string | null;
  /** The driver's currently-selected destination for `current_trip_id`, if
   * any -- surfaced so the map can draw a live route from the vehicle's
   * current position to where it's actually headed (FleetMapCanvas's route
   * overlay). Null whenever there's no open trip, or the driver hasn't
   * picked a destination on it yet -- same honest-null convention as every
   * other optional field on this row (battery/network/lat/lng/heading
   * above): never a placeholder coordinate standing in for "unknown". */
  planned_dest_lat: number | null;
  planned_dest_lng: number | null;
}

/** One row of `GET /v1/drivers`, and the shape of `GET /v1/drivers/{id}`. */
export interface DriverLiveRead {
  id: string;
  tenant_id: string;
  name: string;
  phone: string | null;
  /** User.status from the user domain. */
  user_status: string;
  on_shift: boolean;
  shift_id: string | null;
  /** Vehicle currently assigned via the open shift, if on_shift. */
  vehicle_id: string | null;
  shift_start_at: string | null;
  current_trip_id: string | null;
}

/** One row of `GET /v1/fleet/vehicles/{id}/shift-history` -- "which drivers
 * has this vehicle had", not just the live current one (that's
 * VehicleLiveRead.current_driver_* above). Newest-first by start_at. */
export interface VehicleShiftHistoryItem {
  shift_id: string;
  driver_id: string;
  /** Null only if the driver's User row is gone. */
  driver_name: string | null;
  start_at: string;
  /** Null means this shift is still open. */
  end_at: string | null;
  distance_km: string;
  fare_total: string;
}

export type DuressTrigger = "button" | "gesture" | "voice" | "auto";
export type DuressStatus = "open" | "escalating" | "dispatched" | "resolved" | "cancelled";

/** One row of `GET /v1/duress`. */
export interface DuressEventRead {
  id: string;
  tenant_id: string;
  vehicle_id: string;
  driver_id: string;
  trigger: DuressTrigger;
  status: DuressStatus;
  opened_at: string;
  closed_at: string | null;
  gps_stream_ref: string;
  audio_ref: string | null;
  escalation_log_json: Record<string, unknown>;
  created_at: string;
  updated_at: string;
}

export interface DuressEventListResponse {
  items: DuressEventRead[];
  total: number;
  limit: number;
  offset: number;
}

/** Body for `POST /v1/fleet/positions`. */
export interface PublishPositionRequest {
  vehicle_id: string;
  lat: number;
  lng: number;
  status: string;
  battery?: number | null;
  network?: string | null;
}

/** One row of `GET /v1/vehicles/{id}/position-history`'s `items` array --
 * a single past position fix, not the live/current one (that's
 * VehicleLiveRead.lat/lng/speed_kmh/heading/position_updated_at above).
 * speed_kmh/heading follow the exact same honest-null convention as their
 * VehicleLiveRead counterparts (see that interface's own docs): null means
 * "not known for this fix", never "zero"/"stationary"/"north" -- see
 * formatSpeed in utils.ts. */
export interface PositionHistoryItem {
  lat: number;
  lng: number;
  speed_kmh: number | null;
  heading: number | null;
  /** live_status value at the time this fix was recorded, e.g. "on_trip". */
  status: string;
  recorded_at: string;
}

/** `GET /v1/vehicles/{id}/position-history` response -- backs
 * VehicleDetailModal's replay scrubber (`items`) and driving-signals readout
 * (the three fields below). `items` is retained server-side for a default
 * window (72h as of this writing) that the backend's own doc comment flags
 * explicitly as "a technical default, not a decided data-retention policy"
 * (see backend/app/services/live_ops.py) -- this dashboard never re-states
 * that number as a settled guarantee, only ever as "default retention, not a
 * fixed policy" wherever it's shown (see VehicleDetailModal's Replay section).
 *
 * `harsh_brake_events`/`rapid_accel_events` are plain counts of fixes where
 * the speed delta between consecutive samples exceeded `threshold_kmh_per_s`
 * -- an informational telematics signal computed against a standard
 * threshold, not a certified safety score or disciplinary rating (see
 * VehicleDetailModal's own caption). `threshold_kmh_per_s` is sent by the
 * backend specifically so this dashboard never hardcodes or guesses it. */
export interface VehiclePositionHistoryResponse {
  items: PositionHistoryItem[];
  harsh_brake_events: number;
  rapid_accel_events: number;
  threshold_kmh_per_s: number;
}
