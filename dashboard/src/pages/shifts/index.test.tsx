import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { API, server, startMockServer } from "@/test/server";
import ShiftsPage from "./index";

startMockServer();

/**
 * Row-click navigation for the Shifts list page (dashboard command-centre
 * plan §7 repoint) -- the "View report" row action and the row click both
 * now go to `/shifts/:id` instead of opening the deleted `ShiftReportModal`.
 */

let currentUser: { id: string; role: string; tenant_id: string; name: string; email: string; status: string; mfa_enabled: boolean } = {
  id: "u-owner",
  role: "owner",
  tenant_id: "t1",
  name: "Owner",
  email: "owner@example.com",
  status: "active",
  mfa_enabled: false,
};
vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ user: currentUser }),
}));

const NOW_ISO = "2026-09-09T10:00:00.000Z";
const SHIFT_ID = "shift1";

const SHIFT = {
  id: SHIFT_ID,
  tenant_id: "t1",
  driver_id: "d1",
  vehicle_id: "v1",
  start_at: NOW_ISO,
  end_at: null,
  inspection_json: null,
  trips_count: 2,
  km_total: "12.3",
  cash_total: "20.00",
  card_total: "15.00",
  psl_owed: "1.10",
  reconciled: false,
  break_started_at: null,
  break_taken: false,
  created_at: NOW_ISO,
  updated_at: NOW_ISO,
};

function installHandlers() {
  server.use(
    http.get(`${API}/v1/shifts`, () => HttpResponse.json({ items: [SHIFT], total: 1, limit: 15, offset: 0 })),
    http.get(`${API}/v1/drivers`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 100 })),
    http.get(`${API}/v1/vehicles`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 100 })),
  );
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/shifts"]}>
        <Routes>
          <Route path="/shifts" element={<ShiftsPage />} />
          <Route path="/shifts/:shiftId" element={<h1>Shift page for {SHIFT_ID}</h1>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  currentUser = {
    id: "u-owner",
    role: "owner",
    tenant_id: "t1",
    name: "Owner",
    email: "owner@example.com",
    status: "active",
    mfa_enabled: false,
  };
  installHandlers();
});

describe("ShiftsPage row click", () => {
  it("navigates to /shifts/:id instead of opening the report modal", async () => {
    renderPage();
    const user = userEvent.setup();

    const table = await screen.findByRole("table");
    // Wait for the real data row to render (not just the table shell/header)
    // before grabbing rows -- avoids clicking a header-only snapshot mid-fetch.
    await within(table).findByText("$20.00");
    const rows = within(table).getAllByRole("row");
    await user.click(rows[1]);

    expect(await screen.findByRole("heading", { name: `Shift page for ${SHIFT_ID}` })).toBeInTheDocument();
  });

  it("the 'View shift' row action also navigates to /shifts/:id", async () => {
    renderPage();
    const user = userEvent.setup();

    const button = await screen.findByRole("button", { name: "View shift" });
    await user.click(button);

    expect(await screen.findByRole("heading", { name: `Shift page for ${SHIFT_ID}` })).toBeInTheDocument();
  });
});
