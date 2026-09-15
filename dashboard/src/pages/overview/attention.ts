import type { Trip } from "@/hooks/useTrips";
import { formatDurationShort, formatRelativeTime } from "@/lib/format";
import type { Device, Vehicle } from "@/pages/fleet/types";
import type { DuressEventRead } from "@/pages/live-map/types";
import { isStaleDuress } from "@/pages/live-map/utils";
import type { Shift } from "@/pages/shifts/types";
import { isDeviceOffline } from "./activityFeed";

/**
 * The Overview "Attention" queue (admin-panel plan §6): one list of the
 * things an operator has to act on today, each row a link into the page
 * with the matching filter already applied. The KPI tiles above it are
 * counts; this is the work itself.
 *
 * Pure: takes whatever the page's queries have resolved (null while a
 * source is still loading, so a group can say "checking" rather than
 * "nothing") and returns groups of rows. Nothing here fetches.
 */

export type AttentionTone = "destructive" | "warning";

export interface AttentionItem {
  id: string;
  title: string;
  detail: string;
  href: string;
  tone: AttentionTone;
}

export type AttentionGroupKey =
  | "flagged_trips"
  | "stale_duress"
  | "unreconciled_shifts"
  | "tablets"
  | "documents";

export interface AttentionGroup {
  key: AttentionGroupKey;
  label: string;
  /** The page with the group's filter pre-applied. */
  href: string;
  /** False while the group's source has not resolved yet. */
  loaded: boolean;
  items: AttentionItem[];
}

export interface AttentionInput {
  now: number;
  /** `GET /v1/trips?flagged_for_review=true`. */
  flaggedTrips: Trip[] | null;
  /** `GET /v1/duress?open_only=true`. */
  duress: DuressEventRead[] | null;
  /** `GET /v1/shifts?reconciled=false`. */
  shifts: Shift[] | null;
  devices: Device[] | null;
  /** `GET /v1/fleet/vehicles` -- the CRUD row, which is where the expiry
   * dates live (`GET /v1/vehicles`, the live-ops row, has none). */
  vehicles: Vehicle[] | null;
  vehicleRegoById: ReadonlyMap<string, string>;
  driverNameById: ReadonlyMap<string, string>;
}

/** Documents due inside this window are listed; earlier ones are not. */
export const DOCUMENT_EXPIRY_WINDOW_DAYS = 30;
/** Inside this many days (or already expired) a document row turns red. */
export const DOCUMENT_EXPIRY_URGENT_DAYS = 7;
/** Matches the "Tablets needing attention" KPI tile's own threshold. */
export const LOW_BATTERY_PCT = 20;

const DAY_MS = 24 * 60 * 60 * 1000;

function shortId(id: string): string {
  return id.slice(0, 8);
}

function regoFor(vehicleId: string, regos: ReadonlyMap<string, string>): string {
  return regos.get(vehicleId) ?? `Vehicle ${shortId(vehicleId)}`;
}

function driverFor(driverId: string, names: ReadonlyMap<string, string>, joined?: string | null): string {
  return joined ?? names.get(driverId) ?? `Driver ${shortId(driverId)}`;
}

/** Calendar days from `now`'s local date to a `YYYY-MM-DD` date; 0 on the
 * day itself, negative once past. Local calendar arithmetic, since an expiry
 * date is a date on a document, not an instant. Null for a missing or
 * unparseable date -- "unknown" is not "expiring". */
export function daysUntil(date: string | null | undefined, now: number): number | null {
  if (!date) return null;
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(date);
  if (!match) return null;
  const expiry = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3])).getTime();
  if (Number.isNaN(expiry)) return null;
  const today = new Date(now);
  const todayMidnight = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime();
  return Math.round((expiry - todayMidnight) / DAY_MS);
}

export interface ExpiringDocument {
  label: "Registration" | "Insurance";
  expiresOn: string;
  daysLeft: number;
}

/** Every document on a vehicle due within the window, soonest first. */
export function documentsExpiringWithin(
  vehicle: Pick<Vehicle, "registration_expiry" | "insurance_expiry">,
  now: number,
  windowDays: number = DOCUMENT_EXPIRY_WINDOW_DAYS,
): ExpiringDocument[] {
  const docs: ExpiringDocument[] = [];
  const candidates: Array<[ExpiringDocument["label"], string | null]> = [
    ["Registration", vehicle.registration_expiry],
    ["Insurance", vehicle.insurance_expiry],
  ];
  for (const [label, date] of candidates) {
    const daysLeft = daysUntil(date, now);
    if (daysLeft == null || daysLeft > windowDays) continue;
    docs.push({ label, expiresOn: date as string, daysLeft });
  }
  return docs.sort((a, b) => a.daysLeft - b.daysLeft);
}

