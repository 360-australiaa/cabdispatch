/**
 * Fallback for a Mapbox style that loads but draws nothing.
 *
 * Seen live (admin-panel plan §1.1): both the Live Map and the Overview tile
 * load the tenant's custom Studio style, the style JSON returns 200, and the
 * page then issues no tile requests at all -- only markers on a black canvas.
 * Whether the Studio style has lost its sources or its tile URLs are blocked,
 * the operator gets a map with no streets on it, which is worse than a stock
 * style. Until the style itself is fixed in Studio, every map that opens the
 * custom style calls `installStyleFallback` and swaps to Mapbox's own dark
 * style the moment the custom one turns out to be empty.
 *
 * The decision is made on the first `style.load`, which Mapbox fires before
 * the map's own `load`. Swapping synchronously in that handler means the
 * `load` handlers that install sources and layers (`installMapLayers`,
 * `TripRouteMap`'s own route source) run against the fallback style, not
 * against a style that is about to be replaced -- `setStyle` discards every
 * programmatically added source and layer, so the swap must happen before
 * any of them exist.
 *
 * Written against a minimal `StyleFallbackMapLike` rather than `mapboxgl.Map`
 * so the decision can be unit-tested with a plain object; the real map
 * satisfies the interface structurally.
 */

export const FALLBACK_MAP_STYLE_URL = "mapbox://styles/mapbox/dark-v11";

/** The parts of a loaded style JSON the decision looks at. */
export interface LoadedStyleLike {
  sources?: Record<string, unknown> | null;
  layers?: Array<{ layout?: { visibility?: unknown } | null } | null> | null;
}

/**
 * True when a loaded style cannot draw a basemap: no sources, no layers, or
 * every layer hidden. A missing style (the fetch failed) counts as empty too.
 */
export function styleDrawsNothing(style: LoadedStyleLike | null | undefined): boolean {
  if (!style) return true;
  if (Object.keys(style.sources ?? {}).length === 0) return true;
  const layers = style.layers ?? [];
  if (layers.length === 0) return true;
  return layers.every((layer) => layer?.layout?.visibility === "none");
}

export interface StyleFallbackMapLike {
  on(event: "style.load" | "error", handler: (event?: unknown) => void): unknown;
  getStyle(): LoadedStyleLike | null | undefined;
  setStyle(style: string): unknown;
}

export interface InstallStyleFallbackOptions {
  fallbackUrl?: string;
  /** Side-channel for the one-line warning; defaults to `console.warn`. */
  warn?: (message: string) => void;
}

/**
 * Registers the fallback on a freshly constructed map. Returns a function
 * reporting whether the fallback has been applied, mostly for tests.
 *
 * Fires at most once: the fallback style's own `style.load` must not trigger
 * a second swap, and an `error` event after a successful `style.load` (a tile
 * 404, a missing sprite) is not a reason to throw the whole style away.
 */
export function installStyleFallback(
  map: StyleFallbackMapLike,
  options: InstallStyleFallbackOptions = {},
): () => boolean {
  const fallbackUrl = options.fallbackUrl ?? FALLBACK_MAP_STYLE_URL;
  const warn = options.warn ?? ((message: string) => console.warn(message));
  let applied = false;
  let styleLoaded = false;

  const fallBack = (reason: string) => {
    if (applied) return;
    applied = true;
    warn(`Map style ${reason}; falling back to ${fallbackUrl}.`);
    map.setStyle(fallbackUrl);
  };

  map.on("style.load", () => {
    if (applied || styleLoaded) return;
    styleLoaded = true;
    let style: LoadedStyleLike | null | undefined;
    try {
      style = map.getStyle();
    } catch {
      style = null;
    }
    if (styleDrawsNothing(style)) fallBack("loaded with no drawable sources or layers");
  });

  // A style that never loads at all (404 on the Studio URL, a revoked token
  // scope) surfaces as an `error` before any `style.load`. Anything after the
  // style has loaded is a per-resource error and is left alone.
  map.on("error", () => {
    if (applied || styleLoaded) return;
    fallBack("failed to load");
  });

  return () => applied;
}
