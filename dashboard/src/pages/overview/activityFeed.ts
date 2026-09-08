import type { LiveSocketState } from "@/hooks/useLiveMap";
import type { Trip } from "@/hooks/useTrips";
import type { Device } from "@/pages/fleet/types";
import type { DuressEventRead, VehicleLiveRead } from "@/pages/live-map/types";
import { formatAud, truncateId } from "@/lib/format";

/**
 * Client-side activity feed for the owner Overview.
 *
 * Nothing here is fetched: the backend has no "events" endpoint, and inventing
 * one for a home page is not this change's job. Instead every poll/socket
 * update produces a `FeedSnapshot`, and `diffSnapshots` turns the difference
 * between two consecutive snapshots into human-readable events. The first
 * snapshot of each domain is a silent baseline -- a page load never announces
 * "T22123 started a trip" for a trip that was already running -- and a domain
 * that has not loaded yet (`null`) is skipped rather than diffed against an
 * empty list, which would otherwise announce every existing trip as new.
 *
 * Pure so it can be unit-tested without React: see activityFeed.test.ts.
 */

export type ActivityKind =
  | "vehicle_status"
  | "trip_opened"
  | "trip_closed"
  | "duress_opened"
  | "duress_cleared"
  | "device_offline"
  | "device_online"
  | "socket_connected"
  | "socket_disconnected";

export type ActivityTone = "info" | "success" | "warning" | "destructive";

export interface ActivityEvent {
  /** Stable per-transition id, so a re-render never duplicates a row. */
  id: string;
  kind: ActivityKind;
  tone: ActivityTone;
  /** ISO timestamp -- the data's own timestamp where it has one, else the
   * moment the dashboard observed the change. */
  at: string;
  text: string;
  /** In-app route the row links to, or null when there is nothing to open. */
  href: string | null;
}

/** `null` for a domain means "not loaded yet" -- see the module doc. */
export interface FeedSnapshot {
  vehicles: VehicleLiveRead[] | null;
  trips: Trip[] | null;
  /** Open duress events only (the same `open_only=true` list Live Map polls). */
  duress: DuressEventRead[] | null;
  devices: Device[] | null;
  socket: LiveSocketState;
}

/** A tablet that has not been heard from in this long is "offline" for the
 * feed and the Overview's needs-attention KPI. A default chosen for this
 * page, not a decided product policy. */
export const DEVICE_OFFLINE_AFTER_MS = 15 * 60 * 1000;

export function isDeviceOffline(device: Pick<Device, "last_seen_at">, nowMs: number): boolean {
  if (!device.last_seen_at) return true;
  const seen = new Date(device.last_seen_at).getTime();
  if (Number.isNaN(seen)) return true;
  return nowMs - seen > DEVICE_OFFLINE_AFTER_MS;
}

function statusPhrase(status: string): { text: string; tone: ActivityTone } | null {
  switch (status.toLowerCase()) {
    case "on_trip":
    case "hired":
    case "busy":
    case "trip":
      return { text: "started a trip", tone: "success" };
    case "available":
      return { text: "is available", tone: "info" };
    case "break":
      return { text: "went on break", tone: "warning" };
    case "offline":
      return { text: "went offline", tone: "warning" };
    default:
      return { text: `is now ${status}`, tone: "info" };
  }
}

function vehicleLabel(v: Pick<VehicleLiveRead, "rego" | "current_driver_name">): string {
  return v.current_driver_name ? `${v.rego} · ${v.current_driver_name}` : v.rego;
}

function deviceLabel(d: Pick<Device, "model" | "android_id">): string {
  return d.model ?? `Tablet ${d.android_id.slice(0, 8)}`;
}

function byId<T extends { id: string }>(items: T[]): Map<string, T> {
  const map = new Map<string, T>();
  for (const item of items) map.set(item.id, item);
  return map;
}

/**
 * Events implied by moving from `prev` to `next`, oldest first. `prev === null`
 * is the very first snapshot and yields nothing. `nowIso` is the observation
 * time used wherever the data carries no timestamp of its own.
 */
