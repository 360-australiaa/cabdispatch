import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { API, server, startMockServer } from "@/test/server";
import TripsPage from "./index";

startMockServer();

/**
 * Row-click navigation for the Trips list page (dashboard command-centre
 * plan §7 repoint) -- follows the same pattern the parallel Drivers/
 * Vehicles/Devices repoint workstream used (row click -> `navigate(...)`
 * instead of opening a modal; `TripDetailModal` is gone).
 */

const NOW_ISO = "2026-09-09T10:00:00.000Z";
const TRIP_ID = "trip1";

const TRIP = {
  id: TRIP_ID,
  tenant_id: "t1",
  client_uuid: "c1",
  vehicle_id: "v1",
  driver_id: "d1",
  shift_id: null,
  tariff_id: "tar1",
  type: "rank_hail",
  simulated: false,
  status: "closed",
  time_class: "day",
  is_peak: false,
  maxi: false,
  passenger_count: 1,
  wheelchair_hiring: false,
  airport_rank_requested_maxi: false,
  voucher_code: null,
  account_reference: null,
  split_payments: null,
  start_at: NOW_ISO,
  end_at: NOW_ISO,
  start_lat: -33.86,
  start_lng: 151.2,
  end_lat: -33.87,
  end_lng: 151.21,
  distance_m: 5000,
  moving_s: 600,
  waiting_s: 30,
  flag_fall: "3.60",
  dist_amount: "10.00",
  wait_amount: "0.50",
  peak_amount: "0",
  tolls: "0",
  psl: "1.10",
  extras: "0",
  subtotal: "15.20",
  surcharge: "0",
  total: "15.20",
  gst_component: "1.38",
  payment_method: "cash",
  gps_trace_ref: null,
  max_fare_check_passed: true,
  variance_pct: null,
  receipt_ref: null,
  flagged_for_review: false,
  review_notes: null,
  created_at: NOW_ISO,
  updated_at: NOW_ISO,
};

function installHandlers() {
  server.use(
    http.get(`${API}/v1/trips`, () => HttpResponse.json({ items: [TRIP], total: 1, skip: 0, limit: 200 })),
    http.get(`${API}/v1/vehicles`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 100 })),
    http.get(`${API}/v1/drivers`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 100 })),
    http.get(`${API}/v1/tariffs`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 200 })),
  );
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/trips"]}>
        <Routes>
          <Route path="/trips" element={<TripsPage />} />
          <Route path="/trips/:tripId" element={<h1>Trip page for {TRIP_ID}</h1>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  installHandlers();
});

describe("TripsPage row click", () => {
  it("navigates to /trips/:id instead of opening a modal", async () => {
    renderPage();
    const user = userEvent.setup();

    const table = await screen.findByRole("table");
    // Wait for the real data row to render (not just the table shell/header)
    // before grabbing rows -- avoids clicking a header-only snapshot mid-fetch.
    await within(table).findByText("Rank / Hail");
    const rows = within(table).getAllByRole("row");
    // rows[0] is the header row -- click the first (and only) data row.
    await user.click(rows[1]);

    expect(await screen.findByRole("heading", { name: `Trip page for ${TRIP_ID}` })).toBeInTheDocument();
  });
});
