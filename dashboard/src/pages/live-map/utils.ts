import type { LivePosition } from "@/hooks/useLiveMap";
import type { Geofence } from "@/hooks/useGeofences";
import type { VehicleLiveRead } from "./types";

/**
 * Overlays a WS `/v1/fleet/live` position on top of a REST-fetched vehicle
 * row. WS positions are ephemeral (nothing durable to fall back to), so a
 * vehicle with no live-cache entry yet just keeps whatever `GET /v1/vehicles`
 * already resolved (its live trip tick, or none).
 */
export function mergeLivePosition(
  vehicle: VehicleLiveRead,
  positions: Record<string, LivePosition>,
): VehicleLiveRead {
  const live = positions[vehicle.id];
  if (!live) return vehicle;
  return {
    ...vehicle,
    lat: live.lat,
    lng: live.lng,
    live_status: live.status,
    // Coalesce, don't overwrite: a position publish carrying no telemetry
    // (battery/network both null on the WS payload) must not blank out a
    // value the REST-fetched row already had from an earlier publish or a
    // plain device heartbeat -- see PositionPublishRequest.battery's own doc
    // (backend/app/schemas/live_ops.py) for why both are optional per-call.
    battery: live.battery ?? vehicle.battery,
    network: live.network ?? vehicle.network,
    // NOT the same coalesce as battery/network above -- overwrite plainly,
    // same as lat/lng/live_status just above. speed_kmh/heading are a
    // property of *this specific* position fix, not a slowly-changing
    // property of the tablet: there is no Device-row persistence for them to
    // fall back to, and coalescing onto a stale value from an earlier fix
    // would be actively misleading (e.g. a marker still shown pointing a
    // direction from minutes ago after the vehicle has stopped and its
    // heading is genuinely no longer known) rather than merely lagging --
    // see PositionRead.speed_kmh's own doc (backend/app/schemas/live_ops.py).
    speed_kmh: live.speed_kmh,
    heading: live.heading,
    position_updated_at: live.updated_at,
    position_source: "live",
  };
}

const BUSY_STATUSES = new Set(["on_trip", "hired", "busy", "trip"]);

/** Whether a live_status value counts as "actively working a trip" -- shared
 * by statusColor/statusBadgeVariant below and by computeIdleInfo's own
 * "not on_trip" rule, so idle detection and the status pill never disagree
 * about which live_status values mean "busy". */
export function isBusyStatus(status: string): boolean {
  return BUSY_STATUSES.has(status.toLowerCase());
}

/** SVG-fill-safe color (CSS var) for a live_status value. */
export function statusColor(status: string): string {
  const s = status.toLowerCase();
  if (s === "available") return "var(--success)";
  if (isBusyStatus(s)) return "var(--brand-accent)";
  return "var(--muted-foreground)";
}

export function statusBadgeVariant(status: string): "success" | "accent" | "outline" {
  const s = status.toLowerCase();
  if (s === "available") return "success";
  if (isBusyStatus(s)) return "accent";
  return "outline";
}

export function formatRelativeTime(iso: string | null): string {
  if (!iso) return "—";
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "—";
  const diffSec = Math.max(0, Math.floor((Date.now() - then) / 1000));
  if (diffSec < 5) return "just now";
  if (diffSec < 60) return `${diffSec}s ago`;
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) return `${diffHr}h ago`;
  const diffDay = Math.floor(diffHr / 24);
  return `${diffDay}d ago`;
}

export function formatLatLng(lat: number | null, lng: number | null): string {
  if (lat == null || lng == null) return "—";
  return `${lat.toFixed(4)}, ${lng.toFixed(4)}`;
}

/** Human label for a vehicle's reported speed -- "—" when `speed_kmh` was
 * never reported (never assume 0 for a null reading, same honest-null rule
 * as everything else in this domain), "Stationary" for a real-but-near-zero
 * reading rather than printing "0 km/h" or "1 km/h" GPS jitter, and a rounded
 * "N km/h" otherwise. Used by both map hover cards (FleetMapCanvas) and
 * VehicleDetailModal so the two surfaces never disagree on wording. */
