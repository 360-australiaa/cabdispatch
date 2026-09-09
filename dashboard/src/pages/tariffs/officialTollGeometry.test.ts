import { describe, expect, it } from "vitest";
import { decodePolyline, officialGeometryFor, officialGeometryRoadIds } from "./officialTollGeometry";

/** Asserts each decoded [lat, lng] pair is numerically close to the expected pair --
 * decodePolyline's arithmetic can land a float a fraction of a unit off the exact
 * value (e.g. 151.21120000000002), which a plain `toEqual` would fail on. */
function expectClose(actual: [number, number][], expected: [number, number][]) {
  expect(actual).toHaveLength(expected.length);
  actual.forEach(([lat, lng], i) => {
    expect(lat).toBeCloseTo(expected[i][0], 5);
    expect(lng).toBeCloseTo(expected[i][1], 5);
  });
}

describe("decodePolyline", () => {
  it("decodes the confirmed sample from the NSW toll calculator source (410 -> 415, M2)", () => {
    // Directly confirmed by hand-decode this session: |n`mEuw|x[yQ`E -> these two points.
    expectClose(decodePolyline("|n`mEuw|x[yQ`E"), [
      [-33.75871, 151.04907],
      [-33.7557, 151.0481],
    ]);
  });

  it("decodes a three-point segment with a repeated leading point (A70 -> A60, Bradfield Hwy)", () => {
    expectClose(decodePolyline("tytmEwf{y[??`EtB"), [
      [-33.86283, 151.20508],
      [-33.86283, 151.20508],
      [-33.8638, 151.20449],
    ]);
  });

  it("decodes a third, unrelated segment (B40 -> B50, Bradfield Hwy)", () => {
    expectClose(decodePolyline("f`qmE_m|y[?AiDdA"), [
      [-33.8434, 151.2112],
      [-33.8434, 151.21121],
      [-33.84255, 151.21086],
    ]);
  });

  it("returns an empty array for an empty string", () => {
    expect(decodePolyline("")).toEqual([]);
  });
});

describe("officialGeometryFor / officialGeometryRoadIds", () => {
  it("has real geometry for the roads the source actually covers", () => {
    const ids = officialGeometryRoadIds();
    for (const roadId of ["M2", "M7", "M4", "CCT", "ED", "LCT", "M5SW", "M5E", "NORTHCONNEX", "M4M8_LINK"]) {
      expect(ids).toContain(roadId);
    }
  });

  it("returns real, non-empty segments for a covered road", () => {
    const segments = officialGeometryFor("M2");
    expect(segments).not.toBeNull();
    expect(segments!.length).toBeGreaterThan(0);
    for (const segment of segments!) {
      expect(segment.length).toBeGreaterThan(1);
      for (const point of segment) {
        expect(typeof point.latitude).toBe("number");
        expect(typeof point.longitude).toBe("number");
      }
    }
  });

  it("returns null for a road the source does not cover", () => {
    // M8 has only a single point in the source (no adjacency to decode a line from);
    // M12 / Rozelle Interchange / Iron Cove Link have no rows in the source at all.
    for (const roadId of ["M8", "M12", "ROZELLE_INTERCHANGE", "IRON_COVE_LINK", "SHB_SHT", "not-a-real-road"]) {
      expect(officialGeometryFor(roadId)).toBeNull();
    }
  });
});
