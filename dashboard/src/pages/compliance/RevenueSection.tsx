import { useState } from "react";
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { Card, CardContent, CardDescription, CardHeader, CardTitle, Input, type TableColumn, Table } from "@/components/ui";
import {
  defaultReportRange,
  formatAud,
  useGstSummaryQuery,
  useRevenueReportQuery,
  type GstMonthRow,
} from "@/hooks/useReports";

/**
 * Revenue & GST section — `GET /v1/reports/revenue` (grouped by day, then
 * again by payment_method) rendered as bar-via-recharts, plus
 * `GET /v1/reports/gst-summary` as a plain table. Dashboard already ships
 * recharts (see src/pages/duress/GpsTracePanel.tsx) so no new charting
 * dependency was added.
 */
export function RevenueSection() {
  const initial = defaultReportRange();
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);

  const rangeValid = Boolean(from && to) && from <= to;
  const range = rangeValid ? { from, to } : { from: "", to: "" };

  const byDayQuery = useRevenueReportQuery({ ...range, group_by: "day" });
  const byMethodQuery = useRevenueReportQuery({ ...range, group_by: "payment_method" });
  const gstQuery = useGstSummaryQuery(range);

  const byDayData = (byDayQuery.data?.groups ?? []).map((g) => ({
    label: g.group_label,
    revenue: Number(g.gross_revenue),
  }));
  const byMethodData = (byMethodQuery.data?.groups ?? []).map((g) => ({
    label: g.group_label,
    revenue: Number(g.gross_revenue),
  }));

  const gstColumns: TableColumn<GstMonthRow>[] = [
    { key: "month", header: "Month" },
    { key: "trip_count", header: "Trips", className: "text-right" },
    {
      key: "gross_revenue",
      header: "Gross revenue",
      className: "text-right",
      render: (row) => formatAud(row.gross_revenue),
    },
    {
      key: "gst_component",
      header: "GST",
      className: "text-right",
      render: (row) => formatAud(row.gst_component),
    },
    {
      key: "net_of_gst",
      header: "Net of GST",
      className: "text-right",
      render: (row) => formatAud(row.net_of_gst),
    },
  ];

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardHeader>
          <CardTitle>Revenue & GST</CardTitle>
          <CardDescription>
            Aggregated totals over closed trips only — open trips have no final totals yet.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap items-end gap-3 pt-0">
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground" htmlFor="rev-from">
              From
            </label>
            <Input
              id="rev-from"
              type="date"
              className="w-40"
              value={from}
              max={to || undefined}
              onChange={(e) => setFrom(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground" htmlFor="rev-to">
              To
            </label>
            <Input
              id="rev-to"
              type="date"
              className="w-40"
              value={to}
              min={from || undefined}
              onChange={(e) => setTo(e.target.value)}
            />
          </div>
          {!rangeValid && from && to && (
            <span className="text-xs text-destructive">"From" must be on or before "To".</span>
          )}
        </CardContent>
      </Card>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Revenue by day</CardTitle>
          </CardHeader>
          <CardContent className="pt-0">
            <RevenueBarChart
              data={byDayData}
              isLoading={byDayQuery.isLoading}
              isError={byDayQuery.isError}
              emptyLabel="No closed trips in this range."
            />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Revenue by payment method</CardTitle>
          </CardHeader>
          <CardContent className="pt-0">
            <RevenueBarChart
              data={byMethodData}
              isLoading={byMethodQuery.isLoading}
              isError={byMethodQuery.isError}
              emptyLabel="No closed trips in this range."
            />
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>GST summary</CardTitle>
          <CardDescription>{gstQuery.data?.disclaimer}</CardDescription>
        </CardHeader>
        <CardContent className="pt-0">
          {gstQuery.isError ? (
            <p className="text-sm text-destructive">Failed to load the GST summary for this range.</p>
          ) : (
            <>
              <Table
                columns={gstColumns}
                data={gstQuery.data?.months ?? []}
                rowKey={(row) => row.month}
                isLoading={gstQuery.isLoading}
                emptyState="No closed trips in this range."
              />
              {gstQuery.data && (
                <div className="mt-3 flex flex-wrap justify-end gap-x-6 gap-y-1 border-t border-border pt-3 text-sm">
                  <span className="text-muted-foreground">
                    {gstQuery.data.totals.trip_count} trip{gstQuery.data.totals.trip_count === 1 ? "" : "s"}
                  </span>
                  <span className="text-muted-foreground">
                    Gross <span className="font-medium text-foreground">{formatAud(gstQuery.data.totals.gross_revenue)}</span>
                  </span>
                  <span className="text-muted-foreground">
                    GST <span className="font-medium text-foreground">{formatAud(gstQuery.data.totals.gst_component)}</span>
                  </span>
                  <span className="text-muted-foreground">
                    Net <span className="font-medium text-foreground">{formatAud(gstQuery.data.totals.net_of_gst)}</span>
                  </span>
                </div>
              )}
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function RevenueBarChart({
  data,
  isLoading,
  isError,
  emptyLabel,
}: {
  data: { label: string; revenue: number }[];
  isLoading: boolean;
  isError: boolean;
  emptyLabel: string;
}) {
  if (isError) {
    return <p className="text-sm text-destructive">Failed to load revenue for this range.</p>;
  }
  if (!isLoading && data.length === 0) {
    return (
      <div className="flex h-56 items-center justify-center rounded-md border border-dashed border-border text-xs text-muted-foreground">
        {emptyLabel}
      </div>
    );
  }
  return (
    <div className="h-56 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: 4 }}>
          <CartesianGrid strokeDasharray="3 3" className="stroke-border" vertical={false} />
          <XAxis dataKey="label" tick={{ fontSize: 10 }} interval="preserveStartEnd" />
          <YAxis tick={{ fontSize: 10 }} width={56} tickFormatter={(v: number) => formatAud(String(v))} />
          <Tooltip formatter={(value: number) => formatAud(String(value))} labelFormatter={(label) => label} />
          <Bar dataKey="revenue" fill="var(--brand-primary)" radius={[3, 3, 0, 0]} isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
