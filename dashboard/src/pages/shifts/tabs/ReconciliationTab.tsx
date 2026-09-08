import { useState } from "react";
import { Badge, Button, ErrorBanner, Skeleton, useToast } from "@/components/ui";
import { errorMessage } from "@/lib/format";
import { useShiftReportQuery, useUpdateShiftMutation } from "../api";
import { formatMoney, reconciledBadgeVariant } from "../format";

export interface ReconciliationTabProps {
  shiftId: string;
  canReconcile: boolean;
}

/**
 * Shift page Reconciliation tab (dashboard command-centre plan §7):
 * cash/card/PSL figures from `GET /v1/shifts/{id}/report`, plus a "mark
 * reconciled" toggle backed by the real `PATCH /v1/shifts/{id}` `reconciled`
 * flag (`ShiftUpdateInput.reconciled`, confirmed on the backend schema) --
 * not a placeholder control, an actual admin correction path. Read-only for
 * anyone without `canReconcile` (owner/admin/dispatcher, same gate the
 * Shifts list page already uses for Edit/End/Delete).
 */
export function ReconciliationTab({ shiftId, canReconcile }: ReconciliationTabProps) {
  const toast = useToast();
  const reportQuery = useShiftReportQuery(shiftId);
  const updateMutation = useUpdateShiftMutation();
  const [error, setError] = useState<string | null>(null);

  const report = reportQuery.data;

  async function handleToggle() {
    if (!report) return;
    setError(null);
    try {
      await updateMutation.mutateAsync({ id: shiftId, body: { reconciled: !report.reconciled } });
      toast.success(report.reconciled ? "Marked not reconciled" : "Marked reconciled");
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  if (reportQuery.isLoading) {
    return <Skeleton className="h-32 w-full" />;
  }

  if (reportQuery.isError) {
    return (
      <ErrorBanner
        message={`Failed to load this shift's report (GET /v1/shifts/${shiftId}/report): ${errorMessage(
          reportQuery.error,
        )}`}
      />
    );
  }

  if (!report) return null;

  return (
    <div className="flex flex-col gap-4">
      <div className="rounded-lg border border-border p-4">
        <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm sm:grid-cols-4">
          <div>
            <dt className="text-xs text-muted-foreground">Cash</dt>
            <dd className="font-medium text-foreground">{formatMoney(report.cash_total)}</dd>
          </div>
          <div>
            <dt className="text-xs text-muted-foreground">Card</dt>
            <dd className="font-medium text-foreground">{formatMoney(report.card_total)}</dd>
          </div>
          <div>
            <dt className="text-xs text-muted-foreground">Total takings</dt>
            <dd className="font-semibold text-foreground">{formatMoney(report.total_takings)}</dd>
          </div>
          <div>
            <dt className="text-xs text-muted-foreground">PSL owed</dt>
            <dd className="font-medium text-foreground">{formatMoney(report.psl_owed)}</dd>
          </div>
        </dl>
      </div>

      <div className="flex items-center gap-3">
        <Badge variant={reconciledBadgeVariant(report.reconciled)}>
          {report.reconciled ? "Reconciled" : "Not reconciled"}
        </Badge>
        {canReconcile ? (
          <Button size="sm" variant="outline" disabled={updateMutation.isPending} onClick={handleToggle}>
            {updateMutation.isPending
              ? "Saving…"
              : report.reconciled
                ? "Mark not reconciled"
                : "Mark reconciled"}
          </Button>
        ) : (
          <span className="text-xs text-muted-foreground">Owner/admin/dispatcher only</span>
        )}
      </div>
      {error && <p className="text-sm text-destructive">{error}</p>}
    </div>
  );
}
