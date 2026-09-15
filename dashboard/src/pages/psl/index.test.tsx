import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "@/components/ui";
import { currentPeriod } from "@/lib/format";
import { API, server, startMockServer } from "@/test/server";
import PslPage from "./index";

startMockServer();

/**
 * PSL Centre -- the two admin-plan §4 changes: the Period filter defaults to
 * the current month, and an owner/admin can rebuild the accrual ledger from
 * trips (`POST /v1/psl/ledger/rebuild`) behind a confirm step.
 */

let currentUser = { id: "u-owner", role: "owner", tenant_id: "t1", name: "Owner", email: "o@x", status: "active", mfa_enabled: false };
vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ user: currentUser, tenant: null }),
}));

let ledgerRequests: URL[] = [];
let rebuildCalls = 0;

function installHandlers() {
  server.use(
    http.get(`${API}/v1/psl/ledger`, ({ request }) => {
      ledgerRequests.push(new URL(request.url));
      return HttpResponse.json({ items: [], total: 0, skip: 0, limit: 200 });
    }),
    http.get(`${API}/v1/psl/topups`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 200 })),
    http.get(`${API}/v1/drivers`, () => HttpResponse.json({ items: [] })),
    http.post(`${API}/v1/psl/ledger/rebuild`, () => {
      rebuildCalls += 1;
      return HttpResponse.json({ created: 3, updated: 1 });
    }),
  );
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <MemoryRouter initialEntries={["/psl"]}>
          <PslPage />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  currentUser = { id: "u-owner", role: "owner", tenant_id: "t1", name: "Owner", email: "o@x", status: "active", mfa_enabled: false };
  ledgerRequests = [];
  rebuildCalls = 0;
  installHandlers();
});

describe("PslPage", () => {
  it("defaults the Period filter to the current month and sends it to the ledger query", async () => {
    renderPage();
    await waitFor(() => expect(ledgerRequests.length).toBeGreaterThan(0));
    expect(ledgerRequests[0].searchParams.get("period")).toBe(currentPeriod());
    expect(screen.getByDisplayValue(currentPeriod())).toBeInTheDocument();
    // The default counts as an active filter, so it can be cleared to "all periods".
    expect(screen.getByRole("button", { name: "Clear filters" })).toBeInTheDocument();
  });

  it("rebuilds the ledger behind a confirm step and toasts the created/updated counts", async () => {
    renderPage();
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: /Rebuild ledger from trips/ }));
    expect(rebuildCalls).toBe(0);
    expect(await screen.findByRole("dialog")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Rebuild ledger" }));

    await waitFor(() => expect(rebuildCalls).toBe(1));
    expect(await screen.findByText("Ledger rebuilt")).toBeInTheDocument();
    expect(screen.getByText("3 rows created, 1 updated.")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("shows the rebuild error inside the dialog when the backend refuses", async () => {
    server.use(
      http.post(`${API}/v1/psl/ledger/rebuild`, () =>
        HttpResponse.json({ detail: "Ledger rebuild is not enabled for this tenant" }, { status: 400 }),
      ),
    );
    renderPage();
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: /Rebuild ledger from trips/ }));
    await user.click(await screen.findByRole("button", { name: "Rebuild ledger" }));

    expect(await screen.findByText("Ledger rebuild is not enabled for this tenant")).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("hides the rebuild action from a dispatcher (owner/admin only server-side)", async () => {
    currentUser = { ...currentUser, role: "dispatcher" };
    renderPage();
    // Dispatcher still sees the other manage actions.
    expect(await screen.findByRole("button", { name: /Record top-up/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Rebuild ledger from trips/ })).not.toBeInTheDocument();
  });
});
