import { describe, expect, it } from "vitest";
import {
  buildCameraFeatureCollection,
  buildCameraPopupHtml,
  buildHazardFeatureCollection,
  buildHazardPopupHtml,
} from "./trafficLayers";
import type { TrafficCamera, TrafficHazard } from "./trafficTypes";

/**
 * `FleetMapCanvas`'s live-traffic layers draw via Mapbox GL, which needs a
 * real WebGL context jsdom cannot provide -- the same reason neither
 * `FleetMapCanvas` nor `TollGantryMap` have a direct render test (see
 * `TollGantryMap.test.ts`'s own `corridorCollection` tests). These cover the
 * two pure functions that stand in for "did the right markers render" and
 * "did the right popup show on click": `buildXFeatureCollection` is exactly
 * the GeoJSON the circle/symbol layers draw from, and `buildXPopupHtml` is
 * exactly the HTML the click handler hands to `mapboxgl.Popup#setHTML`.
 */

function camera(overrides: Partial<TrafficCamera> = {}): TrafficCamera {
  return {
    id: "cam-1",
    name: "M4 Motorway at Church St",
    latitude: -33.815,
    longitude: 150.995,
    direction: "Westbound",
    image_url: "https://api.transport.nsw.gov.au/cameras/cam-1.jpg",
    region: "Sydney",
    ...overrides,
  };
}

function hazard(overrides: Partial<TrafficHazard> = {}): TrafficHazard {
  return {
    id: "hz-1",
    category: "roadwork",
    latitude: -33.87,
    longitude: 151.2,
    headline: "Lane closure on Parramatta Rd",
    closure_type: "Single lane closed",
    direction: "Eastbound",
    speed_limit: 40,
    expected_delay_minutes: 15,
    ended: false,
    ...overrides,
  };
}

describe("buildCameraFeatureCollection", () => {
  it("plots one Point feature per camera, at [lng, lat], carrying its display fields", () => {
    const collection = buildCameraFeatureCollection([camera(), camera({ id: "cam-2", direction: null })]);

    expect(collection.features).toHaveLength(2);
    expect(collection.features[0].geometry).toEqual({ type: "Point", coordinates: [150.995, -33.815] });
    expect(collection.features[0].properties).toEqual({
      id: "cam-1",
      name: "M4 Motorway at Church St",
      direction: "Westbound",
      region: "Sydney",
      image_url: "https://api.transport.nsw.gov.au/cameras/cam-1.jpg",
    });
    // A null direction never reaches the layer as `null` -- Mapbox's
    // text-field expressions choke on that -- it becomes an empty string.
    expect(collection.features[1].properties.direction).toBe("");
  });

  it("draws nothing for an empty camera list", () => {
    expect(buildCameraFeatureCollection([])).toEqual({ type: "FeatureCollection", features: [] });
  });
});

describe("buildHazardFeatureCollection", () => {
  it("plots one Point feature per hazard, resolving its glyph/colour from its category", () => {
    const collection = buildHazardFeatureCollection([hazard(), hazard({ id: "hz-2", category: "fire" })]);

    expect(collection.features).toHaveLength(2);
    expect(collection.features[0].geometry).toEqual({ type: "Point", coordinates: [151.2, -33.87] });
    expect(collection.features[0].properties).toMatchObject({
      id: "hz-1",
      category: "roadwork",
      glyph: "🚧",
      color: "#f59e0b",
      ended: false,
    });
    expect(collection.features[1].properties).toMatchObject({ category: "fire", glyph: "🔥", color: "#f97316" });
  });

  it("carries `ended` through untouched, for the fainter-not-hidden treatment", () => {
    const collection = buildHazardFeatureCollection([hazard({ ended: true })]);
    expect(collection.features[0].properties.ended).toBe(true);
  });
});

describe("buildCameraPopupHtml", () => {
  it("shows the camera's title, direction and region, plus a cache-busted image src", () => {
    const html = buildCameraPopupHtml(camera(), 1_700_000_000_000);

    expect(html).toContain("M4 Motorway at Church St");
    expect(html).toContain("Westbound");
    expect(html).toContain("Sydney");
    expect(html).toContain(
      'src="https://api.transport.nsw.gov.au/cameras/cam-1.jpg?_t=1700000000000"',
    );
  });

  it("re-keys with a new refreshKey without duplicating query params", () => {
    const html = buildCameraPopupHtml(camera({ image_url: "https://cam.example/x.jpg?token=abc" }), 42);
    expect(html).toContain('src="https://cam.example/x.jpg?token=abc&amp;_t=42"');
  });

  it("omits the direction line entirely when the feed gave none", () => {
    const html = buildCameraPopupHtml(camera({ direction: null }), 1);
    expect(html).not.toContain("traffic-popup-meta");
  });

  it("escapes an untrusted title so it cannot inject markup into the popup", () => {
    const html = buildCameraPopupHtml(camera({ name: "<script>alert(1)</script>" }), 1);
    expect(html).not.toContain("<script>");
    expect(html).toContain("&lt;script&gt;");
  });
});

describe("buildHazardPopupHtml", () => {
  it("shows the headline plus every field the feed supplied", () => {
    const html = buildHazardPopupHtml(hazard());

    expect(html).toContain("Lane closure on Parramatta Rd");
    expect(html).toContain("Single lane closed");
    expect(html).toContain("Eastbound");
    expect(html).toContain("Speed limit 40 km/h");
    expect(html).toContain("Delay ~15 min");
    expect(html).not.toContain("Cleared");
  });

  it("omits every optional line the feed left null, rather than showing 'null'", () => {
    const html = buildHazardPopupHtml(
      hazard({ closure_type: null, direction: null, speed_limit: null, expected_delay_minutes: null }),
    );

    expect(html).toContain("Lane closure on Parramatta Rd");
    expect(html).not.toContain("null");
    expect(html).not.toContain("Speed limit");
    expect(html).not.toContain("Delay ~");
  });

  it("marks a cleared hazard as Cleared", () => {
    const html = buildHazardPopupHtml(hazard({ ended: true }));
    expect(html).toContain("Cleared");
  });

  it("falls back to a category label as the title when the feed's own headline is null", () => {
    // `headline` is genuinely nullable on the real feed (see
    // TrafficHazard's own doc) -- the popup must still name what it is.
    const html = buildHazardPopupHtml(hazard({ headline: null, category: "flood" }));
    expect(html).toContain("Flood");
    expect(html).not.toContain("null");
  });
});
