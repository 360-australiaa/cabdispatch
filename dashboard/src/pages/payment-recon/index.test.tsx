import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "@/components/ui";
import { API, server, startMockServer } from "@/test/server";
import PaymentReconciliationPage from "./index";
import type { PaymentRead } from "./types";

startMockServer();

/**
 * Payment Reconciliation as a workflow (admin plan §3): open a docket, mark
 * it settled with a settlement reference (`PATCH /v1/payments/{id}`), run
 * the CabCharge authorise flow from a pending docket, and export the
 * filtered list.
 */

let currentUser = { id: "u-owner", role: "owner", tenant_id: "t1", name: "Owner", email: "o@x", status: "active", mfa_enabled: false };
vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ user: currentUser }),
}));

function docket(overrides: Partial<PaymentRead>): PaymentRead {
  return {
    id: "p1",
    tenant_id: "t1",
    trip_id: "trip-0001-aaaa",
    method: "cabcharge",
    amount: "59.03",
    surcharge: "2.95",
    stripe_pi_id: null,
    status: "pending",
    captured_at: null,
    change_given: null,
    docket_number: "CC-1001",
    notes: "keyed from paper docket",
    subsidy_amount: null,
    passenger_paid_amount: null,
    created_at: "2026-09-14T08:00:00Z",
    updated_at: "2026-09-14T08:00:00Z",
    ...overrides,
  };
}

let rows: PaymentRead[] = [];
let patchCalls: { id: string; body: Record<string, unknown> }[] = [];
let authorizeCalls: unknown[] = [];
let listRequests: URL[] = [];

function installHandlers() {
  server.use(
    http.get(`${API}/v1/payments`, ({ request }) => {
      const url = new URL(request.url);
      listRequests.push(url);
      const method = url.searchParams.get("method");
      const status = url.searchParams.get("status");
      const items = rows.filter((r) => r.method === method && (!status || r.status === status));
      return HttpResponse.json({ items, total: items.length, skip: 0, limit: 20 });
    }),
    http.get(`${API}/v1/payments/:id`, ({ params }) => {
      const row = rows.find((r) => r.id === params.id);
      return row ? HttpResponse.json(row) : HttpResponse.json({ detail: "Not found" }, { status: 404 });
    }),
    http.patch(`${API}/v1/payments/:id`, async ({ params, request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      patchCalls.push({ id: String(params.id), body });
      rows = rows.map((r) => (r.id === params.id ? ({ ...r, ...body } as PaymentRead) : r));
      return HttpResponse.json(rows.find((r) => r.id === params.id));
    }),
    http.post(`${API}/v1/payments/cabcharge/authorize`, async ({ request }) => {
      const body = await request.json();
      authorizeCalls.push(body);
      const created = docket({ id: "p-auth", docket_number: "CC-MOCK-77", notes: "CabCharge authorization AUTH-77 (card 4111)" });
      rows = [...rows, created];
      return HttpResponse.json(
        { payment: created, mock: true, authorization_id: "AUTH-77", cabcharge_status: "approved" },
        { status: 201 },
      );
    }),
  );
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <PaymentReconciliationPage />
      </ToastProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  currentUser = { id: "u-owner", role: "owner", tenant_id: "t1", name: "Owner", email: "o@x", status: "active", mfa_enabled: false };
  rows = [
    docket({}),
    docket({ id: "p2", method: "ttss", docket_number: "TT-9", status: "succeeded", subsidy_amount: "29.52", passenger_paid_amount: "29.51", notes: "Settlement ref: REM-2026-09" }),
  ];
  patchCalls = [];
  authorizeCalls = [];
  listRequests = [];
  installHandlers();
});

