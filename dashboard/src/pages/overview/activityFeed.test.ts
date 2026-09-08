import { describe, expect, it } from "vitest";
import type { Trip } from "@/hooks/useTrips";
import type { Device } from "@/pages/fleet/types";
import type { DuressEventRead, VehicleLiveRead } from "@/pages/live-map/types";
import { diffSnapshots, pushEvents, type FeedSnapshot } from "./activityFeed";

const NOW = "2026-09-08T03:00:00.000Z";

function vehicle(overrides: Partial<VehicleLiveRead>): VehicleLiveRead {
  return {
    id: "v1",
    tenant_id: "t1",
    rego: "T22123",
    vehicle_class: "sedan",
    vehicle_status: "active",
    device_id: null,
    device_last_seen_at: null,
    battery: null,
    network: null,
    lat: null,
    lng: null,
    speed_kmh: null,
    heading: null,
    live_status: "available",
    position_updated_at: null,
    position_source: "none",
    current_trip_id: null,
    current_driver_id: "d1",
    current_driver_name: "Arsalan",
    current_shift_id: null,
    current_shift_start_at: null,
    planned_dest_lat: null,
    planned_dest_lng: null,
    ...overrides,
  };
}

function trip(overrides: Partial<Trip>): Trip {
  return {
    id: "trip1",
    tenant_id: "t1",
    client_uuid: "c1",
    vehicle_id: "v1",
    driver_id: "d1",
    shift_id: null,
    tariff_id: "tar1",
    type: "rank_hail",
    simulated: false,
    status: "open",
    time_class: "day",
    is_peak: false,
    maxi: false,
    passenger_count: 1,
    wheelchair_hiring: false,
    airport_rank_requested_maxi: false,
    voucher_code: null,
    account_reference: null,
    split_payments: null,
    start_at: "2026-09-08T02:30:00.000Z",
    end_at: null,
    start_lat: 0,
    start_lng: 0,
    end_lat: null,
    end_lng: null,
    distance_m: 0,
    moving_s: 0,
    waiting_s: 0,
    flag_fall: "0",
    dist_amount: "0",
    wait_amount: "0",
    peak_amount: "0",
    tolls: "0",
    psl: "0",
    extras: "0",
    subtotal: "0",
    surcharge: "0",
    total: "0",
    gst_component: "0",
    payment_method: "cash",
    gps_trace_ref: null,
    max_fare_check_passed: true,
    variance_pct: null,
    receipt_ref: null,
    flagged_for_review: false,
    review_notes: null,
    created_at: "2026-09-08T02:30:00.000Z",
    updated_at: "2026-09-08T02:30:00.000Z",
    ...overrides,
  };
}

function duress(overrides: Partial<DuressEventRead>): DuressEventRead {
  return {
    id: "e1",
    tenant_id: "t1",
    vehicle_id: "v1",
    driver_id: "d1",
    trigger: "button",
    status: "open",
    opened_at: "2026-09-08T02:59:00.000Z",
    closed_at: null,
    gps_stream_ref: "ref",
    audio_ref: null,
    escalation_log_json: {},
    created_at: "2026-09-08T02:59:00.000Z",
    updated_at: "2026-09-08T02:59:00.000Z",
    ...overrides,
  };
}

function device(overrides: Partial<Device>): Device {
  return {
    id: "dev1",
    tenant_id: "t1",
    android_id: "abcdef1234567890",
    model: "SM-T575",
    app_version: "1.0",
    vehicle_id: "v1",
    kiosk_locked: false,
    force_update_pending: false,
    locate_requested: false,
    reboot_requested: false,
    last_seen_at: NOW,
    battery: 80,
    network: "4g",
    paired_at: null,
    revoked_at: null,
    last_locate_lat: null,
    last_locate_lng: null,
    last_locate_accuracy_m: null,
    last_locate_at: null,
    command_acked_at: null,
    created_at: NOW,
    updated_at: NOW,
    ...overrides,
  };
}

function snapshot(overrides: Partial<FeedSnapshot>): FeedSnapshot {
  return { vehicles: null, trips: null, duress: null, devices: null, socket: "connecting", ...overrides };
}

