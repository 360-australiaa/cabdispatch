import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { API, server, startMockServer } from "@/test/server";
import DuressPage from "./index";
import { isStaleEvent, STALE_AFTER_MS } from "./format";

startMockServer();

/**
 * Duress Desk list -- the "stale" handling from the admin plan §1.4: four
 * events sat "dispatched" for two weeks on the live tenant with nothing on
 * the desk to say so and no way to close them without opening each one.
 */

let currentUser = { id: "u-owner", role: "owner", tenant_id: "t1", name: "Owner", email: "o@x", status: "active", mfa_enabled: false };
vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ user: currentUser }),
}));

const NOW = Date.now();
const THREE_DAYS_AGO = new Date(NOW - 3 * 24 * 60 * 60 * 1000).toISOString();
const ONE_MINUTE_AGO = new Date(NOW - 60 * 1000).toISOString();

function event(overrides: Record<string, unknown>) {
  return {
    id: "e-fresh",
    tenant_id: "t1",
    vehicle_id: "v1",
    driver_id: "d2",
    trigger: "button",
    status: "open",
    opened_at: ONE_MINUTE_AGO,
    closed_at: null,
    gps_stream_ref: "gps://x",
    audio_ref: null,
    escalation_log_json: {},
    device_id: null,
    source: "tablet",
    device_audio_ref: null,
    device_call_result_json: null,
    created_at: ONE_MINUTE_AGO,
    updated_at: ONE_MINUTE_AGO,
    ...overrides,
  };
}

const STALE_EVENT = event({
  id: "e-stale",
  status: "dispatched",
  opened_at: THREE_DAYS_AGO,
  driver_id: "d1",
  driver_name: "Benn Farid",
});
const FRESH_EVENT = event({ id: "e-fresh" });
const OLD_RESOLVED = event({
  id: "e-resolved",
  status: "resolved",
  opened_at: THREE_DAYS_AGO,
  closed_at: THREE_DAYS_AGO,
  driver_id: "d3-not-in-lookup",
});

let closeCalls: { id: string; body: unknown }[] = [];
let listResponse: unknown[] = [];

function installHandlers() {
  server.use(
    http.get(`${API}/v1/duress`, () =>
      HttpResponse.json({ items: listResponse, total: listResponse.length, limit: 20, offset: 0 }),
    ),
    http.get(`${API}/v1/fleet/vehicles`, () => HttpResponse.json({ items: [{ id: "v1", rego: "T5453" }] })),
    http.get(`${API}/v1/drivers`, () => HttpResponse.json({ items: [{ id: "d2", name: "Lookup Driver" }] })),
    http.post(`${API}/v1/duress/:id/close`, async ({ params, request }) => {
      closeCalls.push({ id: String(params.id), body: await request.json() });
      listResponse = listResponse.map((e) =>
        (e as { id: string }).id === params.id ? { ...(e as object), status: "resolved" } : e,
      );
      return HttpResponse.json({ ...STALE_EVENT, status: "resolved" });
    }),
  );
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/duress"]}>
        <DuressPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  currentUser = { id: "u-owner", role: "owner", tenant_id: "t1", name: "Owner", email: "o@x", status: "active", mfa_enabled: false };
  closeCalls = [];
  listResponse = [STALE_EVENT, FRESH_EVENT, OLD_RESOLVED];
  installHandlers();
});

describe("isStaleEvent", () => {
  it("trusts the server flag when present, even against the clock", () => {
    expect(isStaleEvent({ status: "open", opened_at: THREE_DAYS_AGO, stale: false })).toBe(false);
    expect(isStaleEvent({ status: "open", opened_at: ONE_MINUTE_AGO, stale: true })).toBe(true);
  });

  it("computes from opened_at when the flag is missing (older backend)", () => {
    expect(isStaleEvent({ status: "dispatched", opened_at: THREE_DAYS_AGO }, NOW)).toBe(true);
    expect(isStaleEvent({ status: "open", opened_at: ONE_MINUTE_AGO }, NOW)).toBe(false);
    const justUnder = new Date(NOW - STALE_AFTER_MS + 1000).toISOString();
    expect(isStaleEvent({ status: "open", opened_at: justUnder }, NOW)).toBe(false);
  });

  it("never calls a terminal event stale", () => {
    expect(isStaleEvent({ status: "resolved", opened_at: THREE_DAYS_AGO, stale: true }, NOW)).toBe(false);
    expect(isStaleEvent({ status: "cancelled", opened_at: THREE_DAYS_AGO }, NOW)).toBe(false);
  });
});

describe("DuressPage stale events", () => {
  it("badges only the stale non-terminal row, shows the server-joined driver name, and falls back to the lookup", async () => {
    renderPage();
    const table = await screen.findByRole("table");
    await within(table).findByText("Benn Farid");

    expect(within(table).getAllByText("Stale")).toHaveLength(1);
    // The fresh event has no `driver_name`; the first-100 lookup still resolves it.
    expect(within(table).getByText("Lookup Driver")).toBeInTheDocument();
  });

  it("closes a stale event straight from the list row without opening the panel", async () => {
    renderPage();
    const user = userEvent.setup();
    const table = await screen.findByRole("table");
    await within(table).findByText("Benn Farid");

    const closeButtons = within(table).getAllByRole("button", { name: "Close" });
    expect(closeButtons).toHaveLength(1);
    await user.click(closeButtons[0]);

    await waitFor(() => expect(closeCalls).toHaveLength(1));
    expect(closeCalls[0].id).toBe("e-stale");
    expect(closeCalls[0].body).toEqual({ note: "Closed from the Duress Desk list as stale" });

    // The list refetches and the row is no longer stale.
    await waitFor(() => expect(within(table).queryByText("Stale")).not.toBeInTheDocument());
    expect(screen.queryByLabelText("Close detail panel")).not.toBeInTheDocument();
  });

  it("hides the row Close button for a role the backend would 403", async () => {
    currentUser = { ...currentUser, role: "driver" };
    renderPage();
    const table = await screen.findByRole("table");
    await within(table).findByText("Stale");
    expect(within(table).queryByRole("button", { name: "Close" })).not.toBeInTheDocument();
  });
});