describe("PaymentReconciliationPage", () => {
  it("marks a docket settled with a settlement reference via PATCH", async () => {
    renderPage();
    const user = userEvent.setup();

    const table = await screen.findByRole("table", { name: "CabCharge dockets" });
    await user.click(await within(table).findByText("CC-1001"));

    const panel = await screen.findByText("Docket CC-1001");
    expect(panel).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();

    await user.selectOptions(screen.getByLabelText("Status"), "succeeded");
    await user.type(screen.getByLabelText("Settlement reference"), "BATCH-4417");
    await user.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(patchCalls).toHaveLength(1));
    expect(patchCalls[0].id).toBe("p1");
    expect(patchCalls[0].body.status).toBe("succeeded");
    expect(patchCalls[0].body.notes).toBe("keyed from paper docket\nSettlement ref: BATCH-4417");
    expect(typeof patchCalls[0].body.captured_at).toBe("string");
    expect(await screen.findByText("Docket updated")).toBeInTheDocument();
    // The list re-reads the reference out of the notes.
    expect(await within(table).findByText("BATCH-4417")).toBeInTheDocument();
  });

  it("authorises via CabCharge from a pending docket, cancels the manual one, and says it was simulated", async () => {
    renderPage();
    const user = userEvent.setup();

    const table = await screen.findByRole("table", { name: "CabCharge dockets" });
    await user.click(await within(table).findByText("CC-1001"));
    await user.click(await screen.findByRole("button", { name: "Authorise via CabCharge" }));

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByLabelText("Trip ID")).toHaveValue("trip-0001-aaaa");
    expect(within(dialog).getByLabelText("Amount (AUD)")).toHaveValue("59.03");
    await user.type(within(dialog).getByLabelText("CabCharge card / NFC identifier"), "4111");
    await user.click(within(dialog).getByRole("button", { name: "Authorise" }));

    await waitFor(() => expect(authorizeCalls).toHaveLength(1));
    expect(authorizeCalls[0]).toEqual({ trip_id: "trip-0001-aaaa", card_identifier: "4111", amount: "59.03", surcharge: "2.95" });
    await waitFor(() => expect(patchCalls).toHaveLength(1));
    expect(patchCalls[0]).toEqual({
      id: "p1",
      body: { status: "canceled", notes: "keyed from paper docket\nSuperseded by docket CC-MOCK-77" },
    });
    expect(await screen.findByText("CabCharge authorisation simulated")).toBeInTheDocument();
    // The new docket is now the selected one.
    expect(await screen.findByText("Docket CC-MOCK-77")).toBeInTheDocument();
  });

  it("uses rail-specific status labels on the TTSS tab and offers the claim flow only while pending", async () => {
    renderPage();
    const user = userEvent.setup();

    await user.click(screen.getByRole("tab", { name: /TTSS/ }));
    const table = await screen.findByRole("table", { name: "TTSS dockets" });
    expect(within(table).getByText("Claim paid")).toBeInTheDocument();
    expect(within(table).getByText("REM-2026-09")).toBeInTheDocument();

    await user.click(within(table).getByText("TT-9"));
    expect(await screen.findByText("Docket TT-9")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Claim via TTSS" })).toBeDisabled();
    // The header-level "new claim" action is unaffected by the selected docket.
    expect(screen.getByRole("button", { name: /Submit TTSS claim/ })).toBeEnabled();
  });

  it("read-only for a driver account", async () => {
    currentUser = { ...currentUser, role: "driver" };
    renderPage();
    const user = userEvent.setup();

    const table = await screen.findByRole("table", { name: "CabCharge dockets" });
    expect(screen.queryByRole("button", { name: /Authorise CabCharge/ })).not.toBeInTheDocument();
    await user.click(await within(table).findByText("CC-1001"));
    expect(await screen.findByLabelText("Status")).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
  });

  it("Export CSV fetches the filtered list at the export cap", async () => {
    renderPage();
    const user = userEvent.setup();
    await screen.findByRole("table", { name: "CabCharge dockets" });
    listRequests = [];

    await user.selectOptions(screen.getByLabelText("Filter by status"), "pending");
    await user.click(screen.getByRole("button", { name: /Export CSV/ }));

    await waitFor(() => {
      const exportReq = listRequests.find((u) => u.searchParams.get("limit") === "200");
      expect(exportReq).toBeDefined();
      expect(exportReq!.searchParams.get("method")).toBe("cabcharge");
      expect(exportReq!.searchParams.get("status")).toBe("pending");
    });
    // jsdom has no object URLs, so the page reports rather than throws.
    expect(await screen.findByText("Export failed")).toBeInTheDocument();
  });
});
