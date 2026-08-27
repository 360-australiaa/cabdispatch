import type { LivePosition } from "@/hooks/useLiveMap";
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
    position_updated_at: live.updated_at,
    position_source: "live",
  };
}

const BUSY_STATUSES = new Set(["on_trip", "hired", "busy", "trip"]);

/** SVG-fill-safe color (CSS var) for a live_status value. */
export function statusColor(status: string): string {
  const s = status.toLowerCase();
  if (s === "available") return "var(--success)";
  if (BUSY_STATUSES.has(s)) return "var(--brand-accent)";
  return "var(--muted-foreground)";
}

export function statusBadgeVariant(status: string): "success" | "accent" | "outline" {
  const s = status.toLowerCase();
  if (s === "available") return "success";
  if (BUSY_STATUSES.has(s)) return "accent";
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
 * 3x the Android app's own 30s live-position heartbeat interval
 * (LivePositionHeartbeat.kt), so a single missed beat (network hiccup)
 * does not flicker stale, only a genuinely dropped connection does. */
const STALE_THRESHOLD_MS = 90_000;

export function isStale(iso: string | null): boolean {
  if (!iso) return false;
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return false;
  return Date.now() - then > STALE_THRESHOLD_MS;
}
