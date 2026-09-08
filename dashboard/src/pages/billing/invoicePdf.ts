/**
 * Invoice PDF via the browser's own print-to-PDF, not a shipped PDF library.
 *
 * The plan is explicit that a PDF library is a large dependency and the
 * browser's print pipeline should be preferred unless a library is genuinely
 * needed -- it isn't here, an invoice is one printable page. This renders a
 * self-contained HTML document into a new window and calls `print()` on it;
 * the operator picks "Save as PDF" in their browser's print dialog exactly
 * as they would for any other page. Nothing is added to `vite.config.ts`'s
 * `manualChunks` and nothing loads on first paint -- this module is only
 * imported from the click handler that needs it.
 */
import { formatAud, formatDate } from "@/lib/format";
import type { InvoiceRead } from "@/hooks/useBilling";

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

export interface InvoicePdfContext {
  tenantName: string;
  vehicleLabel: string;
  planLabel: string;
}

/** Builds the printable HTML. Exposed separately from the window-opening
 * side effect so the markup itself can be unit-tested. */
export function buildInvoiceHtml(invoice: InvoiceRead, ctx: InvoicePdfContext): string {
  const title = `Invoice ${invoice.id}`;
  // `formatAud`/`formatDate` are the same tenant-pinned helpers
  // (`@/lib/format`, en-AU/AUD until X1 lands tenant currency -- see that
  // module's header) the on-screen invoice table uses, so the printed
  // figure can never read differently from what the operator just looked
  // at on screen.
  const amount = formatAud(invoice.amount_aud);
  const period = `${formatDate(invoice.period_start)} – ${formatDate(invoice.period_end)}`;
  const statusLabel = invoice.status.charAt(0).toUpperCase() + invoice.status.slice(1);

  return `<!doctype html>
<html>
<head>
<meta charset="utf-8" />
<title>${escapeHtml(title)}</title>
<style>
  body { font-family: system-ui, -apple-system, "Segoe UI", sans-serif; color: #111; padding: 40px; max-width: 640px; margin: 0 auto; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  .muted { color: #666; font-size: 13px; }
  table { width: 100%; border-collapse: collapse; margin-top: 24px; }
  td, th { padding: 8px 0; text-align: left; border-bottom: 1px solid #ddd; font-size: 14px; }
  th { color: #666; font-weight: 500; }
  .total-row td { font-weight: 600; font-size: 16px; border-bottom: none; padding-top: 16px; }
  .mock-banner { margin-top: 24px; padding: 10px 12px; border: 1px solid #d97706; background: #fffbeb; color: #92400e; font-size: 12px; border-radius: 6px; }
  .footer { margin-top: 32px; font-size: 11px; color: #999; }
</style>
</head>
<body>
  <h1>${escapeHtml(ctx.tenantName)}</h1>
  <p class="muted">${escapeHtml(title)} &middot; ${escapeHtml(ctx.vehicleLabel)} &middot; ${escapeHtml(ctx.planLabel)} plan</p>
  <table>
    <tr><th>Billing period</th><td>${escapeHtml(period)}</td></tr>
    <tr><th>Status</th><td>${escapeHtml(statusLabel)}</td></tr>
    <tr><th>Stripe invoice id</th><td>${escapeHtml(invoice.stripe_invoice_id ?? "—")}</td></tr>
    <tr class="total-row"><td>Amount</td><td>${escapeHtml(amount)}</td></tr>
  </table>
  ${
    invoice.mock
      ? `<div class="mock-banner">This is simulated billing data (Stripe is running in mock mode for this tenant). No real payment was collected or is owed against this document.</div>`
      : ""
  }
  <p class="footer">Generated ${escapeHtml(new Date().toLocaleString("en-AU"))} from Cab Dispatch billing.</p>
</body>
</html>`;
}

/** Opens the invoice in a new window and triggers the browser's print
 * dialog. Returns false (instead of throwing) when the popup was blocked,
 * so the caller can show an honest "allow pop-ups" message rather than a
 * silent no-op. */
export function printInvoice(invoice: InvoiceRead, ctx: InvoicePdfContext): boolean {
  const win = window.open("", "_blank", "width=760,height=900");
  if (!win) return false;
  win.document.write(buildInvoiceHtml(invoice, ctx));
  win.document.close();
  win.focus();
  win.print();
  return true;
}
