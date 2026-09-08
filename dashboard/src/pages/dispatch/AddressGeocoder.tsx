import { useEffect, useId, useRef, useState } from "react";
import { Input } from "@/components/ui";

// Same public/publishable Mapbox token as Live Map and the tariffs map
// pickers (see `pages/tariffs/TollZoneMapPicker.tsx`) — read from config,
// never hardcoded. Forward geocoding degrades honestly when it's unset: the
// search box says search is unavailable rather than silently returning zero
// results as though the address just didn't match anything.
const MAPBOX_TOKEN = import.meta.env.VITE_MAPBOX_TOKEN;

const DEBOUNCE_MS = 300;

export interface GeocoderResult {
  id: string;
  /** Human-readable place name, e.g. "123 George St, Sydney NSW 2000". */
  address: string;
  lat: number;
  lng: number;
}

interface MapboxGeocodeFeature {
  id: string;
  place_name: string;
  center: [number, number];
}

interface MapboxGeocodeResponse {
  features: MapboxGeocodeFeature[];
}

/** Mapbox's public forward-geocoding endpoint — same product surface as the
 * mapbox-gl token, a different API (plain HTTPS, no SDK), so it isn't part
 * of the `mapbox-gl`/`mapbox-gl.css` bundle the map pickers pull in. Biased
 * loosely toward Australia (`country=au`) since every seeded tenant today is
 * NSW; not a hard filter, so it still finds anything else. */
async function geocode(query: string, signal: AbortSignal): Promise<GeocoderResult[]> {
  const url = new URL(
    `https://api.mapbox.com/geocoding/v5/mapbox.places/${encodeURIComponent(query)}.json`,
  );
  url.searchParams.set("access_token", MAPBOX_TOKEN as string);
  url.searchParams.set("country", "au");
  url.searchParams.set("limit", "5");

  const res = await fetch(url.toString(), { signal });
  if (!res.ok) {
    throw new Error(`Mapbox geocoding request failed (${res.status})`);
  }
  const body = (await res.json()) as MapboxGeocodeResponse;
  return body.features.map((f) => ({
    id: f.id,
    address: f.place_name,
    lat: f.center[1],
    lng: f.center[0],
  }));
}

/**
 * Address search-as-you-type for pickup/drop-off. Wraps Mapbox's forward
 * geocoding endpoint directly (fetch, not the mapbox-gl SDK) so it works
 * independently of whether the map picker below it has mounted.
 *
 * No-token behaviour is deliberate: the input stays enabled but every
 * search attempt shows "Address search is unavailable" instead of quietly
 * returning no matches, which would read as "this address doesn't exist"
 * rather than "this feature isn't configured". The dispatcher still has the
 * map picker's plain lat/lng fallback and can always type the address text
 * by hand — this component never blocks job creation.
 */
export function AddressGeocoder({
  label,
  placeholder,
  value,
  onSelect,
}: {
  label: string;
  placeholder?: string;
  value: string;
  onSelect: (result: GeocoderResult) => void;
}) {
  const [query, setQuery] = useState(value);
  const [syncedValue, setSyncedValue] = useState(value);
  const [results, setResults] = useState<GeocoderResult[]>([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const inputId = useId();

  // Keep the field in sync when the parent resets/changes the address (e.g.
  // after a map click or a modal reset) without fighting local typing.
  // Adjusted during render (React's documented pattern for deriving state
  // from a prop change) rather than in an effect, which would cost an extra
  // render and trip `react-hooks/set-state-in-effect`.
  if (value !== syncedValue) {
    setSyncedValue(value);
    setQuery(value);
  }

  useEffect(() => {
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
      abortRef.current?.abort();
    };
  }, []);

  function handleChange(next: string) {
    setQuery(next);
    setError(null);

    if (debounceRef.current) clearTimeout(debounceRef.current);
    abortRef.current?.abort();

    if (!next.trim()) {
      setResults([]);
      setOpen(false);
      return;
    }

    if (!MAPBOX_TOKEN) {
      setError("Address search is unavailable — no map token is configured. Enter the address manually.");
      setOpen(true);
      setResults([]);
      return;
    }

    debounceRef.current = setTimeout(() => {
      const controller = new AbortController();
      abortRef.current = controller;
      setLoading(true);
      setOpen(true);
      geocode(next, controller.signal)
        .then((found) => {
          setResults(found);
          setLoading(false);
        })
        .catch((err: unknown) => {
          if (err instanceof DOMException && err.name === "AbortError") return;
          setError("Address search failed. Enter the address and coordinates manually.");
          setResults([]);
          setLoading(false);
        });
    }, DEBOUNCE_MS);
  }

  function handleSelect(result: GeocoderResult) {
    setQuery(result.address);
    setOpen(false);
    setResults([]);
    onSelect(result);
  }

  return (
    <div className="relative flex flex-col gap-1.5">
      <label htmlFor={inputId} className="text-sm font-medium">
        {label}
      </label>
      <Input
        id={inputId}
        value={query}
        onChange={(e) => handleChange(e.target.value)}
        onFocus={() => results.length > 0 && setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        placeholder={placeholder}
        autoComplete="off"
        required
      />
      {open && (
        <div className="absolute top-full z-10 mt-1 w-full rounded-md border border-border bg-card shadow-md">
          {loading && <p className="px-3 py-2 text-sm text-muted-foreground">Searching…</p>}
          {!loading && error && <p className="px-3 py-2 text-sm text-destructive">{error}</p>}
          {!loading && !error && results.length === 0 && query.trim() && (
            <p className="px-3 py-2 text-sm text-muted-foreground">No matches.</p>
          )}
          {!loading &&
            results.map((r) => (
              <button
                key={r.id}
                type="button"
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => handleSelect(r)}
                className="block w-full px-3 py-2 text-left text-sm hover:bg-accent"
              >
                {r.address}
              </button>
            ))}
        </div>
      )}
    </div>
  );
}
