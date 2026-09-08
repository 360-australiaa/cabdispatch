/* One section of the platform console. Split out of `pages/platform/index.tsx`
 * by D5, which was 774 lines carrying five independent sections plus the page
 * itself (audit sec 6 lists it among the files over 500 lines). Each section
 * owns its own queries, so they move out cleanly; `index.tsx` keeps only the
 * page shell, the tenants table and the create-tenant form.
 */

import { AlertTriangle } from "lucide-react";
import { Badge } from "@/components/ui";
import { useTenantBilling } from "@/hooks/usePlatformConsole";
import { tenantStatusBadgeVariant } from "./format";

/** This tenant's subscriptions — the platform-owner's support-triage view,
 * so staff can review a network's billing without impersonating them via
 * the ?tenant_id= override every other endpoint supports. */
export function TenantBillingSection({ tenantId }: { tenantId: string | null }) {
  const billingQuery = useTenantBilling(tenantId);
  const subscriptions = billingQuery.data ?? [];

  return (
    <div className="mt-4">
      <p className="mb-2 text-xs font-medium uppercase text-muted-foreground">Billing</p>
      {billingQuery.isError && (
        <p className="flex items-center gap-2 text-sm text-destructive">
          <AlertTriangle className="h-4 w-4 shrink-0" />
          Failed to load this tenant's billing. Check the backend connection and try again.
        </p>
      )}
      {!billingQuery.isError && billingQuery.isLoading && (
        <p className="text-sm text-muted-foreground">Loading...</p>
      )}
      {!billingQuery.isError && !billingQuery.isLoading && subscriptions.length === 0 && (
        <p className="text-sm text-muted-foreground">No subscriptions for this tenant.</p>
      )}
      {subscriptions.length > 0 && (
        <div className="flex flex-col gap-2">
          {subscriptions.map((sub) => (
            <div
              key={sub.id}
              className="flex items-center justify-between rounded-md border border-border p-3 text-sm"
            >
              <span className="font-medium text-foreground">Vehicle {sub.vehicle_id}</span>
              <div className="flex items-center gap-2">
                <Badge variant="outline">{sub.plan}</Badge>
                <Badge variant={tenantStatusBadgeVariant(sub.status)}>{sub.status}</Badge>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
