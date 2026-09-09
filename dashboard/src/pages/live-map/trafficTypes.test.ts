import { describe, expect, it } from "vitest";
import { bboxFromLngLatBounds, bboxParam, hazardColor, hazardGlyph } from "./trafficTypes";

describe("bboxParam", () => {
  it("serializes minLng,minLat,maxLng,maxLat in that exact order", () => {
    expect(bboxParam({ minLng: 150.9, minLat: -34, maxLng: 151.3, maxLat: -33.7 })).toBe(
      "150.9,-34,151.3,-33.7",
    );
  });
});

describe("bboxFromLngLatBounds", () => {
  it("reads west/south/east/north into minLng/minLat/maxLng/maxLat", () => {
    const bounds = {
      getWest: () => 150.9,
      getSouth: () => -34,
      getEast: () => 151.3,
      getNorth: () => -33.7,
    };
    expect(bboxFromLngLatBounds(bounds)).toEqual({
      minLng: 150.9,
      minLat: -34,
      maxLng: 151.3,
      maxLat: -33.7,
    });
  });
});

describe("hazardGlyph / hazardColor", () => {
  it.each([
    ["incident", "⚠", "#ef4444"],
    ["roadwork", "🚧", "#f59e0b"],
    ["flood", "🌊", "#0ea5e9"],
    ["fire", "🔥", "#f97316"],
  ])("maps %s to its own glyph and colour", (category, glyph, color) => {
    expect(hazardGlyph(category)).toBe(glyph);
    expect(hazardColor(category)).toBe(color);
  });

  it("falls back to a plain dot and neutral grey for a category nobody has named yet", () => {
    expect(hazardGlyph("majorevent")).toBe("•");
    expect(hazardColor("majorevent")).toBe("#94a3b8");
  });

  it("gives every known category a distinct glyph and colour from each other", () => {
    const categories = ["incident", "roadwork", "flood", "fire"];
    const glyphs = categories.map(hazardGlyph);
    const colors = categories.map(hazardColor);
    expect(new Set(glyphs).size).toBe(categories.length);
    expect(new Set(colors).size).toBe(categories.length);
  });
});
