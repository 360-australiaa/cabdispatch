/* One section of the platform console. Split out of `pages/platform/index.tsx`
 * by D5, which was 774 lines carrying five independent sections plus the page
 * itself (audit sec 6 lists it among the files over 500 lines). Each section
 * owns its own queries, so they move out cleanly; `index.tsx` keeps only the
 * page shell, the tenants table and the create-tenant form.
 */

import { AlertTriangle, Building2, Car, Route } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui";
import { usePlatformHealth } from "@/hooks/usePlatformConsole";

export function HealthSummary() {
  const healthQuery = usePlatformHealth();
  const health = healthQuery.data;

  const tiles = [
    { label: "Total tenants", value: health?.total_tenants, icon: Building2 },
    { label: "Total vehicles", value: health?.total_vehicles, icon: Car },
    { label: "Trips today", value: health?.total_trips_today, icon: Route },
  ];

  return (
    <Card className="mb-6">
      <CardHeader>
        <CardTitle>Platform health</CardTitle>
      </CardHeader>
      <CardContent>
        {healthQuery.isError ? (
          <p className="flex items-center gap-2 text-sm text-destructive">
            <AlertTriangle className="h-4 w-4 shrink-0" />
            Failed to load platform health. Check the backend connection and try again.
          </p>
        ) : (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            {tiles.map(({ label, value, icon: Icon }) => (
              <div
                key={label}
                className="flex items-center gap-3 rounded-md border border-border p-4"
              >
                <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-brand-lavender text-brand-lavender-foreground">
                  <Icon className="h-4 w-4" />
                </div>
                <div>
                  <p className="text-xs text-muted-foreground">{label}</p>
                  <p className="text-lg font-semibold text-foreground">
                    {healthQuery.isLoading ? "..." : (value ?? "-")}
                  </p>
                </div>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
