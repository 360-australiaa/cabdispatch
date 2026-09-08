/* One section of the platform console. Split out of `pages/platform/index.tsx`
 * by D5, which was 774 lines carrying five independent sections plus the page
 * itself (audit sec 6 lists it among the files over 500 lines). Each section
 * owns its own queries, so they move out cleanly; `index.tsx` keeps only the
 * page shell, the tenants table and the create-tenant form.
 */

import { AlertTriangle, Car, Route, Siren, Users } from "lucide-react";
import { Modal } from "@/components/ui";
import { useTenantSummary } from "@/hooks/usePlatformConsole";
import { TenantBillingSection } from "./TenantBillingSection";

/** Read-only health rollup for one tenant, opened from a tenants-table row click. */
export function TenantDetailModal({
  tenantId,
  tenantName,
  onClose,
}: {
  tenantId: string | null;
  tenantName: string | undefined;
  onClose: () => void;
}) {
  const summaryQuery = useTenantSummary(tenantId);
  const summary = summaryQuery.data;

  const tiles = [
    { label: "Vehicles", value: summary?.vehicle_count, icon: Car },
    { label: "Drivers", value: summary?.driver_count, icon: Users },
    { label: "Trips (last 30 days)", value: summary?.trip_count_last_30_days, icon: Route },
    { label: "Active duress events", value: summary?.active_duress_count, icon: Siren },
  ];

  return (
    <Modal
      open={tenantId != null}
      onClose={onClose}
      title={tenantName ?? "Tenant"}
      description="Health rollup for this tenant."
    >
      {summaryQuery.isError && (
        <p className="flex items-center gap-2 text-sm text-destructive">
          <AlertTriangle className="h-4 w-4 shrink-0" />
          Failed to load this tenant's summary. Check the backend connection and try again.
        </p>
      )}
      {!summaryQuery.isError && (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
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
                  {summaryQuery.isLoading ? "..." : (value ?? "-")}
                </p>
              </div>
            </div>
          ))}
        </div>
      )}

      <TenantBillingSection tenantId={tenantId} />
    </Modal>
  );
}
