import { Car, CircleDot, Clock3, Hand, PhoneCall, Users } from "lucide-react";
import { Card, CardContent } from "@/components/ui";
import { useZoneStatsQuery } from "@/hooks/useZones";

/** Live per-zone supply/demand grid -- mirrors the "Statistics" screen a
 * driver already sees on their own meter/app (plotted, vacant, busy, jobs
 * holding, bookings + street hails in the last hour), just viewed from the
 * dispatcher's side across every zone at once. Auto-refetches every 20s via
 * `useZoneStatsQuery` (GET /v1/zones/stats), same live-polling pattern as
 * `useOpenFatigueAlerts`. */
export function ZoneStatsPanel() {
  const statsQuery = useZoneStatsQuery();
  const stats = statsQuery.data ?? [];

  return (
    <div>
      <div className="mb-3 flex items-center justify-between">
        <p className="text-sm text-muted-foreground">
          Live supply and demand per zone. Refreshes automatically every 20 seconds.
        </p>
        {statsQuery.isFetching && !statsQuery.isLoading && (
          <span className="text-xs text-muted-foreground">Refreshing...</span>
        )}
      </div>

      {statsQuery.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load zone stats. Check the backend connection and try again.
        </p>
      )}

      {statsQuery.isLoading ? (
        <p className="py-6 text-center text-sm text-muted-foreground">Loading...</p>
      ) : stats.length === 0 ? (
        <p className="py-6 text-center text-sm text-muted-foreground">
          No zones configured yet -- add a zone in the Zones tab to see live stats here.
        </p>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {stats.map((row) => (
            <Card key={row.zone_id}>
              <CardContent className="pt-4">
                <div className="mb-3 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="flex h-7 w-7 items-center justify-center rounded-md bg-brand-lavender font-mono text-sm font-bold text-brand-primary">
                      {row.zone_number}
                    </span>
                    <span className="font-medium text-foreground">{row.zone_name}</span>
                  </div>
                </div>

                <div className="grid grid-cols-3 gap-2 text-center">
                  <StatCell icon={Users} label="Plotted" value={row.plotted_vehicles} />
                  <StatCell icon={CircleDot} label="Vacant" value={row.vacant_vehicles} tone="success" />
                  <StatCell icon={Car} label="Busy" value={row.busy_vehicles} tone="destructive" />
                  <StatCell icon={Clock3} label="Jobs holding" value={row.jobs_holding} />
                  <StatCell icon={PhoneCall} label="Bookings/hr" value={row.bookings_last_hour} />
                  <StatCell icon={Hand} label="Street hails/hr" value={row.street_hails_last_hour} />
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}

function StatCell({
  icon: Icon,
  label,
  value,
  tone,
}: {
  icon: typeof Users;
  label: string;
  value: number;
  tone?: "success" | "destructive";
}) {
  return (
    <div className="flex flex-col items-center gap-1 rounded-md bg-muted px-2 py-2.5">
      <Icon
        className={
          "h-4 w-4 " +
          (tone === "success"
            ? "text-success"
            : tone === "destructive"
              ? "text-destructive"
              : "text-muted-foreground")
        }
      />
      <span className="text-lg font-semibold text-foreground">{value}</span>
      <span className="text-[11px] leading-tight text-muted-foreground">{label}</span>
    </div>
  );
}
