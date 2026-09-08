import { describe, expect, it } from "vitest";
import { buildInvoiceHtml } from "./invoicePdf";
import type { InvoiceRead } from "@/hooks/useBilling";

function invoice(overrides: Partial<InvoiceRead>): InvoiceRead {
  return {
    id: "inv-1",
    subscription_id: "sub-1",
    stripe_invoice_id: "stripe-1",
    amount_aud: "49.00",
    status: "paid",
    period_start: "2026-01-01",
    period_end: "2026-01-31",
    mock: false,
    ...overrides,
  };
}

const ctx = { tenantName: "Acme Cabs", vehicleLabel: "ABC-123", planLabel: "Pro" };

describe("buildInvoiceHtml", () => {
  it("renders the amount through the shared en-AU money formatter", () => {
    const html = buildInvoiceHtml(invoice({}), ctx);
    expect(html).toContain("$49.00");
  });

  it("includes the tenant, vehicle and plan context", () => {
    const html = buildInvoiceHtml(invoice({}), ctx);
    expect(html).toContain("Acme Cabs");
    expect(html).toContain("ABC-123");
    expect(html).toContain("Pro");
  });

  it("discloses mock/simulated invoices instead of presenting them as real", () => {
    const html = buildInvoiceHtml(invoice({ mock: true }), ctx);
    expect(html).toContain("simulated billing data");
  });

  it("does not disclose the mock banner for a real invoice", () => {
    const html = buildInvoiceHtml(invoice({ mock: false }), ctx);
    expect(html).not.toContain("simulated billing data");
  });

  it("escapes untrusted-looking text fields", () => {
    const html = buildInvoiceHtml(invoice({}), { ...ctx, tenantName: "<script>alert(1)</script>" });
    expect(html).not.toContain("<script>alert(1)</script>");
    expect(html).toContain("&lt;script&gt;");
  });
});
