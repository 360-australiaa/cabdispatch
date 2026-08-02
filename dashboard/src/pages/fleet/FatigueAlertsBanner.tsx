import { useState } from "react";
import { AlertTriangle, Check } from "lucide-react";
import { Badge, Button, Card, CardContent } from "@/components/ui";
import { useAcknowledgeFatigueAlert, useOpenFatigueAlerts } from "./api";
import { errorMessage, relativeFromNow, truncateId } from "./format";
import { FATIGUE_ALERT_KIND_LABELS, type FatigueAlert } from "./types";

/** Cap on how many open alerts are listed inline before collapsing to a count. */
const VISIBLE_LIMIT = 5;

/**
 * Compact warning banner surfacing open (unacknowledged) driving-hours fatigue
 * alerts (`GET /v1/fatigue-alerts?acknowledged=false`) at the top of Fleet &
 * Drivers, visible regardless of which tab is active. Alerts are raised
 * server-side as a side effect of trip telemetry ticks — this page only
 * lists and acknowledges them.
 */
export function FatigueAlertsBanner() {
  const alertsQuery = useOpenFatigueAlerts();
  const acknowledge = useAcknowledgeFatigueAlert();
  const [pendingId, setPendingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const alerts = alertsQuery.data?.items ?? [];
  if (alertsQuery.isLoading || alertsQuery.isError || alerts.length === 0) return null;

  async function acknowledgeAlert(alert: FatigueAlert) {
    setPendingId(alert.id);
    setError(null);
    try {
      await acknowledge.mutateAsync(alert.id);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setPendingId(null);
    }
  }

  const visible = alerts.slice(0, VISIBLE_LIMIT);
  const overflow = alerts.length - visible.length;

  return (
    <Card className="mb-4 border-destructive/40 bg-destructive/5">
      <CardContent className="pt-4">
        <div className="mb-2 flex items-center gap-2 text-sm font-semibold text-destructive">
          <AlertTriangle className="h-4 w-4 shrink-0" />
          {alerts.length} open fatigue alert{alerts.length === 1 ? "" : "s"}
        </div>
        <ul className="flex flex-col gap-1.5">
          {visible.map((alert) => (
            <li
              key={alert.id}
              className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-card px-3 py-1.5 text-sm"
            >
              <span className="flex flex-wrap items-center gap-2">
                <Badge variant="destructive">{FATIGUE_ALERT_KIND_LABELS[alert.kind]}</Badge>
                <span className="text-muted-foreground">Driver {truncateId(alert.driver_id)}</span>
                <span className="text-xs text-muted-foreground" title={alert.triggered_at}>
                  {relativeFromNow(alert.triggered_at)}
                </span>
              </span>
              <Button
                variant="outline"
                size="sm"
                onClick={() => acknowledgeAlert(alert)}
                disabled={pendingId === alert.id}
              >
                <Check className="h-3.5 w-3.5" /> Acknowledge
              </Button>
            </li>
          ))}
        </ul>
        {overflow > 0 && (
          <p className="mt-2 text-xs text-muted-foreground">+{overflow} more open alert{overflow === 1 ? "" : "s"}.</p>
        )}
        {error && <p className="mt-2 text-sm text-destructive">{error}</p>}
      </CardContent>
    </Card>
  );
}
