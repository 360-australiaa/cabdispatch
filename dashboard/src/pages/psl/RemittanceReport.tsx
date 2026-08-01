import { useMemo, useState } from "react";
import { FileWarning } from "lucide-react";
import { Badge, Card, CardContent, CardDescription, CardHeader, CardTitle, Input, Table, type TableColumn } from "@/components/ui";
import { usePSLReportQuery, type PSLReportDriverLine } from "@/hooks/usePSLCentre";
import { currentPeriod, formatMoney, formatPeriod } from "./format";

const PERIOD_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;

/** Remittance report view — GET /v1/psl/report?period=YYYY-MM. Shows the
 * tenant-wide roll-up plus a per-driver breakdown for the selected period. */
export function RemittanceReport() {
  const [period, setPeriod] = useState(currentPeriod());
  const periodValid = PERIOD_PATTERN.test(period);

  const reportQuery = usePSLReportQuery(period, periodValid);
  const report = reportQuery.data;

  const columns: TableColumn<PSLReportDriverLine>[] = useMemo(
    () => [
      {
        key: "driver_name",
        header: "Driver",
        render: (row) => row.driver_name ?? row.driver_id.slice(0, 8),
        sortable: true,
        sortAccessor: (row) => row.driver_name ?? row.driver_id,
      },
      {
        key: "trips_count",
        header: "Trips",
        render: (row) => row.trips_count,
        sortable: true,
        sortAccessor: (row) => row.trips_count,
      },
      {
        key: "amount_owed",
        header: "Owed",
        render: (row) => formatMoney(row.amount_owed),
        sortable: true,
        sortAccessor: (row) => Number(row.amount_owed),
      },
      {
        key: "amount_collected",
        header: "Collected",
        render: (row) => formatMoney(row.amount_collected),
        sortable: true,
        sortAccessor: (row) => Number(row.amount_collected),
      },
      {
        key: "amount_outstanding",
        header: "Outstanding",
        render: (row) => (
          <span className={Number(row.amount_outstanding) > 0 ? "font-medium text-destructive" : ""}>
            {formatMoney(row.amount_outstanding)}
          </span>
        ),
        sortable: true,
        sortAccessor: (row) => Number(row.amount_outstanding),
      },
      {
        key: "remitted",
        header: "Remitted",
        render: (row) => (
          <Badge variant={row.remitted ? "success" : "outline"}>
            {row.remitted ? "Remitted" : "Pending"}
          </Badge>
        ),
        sortable: true,
        sortAccessor: (row) => (row.remitted ? 1 : 0),
      },
    ],
    [],
  );

  return (
    <div>
      <Card className="mb-4">
        <CardContent className="flex flex-wrap items-end gap-3 pt-4">
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Period</label>
            <Input type="month" className="w-40" value={period} onChange={(e) => setPeriod(e.target.value)} />
          </div>
          {!periodValid && (
            <p className="text-sm text-muted-foreground">Select a full month to run the report.</p>
          )}
        </CardContent>
      </Card>

      {periodValid && reportQuery.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load the remittance report for {formatPeriod(period)}. Check the backend connection
          and try again.
        </p>
      )}

      {periodValid && (
        <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Card>
            <CardHeader className="pb-1">
              <CardDescription>Drivers</CardDescription>
              <CardTitle className="text-2xl">
                {reportQuery.isLoading ? "…" : (report?.driver_count ?? 0)}
              </CardTitle>
            </CardHeader>
          </Card>
          <Card>
            <CardHeader className="pb-1">
              <CardDescription>Trips</CardDescription>
              <CardTitle className="text-2xl">
                {reportQuery.isLoading ? "…" : (report?.total_trips ?? 0)}
              </CardTitle>
            </CardHeader>
          </Card>
          <Card>
            <CardHeader className="pb-1">
              <CardDescription>Owed</CardDescription>
              <CardTitle className="text-2xl">
                {reportQuery.isLoading ? "…" : formatMoney(report?.total_owed)}
              </CardTitle>
            </CardHeader>
          </Card>
          <Card>
            <CardHeader className="pb-1">
              <CardDescription>Outstanding</CardDescription>
              <CardTitle
                className={
                  "text-2xl " +
                  (report && Number(report.total_outstanding) > 0 ? "text-destructive" : "")
                }
              >
                {reportQuery.isLoading ? "…" : formatMoney(report?.total_outstanding)}
              </CardTitle>
            </CardHeader>
          </Card>
        </div>
      )}

      {periodValid && (
        <Table
          key={period}
          columns={columns}
          data={report?.drivers ?? []}
          rowKey={(row) => row.driver_id}
          isLoading={reportQuery.isLoading}
          pageSize={15}
          emptyState={
            <span className="inline-flex items-center gap-2">
              <FileWarning className="h-4 w-4" /> No PSL ledger entries for {formatPeriod(period)}.
            </span>
          }
        />
      )}
    </div>
  );
}
