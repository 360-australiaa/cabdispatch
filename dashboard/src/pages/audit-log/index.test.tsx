import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { API, server, startMockServer } from "@/test/server";
import AuditLogPage from "./index";

startMockServer();

/**
 * Actor names on the Audit Log (admin-panel plan §5): the API-joined
 * `actor_name` first, the users lookup second, the shortened id last -- and
 * the new `fare_correction` action in the filter.
 */

const NOW_ISO = "2026-09-14T08:04:00.000Z";

const ENTRY_BASE = {
  tenant_id: "t1",
  entity_type: "trip",
  entity_id: "trip-1234-5678",
  before_json: { total: "32.52" },
  after_json: { total: "59.03" },
  at: NOW_ISO,
  hash: "h1",
  previous_hash: "h0",
};

const ENTRIES = {
  items: [
    // Name joined by the API: wins outright.
    { ...ENTRY_BASE, id: "a1", action: "fare_correction", actor_user_id: "user-owner-1", actor_name: "Ben Farid" },
    // No joined name, but the user is in the lookup.
    { ...ENTRY_BASE, id: "a2", action: "update", actor_user_id: "user-disp-2", actor_name: null },
    // Neither: the id, shortened.
    { ...ENTRY_BASE, id: "a3", action: "close", actor_user_id: "bdb0c6db-0000-4000-8000-000000000000" },
    // System-attributed.
    { ...ENTRY_BASE, id: "a4", action: "tick", actor_user_id: null },
  ],
  total: 4,
  limit: 25,
  offset: 0,
};

/** Query params of every GET /v1/audit-log the page issued, newest last. */
let auditRequests: URLSearchParams[] = [];

function installHandlers() {
  auditRequests = [];
  server.use(
    http.get(`${API}/v1/audit-log`, ({ request }) => {
      auditRequests.push(new URL(request.url).searchParams);
      return HttpResponse.json(ENTRIES);
    }),
    http.get(`${API}/v1/users`, () =>
      HttpResponse.json({
        items: [{ id: "user-disp-2", name: "Dispatcher Dee", email: "dee@example.com" }],
        total: 1,
        skip: 0,
        limit: 100,
      }),
    ),
  );
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/audit-log"]}>
        <AuditLogPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  installHandlers();
});

describe("AuditLogPage actors", () => {
  it("shows the API-joined name, then the users lookup, then the shortened id", async () => {
    renderPage();

    const table = await screen.findByRole("table");
    expect(await within(table).findByText("Ben Farid")).toBeInTheDocument();
    expect(await within(table).findByText("Dispatcher Dee")).toBeInTheDocument();
    expect(within(table).getByText("bdb0c6db")).toBeInTheDocument();
    // The full id stays reachable as a tooltip for anyone who needs to quote it.
    expect(within(table).getByText("Ben Farid")).toHaveAttribute("title", "user-owner-1");
  });

  it("names the actor in the diff dialog too", async () => {
    renderPage();
    const user = userEvent.setup();

    const table = await screen.findByRole("table");
    await within(table).findByText("Ben Farid");
    await user.click(within(table).getAllByRole("button", { name: "View diff" })[0]);

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("Actor: Ben Farid")).toBeInTheDocument();
    expect(within(dialog).getByText("59.03")).toBeInTheDocument();
  });
});

describe("AuditLogPage action filter", () => {
  it("offers fare_correction and sends it to the server", async () => {
    renderPage();
    const user = userEvent.setup();
    await screen.findByRole("table");

    const option = screen.getByRole("option", { name: "fare_correction" });
    expect(option).toBeInTheDocument();
    await user.selectOptions(screen.getByDisplayValue("All actions"), "fare_correction");

    expect(await screen.findByDisplayValue("fare_correction")).toBeInTheDocument();
    expect(auditRequests.at(-1)?.get("action")).toBe("fare_correction");
  });
});
