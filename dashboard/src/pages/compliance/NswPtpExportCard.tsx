import { useState } from "react";
import { Download } from "lucide-react";
import { Button, Card, CardContent, CardDescription, CardHeader, CardTitle, Input } from "@/components/ui";
import { defaultReportRange, downloadNswPtpExport } from "@/hooks/useReports";
import { extractErrorMessage } from "./format";

/**
 * Blueprint 7.2.8 "NSW PtP Export: One-click generation" — date range picker
 * + a single button that streams `GET /v1/reports/nsw-ptp-export?format=csv`
 * and saves it as a CSV file. No preview step; the export itself is the
 * deliverable regulators/finance ask for.
 */
export function NswPtpExportCard() {
  const initial = defaultReportRange();
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [isDownloading, setIsDownloading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const rangeValid = Boolean(from && to) && from <= to;

  async function handleExport() {
    if (!rangeValid) return;
    setError(null);
    setIsDownloading(true);
    try {
      await downloadNswPtpExport({ from, to });
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setIsDownloading(false);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>NSW PtP export</CardTitle>
        <CardDescription>
          One-click CSV export of closed trips for the selected date range, formatted for NSW
          Point to Point Transport Commissioner reporting.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-wrap items-end gap-3 pt-0">
        <div className="flex flex-col gap-1.5">
          <label className="text-xs font-medium text-muted-foreground" htmlFor="ptp-from">
            From
          </label>
          <Input
            id="ptp-from"
            type="date"
            className="w-40"
            value={from}
            max={to || undefined}
            onChange={(e) => setFrom(e.target.value)}
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <label className="text-xs font-medium text-muted-foreground" htmlFor="ptp-to">
            To
          </label>
          <Input
            id="ptp-to"
            type="date"
            className="w-40"
            value={to}
            min={from || undefined}
            onChange={(e) => setTo(e.target.value)}
          />
        </div>
        <Button onClick={handleExport} disabled={!rangeValid || isDownloading}>
          <Download className="h-4 w-4" />
          {isDownloading ? "Generating…" : "Export CSV"}
        </Button>
        {!rangeValid && from && to && (
          <span className="text-xs text-destructive">"From" must be on or before "To".</span>
        )}
      </CardContent>
      {error && <p className="px-4 pb-4 text-sm text-destructive">{error}</p>}
    </Card>
  );
}
