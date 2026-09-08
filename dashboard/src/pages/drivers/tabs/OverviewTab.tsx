import { useMemo, type ReactNode } from "react";
import { AlertTriangle } from "lucide-react";
import { Badge, Card, CardContent, CardHeader, CardTitle, Spinner } from "@/components/ui";
import { EntityLink } from "@/components/EntityLink";
import { useTripsQuery } from "@/hooks/useTrips";
import { useShiftsQuery } from "@/pages/shifts/api";
import { formatDurationMinutes, formatKm, formatMoney } from "@/lib/format";
import { isForbidden, useDriverFatigueAlerts, useDriverRatingsSummary } from "../api";

const FATIGUE_KIND_LABELS: Record<string, string> = {
  shift_duration_exceeded: "Shift duration exceeded",
  no_break_taken: "No break taken",
  speed_exceeded: "Speed exceeded",
};

function startOfTodayIso(): string {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  return d.toISOString();
}

function StatCard({ label, value, sub }: { label: string; value: ReactNode; sub?: ReactNode }) {
  return (
    <Card>
      <CardContent className="pt-4">
        <p className="text-xs text-muted-foreground">{label}</p>
        <p className="mt-1 text-2xl font-semibold text-foreground">{value}</p>
        {sub && <p className="mt-1 text-xs text-muted-foreground">{sub}</p>}
      </CardContent>
    </Card>
  );
}

/**
 * Driver page Overview tab (dashboard command-centre plan §4.1): today's
 * trips/fares/km/hours-on-shift, the driver's rating summary, and open
 * fatigue alerts. Every number is a real endpoint scoped to this driver via
 * a server-side `driver_id` filter -- `GET /v1/trips` and `GET /v1/shifts`
 * both filter server-side (confirmed against `backend/app/api/v1/trips.py`
 * and `shifts.py`), so nothing here is a client-side filter over an
 * unscoped fetch.
 */
export function OverviewTab({ driverId }: { driverId: string }) {
  const todayIso = useMemo(() => startOfTodayIso(), []);

  const tripsQuery = useTripsQuery({ driver_id: driverId, start_from: todayIso, limit: 200, skip: 0 });
  const shiftsQuery = useShiftsQuery({ driver_id: driverId, start_at_from: todayIso, limit: 50 });
  const ratingsSummaryQuery = useDriverRatingsSummary(driverId);
  const fatigueQuery = useDriverFatigueAlerts(driverId);

  const trips = tripsQuery.data?.items ?? [];
  const tripCount = trips.length;
  const closedTrips = trips.filter((t) => t.status === "closed");
  const faresToday = closedTrips.reduce((sum, t) => sum + Number(t.total || 0), 0);
  const kmToday = trips.reduce((sum, t) => sum + (t.distance_m ?? 0), 0);

  const shifts = shiftsQuery.data?.items ?? [];
  const minutesOnShift = shifts.reduce((sum, s) => {
    const start = new Date(s.start_at).getTime();
    const end = s.end_at ? new Date(s.end_at).getTime() : Date.now();
    if (Number.isNaN(start) || Number.isNaN(end) || end < start) return sum;
    return sum + (end - start) / 60000;
  }, 0);

  const ratingsUnavailable = isForbidden(ratingsSummaryQuery.error);
  const fatigueAlerts = fatigueQuery.data?.items ?? [];

  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <StatCard
          label="Trips today"
          value={tripsQuery.isLoading ? <Spinner size="sm" /> : tripCount}
          sub={`${closedTrips.length} closed`}
        />
        <StatCard
          label="Fares today"
          value={tripsQuery.isLoading ? <Spinner size="sm" /> : formatMoney(faresToday.toFixed(2))}
          sub="Closed trips only"
        />
        <StatCard
          label="Distance today"
          value={tripsQuery.isLoading ? <Spinner size="sm" /> : formatKm(String(kmToday / 1000))}
        />
        <StatCard
          label="Hours on shift today"
          value={shiftsQuery.isLoading ? <Spinner size="sm" /> : formatDurationMinutes(minutesOnShift)}
        />
      </div>

      {tripsQuery.isError && (
        <p className="text-xs text-destructive">Couldn&apos;t load today&apos;s trips (GET /v1/trips).</p>
      )}
      {shiftsQuery.isError && (
        <p className="text-xs text-destructive">Couldn&apos;t load today&apos;s shifts (GET /v1/shifts).</p>
      )}

      <Card>
        <CardHeader>
          <CardTitle>Rating summary</CardTitle>
        </CardHeader>
        <CardContent>
          {ratingsSummaryQuery.isLoading ? (
            <Spinner size="sm" />
          ) : ratingsUnavailable ? (
            <p className="text-sm text-muted-foreground">
              Not available for your role (GET /v1/ratings/summary is owner/admin only).
            </p>
          ) : ratingsSummaryQuery.isError ? (
            <p className="text-sm text-destructive">Failed to load rating summary.</p>
          ) : ratingsSummaryQuery.data && ratingsSummaryQuery.data.total > 0 ? (
            <div className="flex items-baseline gap-3">
              <span className="text-2xl font-semibold text-foreground">
                {ratingsSummaryQuery.data.average?.toFixed(2) ?? "—"}
              </span>
              <span className="text-sm text-muted-foreground">
                average over {ratingsSummaryQuery.data.total} rating
                {ratingsSummaryQuery.data.total === 1 ? "" : "s"}
              </span>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">No ratings yet.</p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Fatigue alerts</CardTitle>
        </CardHeader>
        <CardContent>
          {fatigueQuery.isLoading ? (
            <Spinner size="sm" />
          ) : fatigueQuery.isError ? (
            <p className="text-sm text-muted-foreground">
              Fatigue alerts unavailable right now (GET /v1/fatigue-alerts).
            </p>
          ) : fatigueAlerts.length === 0 ? (
            <p className="text-sm text-muted-foreground">No open fatigue alerts for this driver.</p>
          ) : (
            <ul className="flex flex-col gap-2">
              {fatigueAlerts.map((a) => (
                <li key={a.id} className="flex items-center gap-2 text-sm">
                  <AlertTriangle className="h-4 w-4 shrink-0 text-amber-500" aria-hidden="true" />
                  <Badge variant="outline">{FATIGUE_KIND_LABELS[a.kind] ?? a.kind}</Badge>
                  {a.shift_id && (
                    <>
                      <span className="text-muted-foreground">on</span>
                      <EntityLink kind="shift" id={a.shift_id} name={`shift ${a.shift_id.slice(0, 8)}`} />
                    </>
                  )}
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
