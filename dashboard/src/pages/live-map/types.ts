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
  /** Best-known live status, e.g. available | on_trip | offline | break. */
  live_status: string;
  position_updated_at: string | null;
  position_source: PositionSource;
  current_trip_id: string | null;
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
