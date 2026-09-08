import { describe, expect, it } from "vitest";
import { isPlaceholderStatus, mergeLivePosition } from "./utils";
import type { VehicleLiveRead } from "./types";
import type { LivePosition } from "@/hooks/useLiveMap";

const vehicle = {
  id: "v1",
  rego: "T22123",
  live_status: "on_trip",
  lat: -33.86,
  lng: 151.2,
  battery: 80,
  network: "wifi",
  speed_kmh: 12,
  heading: 90,
  position_updated_at: "2026-09-08T10:00:00Z",
} as unknown as VehicleLiveRead;

function frame(status: string): Record<string, LivePosition> {
  return {
    v1: {
      vehicle_id: "v1",
      lat: -33.87,
      lng: 151.21,
      status,
      battery: null,
      network: null,
      speed_kmh: 30,
      heading: 180,
      updated_at: "2026-09-08T10:00:05Z",
    },
  };
}

describe("isPlaceholderStatus", () => {
  it("treats the tablet heartbeat placeholder and blanks as no information", () => {
    expect(isPlaceholderStatus("unknown")).toBe(true);
    expect(isPlaceholderStatus(" Unknown ")).toBe(true);
    expect(isPlaceholderStatus("")).toBe(true);
    expect(isPlaceholderStatus(null)).toBe(true);
    expect(isPlaceholderStatus(undefined)).toBe(true);
  });
  it("keeps real statuses", () => {
    expect(isPlaceholderStatus("available")).toBe(false);
    expect(isPlaceholderStatus("on_trip")).toBe(false);
  });
});

describe("mergeLivePosition", () => {
  it("keeps the server-composed live_status when the socket frame carries the placeholder", () => {
    const merged = mergeLivePosition(vehicle, frame("unknown"));
    expect(merged.live_status).toBe("on_trip");
    // position still moves with the frame
    expect(merged.lat).toBe(-33.87);
    expect(merged.lng).toBe(151.21);
  });
  it("takes a real socket status over the REST snapshot", () => {
    expect(mergeLivePosition(vehicle, frame("break")).live_status).toBe("break");
  });
  it("returns the vehicle untouched when no frame has arrived for it", () => {
    expect(mergeLivePosition(vehicle, {})).toBe(vehicle);
  });
});
