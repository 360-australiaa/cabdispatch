import { describe, expect, it } from "vitest";
import { describePositionSource, isPlaceholderStatus, isStaleDuress, mergeLivePosition } from "./utils";
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

describe("describePositionSource", () => {
  const now = Date.parse("2026-09-15T10:00:00Z");
  const fresh = "2026-09-15T09:59:58Z";
  const old = "2026-09-15T09:58:00Z";

  it("calls a fresh live fix Live", () => {
    const badge = describePositionSource("live", fresh, now);
    expect(badge.kind).toBe("live");
    expect(badge.label).toBe("Live");
    expect(badge.variant).toBe("success");
  });

  it("treats a trip-tick fix as live", () => {
    expect(describePositionSource("trip", fresh, now).kind).toBe("live");
  });

  it("calls a real fix older than the threshold Stale", () => {
    const badge = describePositionSource("live", old, now);
    expect(badge.kind).toBe("stale");
    expect(badge.variant).toBe("destructive");
  });

  it("labels a dead-reckoned position Estimated, and never Stale", () => {
    expect(describePositionSource("estimated", fresh, now).kind).toBe("estimated");
    expect(describePositionSource("estimated", old, now).kind).toBe("estimated");
    expect(describePositionSource("estimated", old, now).variant).toBe("accent");
  });

  it("reports No fix for a vehicle that never reported, whatever the source says", () => {
    expect(describePositionSource("none", fresh, now).kind).toBe("none");
    expect(describePositionSource("live", null, now).kind).toBe("none");
    expect(describePositionSource("none", null, now).label).toBe("No fix");
  });
});

describe("isStaleDuress", () => {
  const now = Date.parse("2026-09-15T10:00:00Z");
  const thirteenHoursAgo = "2026-09-14T21:00:00Z";
  const oneHourAgo = "2026-09-15T09:00:00Z";

  it("is stale after 12 hours unresolved", () => {
    expect(isStaleDuress({ status: "dispatched", opened_at: thirteenHoursAgo }, now)).toBe(true);
    expect(isStaleDuress({ status: "open", opened_at: oneHourAgo }, now)).toBe(false);
  });

  it("trusts the server's own stale flag", () => {
    expect(isStaleDuress({ status: "open", opened_at: oneHourAgo, stale: true }, now)).toBe(true);
  });

  it("is never stale once resolved or cancelled", () => {
    expect(isStaleDuress({ status: "resolved", opened_at: thirteenHoursAgo, stale: true }, now)).toBe(false);
    expect(isStaleDuress({ status: "cancelled", opened_at: thirteenHoursAgo }, now)).toBe(false);
  });
});
