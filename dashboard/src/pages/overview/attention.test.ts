import { describe, expect, it } from "vitest";
import type { Trip } from "@/hooks/useTrips";
import type { Device, Vehicle } from "@/pages/fleet/types";
import type { DuressEventRead } from "@/pages/live-map/types";
import type { Shift } from "@/pages/shifts/types";
import {
  buildAttentionGroups,
  daysUntil,
  documentsExpiringWithin,
  tabletAttentionReasons,
  type AttentionInput,
} from "./attention";

const NOW = Date.parse("2026-09-15T10:00:00Z");
const iso = (offsetMs: number) => new Date(NOW + offsetMs).toISOString();
const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;

/** `YYYY-MM-DD` of an instant in the local calendar -- the same calendar
 * `daysUntil` counts in, so a "+3 days" fixture is 3 days in any timezone. */
function localDate(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

const regos = new Map([["v1", "T22123"]]);
const drivers = new Map([["d1", "Arsalan Rehman"]]);

function input(overrides: Partial<AttentionInput> = {}): AttentionInput {
  return {
    now: NOW,
    flaggedTrips: [],
    duress: [],
    shifts: [],
    devices: [],
    vehicles: [],
    vehicleRegoById: regos,
    driverNameById: drivers,
    ...overrides,
  };
}

function group(groups: ReturnType<typeof buildAttentionGroups>, key: string) {
  const found = groups.find((g) => g.key === key);
  if (!found) throw new Error(`no group ${key}`);
  return found;
}

describe("buildAttentionGroups", () => {
  it("reports every group as unloaded while its source is still null", () => {
    const groups = buildAttentionGroups(
      input({ flaggedTrips: null, duress: null, shifts: null, devices: null, vehicles: null }),
    );
    expect(groups.every((g) => !g.loaded && g.items.length === 0)).toBe(true);
  });

  it("links a flagged trip to its page and the group to the flagged filter", () => {
    const trip = {
      id: "trip-abcdef12",
      vehicle_id: "v1",
      driver_id: "d1",
      flagged_for_review: true,
      max_fare_check_passed: false,
      review_notes: "Device charged 59.03",
    } as Trip;
    const g = group(buildAttentionGroups(input({ flaggedTrips: [trip] })), "flagged_trips");

    expect(g.href).toBe("/trips?review=flagged");
    expect(g.loaded).toBe(true);
    expect(g.items).toHaveLength(1);
    expect(g.items[0].href).toBe("/trips/trip-abcdef12");
    expect(g.items[0].title).toBe("Trip trip-abc — T22123 · Arsalan Rehman");
    expect(g.items[0].detail).toBe("Device charged 59.03");
  });

  it("ignores an unflagged trip even if the server handed it back", () => {
    const trip = { id: "t", vehicle_id: "v1", driver_id: "d1", flagged_for_review: false } as Trip;
    expect(group(buildAttentionGroups(input({ flaggedTrips: [trip] })), "flagged_trips").items).toEqual([]);
  });

  it("lists only stale duress events, named, linking into the open-events filter", () => {
    const base = { vehicle_id: "v1", driver_id: "d1", closed_at: null } as DuressEventRead;
    const fresh = { ...base, id: "e-fresh", status: "open", opened_at: iso(-HOUR) } as DuressEventRead;
    const old = { ...base, id: "e-old", status: "dispatched", opened_at: iso(-13 * HOUR) } as DuressEventRead;
    const flagged = {
      ...base,
      id: "e-flag",
      status: "open",
      opened_at: iso(-HOUR),
      stale: true,
      driver_name: "Benn Named",
    } as DuressEventRead;

    const g = group(buildAttentionGroups(input({ duress: [fresh, old, flagged] })), "stale_duress");

    expect(g.href).toBe("/duress?status=open");
    expect(g.items.map((i) => i.id)).toEqual(["e-old", "e-flag"]);
    expect(g.items[0].href).toBe("/duress?event=e-old&status=open");
    expect(g.items[0].title).toBe("T22123 — Arsalan Rehman");
    expect(g.items[0].detail).toContain("dispatched for 13h");
    expect(g.items[1].title).toBe("T22123 — Benn Named");
  });

  it("lists ended, unreconciled shifts only", () => {
    const base = { vehicle_id: "v1", driver_id: "d1", trips_count: 3 } as Shift;
    const shifts = [
      { ...base, id: "s-open", end_at: null, reconciled: false },
      { ...base, id: "s-done", end_at: iso(-2 * HOUR), reconciled: true },
      { ...base, id: "s-todo", end_at: iso(-2 * HOUR), reconciled: false },
    ] as Shift[];

    const g = group(buildAttentionGroups(input({ shifts })), "unreconciled_shifts");

    expect(g.href).toBe("/shifts?reconciled=false");
    expect(g.items.map((i) => i.id)).toEqual(["s-todo"]);
    expect(g.items[0].href).toBe("/shifts/s-todo");
    expect(g.items[0].detail).toBe("Ended 2h ago · 3 trips, not reconciled");
  });

  it("lists tablets for the same three reasons as the KPI tile, once each", () => {
    const base = {
      android_id: "abcdef1234567890",
      model: "SM-T575",
      vehicle_id: "v1",
      revoked_at: null,
      force_update_pending: false,
      battery: 90,
      last_seen_at: iso(-10_000),
    } as Device;
    const devices = [
      { ...base, id: "d-ok" },
      { ...base, id: "d-bad", battery: 15, force_update_pending: true, last_seen_at: iso(-HOUR), vehicle_id: null },
      { ...base, id: "d-gone", battery: 5, revoked_at: iso(-DAY) },
    ] as Device[];

    const g = group(buildAttentionGroups(input({ devices })), "tablets");

    expect(g.href).toBe("/fleet?tab=devices");
    expect(g.items.map((i) => i.id)).toEqual(["d-bad"]);
    expect(g.items[0].href).toBe("/devices/d-bad");
    expect(g.items[0].title).toBe("SM-T575 abcdef12 — unpaired");
    expect(g.items[0].detail).toBe("Battery 15% · No heartbeat since 1h ago · Update pending");
  });

  it("lists vehicle documents due within 30 days, red inside a week or once expired", () => {
    const date = (days: number) => localDate(NOW + days * DAY);
    const vehicles = [
      { id: "v1", rego: "T22123", status: "active", registration_expiry: date(10), insurance_expiry: date(3) },
      { id: "v2", rego: "T99001", status: "active", registration_expiry: date(60), insurance_expiry: null },
      { id: "v3", rego: "T55555", status: "active", registration_expiry: date(-2), insurance_expiry: null },
      { id: "v4", rego: "GONE01", status: "retired", registration_expiry: date(1), insurance_expiry: null },
    ] as Vehicle[];

    const g = group(buildAttentionGroups(input({ vehicles })), "documents");

    expect(g.href).toBe("/compliance");
    expect(g.items.map((i) => [i.title, i.tone])).toEqual([
      ["T22123", "destructive"],
      ["T22123", "warning"],
      ["T55555", "destructive"],
    ]);
    expect(g.items[0].detail).toContain("Insurance expires in 3 days");
    expect(g.items[1].detail).toContain("Registration expires in 10 days");
    expect(g.items[2].detail).toContain("Registration expired 2 days ago");
    expect(g.items[0].href).toBe("/vehicles/v1");
  });

  it("falls back to shortened ids when a rego or driver name is unknown", () => {
    const trip = { id: "t1", vehicle_id: "v-unknown-1", driver_id: "d-unknown-1", flagged_for_review: true } as Trip;
    const g = group(buildAttentionGroups(input({ flaggedTrips: [trip] })), "flagged_trips");
    expect(g.items[0].title).toBe("Trip t1 — Vehicle v-unknow · Driver d-unknow");
  });
});

describe("daysUntil / documentsExpiringWithin", () => {
  it("returns null for a missing or malformed date rather than treating it as due", () => {
    expect(daysUntil(null, NOW)).toBeNull();
    expect(daysUntil("not-a-date", NOW)).toBeNull();
    expect(documentsExpiringWithin({ registration_expiry: null, insurance_expiry: "garbage" }, NOW)).toEqual([]);
  });

  it("orders documents soonest first", () => {
    const date = (days: number) => localDate(NOW + days * DAY);
    const docs = documentsExpiringWithin({ registration_expiry: date(20), insurance_expiry: date(5) }, NOW);
    expect(docs.map((d) => d.label)).toEqual(["Insurance", "Registration"]);
  });
});

describe("tabletAttentionReasons", () => {
  it("is empty for a healthy tablet and for a retired one", () => {
    const healthy = { revoked_at: null, battery: 80, last_seen_at: iso(-5000), force_update_pending: false } as Device;
    expect(tabletAttentionReasons(healthy, NOW)).toEqual([]);
    expect(tabletAttentionReasons({ ...healthy, battery: 1, revoked_at: iso(-DAY) }, NOW)).toEqual([]);
  });

  it("says never seen for a tablet with no heartbeat at all", () => {
    const never = { revoked_at: null, battery: null, last_seen_at: null, force_update_pending: false } as Device;
    expect(tabletAttentionReasons(never, NOW)).toEqual(["Never seen"]);
  });
});
