import { describe, expect, it } from "vitest";
import { corridorCollection } from "./TollGantryMap";
import { officialGeometryFor } from "./officialTollGeometry";
import type { TollGantry } from "@/hooks/useTollRoads";

function gantry(id: string, toll_road_id: string, latitude: number, longitude: number): TollGantry {
  return { id, toll_road_id, location: id, ramp: null, direction: null, latitude, longitude };
}

describe("corridorCollection", () => {
  it("draws the real official path for a road the source covers, not a reconstruction", () => {
    // These two M2 "gantries" sit nowhere near the real M2 corridor -- if the map fell
    // back to reconstruction, the drawn line would run through exactly these two points.
    const gantries = [gantry("g1", "M2", -10, 10), gantry("g2", "M2", -11, 11)];
    const collection = corridorCollection(gantries);

    const official = officialGeometryFor("M2");
    expect(official).not.toBeNull();

    const drawnCoords = collection.features
      .filter((f) => f.properties.roadId === "M2")
      .map((f) => f.geometry.coordinates);
    const officialCoords = official!.map((segment) => segment.map((p) => [p.longitude, p.latitude]));
    expect(drawnCoords).toEqual(officialCoords);

    // And neither decoy gantry made it into what was drawn.
    const flat = drawnCoords.flat();
    expect(flat).not.toContainEqual([10, -10]);
    expect(flat).not.toContainEqual([11, -11]);
  });

  it("falls back to the reconstruction for a road the source does not cover", () => {
    expect(officialGeometryFor("M8")).toBeNull();
    const gantries = [gantry("g1", "M8", -33.8, 151.0), gantry("g2", "M8", -33.9, 151.1)];
    const collection = corridorCollection(gantries);

    const drawn = collection.features.filter((f) => f.properties.roadId === "M8");
    expect(drawn).toHaveLength(1);
    const coords = drawn[0].geometry.coordinates;
    // Both reconstructed gantries appear, as [lng, lat] -- there is no other source
    // for an uncovered road's line.
    expect(coords).toContainEqual([151.0, -33.8]);
    expect(coords).toContainEqual([151.1, -33.9]);
  });

  it("draws nothing for an uncovered road with only one gantry", () => {
    const collection = corridorCollection([gantry("g1", "M8", -33.8, 151.0)]);
    expect(collection.features.filter((f) => f.properties.roadId === "M8")).toHaveLength(0);
  });
});