export function formatSpeed(speedKmh: number | null): string {
  if (speedKmh == null) return "—";
  if (speedKmh < 1) return "Stationary";
  return `${Math.round(speedKmh)} km/h`;
}


/** Color for a battery-pct chip -- red under 20%, amber under 40%, green
 * otherwise. Null (never reported) is handled by the caller, not here. */
export function batteryColor(pct: number): string {
  if (pct < 20) return "var(--destructive)";
  if (pct < 40) return "var(--warning, #d97706)";
  return "var(--success)";
}

/** Human label + badge variant for a Device.network value. Unknown/unmapped
 * values (e.g. a future "5g") still render, just with the neutral variant. */
export function networkBadgeVariant(
  network: string | null,
): "success" | "accent" | "destructive" | "outline" {
  if (network === "wifi") return "success";
  if (network === "offline") return "destructive";
  if (network != null) return "accent"; // "4g" / "3g" / etc.
  return "outline";
}

/** Whether a position/telemetry timestamp is old enough to flag visually --
 * 3x the Android app's own live-position heartbeat interval
 * (LivePositionHeartbeat.kt, dropped from 30s to 5s in the same change that
 * introduced this threshold's current value), so a single missed beat
 * (network hiccup) does not flicker stale, only a genuinely dropped
 * connection does. */
const STALE_THRESHOLD_MS = 15_000;

export function isStale(iso: string | null): boolean {
  if (!iso) return false;
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return false;
  return Date.now() - then > STALE_THRESHOLD_MS;
}

/** Human-readable "signal lost" label for a stale position, or null when the
 * vehicle isn't stale -- shared by the map hover card, VehicleDetailModal and
 * PlainCanvasMap so all three word this identically (same convention as
 * formatSpeed's own doc above). */
export function staleLabel(iso: string | null): string | null {
  if (!isStale(iso)) return null;
  return `Signal lost ${formatRelativeTime(iso)}`;
}

// ---------------------------------------------------------------------------
// Idle detection ("heartbeating on time, but hasn't actually moved") -- a
// genuinely different concept from isStale above ("stopped heartbeating at
// all"). Computed entirely client-side from a short position-history buffer
// the caller maintains (see usePositionHistory.ts) -- there is no backend
// endpoint for this, and deliberately isn't one: this backend has no
// scheduler/background-job runner at all, and everything idle detection
// needs is already in the live position stream the dashboard already polls,
// so introducing server-side infra just to move this computation off the
// client would be a much bigger, riskier change than the payoff justifies.
// ---------------------------------------------------------------------------

/** One remembered fix for a vehicle -- see usePositionHistory.ts. */
export interface PositionSample {
  lat: number;
  lng: number;
  at: number;
}

/** How far back idle detection looks for movement -- a default chosen for
 * this task, not a decided product policy (see docs/DURESS_DEVICE_INTEGRATION.md
 * sec 8 for this codebase's convention on flagging such numbers honestly). */
export const IDLE_WINDOW_MS = 10 * 60 * 1000;

/** How far a vehicle may drift and still count as "hasn't moved" -- GPS
 * jitter on a parked vehicle easily wanders a few meters, so this needs
 * enough slack to not flicker idle/not-idle on noise alone. Also a default,
 * not a decided policy, same caveat as IDLE_WINDOW_MS above. */
export const IDLE_RADIUS_M = 30;

export interface IdleInfo {
  idle: boolean;
  /** Milliseconds since the oldest still-relevant history sample, i.e. "how
   * long we've observed it parked" -- only meaningful when `idle` is true. */
  idleSinceMs: number | null;
}

const NOT_IDLE: IdleInfo = { idle: false, idleSinceMs: null };

/**
 * True only once history spans (close enough to) the full IDLE_WINDOW_MS and
 * every sample in it stays within IDLE_RADIUS_M of the latest fix -- a
 * vehicle that just appeared (one sample, or a short history because the
 * dashboard tab only just opened) is "not enough data yet", not idle, so a
 * freshly-loaded page never flashes every parked vehicle idle instantly.
 * `liveStatus`/`positionUpdatedAt` gate out vehicles that are busy (on a
 * trip -- stationary in traffic isn't "idle") or already stale (a vehicle
 * that stopped heartbeating has nothing meaningful to say about whether it
 * also stopped moving beforehand).
 */
