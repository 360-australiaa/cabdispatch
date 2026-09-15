import { useMemo } from "react";
import { Badge } from "@/components/ui";
import { formatDurationSeconds, formatTimeShort } from "@/lib/format";
import type { Trip } from "@/hooks/useTrips";
import { blackoutRows, describeReconciliationFlag, describeResolution, type BlackoutRow } from "../blackouts";

export interface BlackoutSectionProps {
  trip: Trip;
  /** `TollRoad.id` -> display name, for a device segment's `corridor_road_id`.
   * Optional: without it the raw id is shown, never dropped. */
  roadNameById?: Map<string, string>;
}

const EM_DASH = "—";

function km(value: string | null): string {
  if (value == null || value === "") return EM_DASH;
  const n = Number(value);
  return Number.isNaN(n) ? value : `${n.toFixed(2)} km`;
}

/** Signed, so a negative correction (the estimate overshot) reads as such. */
function signedKm(value: string | null): string {
  if (value == null || value === "") return EM_DASH;
  const n = Number(value);
  if (Number.isNaN(n)) return value;
  return `${n > 0 ? "+" : ""}${n.toFixed(2)} km`;
}

/** The server's billing outcome in the same words the Fare tab used before
 * this section existed, so a dispute reads the same as it always has. */
function serverBilledLabel(row: BlackoutRow): string {
  return row.billedKm != null
    ? `${Number(row.billedKm).toFixed(2)} km via known corridor`
    : "No known corridor — billed $0 for this gap";
}

/**
 * The trip's GPS-blackout audit trail (admin-panel plan §2): every gap the
 * meter and the server each recorded, side by side, with the figures a
 * disputed tunnel fare is checked against -- when, for how long, how the gap
 * was resolved, what was billed, and for a dead-reckoned (INERTIAL) segment
 * the estimate against its reference, the correction applied, the
 * confidence and the ZUPT count.
 *
 * Rendered on both the Fare tab (next to the money) and the Route tab (under
 * the map that draws these stretches dashed). Renders nothing at all for the
 * overwhelming majority of trips, which have no blackout on either account.
 */
export function BlackoutSection({ trip, roadNameById }: BlackoutSectionProps) {
  const rows = useMemo(() => blackoutRows(trip), [trip]);
  const flags = trip.blackout_reconciliation ?? [];
  if (rows.length === 0 && flags.length === 0) return null;

  const hasInertial = rows.some((r) => r.estimatedKm != null || r.zuptCount != null);

  return (
    <div className="rounded-lg border border-border p-3">
      <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">GPS blackouts</p>
      <p className="mb-2 text-xs text-muted-foreground">
        Two independent accounts of the same trip: the meter's own record and the server's replay of the
        trace. They are listed side by side and never merged — a disagreement is itself evidence.
      </p>

      {rows.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-xs" aria-label="GPS blackouts">
            <thead className="text-muted-foreground">
              <tr>
                <th className="py-1 pr-3 text-left font-medium">Source</th>
                <th className="py-1 pr-3 text-left font-medium">When</th>
                <th className="py-1 pr-3 text-left font-medium">Duration</th>
                <th className="py-1 pr-3 text-left font-medium">Resolution</th>
                <th className="py-1 pr-3 text-left font-medium">Billed</th>
                {hasInertial && (
                  <>
                    <th className="py-1 pr-3 text-left font-medium">Estimated / reference</th>
                    <th className="py-1 pr-3 text-left font-medium">Correction</th>
                    <th className="py-1 pr-3 text-left font-medium">Confidence</th>
                    <th className="py-1 pr-3 text-left font-medium">ZUPT</th>
                  </>
                )}
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.key} className="border-t border-border/60 align-top">
                  <td className="py-1.5 pr-3">
                    <Badge variant={row.source === "device" ? "accent" : "outline"}>
                      {row.source === "device" ? "Meter" : "Server"}
                    </Badge>
                  </td>
                  <td className="py-1.5 pr-3 whitespace-nowrap text-foreground">
                    {formatTimeShort(row.startedAt)} – {formatTimeShort(row.endedAt)}
                  </td>
                  <td className="py-1.5 pr-3 whitespace-nowrap text-muted-foreground">
                    {formatDurationSeconds(row.elapsedS)}
                  </td>
                  <td className="py-1.5 pr-3 text-foreground">
                    {describeResolution(row.resolution)}
                    {row.corridorRoadId && (
                      <span className="block text-muted-foreground">
                        {roadNameById?.get(row.corridorRoadId) ?? row.corridorRoadId}
                      </span>
                    )}
                    {row.entryWasMoving === false && (
                      <span className="block text-muted-foreground">Stationary at entry</span>
                    )}
                  </td>
                  <td className={row.billedKm != null ? "py-1.5 pr-3 font-medium text-foreground" : "py-1.5 pr-3 text-muted-foreground"}>
                    {row.source === "server" ? serverBilledLabel(row) : km(row.billedKm)}
                  </td>
                  {hasInertial && (
                    <>
                      <td className="py-1.5 pr-3 whitespace-nowrap text-foreground">
                        {row.estimatedKm != null || row.referenceKm != null
                          ? `${km(row.estimatedKm)} / ${km(row.referenceKm)}`
                          : EM_DASH}
                        {row.referenceSource && (
                          <span className="block text-muted-foreground">ref: {row.referenceSource}</span>
                        )}
                      </td>
                      <td className="py-1.5 pr-3 whitespace-nowrap text-foreground">{signedKm(row.correctionKm)}</td>
                      <td className="py-1.5 pr-3 text-foreground">{row.confidence ?? EM_DASH}</td>
                      <td className="py-1.5 pr-3 text-foreground">{row.zuptCount ?? EM_DASH}</td>
                    </>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {flags.length > 0 && (
        <div className="mt-3 rounded-md border border-destructive/60 bg-destructive/10 p-2 text-xs">
          <p className="font-semibold text-destructive">
            Reconciliation flag{flags.length === 1 ? "" : "s"} — audit only, the fare was not changed
          </p>
          <ul className="mt-1 list-disc pl-4 text-foreground">
            {flags.map((flag, i) => {
              const { type: _type, ...details } = flag;
              const detail = Object.entries(details)
                .map(([k, v]) => `${k}: ${typeof v === "object" ? JSON.stringify(v) : String(v)}`)
                .join(", ");
              return (
                <li key={`${flag.type}-${i}`}>
                  {describeReconciliationFlag(flag)}
                  {detail && <span className="text-muted-foreground"> ({detail})</span>}
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </div>
  );
}