export function diffSnapshots(prev: FeedSnapshot | null, next: FeedSnapshot, nowIso: string): ActivityEvent[] {
  if (!prev) return [];
  const events: ActivityEvent[] = [];
  const nowMs = new Date(nowIso).getTime();

  // Vehicle rego/driver lookups for trip rows, from whichever vehicle list is
  // freshest. Trips carry only ids.
  const vehicleById = byId(next.vehicles ?? prev.vehicles ?? []);
  const driverName = (driverId: string): string | null => {
    for (const v of vehicleById.values()) {
      if (v.current_driver_id === driverId && v.current_driver_name) return v.current_driver_name;
    }
    return null;
  };
  const tripSubject = (t: Trip): string => {
    const rego = vehicleById.get(t.vehicle_id)?.rego ?? truncateId(t.vehicle_id);
    const driver = driverName(t.driver_id);
    return driver ? `${rego} · ${driver}` : rego;
  };

  // --- vehicle live_status transitions -----------------------------------
  if (prev.vehicles && next.vehicles) {
    const before = byId(prev.vehicles);
    for (const v of next.vehicles) {
      const was = before.get(v.id);
      if (!was || was.live_status === v.live_status) continue;
      const phrase = statusPhrase(v.live_status);
      if (!phrase) continue;
      events.push({
        id: `vehicle:${v.id}:${was.live_status}>${v.live_status}:${v.position_updated_at ?? nowIso}`,
        kind: "vehicle_status",
        tone: phrase.tone,
        at: v.position_updated_at ?? nowIso,
        text: `${vehicleLabel(v)} ${phrase.text}`,
        href: `/live-map?vehicle=${v.id}`,
      });
    }
  }

  // --- trips opened / closed since the last poll --------------------------
  if (prev.trips && next.trips) {
    const before = byId(prev.trips);
    for (const t of next.trips) {
      const was = before.get(t.id);
      if (!was) {
        if (t.status === "closed") {
          events.push(tripClosedEvent(t, tripSubject(t)));
        } else {
          events.push({
            id: `trip:${t.id}:opened`,
            kind: "trip_opened",
            tone: "info",
            at: t.start_at,
            text: `Trip opened · ${tripSubject(t)}`,
            href: "/trips",
          });
        }
      } else if (was.status === "open" && t.status === "closed") {
        events.push(tripClosedEvent(t, tripSubject(t)));
      }
    }
  }

  // --- duress raised / cleared ---------------------------------------------
  if (prev.duress && next.duress) {
    const before = byId(prev.duress);
    const after = byId(next.duress);
    for (const e of next.duress) {
      if (before.has(e.id)) continue;
      const rego = vehicleById.get(e.vehicle_id)?.rego ?? truncateId(e.vehicle_id);
      events.push({
        id: `duress:${e.id}:opened`,
        kind: "duress_opened",
        tone: "destructive",
        at: e.opened_at,
        text: `Duress raised · ${rego} (${e.trigger})`,
        href: `/duress?event=${e.id}`,
      });
    }
    for (const e of prev.duress) {
      if (after.has(e.id)) continue;
      const rego = vehicleById.get(e.vehicle_id)?.rego ?? truncateId(e.vehicle_id);
      events.push({
        id: `duress:${e.id}:cleared`,
        kind: "duress_cleared",
        tone: "success",
        at: nowIso,
        text: `Duress cleared · ${rego}`,
        href: `/duress?event=${e.id}`,
      });
    }
  }

  // --- tablets going offline / coming back ---------------------------------
  if (prev.devices && next.devices) {
    const before = byId(prev.devices);
    for (const d of next.devices) {
      if (d.revoked_at) continue;
      const was = before.get(d.id);
      if (!was) continue;
      // `prev` is judged at the time it was taken, so a tablet that simply
      // aged past the threshold between polls reads as a transition too --
      // which is exactly the event the owner wants to see.
      const wasOffline = isDeviceOffline(was, nowMs - 1);
      const nowOffline = isDeviceOffline(d, nowMs);
      if (wasOffline === nowOffline) continue;
      events.push({
        id: `device:${d.id}:${nowOffline ? "offline" : "online"}:${d.last_seen_at ?? nowIso}`,
        kind: nowOffline ? "device_offline" : "device_online",
        tone: nowOffline ? "warning" : "success",
        at: nowOffline ? nowIso : (d.last_seen_at ?? nowIso),
        text: nowOffline
          ? `${deviceLabel(d)} went offline (no heartbeat for 15 min)`
          : `${deviceLabel(d)} is back online`,
        href: "/fleet",
      });
    }
  }

  // --- live feed socket ------------------------------------------------------
  if (prev.socket !== next.socket) {
    if (next.socket === "open") {
      events.push({
        id: `socket:open:${nowIso}`,
        kind: "socket_connected",
        tone: "success",
        at: nowIso,
        text: "Live position feed connected",
        href: null,
      });
    } else if (prev.socket === "open") {
      events.push({
        id: `socket:closed:${nowIso}`,
        kind: "socket_disconnected",
        tone: "warning",
        at: nowIso,
        text: "Live position feed dropped — reconnecting",
        href: null,
      });
    }
  }

  return events;
}

function tripClosedEvent(t: Trip, subject: string): ActivityEvent {
  return {
    id: `trip:${t.id}:closed`,
    kind: "trip_closed",
    tone: "success",
    at: t.end_at ?? t.updated_at,
    text: `Trip closed · ${subject} · ${formatAud(t.total)}${t.simulated ? " (simulated)" : ""}`,
    href: "/trips",
  };
}

/** Newest-first merge of `incoming` (oldest-first, as diffSnapshots returns)
 * onto an existing feed, de-duplicated by id and capped. */
export function pushEvents(feed: ActivityEvent[], incoming: ActivityEvent[], cap: number): ActivityEvent[] {
  if (incoming.length === 0) return feed;
  const seen = new Set(feed.map((e) => e.id));
  const fresh = incoming.filter((e) => !seen.has(e.id));
  if (fresh.length === 0) return feed;
  return [...fresh.reverse(), ...feed].slice(0, cap);
}
