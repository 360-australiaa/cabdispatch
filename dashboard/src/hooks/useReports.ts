import { useQuery } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";

/**
 * Data layer for the Reports module surfaced inside Compliance
 * (`src/pages/compliance`). Mirrors `shared/openapi.json` schemas
 * RevenueReportResponse / RevenueGroupRow / RevenueTotals /
 * GstSummaryResponse / GstMonthRow / GstTotals and the `/v1/reports/...`
 * endpoints (see shared/API_SUMMARY.md). This router owns no table of its
 * own — it's a pure read layer over `trips`/`payments`, so every query here
 * is a plain GET with a `from`/`to` calendar-day range.
 */

export type RevenueGroupBy =
  | "day"
  | "week"
  | "month"
  | "driver"
  | "vehicle"
  | "tariff"
  | "payment_method";

export interface RevenueGroupRow {
  group_key: string;
  group_label: string;
  trip_count: number;
  gross_revenue: string;
  subtotal: string;
  surcharge: string;
  gst_component: string;
  tolls: string;
  psl: string;
  extras: string;
}

export interface RevenueTotals {
  trip_count: number;
  gross_revenue: string;
  subtotal: string;
  surcharge: string;
  gst_component: string;
  tolls: string;
  psl: string;
  extras: string;
}

export interface RevenueReportResponse {
  tenant_id: string;
  from_date: string;
  to_date: string;
  group_by: RevenueGroupBy;
  note: string;
  groups: RevenueGroupRow[];
  totals: RevenueTotals;
}

export interface GstMonthRow {
  month: string;
  trip_count: number;
  gross_revenue: string;
  gst_component: string;
  net_of_gst: string;
}

export interface GstTotals {
  trip_count: number;
  gross_revenue: string;
  gst_component: string;
  net_of_gst: string;
}

export interface GstSummaryResponse {
  tenant_id: string;
  from_date: string;
  to_date: string;
  disclaimer: string;
  months: GstMonthRow[];
  totals: GstTotals;
}

export interface ReportDateRange {
  from: string;
  to: string;
}

/** Money fields come back as decimal strings; format explicitly for display/chart use only. */
export function formatAud(amount: string | null | undefined): string {
  if (amount == null || amount === "") return "—";
  const n = Number(amount);
  if (Number.isNaN(n)) return amount;
  return new Intl.NumberFormat("en-AU", { style: "currency", currency: "AUD" }).format(n);
}

export function toDateInputValue(d: Date): string {
  return d.toISOString().slice(0, 10);
}

/** Default range: first day of the current month through today. */
export function defaultReportRange(): ReportDateRange {
  const now = new Date();
  const from = new Date(now.getFullYear(), now.getMonth(), 1);
  return { from: toDateInputValue(from), to: toDateInputValue(now) };
}

export function useRevenueReportQuery(params: ReportDateRange & { group_by: RevenueGroupBy }) {
  return useQuery({
    queryKey: ["reports", "revenue", params],
    queryFn: async () => {
      const res = await apiClient.get<RevenueReportResponse>("/v1/reports/revenue", {
        params: { from: params.from, to: params.to, group_by: params.group_by },
      });
      return res.data;
    },
    enabled: Boolean(params.from && params.to),
    placeholderData: (prev) => prev,
  });
}

export function useGstSummaryQuery(params: ReportDateRange) {
  return useQuery({
    queryKey: ["reports", "gst-summary", params],
    queryFn: async () => {
      const res = await apiClient.get<GstSummaryResponse>("/v1/reports/gst-summary", {
        params: { from: params.from, to: params.to },
      });
      return res.data;
    },
    enabled: Boolean(params.from && params.to),
    placeholderData: (prev) => prev,
  });
}

/** Streams the NSW PtP CSV export via the authenticated apiClient (no public
 * download URL) and triggers a browser save via a throwaway object URL +
 * anchor click — same pattern as `downloadComplianceDocument`. */
export async function downloadNswPtpExport(params: ReportDateRange): Promise<void> {
  const res = await apiClient.get("/v1/reports/nsw-ptp-export", {
    params: { from: params.from, to: params.to, format: "csv" },
    responseType: "blob",
  });
  const blob = new Blob([res.data], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `nsw-ptp-export_${params.from}_${params.to}.csv`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
