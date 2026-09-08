import { Button } from "@/components/ui";
import { cn } from "@/lib/utils";
import type { TrailPoint } from "./FleetMapCanvas";

const WINDOWS: { hours: number | null; label: string }[] = [
  { hours: null, label: "Off" },
  { hours: 1, label: "1h" },
  { hours: 4, label: "4h" },
  { hours: 12, label: "12h" },
  { hours: 72, label: "72h" },
];

interface TrailControlsProps {
  hours: number | null;
  onHoursChange: (hours: number | null) => void;
  trail: TrailPoint[];
  cursor: number | null;
  onCursorChange: (cursor: number | null) => void;
  loading: boolean;
  harshBrakes: number;
  rapidAccels: number;
}

/**
 * Where this vehicle has been, and a scrubber to step back through it.
 *
 * The 72-hour history has always been served by the backend; it was only ever
 * rendered as a static SVG inside a modal, which could not be compared against
 * anything else on the map. Drawing it as a real line layer answers the question
 * an operator actually has — "where was this car at 2am?" — which a live pin
 * never can.
 *
 * Off by default. A trail for every selection would bury the live fleet under
 * lines; asking for one is a deliberate act.
 */
export function TrailControls({
  hours,
  onHoursChange,
  trail,
  cursor,
  onCursorChange,
  loading,
  harshBrakes,
  rapidAccels,
}: TrailControlsProps) {
  const at = cursor != null ? trail[cursor] : undefined;

  return (
    <div className="mt-3 rounded-md border border-border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-xs font-medium text-muted-foreground">Trail</span>
        {WINDOWS.map((w) => (
          <button
            key={w.label}
            type="button"
            onClick={() => onHoursChange(w.hours)}
            className={cn(
              "rounded-full border px-2.5 py-1 text-xs transition-colors",
              hours === w.hours
                ? "border-transparent bg-primary text-primary-foreground"
                : "border-border text-muted-foreground hover:text-foreground",
            )}
          >
            {w.label}
          </button>
        ))}
        {hours != null && (
          <span className="ml-1 text-xs text-muted-foreground">
            {loading ? "Loading…" : `${trail.length} point${trail.length === 1 ? "" : "s"}`}
          </span>
        )}
      </div>

      {hours != null && trail.length > 1 && (
        <>
          <div className="mt-3 flex items-center gap-3">
            <input
              type="range"
              min={0}
              max={trail.length - 1}
              value={cursor ?? trail.length - 1}
              onChange={(e) => onCursorChange(Number(e.target.value))}
              className="h-1.5 flex-1 cursor-pointer appearance-none rounded-full bg-muted accent-[var(--brand-accent)]"
              aria-label="Scrub through this vehicle's recorded positions"
            />
            <Button variant="outline" size="sm" onClick={() => onCursorChange(null)} disabled={cursor == null}>
              Live
            </Button>
          </div>
          <p className="mt-2 text-xs text-muted-foreground">
            {at
              ? `${new Date(at.recordedAt).toLocaleString()} · ${
                  at.speedKmh == null ? "speed unknown" : `${Math.round(at.speedKmh)} km/h`
                }`
              : "Showing the whole window. Drag to step back through the drive."}
          </p>
        </>
      )}

      {hours != null && (harshBrakes > 0 || rapidAccels > 0) && (
        <p className="mt-2 text-xs text-muted-foreground">
          {/* Worded exactly as the endpoint's own schema insists: these are counts
              of speed changes over a threshold, not a driver score, and must not
              be presented as one. */}
          {harshBrakes} heavy braking and {rapidAccels} rapid acceleration events in this window —
          informational only, not a certified safety measure.
        </p>
      )}

      {hours != null && !loading && trail.length === 0 && (
        <p className="mt-2 text-xs text-muted-foreground">
          No recorded positions in this window. History is kept for about 72 hours.
        </p>
      )}

      {/* One point is a real state, not an empty one, and it used to render as a
          bare "1 points" with no line, no scrubber and no explanation. A line
          needs two ends; say so rather than leaving the operator wondering which
          control they failed to find. */}
      {hours != null && !loading && trail.length === 1 && (
        <p className="mt-2 text-xs text-muted-foreground">
          Only one recorded position in this window, so there is no route to draw yet.
        </p>
      )}
    </div>
  );
}
