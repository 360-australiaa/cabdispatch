/**
 * A single lat/lng plotted on a small, self-contained SVG canvas -- the
 * device page's Status tab (dashboard command-centre plan §6.1) "current
 * location on a small map" requirement.
 *
 * There is no existing single-point map component to reuse. `PlainCanvasMap`
 * (`pages/live-map/PlainCanvasMap.tsx`) is the closest thing in the app, but
 * it takes the whole fleet's `MapDataProps` (plotted vehicles, routes,
 * geofences, duress) -- restructuring it to also accept "just one point, no
 * vehicle" would be a bigger, riskier change than this page needs.
 * `TabletDetailSheet` (the other place a bare tablet's position is shown
 * today) doesn't draw a map at all, only lat/lng text. The one thing that
 * IS reused is the technique: this is `VehicleDetailModal.tsx`'s
 * `ReplayMiniMap` with its path/scrubber removed -- same local
 * lat/lng-to-bounding-box projection, same padding, same theme-token
 * styling -- collapsed to a single marker since a device has no position
 * history to draw a path from (that's the Heartbeats tab's job, once the
 * backend stores one).
 *
 * A real Mapbox tile underlay is not used here for the same reason
 * `ReplayMiniMap` gave: wiring one more read-only dot into the shared
 * `FleetMapCanvas`/Mapbox instance (owned by the Live Map page, not this
 * one) would mean restructuring a component several other workstreams
 * already touch, for a payoff this self-contained view already delivers.
 */

const WIDTH = 320;
const HEIGHT = 200;
const PADDING = 24;

export interface DeviceLocationMapProps {
  lat: number;
  lng: number;
}

export function DeviceLocationMap({ lat, lng }: DeviceLocationMapProps) {
  return (
    <svg
      viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
      className="w-full rounded-md border border-border bg-muted/40"
      role="img"
      aria-label={`Last known device position: ${lat.toFixed(4)}, ${lng.toFixed(4)}`}
    >
      {/* Faint gridlines only -- there is no real basemap under this, and no
          bounding box to compute from a single point, so this deliberately
          does not pretend to be a geographic projection the way
          ReplayMiniMap's multi-point bounds-fit can. It is "here is a dot,
          roughly centered", nothing more. */}
      {Array.from({ length: 5 }).map((_, i) => (
        <line
          key={`v-${i}`}
          x1={(WIDTH / 4) * i}
          y1={0}
          x2={(WIDTH / 4) * i}
          y2={HEIGHT}
          stroke="var(--border)"
          strokeWidth={1}
        />
      ))}
      {Array.from({ length: 3 }).map((_, i) => (
        <line
          key={`h-${i}`}
          x1={0}
          y1={(HEIGHT / 2) * i}
          x2={WIDTH}
          y2={(HEIGHT / 2) * i}
          stroke="var(--border)"
          strokeWidth={1}
        />
      ))}
      <circle
        cx={WIDTH / 2}
        cy={HEIGHT / 2}
        r={9}
        fill="none"
        stroke="var(--brand-accent)"
        strokeWidth={2}
        opacity={0.5}
      />
      <circle cx={WIDTH / 2} cy={HEIGHT / 2} r={5} fill="var(--brand-accent)" stroke="var(--card)" strokeWidth={1.5} />
      <text
        x={WIDTH / 2}
        y={HEIGHT / 2 + PADDING}
        textAnchor="middle"
        fontSize={10}
        style={{ fill: "var(--muted-foreground)" }}
      >
        {lat.toFixed(4)}, {lng.toFixed(4)}
      </text>
    </svg>
  );
}
