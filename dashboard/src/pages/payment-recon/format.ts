/** Display formatting and the small pure helpers for the Payment
 * Reconciliation page.
 *
 * The `format*` helpers are re-exported from `@/lib/format` (the single
 * implementation, see that module's header); the page used to carry its own
 * `formatAud`/`formatDateTime` copies inline. Only the page-specific helpers
 * below are local. */

import type { PaymentRead, PaymentStatus, ReconciliationMethod } from "./types";

export { errorMessage, formatDateTime, formatMoney as formatAud } from "@/lib/format";

/** Status labels in the operator's words per rail: a CabCharge docket is
 * "settled" by the settlement batch, a TTSS claim is "paid" by the scheme.
 * The wire value is the same `succeeded` either way (`PaymentStatus` has no
 * settled/claimed members -- this is the "as the schema allows" mapping). */
export function statusOptionsFor(method: ReconciliationMethod): { value: PaymentStatus; label: string }[] {
  const settled = method === "ttss" ? "Claim paid" : "Settled";
  const failed = method === "ttss" ? "Claim rejected" : "Failed";
  return [
    { value: "pending", label: "Pending" },
    { value: "requires_action", label: "Requires action" },
    { value: "succeeded", label: settled },
    { value: "failed", label: failed },
    { value: "refunded", label: "Refunded" },
    { value: "canceled", label: "Canceled" },
  ];
}

export function statusLabel(method: ReconciliationMethod, status: PaymentStatus): string {
  return statusOptionsFor(method).find((s) => s.value === status)?.label ?? status;
}

export const STATUS_BADGE_VARIANT: Record<PaymentStatus, "success" | "accent" | "destructive" | "outline" | "default"> = {
  pending: "outline",
  requires_action: "accent",
  succeeded: "success",
  failed: "destructive",
  refunded: "accent",
  canceled: "outline",
};

/** The settlement/claim reference lives inside the free-text `notes`
 * (`PaymentUpdate` has no dedicated column) as one line of the form
 * `Settlement ref: <ref>`. These two helpers round-trip it so the detail
 * form can show it as its own field and edit it without clobbering
 * whatever else is in the notes (the authorisation/claim id the backend
 * wrote there, an operator's remark). */
const SETTLEMENT_REF_PREFIX = "Settlement ref: ";

export function splitSettlementRef(notes: string | null | undefined): { reference: string; rest: string } {
  if (!notes) return { reference: "", rest: "" };
  const lines = notes.split("\n");
  const idx = lines.findIndex((l) => l.startsWith(SETTLEMENT_REF_PREFIX));
  if (idx === -1) return { reference: "", rest: notes };
  const reference = lines[idx].slice(SETTLEMENT_REF_PREFIX.length).trim();
  const rest = [...lines.slice(0, idx), ...lines.slice(idx + 1)].join("\n").trim();
  return { reference, rest };
}

export function joinSettlementRef(rest: string, reference: string): string | null {
  const parts = [rest.trim(), reference.trim() ? `${SETTLEMENT_REF_PREFIX}${reference.trim()}` : ""].filter(Boolean);
  return parts.length ? parts.join("\n") : null;
}

/** RFC 4180-style cell: quote when the value carries a comma, quote or
 * newline; double any embedded quotes. */
function csvCell(value: string | number | null | undefined): string {
  if (value == null) return "";
  const text = String(value);
  return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

/** The claim/settlement file for the currently filtered dockets. TTSS
 * rows carry the subsidy split; CabCharge rows do not (blank columns are
 * not emitted, so each rail's file has only its own columns). Money stays
 * as the wire's decimal strings -- no locale formatting in a file another
 * system will parse. */
export function buildDocketCsv(rows: PaymentRead[], method: ReconciliationMethod): string {
  const header = [
    "docket_number",
    "trip_id",
    "method",
    "amount",
    "surcharge",
    ...(method === "ttss" ? ["subsidy_amount", "passenger_paid_amount"] : []),
    "status",
    "captured_at",
    "settlement_ref",
    "notes",
    "created_at",
    "payment_id",
  ];
  const lines = rows.map((row) => {
    const { reference, rest } = splitSettlementRef(row.notes);
    return [
      row.docket_number,
      row.trip_id,
      row.method,
      row.amount,
      row.surcharge,
      ...(method === "ttss" ? [row.subsidy_amount, row.passenger_paid_amount] : []),
      row.status,
      row.captured_at,
      reference,
      rest,
      row.created_at,
      row.id,
    ]
      .map(csvCell)
      .join(",");
  });
  return [header.join(","), ...lines].join("\r\n") + "\r\n";
}

/** Browser download of a text file via a throwaway object URL -- same
 * pattern as `downloadShiftReportPdf`. Returns false when the browser has
 * no object-URL support (jsdom), so callers can report rather than throw. */
export function downloadTextFile(filename: string, text: string, mime = "text/csv;charset=utf-8"): boolean {
  if (typeof URL === "undefined" || typeof URL.createObjectURL !== "function") return false;
  const blob = new Blob([text], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
  return true;
}