export function computeIdleInfo(
  vehicle: { live_status: string; position_updated_at: string | null },
  history: PositionSample[] | undefined,
  now: number = Date.now(),
): IdleInfo {
  if (isBusyStatus(vehicle.live_status)) return NOT_IDLE;
  if (isStale(vehicle.position_updated_at)) return NOT_IDLE;
  if (!history || history.length === 0) return NOT_IDLE;

  const oldest = history[0];
  const IDLE_GRACE_MS = 30_000; // tolerate the last fix landing slightly before the window boundary
  if (oldest.at > now - IDLE_WINDOW_MS + IDLE_GRACE_MS) return NOT_IDLE;

  const latest = history[history.length - 1];
  const maxDriftM = history.reduce(
    (max, sample) => Math.max(max, haversineMeters(latest.lat, latest.lng, sample.lat, sample.lng)),
    0,
  );
  if (maxDriftM > IDLE_RADIUS_M) return NOT_IDLE;

  return { idle: true, idleSinceMs: now - oldest.at };
}

/** "Idle 12m" style label, or null when not idle -- see computeIdleInfo. */
export function idleLabel(info: IdleInfo): string | null {
  if (!info.idle || info.idleSinceMs == null) return null;
  return `Idle ${formatDurationShort(info.idleSinceMs)}`;
}

/** Compact "Xh Ym" / "Ym" duration label for a millisecond span -- distinct
 * from formatRelativeTime above (that one always suffixes "ago" for a
 * point-in-time timestamp; this one labels a plain duration, e.g. "how long
 * has it been idle for"). */
export function formatDurationShort(ms: number): string {
  const totalMinutes = Math.max(0, Math.round(ms / 60_000));
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (totalMinutes < 1) return "<1m";
  return `${minutes}m`;
}

// ---------------------------------------------------------------------------
// Geofence containment -- a direct TS port of the backend's own
// haversine-distance-under-radius check (backend/app/services/geofence.py
// haversine_m / point_in_geofence) so the dashboard can flag a vehicle as
// "inside geofence X" for live map badges without a new endpoint. Kept as
// simple as the backend's own version (no PostGIS, no spatial index -- see
// that module's own doc comment for why that's fine at this data scale).
// ---------------------------------------------------------------------------

// Mean earth radius in metres -- same constant as the backend's
// _EARTH_RADIUS_M (app/services/geofence.py), duplicated here rather than
// shared across the Python/TS boundary since there's no existing mechanism
// for that in this codebase.
const EARTH_RADIUS_M = 6_371_008.8;

/** Great-circle distance between two lat/lng points, in metres -- mirrors
 * backend/app/services/geofence.py's haversine_m exactly. */
export function haversineMeters(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const toRad = (deg: number) => (deg * Math.PI) / 180;
  const phi1 = toRad(lat1);
  const phi2 = toRad(lat2);
  const dPhi = toRad(lat2 - lat1);
  const dLambda = toRad(lng2 - lng1);
  const a = Math.sin(dPhi / 2) ** 2 + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) ** 2;
  const c = 2 * Math.asin(Math.sqrt(a));
  return EARTH_RADIUS_M * c;
}

/** True if (lat, lng) falls on or inside `geofence`'s circle -- mirrors
 * backend/app/services/geofence.py's point_in_geofence exactly. */
export function pointInGeofence(lat: number, lng: number, geofence: Geofence): boolean {
  return haversineMeters(lat, lng, geofence.center_lat, geofence.center_lng) <= geofence.radius_m;
}

/** Every fetched geofence that currently contains (lat, lng) -- used to badge
 * a vehicle's marker/hover-card/detail-modal with which geofence(s) it's in,
 * if any. Empty array (never null) when outside all of them, same
 * never-fabricate-let-the-caller-branch convention as the rest of this file. */
export function geofencesContaining(lat: number, lng: number, geofences: Geofence[]): Geofence[] {
  return geofences.filter((g) => pointInGeofence(lat, lng, g));
}
