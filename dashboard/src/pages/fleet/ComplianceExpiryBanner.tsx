import { AlertTriangle } from "lucide-react";
import { Badge, Card, CardContent } from "@/components/ui";
import { useComplianceExpiry } from "./api";
import { truncateId } from "./format";
import { COMPLIANCE_EXPIRY_FIELD_LABELS, type ComplianceExpiryItem } from "./types";

/** Cap on how many expiry items are listed inline before collapsing to a count. */
const VISIBLE_LIMIT = 5;

/**
 * Compact warning banner surfacing driver-accreditation (licence/authority)
 * and vehicle-registration/insurance dates that are expiring soon or already
 * past due (`GET /v1/fleet/compliance-expiry`), at the top of Fleet & Drivers,
 * visible regardless of which tab is active. Mirrors `FatigueAlertsBanner`'s
 * style — this rollup has no acknowledge endpoint, so it's read-only.
 */
export function ComplianceExpiryBanner() {
  const expiryQuery = useComplianceExpiry();

  const items = expiryQuery.data?.items ?? [];
  if (expiryQuery.isLoading || expiryQuery.isError || items.length === 0) return null;

  const expiredCount = items.filter((item) => item.status === "expired").length;
  const visible = items.slice(0, VISIBLE_LIMIT);
  const overflow = items.length - visible.length;

  function describe(item: ComplianceExpiryItem): string {
    if (item.status === "expired") {
      const days = Math.abs(item.days_remaining);
      return days === 0 ? "expired today" : `expired ${days}d ago`;
    }
    return item.days_remaining === 0 ? "expires today" : `in ${item.days_remaining}d`;
  }

  return (
    <Card className="mb-4 border-destructive/40 bg-destructive/5">
      <CardContent className="pt-4">
        <div className="mb-2 flex items-center gap-2 text-sm font-semibold text-destructive">
          <AlertTriangle className="h-4 w-4 shrink-0" />
          {items.length} accreditation/registration item{items.length === 1 ? "" : "s"} expiring or
          expired
          {expiredCount > 0 && ` (${expiredCount} already expired)`}
        </div>
        <ul className="flex flex-col gap-1.5">
          {visible.map((item) => (
            <li
              key={`${item.entity_type}-${item.entity_id}-${item.field}`}
              className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-card px-3 py-1.5 text-sm"
            >
              <span className="flex flex-wrap items-center gap-2">
                <Badge variant={item.status === "expired" ? "destructive" : "accent"}>
                  {item.status === "expired" ? "Expired" : "Expiring soon"}
                </Badge>
                <span className="font-medium text-foreground">
                  {item.label || truncateId(item.entity_id)}
                </span>
                <span className="text-muted-foreground">
                  {COMPLIANCE_EXPIRY_FIELD_LABELS[item.field]}
                </span>
                <span className="text-xs text-muted-foreground" title={item.expiry_date}>
                  {describe(item)}
                </span>
              </span>
            </li>
          ))}
        </ul>
        {overflow > 0 && (
          <p className="mt-2 text-xs text-muted-foreground">
            +{overflow} more expiring/expired item{overflow === 1 ? "" : "s"}.
          </p>
        )}
      </CardContent>
    </Card>
  );
}
