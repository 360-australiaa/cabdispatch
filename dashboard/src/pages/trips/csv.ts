/**
 * CSV export for the Trips ledger.
 *
 * Exports exactly the rows currently on screen (`filteredTrips` in
 * `index.tsx` -- server-side status/type/vehicle/driver/flagged filters plus
 * the client-side date-range and text search already applied), never a
 * separate unfiltered fetch. `index.tsx`'s own `FETCH_LIMIT` note applies
 * here too: the underlying `GET /v1/trips` call is capped at 200 rows with
 * no server-side date range (see that file's comment) -- adding pagination
 * or a date param there is D12's job, not this workstream's. So when the
 * fetch was itself capped, the export says so in its header rather than
 * quietly presenting a partial ledger as the whole one.
 */
import type { Trip } from "@/hooks/useTrips";
import { formatMoney } from "@/lib/format";

function csvEscape(value: unknown): string {
  if (value === null || value === undefined) return "";
  const s = String(value);
  if (/[",\n\r]/.test(s)) return `"${s.replace(/"/g, '""')}"`;
  return s;
}

const CSV_COLUMNS = [
  "id",
  "start_at",
  "end_at",
  "vehicle",
  "driver",
  "type",
  "status",
  "simulated",
  "payment_method",
  "total_aud",
  "fare_check_passed",
  "flagged_for_review",
  "receipt_ref",
];

export interface TripsCsvMeta {
  exportedAt: string;
  filters: Record<string, string>;
  fetchCapped: boolean;
  fetchLimit: number;
  rowsFetched: number;
  totalMatching: number;
}

export function buildTripsCsv(
  trips: Trip[],
  vehicleLabelById: Map<string, string>,
  driverLabelById: Map<string, string>,
  meta: TripsCsvMeta,
): string {
  const lines: string[] = [];
  lines.push(`# Cab Dispatch trips export`);
  lines.push(`# Exported: ${meta.exportedAt}`);
  for (const [key, value] of Object.entries(meta.filters)) {
    lines.push(`# ${key}: ${value || "(all)"}`);
  }
  lines.push(`# Rows in this file: ${trips.length}`);
  if (meta.fetchCapped) {
    lines.push(
      `# WARNING: the underlying fetch was capped at ${meta.fetchLimit} of ${meta.totalMatching} matching trips (see trips/index.tsx FETCH_LIMIT). This file does not contain the whole matching set -- narrow the filters and export again to see the rest.`,
    );
  }
  // Money here is rendered through the same `formatMoney` (en-AU, pinned
  // locale) the screen uses, not a raw decimal string, so a figure never
  // has two spellings between the screen and the file that came off it. A
  // trip whose total the API did not return (open trips have none) stays
  // blank, never "$0.00" -- see `formatMoney`'s honest-null rule.
  lines.push(CSV_COLUMNS.join(","));
  for (const t of trips) {
    lines.push(
      [
        t.id,
        t.start_at,
        t.end_at ?? "",
        vehicleLabelById.get(t.vehicle_id) ?? t.vehicle_id,
        driverLabelById.get(t.driver_id) ?? t.driver_id,
        t.type,
        t.status,
        t.simulated ? "yes" : "no",
        t.payment_method,
        t.status === "closed" ? formatMoney(t.total) : "",
        t.status === "closed" ? (t.max_fare_check_passed ? "yes" : "no") : "",
        t.flagged_for_review ? "yes" : "no",
        t.receipt_ref ?? "",
      ]
        .map(csvEscape)
        .join(","),
    );
  }
  return lines.join("\r\n") + "\r\n";
}

/** Same throwaway-object-URL download pattern as
 * `hooks/useReports.ts`'s `downloadNswPtpExport`, but built entirely
 * client-side since there is no server export endpoint for this domain. */
export function downloadTripsCsv(
  trips: Trip[],
  vehicleLabelById: Map<string, string>,
  driverLabelById: Map<string, string>,
  meta: TripsCsvMeta,
): void {
  const csv = buildTripsCsv(trips, vehicleLabelById, driverLabelById, meta);
  const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `trips-export_${new Date().toISOString().slice(0, 10)}.csv`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
