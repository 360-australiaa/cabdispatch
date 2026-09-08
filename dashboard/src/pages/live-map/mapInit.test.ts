import { describe, expect, it } from "vitest";
import {
  SINGLE_POINT_ZOOM,
  WORLD_VIEW,
  cameraForPoints,
  parseTenantCamera,
  resolveInitialCamera,
} from "./mapInit";

/**
 * These cover the three-step fallback that replaced the hardcoded default
 * centre. The important properties are that a malformed tenant value never
 * reaches the map (it falls through instead of producing NaN), that the
 * ordering is tenant → fleet → world, and that the last resort is a whole-world
 * view rather than any particular place on earth.
 */

const sydney: [number, number] = [151.2093, -33.8688];

describe("parseTenantCamera", () => {
  it("reads a well-formed [lng, lat] centre", () => {
    expect(parseTenantCamera({ default_center: sydney })).toEqual({
      center: sydney,
      zoom: SINGLE_POINT_ZOOM - 2,
    });
  });

  it("honours an explicit default_zoom", () => {
    expect(parseTenantCamera({ default_center: sydney, default_zoom: 9 })?.zoom).toBe(9);
  });

  it.each([
    ["null theme", null],
    ["undefined theme", undefined],
    ["theme without a centre", { logo_url: null }],
    ["centre of the wrong length", { default_center: [1, 2, 3] }],
    ["non-numeric centre", { default_center: ["151.2", "-33.8"] }],
    ["NaN centre", { default_center: [Number.NaN, 0] }],
    ["out-of-range longitude", { default_center: [200, 0] }],
    ["out-of-range latitude", { default_center: [0, 100] }],
    ["a centre that is not an array", { default_center: { lng: 1, lat: 2 } }],
  ])("returns null for %s", (_label, theme) => {
    expect(parseTenantCamera(theme)).toBeNull();
  });

  it("ignores an out-of-range zoom rather than the whole centre", () => {
    const camera = parseTenantCamera({ default_center: sydney, default_zoom: 99 });
    expect(camera?.center).toEqual(sydney);
    expect(camera?.zoom).toBe(SINGLE_POINT_ZOOM - 2);
  });
});

describe("cameraForPoints", () => {
  it("returns null when the fleet has never reported a position", () => {
    expect(cameraForPoints([])).toBeNull();
  });

  it("frames a single point directly", () => {
    expect(cameraForPoints([{ lat: -33.8688, lng: 151.2093 }])).toEqual({
      center: [151.2093, -33.8688],
      zoom: SINGLE_POINT_ZOOM,
    });
  });

  it("centres on the bounding box of several points", () => {
    const camera = cameraForPoints([
      { lat: 0, lng: 0 },
      { lat: 2, lng: 4 },
    ]);
    expect(camera?.center).toEqual([2, 1]);
  });

  it("zooms out for a widely spread fleet and in for a tight one", () => {
    const spread = cameraForPoints([
      { lat: -40, lng: 100 },
      { lat: 40, lng: 180 },
    ]);
    const tight = cameraForPoints([
      { lat: 0, lng: 0 },
      { lat: 0.01, lng: 0.01 },
    ]);
    expect(spread!.zoom).toBeLessThan(tight!.zoom);
    expect(spread!.zoom).toBeGreaterThanOrEqual(WORLD_VIEW.zoom);
    expect(tight!.zoom).toBeLessThanOrEqual(14);
  });

  it("treats a fleet parked at one depot as a single point", () => {
    expect(
      cameraForPoints([
        { lat: 10, lng: 20 },
        { lat: 10.000001, lng: 20.000001 },
      ])?.zoom,
    ).toBe(SINGLE_POINT_ZOOM);
  });
});

describe("resolveInitialCamera", () => {
  const points = [{ lat: 10, lng: 20 }];

  it("prefers the tenant's configured centre over the fleet's own positions", () => {
    const { camera, source } = resolveInitialCamera({ default_center: sydney }, points);
    expect(source).toBe("tenant");
    expect(camera.center).toEqual(sydney);
  });

  it("falls back to the fleet's last-known bounding box", () => {
    const { camera, source } = resolveInitialCamera({ logo_url: null }, points);
    expect(source).toBe("fleet");
    expect(camera.center).toEqual([20, 10]);
  });

  it("falls back to a world view when there is nothing at all", () => {
    const { camera, source } = resolveInitialCamera(null, []);
    expect(source).toBe("world");
    expect(camera).toEqual(WORLD_VIEW);
  });

  it("falls through a malformed tenant centre to the fleet's own positions", () => {
    const { source } = resolveInitialCamera({ default_center: [999, 999] }, points);
    expect(source).toBe("fleet");
  });

  it("hardcodes no city: the last resort is the null island world view", () => {
    expect(resolveInitialCamera(null, []).camera.center).toEqual([0, 0]);
  });
});
