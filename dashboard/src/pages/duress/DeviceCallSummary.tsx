import { Phone } from "lucide-react";
import { Badge } from "@/components/ui";
import { formatCallResultSummary } from "./format";
import type { DuressCallResult } from "./types";

/**
 * Summarizes `device_call_result_json` — the outcome of the last
 * `POST /v1/duress/{id}/call` attempt against this event's paired physical
 * duress device, including any status the Twilio status webhook has since
 * reported. Uses the same "Simulated" badge convention as the billing
 * page's mock invoices (see `pages/billing/index.tsx`).
 */
export function DeviceCallSummary({ result }: { result: DuressCallResult }) {
  const showReason = !!result.reason && !(result.mock && result.skipped);

  return (
    <div className="rounded-md border border-border p-3">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <Phone className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
        <span className="text-foreground">{formatCallResultSummary(result)}</span>
        {result.mock && <Badge variant="outline">Simulated</Badge>}
      </div>

      {(result.status || showReason) && (
        <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
          {result.status && (
            <div>
              <dt className="text-muted-foreground">Call status</dt>
              <dd className="font-medium text-foreground">{result.status}</dd>
            </div>
          )}
          {showReason && (
            <div>
              <dt className="text-muted-foreground">Reason</dt>
              <dd className="font-medium text-foreground">{result.reason}</dd>
            </div>
          )}
        </dl>
      )}
    </div>
  );
}
