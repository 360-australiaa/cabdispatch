import { useMemo } from "react";
import { useNavigate } from "react-router-dom";
import type { DuressEventRead, VehicleLiveRead } from "./types";
import { statusColor } from "./utils";

interface FleetMapCanvasProps {
  vehicles: VehicleLiveRead[];
  duressEvents: DuressEventRead[];
}

const WIDTH = 900;
const HEIGHT = 460;
const PADDING = 32;

/**
 * Plain-SVG lat/lng plot — no paid maps SDK available offline. Vehicles are
 * projected into a local bounding box (not real map tiles), colored by live
 * status, and any vehicle with an open duress event is drawn oversized in
 * red with a click target that routes to `/duress?event=<id>` (the "red pin"
 * requirement — since a duress row itself has no lat/lng, its pin position
 * is its vehicle's last-known position).
 */
export function FleetMapCanvas({ vehicles, duressEvents }: FleetMapCanvasProps) {
  const navigate = useNavigate();

  const plotted = useMemo(
    () => vehicles.filter((v): v is VehicleLiveRead & { lat: number; lng: number } => v.lat != null && v.lng != null),
    [vehicles],
  );

  const duressByVehicleId = useMemo(() => {
    const map = new Map<string, DuressEventRead>();
    for (const event of duressEvents) {
      if (event.status !== "resolved" && event.status !== "cancelled") {
        map.set(event.vehicle_id, event);
      }
    }
    return map;
  }, [duressEvents]);

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

  if (plotted.length === 0) {
    return (
      <div className="flex h-[460px] flex-col items-center justify-center gap-1 rounded-md border border-dashed border-border text-center text-sm text-muted-foreground">
        <p>No live vehicle positions yet.</p>
        <p className="text-xs">Positions appear here once a device publishes via POST /v1/fleet/positions.</p>
      </div>
    );
  }

  return (
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

      {plotted.map((v) => {
        const [x, y] = project(v.lat, v.lng);
        const duressEvent = duressByVehicleId.get(v.id);
        const color = duressEvent ? "var(--destructive)" : statusColor(v.live_status);
        return (
          <g
            key={v.id}
            transform={`translate(${x}, ${y})`}
            className={duressEvent ? "cursor-pointer" : undefined}
            onClick={duressEvent ? () => navigate(`/duress?event=${duressEvent.id}`) : undefined}
          >
            {duressEvent && (
              <circle r={13} fill="none" stroke="var(--destructive)" strokeWidth={2} opacity={0.5}>
                <animate attributeName="r" values="9;15;9" dur="1.6s" repeatCount="indefinite" />
                <animate attributeName="opacity" values="0.6;0.1;0.6" dur="1.6s" repeatCount="indefinite" />
              </circle>
            )}
            <circle r={duressEvent ? 8 : 6} fill={color} stroke="var(--card)" strokeWidth={2} />
            <text y={-14} textAnchor="middle" fontSize={10} style={{ fill: "var(--foreground)" }}>
              {v.rego}
            </text>
          </g>
        );
      })}
    </svg>
  );
}
