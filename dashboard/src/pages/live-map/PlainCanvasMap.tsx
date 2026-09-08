import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import type { MapDataProps } from "./mapTypes";
import { ROUTE_LINE_COLOR } from "./mapInit";
import { getHoverCardFields, HOVER_CARD_ROWS } from "./markers";
import { isStale, statusColor } from "./utils";

/**
 * The no-token fallback renderer, and the geometry constants only it uses.
 *
 * Extracted verbatim from FleetMapCanvas.tsx during the Phase 0 file split --
 * this is the plain-SVG plot that runs when VITE_MAPBOX_TOKEN is unset. It
 * shares nothing with the Mapbox renderer except the hover-card fields and the
 * route colour, both imported above so the two can never disagree.
 */

const WIDTH = 900;
const HEIGHT = 460;
const PADDING = 32;

// ---------------------------------------------------------------------------
// Plain-SVG fallback — no Mapbox token configured
// ---------------------------------------------------------------------------

// Same arrow silhouette as VEHICLE_ARROW_VIEWBOX_PATH above, re-expressed in
// this renderer's own local coordinate space (each vehicle's <g> is already
// translated to its (x, y), so this path is centered on its own origin
// instead of a 0-24 viewBox) -- keeps the two renderers' glyphs matching
// without forcing an extra nested <svg>/viewBox indirection in plain SVG.
const VEHICLE_ARROW_LOCAL_PATH = "M0,-8 L6,8 L0,4 L-6,8 Z";

/**
 * Plain-SVG lat/lng plot, used when no Mapbox token is configured. Vehicles
 * are projected into a local bounding box (not real map tiles), colored by
 * live status. Every vehicle pin is clickable: one with an open duress event
 * is drawn oversized in red and routes to `/duress?event=<id>` (the "red
 * pin" requirement — since a duress row itself has no lat/lng, its pin
 * position is its vehicle's last-known position); any other vehicle opens
 * the vehicle detail panel instead.
 */
