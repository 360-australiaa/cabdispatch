import { describe, expect, it } from "vitest";
import type { DeviceGpsBlackoutSegment, GpsBlackoutEvent, TripGpsTracePoint } from "@/hooks/useTrips";
import {
  blackoutRows,
  blackoutStretches,
  describeReconciliationFlag,
  describeResolution,
} from "./blackouts";

const deviceSegment: DeviceGpsBlackoutSegment = {
  client_uuid: "seg-1",
  started_at: "2026-09-14T08:00:00.000Z",
  ended_at: "2026-09-14T08:02:30.000Z",
  entry_lat: -33.87,
  entry_lng: 151.2,
  exit_lat: -33.88,
  exit_lng: 151.21,
  entry_was_moving: true,
  resolution: "INERTIAL",
  billed_distance_km: "1.42",
  corridor_road_id: null,
  estimated_distance_km: "1.50",
  reference_distance_km: "1.42",
  correction_km: "-0.08",
  reference_source: "road_path",
  confidence: "high",
  zupt_count: 2,
};

const serverEvent: GpsBlackoutEvent = {
  start: "2026-09-14T08:00:00.000Z",
  end: "2026-09-14T08:02:30.000Z",
  elapsed_s: 150,
  matched_km: null,
};

describe("blackoutRows", () => {
  it("flattens the device and server accounts into labelled rows, device first", () => {
    const rows = blackoutRows({
      gps_blackout_events: [serverEvent],
      device_gps_blackout_segments: [deviceSegment],
    });

    expect(rows.map((r) => r.source)).toEqual(["device", "server"]);
    const [device, server] = rows;
    expect(device.elapsedS).toBe(150);
    expect(device.billedKm).toBe("1.42");
    expect(device.estimatedKm).toBe("1.50");
    expect(device.correctionKm).toBe("-0.08");
    expect(device.confidence).toBe("high");
    expect(device.zuptCount).toBe(2);
    expect(device.entry).toEqual([151.2, -33.87]);
    expect(device.exit).toEqual([151.21, -33.88]);
    expect(server.billedKm).toBeNull();
    expect(server.estimatedKm).toBeNull();
    expect(server.entry).toBeNull();
  });

  it("leaves the W2 inertial fields null for a segment from a pre-W2 meter build", () => {
    const [row] = blackoutRows({
      gps_blackout_events: [],
      device_gps_blackout_segments: [
        {
          client_uuid: "seg-2",
          started_at: "2026-09-14T08:00:00.000Z",
          ended_at: "2026-09-14T08:01:00.000Z",
          entry_lat: -33.87,
          entry_lng: 151.2,
          exit_lat: -33.88,
          exit_lng: 151.21,
          entry_was_moving: false,
          resolution: "STATIONARY",
          billed_distance_km: "0",
        },
      ],
    });
    expect(row.estimatedKm).toBeNull();
    expect(row.zuptCount).toBeNull();
    expect(row.billedKm).toBe("0");
  });

  it("is empty when the trip carries neither account", () => {
    expect(blackoutRows({ gps_blackout_events: null, device_gps_blackout_segments: undefined })).toEqual([]);
  });

  it("sorts each account by start time", () => {
    const rows = blackoutRows({
      gps_blackout_events: [
        { ...serverEvent, start: "2026-09-14T09:00:00.000Z", end: "2026-09-14T09:01:00.000Z" },
        serverEvent,
      ],
      device_gps_blackout_segments: [],
    });
    expect(rows.map((r) => r.startedAt)).toEqual(["2026-09-14T08:00:00.000Z", "2026-09-14T09:00:00.000Z"]);
  });
});

describe("blackoutStretches", () => {
  const trace: TripGpsTracePoint[] = [
    { lat: -33.86, lng: 151.19, speed_kmh: 40, ts: "2026-09-14T07:59:00.000Z" },
    { lat: -33.87, lng: 151.2, speed_kmh: 45, ts: "2026-09-14T08:00:00.000Z" },
    { lat: -33.88, lng: 151.21, speed_kmh: 50, ts: "2026-09-14T08:02:30.000Z" },
  ];

  it("uses a device segment's own entry and exit fix", () => {
    const [stretch] = blackoutStretches({ gps_blackout_events: [], device_gps_blackout_segments: [deviceSegment] }, null);
    expect(stretch.source).toBe("device");
    expect(stretch.from).toEqual([151.2, -33.87]);
    expect(stretch.to).toEqual([151.21, -33.88]);
    expect(stretch.label).toContain("Meter");
    expect(stretch.label).toContain("150s");
  });

  it("locates a server event against the recorded trace by timestamp", () => {
    const [stretch] = blackoutStretches({ gps_blackout_events: [serverEvent], device_gps_blackout_segments: [] }, trace);
    expect(stretch.source).toBe("server");
    expect(stretch.from).toEqual([151.2, -33.87]);
    expect(stretch.to).toEqual([151.21, -33.88]);
  });

  it("does not draw a server event with no trace to place it on", () => {
    expect(blackoutStretches({ gps_blackout_events: [serverEvent], device_gps_blackout_segments: [] }, [])).toEqual([]);
    expect(blackoutStretches({ gps_blackout_events: [serverEvent], device_gps_blackout_segments: [] }, null)).toEqual([]);
  });

  it("does not guess when the trace has no point near the event boundary", () => {
    const farTrace: TripGpsTracePoint[] = [
      { lat: -33.86, lng: 151.19, speed_kmh: 40, ts: "2026-09-14T07:00:00.000Z" },
      { lat: -33.88, lng: 151.21, speed_kmh: 50, ts: "2026-09-14T09:00:00.000Z" },
    ];
    expect(blackoutStretches({ gps_blackout_events: [serverEvent], device_gps_blackout_segments: [] }, farTrace)).toEqual([]);
  });

  it("skips a device segment with an unusable coordinate rather than drawing a partial line", () => {
    const broken = { ...deviceSegment, exit_lat: Number.NaN };
    expect(blackoutStretches({ gps_blackout_events: [], device_gps_blackout_segments: [broken] }, null)).toEqual([]);
  });
});

describe("describeResolution", () => {
  it.each([
    ["CORRIDOR", "Known corridor — real distance billed"],
    ["INERTIAL", "Inertial dead-reckoning"],
    ["NONE", "No known road — nothing extra billed"],
    [null, "Not labelled"],
    ["SOMETHING_NEW", "SOMETHING_NEW"],
  ])("labels %s", (value, expected) => {
    expect(describeResolution(value)).toBe(expected);
  });
});

describe("describeReconciliationFlag", () => {
  it("labels a known flag and echoes an unknown one", () => {
    expect(describeReconciliationFlag({ type: "corridor_distance_mismatch" })).toContain("corridor");
    expect(describeReconciliationFlag({ type: "brand_new_check" })).toBe("brand_new_check");
  });
});
