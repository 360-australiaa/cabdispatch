import { describe, expect, it } from "vitest";
import { orderAlongRoad } from "./tollCorridor";

// Five points on a north-south road, listed in a scrambled registry order.
const road = [
  { id: "c", latitude: -33.80, longitude: 150.85 },
  { id: "a", latitude: -33.70, longitude: 150.85 },
  { id: "e", latitude: -33.90, longitude: 150.85 },
  { id: "b", latitude: -33.75, longitude: 150.85 },
  { id: "d", latitude: -33.85, longitude: 150.85 },
];

describe("orderAlongRoad", () => {
  it("walks the road end to end regardless of registry order", () => {
    const ids = orderAlongRoad(road).map((p) => p.id).join("");
    expect(["abcde", "edcba"]).toContain(ids);
  });

  it("keeps every point exactly once", () => {
    const out = orderAlongRoad(road);
    expect(out).toHaveLength(road.length);
    expect(new Set(out.map((p) => p.id)).size).toBe(road.length);
  });

  it("follows a bend rather than cutting a chord across it", () => {
    // An L-shaped road: south along one meridian, then east.
    const bend = [
      { id: "3", latitude: -33.90, longitude: 150.90 },
      { id: "1", latitude: -33.70, longitude: 150.80 },
      { id: "4", latitude: -33.90, longitude: 151.00 },
      { id: "2", latitude: -33.80, longitude: 150.80 },
      { id: "0", latitude: -33.60, longitude: 150.80 },
    ];
    const ids = orderAlongRoad(bend).map((p) => p.id).join("");
    expect(["01234", "43210"]).toContain(ids);
  });

  it("returns short inputs untouched", () => {
    expect(orderAlongRoad([])).toEqual([]);
    expect(orderAlongRoad(road.slice(0, 2))).toEqual(road.slice(0, 2));
  });

  it("does not mutate its input", () => {
    const copy = road.map((p) => ({ ...p }));
    orderAlongRoad(road);
    expect(road).toEqual(copy);
  });
});