describe("diffSnapshots", () => {
  it("treats the first snapshot as a silent baseline", () => {
    const next = snapshot({
      vehicles: [vehicle({ live_status: "on_trip" })],
      trips: [trip({})],
      duress: [duress({})],
      socket: "open",
    });
    expect(diffSnapshots(null, next, NOW)).toEqual([]);
  });

  it("skips a domain that has not loaded yet instead of announcing everything as new", () => {
    const prev = snapshot({ trips: null });
    const next = snapshot({ trips: [trip({}), trip({ id: "trip2" })] });
    expect(diffSnapshots(prev, next, NOW)).toEqual([]);
  });

  it("reports a vehicle live_status transition with rego and driver, linking to the map", () => {
    const prev = snapshot({ vehicles: [vehicle({ live_status: "available" })] });
    const next = snapshot({
      vehicles: [vehicle({ live_status: "on_trip", position_updated_at: "2026-09-08T02:59:30.000Z" })],
    });
    const events = diffSnapshots(prev, next, NOW);
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({
      kind: "vehicle_status",
      text: "T22123 · Arsalan started a trip",
      href: "/live-map?vehicle=v1",
      at: "2026-09-08T02:59:30.000Z",
    });
  });

  it("is silent when nothing changed", () => {
    const same = snapshot({ vehicles: [vehicle({})], trips: [trip({})], duress: [], devices: [device({})], socket: "open" });
    expect(diffSnapshots(same, { ...same }, NOW)).toEqual([]);
  });

  it("reports trips opened and closed since the last poll, resolving ids to rego/driver", () => {
    const vehicles = [vehicle({})];
    const prev = snapshot({ vehicles, trips: [trip({ id: "trip1", status: "open" })] });
    const next = snapshot({
      vehicles,
      trips: [
        trip({ id: "trip1", status: "closed", end_at: "2026-09-08T02:58:00.000Z", total: "42.50" }),
        trip({ id: "trip2", status: "open" }),
        trip({ id: "trip3", status: "closed", total: "10.00", simulated: true }),
      ],
    });
    const events = diffSnapshots(prev, next, NOW);
    expect(events.map((e) => e.kind)).toEqual(["trip_closed", "trip_opened", "trip_closed"]);
    expect(events[0].text).toBe("Trip closed · T22123 · Arsalan · $42.50");
    expect(events[0].at).toBe("2026-09-08T02:58:00.000Z");
    expect(events[1].text).toBe("Trip opened · T22123 · Arsalan");
    expect(events[2].text).toContain("(simulated)");
    expect(events.every((e) => e.href === "/trips")).toBe(true);
  });

  it("reports duress raised and cleared, in red for raised, linking to the event", () => {
    const vehicles = [vehicle({})];
    const raised = diffSnapshots(snapshot({ vehicles, duress: [] }), snapshot({ vehicles, duress: [duress({})] }), NOW);
    expect(raised).toHaveLength(1);
    expect(raised[0]).toMatchObject({
      kind: "duress_opened",
      tone: "destructive",
      text: "Duress raised · T22123 (button)",
      href: "/duress?event=e1",
      at: "2026-09-08T02:59:00.000Z",
    });

    const cleared = diffSnapshots(snapshot({ vehicles, duress: [duress({})] }), snapshot({ vehicles, duress: [] }), NOW);
    expect(cleared).toHaveLength(1);
    expect(cleared[0]).toMatchObject({ kind: "duress_cleared", text: "Duress cleared · T22123", at: NOW });
  });

  it("reports a tablet going offline and coming back, ignoring retired tablets", () => {
    const stale = "2026-09-08T02:00:00.000Z"; // an hour before NOW
    const offline = diffSnapshots(
      snapshot({ devices: [device({ last_seen_at: NOW }), device({ id: "dev2", last_seen_at: NOW })] }),
      snapshot({
        devices: [device({ last_seen_at: stale }), device({ id: "dev2", last_seen_at: stale, revoked_at: NOW })],
      }),
      NOW,
    );
    expect(offline).toHaveLength(1);
    expect(offline[0]).toMatchObject({ kind: "device_offline", text: "SM-T575 went offline (no heartbeat for 15 min)", href: "/fleet" });

    const online = diffSnapshots(
      snapshot({ devices: [device({ last_seen_at: stale })] }),
      snapshot({ devices: [device({ last_seen_at: NOW })] }),
      NOW,
    );
    expect(online).toHaveLength(1);
    expect(online[0]).toMatchObject({ kind: "device_online", text: "SM-T575 is back online", at: NOW });
  });

  it("reports the live socket connecting and dropping, but not intermediate retry states", () => {
    expect(diffSnapshots(snapshot({ socket: "connecting" }), snapshot({ socket: "open" }), NOW)[0]).toMatchObject({
      kind: "socket_connected",
      href: null,
    });
    expect(diffSnapshots(snapshot({ socket: "open" }), snapshot({ socket: "closed" }), NOW)[0]).toMatchObject({
      kind: "socket_disconnected",
    });
    // closed -> connecting -> error is the reconnect loop, not news.
    expect(diffSnapshots(snapshot({ socket: "closed" }), snapshot({ socket: "connecting" }), NOW)).toEqual([]);
    expect(diffSnapshots(snapshot({ socket: "connecting" }), snapshot({ socket: "error" }), NOW)).toEqual([]);
  });
});

describe("pushEvents", () => {
  it("prepends newest-first, de-duplicates by id and caps the buffer", () => {
    const mk = (id: string) => ({ id, kind: "trip_opened" as const, tone: "info" as const, at: NOW, text: id, href: null });
    const feed = pushEvents([mk("a")], [mk("b"), mk("c")], 50);
    expect(feed.map((e) => e.id)).toEqual(["c", "b", "a"]);

    expect(pushEvents(feed, [mk("b")], 50)).toBe(feed);

    const capped = pushEvents(feed, [mk("d"), mk("e")], 3);
    expect(capped.map((e) => e.id)).toEqual(["e", "d", "c"]);
  });
});
