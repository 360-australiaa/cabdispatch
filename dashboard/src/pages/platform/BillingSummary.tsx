/* One section of the platform console. Split out of `pages/platform/index.tsx`
 * by D5, which was 774 lines carrying five independent sections plus the page
 * itself (audit sec 6 lists it among the files over 500 lines). Each section
 * owns its own queries, so they move out cleanly; `index.tsx` keeps only the
 * page shell, the tenants table and the create-tenant form.
 */

import { AlertTriangle, CreditCard } from "lucide-react";
import { Badge, Card, CardContent, CardHeader, CardTitle } from "@/components/ui";
import { usePlatformBillingSummary } from "@/hooks/usePlatformConsole";
import { formatAud } from "./format";

/** MRR headline + per-plan subscription counts, server-computed via
 * GET /v1/platform/billing/summary — never trusts anything client-supplied. */
export function BillingSummary() {
  const billingQuery = usePlatformBillingSummary();
  const billing = billingQuery.data;
  const planEntries = Object.entries(billing?.plan_counts ?? {});

  return (
    <Card className="mb-6">
      <CardHeader>
        <CardTitle>Billing</CardTitle>
      </CardHeader>
      <CardContent>
        {billingQuery.isError ? (
          <p className="flex items-center gap-2 text-sm text-destructive">
            <AlertTriangle className="h-4 w-4 shrink-0" />
            Failed to load platform billing. Check the backend connection and try again.
          </p>
        ) : (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <div className="flex items-center gap-3 rounded-md border border-border p-4">
              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-brand-lavender text-brand-lavender-foreground">
                <CreditCard className="h-4 w-4" />
              </div>
              <div>
                <p className="text-xs text-muted-foreground">MRR</p>
                <p className="text-lg font-semibold text-foreground">
                  {billingQuery.isLoading ? "..." : formatAud(billing?.mrr_aud)}
                </p>
              </div>
            </div>
            <div className="rounded-md border border-border p-4 sm:col-span-2">
              <p className="mb-2 text-xs text-muted-foreground">Active subscriptions by plan</p>
              {billingQuery.isLoading ? (
                <p className="text-sm text-muted-foreground">...</p>
              ) : planEntries.length === 0 ? (
                <p className="text-sm text-muted-foreground">No active subscriptions yet.</p>
              ) : (
                <div className="flex flex-wrap gap-2">
                  {planEntries.map(([plan, count]) => (
                    <Badge key={plan} variant="outline">
                      {plan}: {count}
                    </Badge>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
