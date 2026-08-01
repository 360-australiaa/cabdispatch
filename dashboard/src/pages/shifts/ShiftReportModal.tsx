import { type ReactNode } from "react";
import { Badge, Modal } from "@/components/ui";
import { useShiftReportQuery } from "./api";
import {
  formatDateTime,
  formatDurationMinutes,
  formatKm,
  formatMoney,
  reconciledBadgeVariant,
} from "./format";

/** Detail view backed by `GET /v1/shifts/{id}/report` — the reconciliation
 * summary a dispatcher/owner checks a shift against (total takings, PSL
 * owed, whether it's marked reconciled) plus the pre-shift inspection
 * checklist if one was recorded. */
export function ShiftReportModal({
  shiftId,
  onClose,
  driverLabelById,
  vehicleLabelById,
}: {
  shiftId: string | null;
  onClose: () => void;
  driverLabelById: Map<string, string>;
  vehicleLabelById: Map<string, string>;
}) {
  const reportQuery = useShiftReportQuery(shiftId);
  const report = reportQuery.data;

  return (
    <Modal
      open={shiftId != null}
      onClose={onClose}
      title="Shift report"
      description={shiftId ? `Shift ${shiftId.slice(0, 8)}` : undefined}
      className="max-w-xl"
    >
      {reportQuery.isLoading && (
        <p className="py-6 text-center text-sm text-muted-foreground">Loading report…</p>
      )}
      {reportQuery.isError && (
        <p className="text-sm text-destructive">Failed to load this shift's report.</p>
      )}
      {report && (
        <div className="flex flex-col gap-5">
          <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
            <Field label="Driver">
              {driverLabelById.get(report.driver_id) ?? report.driver_id.slice(0, 8)}
            </Field>
            <Field label="Vehicle">
              {vehicleLabelById.get(report.vehicle_id) ?? report.vehicle_id.slice(0, 8)}
            </Field>
            <Field label="Started">{formatDateTime(report.start_at)}</Field>
            <Field label="Ended">{formatDateTime(report.end_at)}</Field>
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
            <h3 className="mb-3 text-xs font-medium uppercase tracking-wide text-muted-foreground">
              Takings
            </h3>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
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

          <p className="text-xs text-muted-foreground">
            Report generated {formatDateTime(report.generated_at)}
          </p>
        </div>
      )}
    </Modal>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 font-medium text-foreground">{children}</dd>
    </div>
  );
}
