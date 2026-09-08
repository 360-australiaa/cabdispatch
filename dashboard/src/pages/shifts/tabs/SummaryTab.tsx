import { type ReactNode } from "react";
import { Badge, ErrorBanner, Skeleton } from "@/components/ui";
import { errorMessage } from "@/lib/format";
import { useShiftReportQuery } from "../api";
import { formatDurationMinutes, formatKm, formatMoney, reconciledBadgeVariant } from "../format";

export interface SummaryTabProps {
  shiftId: string;
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 font-medium text-foreground">{children}</dd>
    </div>
  );
}

/**
 * Shift page Summary tab (dashboard command-centre plan §7): the same
 * figures `ShiftReportModal` showed -- `GET /v1/shifts/{id}/report`
 * (trips/distance/duration + cash/card/PSL money block) -- read-only here;
 * the page's own header actions carry the PDF/CSV downloads and Edit.
 */
export function SummaryTab({ shiftId }: SummaryTabProps) {
  const reportQuery = useShiftReportQuery(shiftId);
  const report = reportQuery.data;

  if (reportQuery.isLoading) {
    return <Skeleton className="h-48 w-full" />;
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
    <div className="flex flex-col gap-5">
      <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm sm:grid-cols-4">
        <Field label="Duration">{formatDurationMinutes(report.duration_minutes)}</Field>
        <Field label="Trips">{report.trips_count}</Field>
        <Field label="Distance">{formatKm(report.km_total)}</Field>
        <Field label="Status">
          <Badge variant={reconciledBadgeVariant(report.reconciled)}>
            {report.reconciled ? "Reconciled" : "Not reconciled"}
          </Badge>
        </Field>
      </dl>

      <div className="rounded-lg border border-border bg-muted/40 p-4">
        <h3 className="mb-3 text-xs font-medium uppercase tracking-wide text-muted-foreground">Takings</h3>
        <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm sm:grid-cols-4">
          <Field label="Cash">{formatMoney(report.cash_total)}</Field>
          <Field label="Card">{formatMoney(report.card_total)}</Field>
          <Field label="Total takings">
            <span className="font-semibold">{formatMoney(report.total_takings)}</span>
          </Field>
          <Field label="PSL owed">{formatMoney(report.psl_owed)}</Field>
        </dl>
      </div>

      {report.inspection_json && Object.keys(report.inspection_json).length > 0 && (
        <div>
          <h3 className="mb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
            Pre-shift inspection
          </h3>
          <pre className="max-h-48 overflow-auto rounded-md border border-border bg-muted/40 p-3 text-xs">
            {JSON.stringify(report.inspection_json, null, 2)}
          </pre>
        </div>
      )}
    </div>
  );
}
