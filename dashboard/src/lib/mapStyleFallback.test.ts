import { describe, expect, it, vi } from "vitest";
import {
  FALLBACK_MAP_STYLE_URL,
  installStyleFallback,
  styleDrawsNothing,
  type LoadedStyleLike,
  type StyleFallbackMapLike,
} from "./mapStyleFallback";

/**
 * The decision the live map, the Overview tile and the trip route map all
 * share: a custom style that loads with nothing to draw is swapped for the
 * stock dark style exactly once, and a healthy style is left alone.
 */

const healthy: LoadedStyleLike = {
  sources: { composite: { type: "vector" } },
  layers: [{ layout: { visibility: "visible" } }, { layout: null }],
};

describe("styleDrawsNothing", () => {
  it("accepts a style with sources and a visible layer", () => {
    expect(styleDrawsNothing(healthy)).toBe(false);
  });

  it.each([
    ["no style at all", null],
    ["undefined style", undefined],
    ["no sources key", { layers: [{}] }],
    ["an empty sources map", { sources: {}, layers: [{}] }],
    ["sources but no layers", { sources: { a: {} }, layers: [] }],
    ["sources but every layer hidden", { sources: { a: {} }, layers: [{ layout: { visibility: "none" } }] }],
  ])("treats %s as drawing nothing", (_label, style) => {
    expect(styleDrawsNothing(style as LoadedStyleLike | null | undefined)).toBe(true);
  });
});

/** A map that records its handlers so a test can fire events by hand. */
function fakeMap(style: LoadedStyleLike | null | (() => LoadedStyleLike | null)) {
  const handlers: Record<string, Array<(event?: unknown) => void>> = {};
  const setStyle = vi.fn();
  const map: StyleFallbackMapLike = {
    on: (event, handler) => {
      (handlers[event] ??= []).push(handler);
    },
    getStyle: () => (typeof style === "function" ? style() : style),
    setStyle,
  };
  const fire = (event: "style.load" | "error") => {
    for (const handler of handlers[event] ?? []) handler({});
  };
  return { map, setStyle, fire };
}

describe("installStyleFallback", () => {
  it("leaves a healthy style alone", () => {
    const { map, setStyle, fire } = fakeMap(healthy);
    const warn = vi.fn();
    const applied = installStyleFallback(map, { warn });

    fire("style.load");

    expect(setStyle).not.toHaveBeenCalled();
    expect(warn).not.toHaveBeenCalled();
    expect(applied()).toBe(false);
  });

  it("swaps to the stock dark style when the custom style has no sources, and warns", () => {
    const { map, setStyle, fire } = fakeMap({ sources: {}, layers: [] });
    const warn = vi.fn();
    const applied = installStyleFallback(map, { warn });

    fire("style.load");

    expect(setStyle).toHaveBeenCalledWith(FALLBACK_MAP_STYLE_URL);
    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn.mock.calls[0][0]).toContain(FALLBACK_MAP_STYLE_URL);
    expect(applied()).toBe(true);
  });

  it("swaps only once: the fallback's own style.load does not trigger a second swap", () => {
    const { map, setStyle, fire } = fakeMap({ sources: {} });
    installStyleFallback(map, { warn: () => {} });

    fire("style.load");
    fire("style.load");
    fire("error");

    expect(setStyle).toHaveBeenCalledTimes(1);
  });

  it("falls back when the style errors before it ever loads", () => {
    const { map, setStyle, fire } = fakeMap(null);
    installStyleFallback(map, { warn: () => {} });

    fire("error");

    expect(setStyle).toHaveBeenCalledWith(FALLBACK_MAP_STYLE_URL);
  });

  it("ignores a per-resource error after a healthy style has loaded", () => {
    const { map, setStyle, fire } = fakeMap(healthy);
    installStyleFallback(map, { warn: () => {} });

    fire("style.load");
    fire("error");

    expect(setStyle).not.toHaveBeenCalled();
  });

  it("treats a getStyle() that throws as an empty style", () => {
    const { map, setStyle, fire } = fakeMap(() => {
      throw new Error("style not ready");
    });
    installStyleFallback(map, { warn: () => {} });

    fire("style.load");

    expect(setStyle).toHaveBeenCalledWith(FALLBACK_MAP_STYLE_URL);
  });

  it("honours a custom fallback URL", () => {
    const { map, setStyle, fire } = fakeMap({ sources: {} });
    installStyleFallback(map, { fallbackUrl: "mapbox://styles/mapbox/light-v11", warn: () => {} });

    fire("style.load");

    expect(setStyle).toHaveBeenCalledWith("mapbox://styles/mapbox/light-v11");
  });
});