export function PlainCanvasMap({ plotted, duressByVehicleId, geofences, routes, onSelectVehicle, selectedVehicleId }: MapDataProps) {
  const navigate = useNavigate();
  // No Mapbox Popup infra exists in this fallback -- track the hovered
  // vehicle id ourselves and render the same theme-aware hover card as a
  // plain absolutely-positioned div synced to its projected (x, y).
  const [hoveredId, setHoveredId] = useState<string | null>(null);

  const bounds = useMemo(() => {
    if (plotted.length === 0) return null;
    let minLat = Infinity;
    let maxLat = -Infinity;
    let minLng = Infinity;
    let maxLng = -Infinity;
    for (const v of plotted) {
      minLat = Math.min(minLat, v.lat);
      maxLat = Math.max(maxLat, v.lat);
      minLng = Math.min(minLng, v.lng);
      maxLng = Math.max(maxLng, v.lng);
    }
    // Pad degenerate ranges (single vehicle, or a fleet parked at one depot)
    // so markers don't collapse onto the viewport edge.
    const latSpan = maxLat - minLat || 0.01;
    const lngSpan = maxLng - minLng || 0.01;
    return {
      minLat: minLat - latSpan * 0.15,
      maxLat: maxLat + latSpan * 0.15,
      minLng: minLng - lngSpan * 0.15,
      maxLng: maxLng + lngSpan * 0.15,
    };
  }, [plotted]);

  function project(lat: number, lng: number): [number, number] {
    if (!bounds) return [WIDTH / 2, HEIGHT / 2];
    const x = PADDING + ((lng - bounds.minLng) / (bounds.maxLng - bounds.minLng)) * (WIDTH - PADDING * 2);
    const y = PADDING + ((bounds.maxLat - lat) / (bounds.maxLat - bounds.minLat)) * (HEIGHT - PADDING * 2);
    return [x, y];
  }

  // Approximate pixel radius for a geofence circle in this local projection --
  // converts meters to degrees latitude (111,320 m/deg, same constant as
  // TollZoneMapPicker.tsx's circlePolygon) then to pixels via this bounding
  // box's own vertical scale. Only the vertical (lat) scale is used, same
  // simplifying assumption as project() above (which doesn't lng-compress by
  // cos(lat) either) -- fine for "roughly where the boundary is" at this
  // fallback's non-tile-based zoom, not for a geodesically exact circle.
  function projectedRadiusPx(radiusM: number): number {
    if (!bounds) return 0;
    const metersPerDegLat = 111_320;
    const yScale = (HEIGHT - PADDING * 2) / (bounds.maxLat - bounds.minLat);
    return (radiusM / metersPerDegLat) * yScale;
  }

  if (plotted.length === 0) {
    return (
      <div className="flex h-[460px] flex-col items-center justify-center gap-1 rounded-md border border-dashed border-border text-center text-sm text-muted-foreground">
        <p>No live vehicle positions yet.</p>
        <p className="text-xs">Positions appear here once a device publishes via POST /v1/fleet/positions.</p>
      </div>
    );
  }

  const hoveredVehicle = hoveredId ? plotted.find((v) => v.id === hoveredId) : undefined;
  const hoveredDuress = hoveredVehicle ? duressByVehicleId.get(hoveredVehicle.id) : undefined;
  const hoveredPos = hoveredVehicle ? project(hoveredVehicle.lat, hoveredVehicle.lng) : null;
  const hoveredFields = hoveredVehicle ? getHoverCardFields(hoveredVehicle, hoveredDuress) : null;

  return (
    <div className="relative">
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        className="w-full rounded-md border border-border bg-muted/40"
        role="img"
        aria-label="Fleet live map"
      >
        {Array.from({ length: 6 }).map((_, i) => (
          <line
            key={`v-${i}`}
            x1={(WIDTH / 5) * i}
            y1={0}
            x2={(WIDTH / 5) * i}
            y2={HEIGHT}
            stroke="var(--border)"
            strokeWidth={1}
          />
        ))}
        {Array.from({ length: 4 }).map((_, i) => (
          <line
            key={`h-${i}`}
            x1={0}
            y1={(HEIGHT / 3) * i}
            x2={WIDTH}
            y2={(HEIGHT / 3) * i}
            stroke="var(--border)"
            strokeWidth={1}
          />
        ))}

        {/* Geofence-breach overlay circles, drawn beneath every vehicle so a
            dispatcher can see the boundary itself -- same brand-accent/gold
            treatment as the Mapbox renderer's fill+line layers above, and as
            TollZoneMapPicker.tsx's own zone-radius preview. */}
        {geofences.map((g) => {
          const [cx, cy] = project(g.center_lat, g.center_lng);
          const r = projectedRadiusPx(g.radius_m);
          if (r <= 0) return null;
          return (
            <circle
              key={g.id}
              cx={cx}
              cy={cy}
              r={r}
              fill="var(--brand-accent)"
              fillOpacity={0.12}
              stroke="var(--brand-accent)"
              strokeWidth={1.5}
              strokeDasharray="4 3"
            />
          );
        })}

        {/* On-trip route-to-destination lines -- drawn beneath the vehicle
            <g>s below (same "route under markers, never over them" ordering
            as the Mapbox renderer's GL layers vs. its DOM markers) and in a
            color (ROUTE_LINE_COLOR) that appears nowhere else on this map, so
            it never reads as the same signal as a geofence boundary or the
            duress ring. Dashed = still the straight-line stand-in (Directions
            fetch pending or failed); solid = a real routed geometry -- see
            useVehicleRoutes.ts's own VehicleRouteState.isFallback doc. */}
        {Array.from(routes.entries()).map(([vehicleId, state]) => (
          <polyline
            key={`route-${vehicleId}`}
            points={state.points.map(([lat, lng]) => project(lat, lng).join(",")).join(" ")}
            fill="none"
            stroke={ROUTE_LINE_COLOR}
            strokeWidth={2.5}
            strokeOpacity={state.isFallback ? 0.55 : 0.85}
            strokeDasharray={state.isFallback ? "5 3" : undefined}
          />
        ))}

        {plotted.map((v) => {
          const [x, y] = project(v.lat, v.lng);
          const duressEvent = duressByVehicleId.get(v.id);
          const stale = isStale(v.position_updated_at);
          const idle = !stale && v.idleInfo.idle;
          const inGeofence = v.insideGeofences.length > 0;
          const color = duressEvent ? "var(--destructive)" : statusColor(v.live_status);
          const scale = duressEvent ? 1.3 : 1;
          return (
            <g
              key={v.id}
              transform={`translate(${x}, ${y})`}
              className="cursor-pointer"
              opacity={stale ? 0.5 : 1}
              onClick={() => (duressEvent ? navigate(`/duress?event=${duressEvent.id}`) : onSelectVehicle(v.id))}
              onMouseEnter={() => setHoveredId(v.id)}
              onMouseLeave={() => setHoveredId((id) => (id === v.id ? null : id))}
            >
              {duressEvent && (
                <circle r={13} fill="none" stroke="var(--destructive)" strokeWidth={2} opacity={0.5}>
                  <animate attributeName="r" values="9;15;9" dur="1.6s" repeatCount="indefinite" />
                  <animate attributeName="opacity" values="0.6;0.1;0.6" dur="1.6s" repeatCount="indefinite" />
                </circle>
              )}
              {/* Static (non-animated) stale/idle outline -- same dashed-vs-
                  dotted, muted-vs-amber distinction as the Mapbox renderer's
                  renderMarkerContent, so the two never disagree visually. */}
              {(stale || idle) && (
                <circle
                  r={11}
                  fill="none"
                  stroke={stale ? "var(--muted-foreground)" : "var(--warning, #d97706)"}
                  strokeWidth={2}
                  strokeDasharray={stale ? "3 2" : "1 2"}
                />
              )}
              {v.heading != null ? (
                <path
                  d={VEHICLE_ARROW_LOCAL_PATH}
                  transform={`scale(${scale}) rotate(${v.heading})`}
                  fill={color}
                  stroke="var(--card)"
                  strokeWidth={1.5}
                  strokeLinejoin="round"
                />
              ) : (
                // heading == null -- vehicle stationary or never reported one.
                // Same neutral-dot fallback as the Mapbox marker (buildVehicleGlyph)
                // rather than guessing a direction.
                <circle r={duressEvent ? 8 : 6} fill={color} stroke="var(--card)" strokeWidth={2} />
              )}
              {inGeofence && (
                <circle cx={7} cy={-7} r={3} fill="var(--brand-accent)" stroke="var(--card)" strokeWidth={1} />
              )}
              <text y={-14} textAnchor="middle" fontSize={10} style={{ fill: "var(--foreground)" }}>
                {v.rego}
              </text>
            </g>
          );
        })}
      </svg>

      {hoveredVehicle && hoveredPos && hoveredFields && (
        <div
          className="pointer-events-none absolute z-10 rounded-lg border px-2.5 py-2 text-xs shadow-lg"
          style={{
            left: `${(hoveredPos[0] / WIDTH) * 100}%`,
            top: `${(hoveredPos[1] / HEIGHT) * 100}%`,
            transform: "translate(-50%, calc(-100% - 16px))",
            background: "var(--card)",
            color: "var(--card-foreground)",
            borderColor: "var(--border)",
            lineHeight: 1.6,
            minWidth: "150px",
          }}
        >
          <div className="mb-0.5 font-semibold">{hoveredFields.rego}</div>
          <div className="flex justify-between gap-3">
            <span style={{ color: "var(--muted-foreground)" }}>Status</span>
            <span style={hoveredFields.duressActive ? { color: "var(--destructive)" } : undefined}>
              {hoveredFields.duressActive ? "Duress active" : hoveredFields.statusLabel}
            </span>
          </div>
          {HOVER_CARD_ROWS.map(([label, key]) => (
            <div key={label} className="flex justify-between gap-3">
              <span style={{ color: "var(--muted-foreground)" }}>{label}</span>
              <span>{hoveredFields[key]}</span>
            </div>
          ))}
          {hoveredFields.staleLabel && (
            <div className="mt-1" style={{ color: "var(--muted-foreground)" }}>
              {hoveredFields.staleLabel}
            </div>
          )}
          {hoveredFields.idleLabel && (
            <div className="mt-1" style={{ color: "var(--warning, #d97706)" }}>
              {hoveredFields.idleLabel}
            </div>
          )}
          {hoveredFields.geofenceNames.length > 0 && (
            <div className="mt-1" style={{ color: "var(--brand-accent)" }}>
              Inside {hoveredFields.geofenceNames.join(", ")}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