function describeExpiry(doc: ExpiringDocument): string {
  if (doc.daysLeft < 0) {
    const ago = -doc.daysLeft;
    return `${doc.label} expired ${ago} day${ago === 1 ? "" : "s"} ago`;
  }
  if (doc.daysLeft === 0) return `${doc.label} expires today`;
  return `${doc.label} expires in ${doc.daysLeft} day${doc.daysLeft === 1 ? "" : "s"}`;
}

/** Why a tablet is on the list, or null when it is fine. Mirrors the KPI
 * tile's three reasons so the tile's count and this list never disagree. */
export function tabletAttentionReasons(device: Device, now: number): string[] {
  if (device.revoked_at) return [];
  const reasons: string[] = [];
  if (device.battery != null && device.battery < LOW_BATTERY_PCT) reasons.push(`Battery ${device.battery}%`);
  if (isDeviceOffline(device, now)) {
    reasons.push(device.last_seen_at ? `No heartbeat since ${formatRelativeTime(device.last_seen_at, now)}` : "Never seen");
  }
  if (device.force_update_pending) reasons.push("Update pending");
  return reasons;
}

export function buildAttentionGroups(input: AttentionInput): AttentionGroup[] {
  const { now, vehicleRegoById, driverNameById } = input;

  const flaggedTrips: AttentionGroup = {
    key: "flagged_trips",
    label: "Flagged trips",
    href: "/trips?review=flagged",
    loaded: input.flaggedTrips != null,
    items: (input.flaggedTrips ?? [])
      .filter((t) => t.flagged_for_review)
      .map((t) => ({
        id: t.id,
        title: `Trip ${shortId(t.id)} — ${regoFor(t.vehicle_id, vehicleRegoById)} · ${driverFor(t.driver_id, driverNameById)}`,
        detail: t.review_notes?.trim() || `Flagged for review${t.max_fare_check_passed ? "" : " — fare check failed"}`,
        href: `/trips/${t.id}`,
        tone: "destructive" as const,
      })),
  };

  const staleDuress: AttentionGroup = {
    key: "stale_duress",
    label: "Stale duress events",
    href: "/duress?status=open",
    loaded: input.duress != null,
    items: (input.duress ?? [])
      .filter((e) => isStaleDuress(e, now))
      .map((e) => ({
        id: e.id,
        title: `${regoFor(e.vehicle_id, vehicleRegoById)} — ${driverFor(e.driver_id, driverNameById, e.driver_name)}`,
        detail: `${e.status} for ${formatDurationShort(now - new Date(e.opened_at).getTime())} — resolve or cancel it`,
        href: `/duress?event=${e.id}&status=open`,
        tone: "destructive" as const,
      })),
  };

  const unreconciledShifts: AttentionGroup = {
    key: "unreconciled_shifts",
    label: "Unreconciled shifts",
    href: "/shifts?reconciled=false",
    loaded: input.shifts != null,
    items: (input.shifts ?? [])
      .filter((s) => s.end_at != null && !s.reconciled)
      .map((s) => ({
        id: s.id,
        title: `${regoFor(s.vehicle_id, vehicleRegoById)} — ${driverFor(s.driver_id, driverNameById)}`,
        detail: `Ended ${formatRelativeTime(s.end_at, now)} · ${s.trips_count} trip${s.trips_count === 1 ? "" : "s"}, not reconciled`,
        href: `/shifts/${s.id}`,
        tone: "warning" as const,
      })),
  };

  const tablets: AttentionGroup = {
    key: "tablets",
    label: "Tablets needing attention",
    href: "/fleet?tab=devices",
    loaded: input.devices != null,
    items: (input.devices ?? [])
      .map((d) => ({ device: d, reasons: tabletAttentionReasons(d, now) }))
      .filter(({ reasons }) => reasons.length > 0)
      .map(({ device, reasons }) => ({
        id: device.id,
        title: `${device.model ?? "Tablet"} ${shortId(device.android_id)}${
          device.vehicle_id ? ` — ${regoFor(device.vehicle_id, vehicleRegoById)}` : " — unpaired"
        }`,
        detail: reasons.join(" · "),
        href: `/devices/${device.id}`,
        tone: "warning" as const,
      })),
  };

  const documents: AttentionGroup = {
    key: "documents",
    label: `Documents expiring within ${DOCUMENT_EXPIRY_WINDOW_DAYS} days`,
    href: "/compliance",
    loaded: input.vehicles != null,
    items: (input.vehicles ?? [])
      .filter((v) => v.status !== "retired")
      .flatMap((v) =>
        documentsExpiringWithin(v, now).map((doc) => ({
          id: `${v.id}-${doc.label}`,
          title: v.rego,
          detail: `${describeExpiry(doc)} (${doc.expiresOn})`,
          href: `/vehicles/${v.id}`,
          tone: doc.daysLeft <= DOCUMENT_EXPIRY_URGENT_DAYS ? ("destructive" as const) : ("warning" as const),
        })),
      ),
  };

  return [flaggedTrips, staleDuress, unreconciledShifts, tablets, documents];
}
