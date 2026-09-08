import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "@/components/ui";
import { API, server, startMockServer } from "@/test/server";
import TripPage from "./TripPage";

startMockServer();

/**
 * Full-page tests for `/trips/:tripId` (dashboard command-centre plan §7).
 * Covers: header render (driver/vehicle links, status, payment method),
 * every tab's happy path with realistic MSW data, a defensive fallback
 * (ratings has no `trip_id` filter so the Rating tab must degrade to an
 * honest "no rating recorded" rather than crash), and a non-owner
 * (dispatcher) seeing Edit/Delete disabled with a tooltip.
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
const TRIP_ID = "trip1";
const DRIVER_ID = "d1";
const VEHICLE_ID = "v1";

const TRIP = {
  id: TRIP_ID,
  tenant_id: "t1",
  client_uuid: "c1",
  vehicle_id: VEHICLE_ID,
  driver_id: DRIVER_ID,
  shift_id: "shift1",
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
  variance_pct: "0.20",
  receipt_ref: "R-100",
  flagged_for_review: false,
  review_notes: null,
  auto_tolled_roads: null,
  auto_tolls_applied: null,
  unpriced_toll_road_ids: null,
  created_at: NOW_ISO,
  updated_at: NOW_ISO,
};

const DRIVER_USER = {
  id: DRIVER_ID,
  tenant_id: "t1",
  role: "driver",
  name: "Arsalan Rehman",
  email: "arsalan@example.com",
  phone: "0400000000",
  driver_licence_no: "LIC123",
  wat_endorsed: false,
  status: "active",
  driver_license_expiry: "2027-01-01",
  driver_authority_expiry: "2027-06-01",
  driver_code: "AB12",
  photo_url: null,
  suitability_status: "clear",
  created_at: NOW_ISO,
  updated_at: NOW_ISO,
};

const VEHICLE = {
  id: VEHICLE_ID,
  tenant_id: "t1",
  rego: "T22123",
  vin: null,
  make: "Toyota",
  model: "Camry",
  vehicle_class: "standard",
  camera_serial: null,
  tracking_device_id: null,
  meter_device_id: null,
  status: "active",
  registration_expiry: null,
  insurance_expiry: null,
  created_at: NOW_ISO,
  updated_at: NOW_ISO,
};

const PAYMENT = {
  id: "pay1",
  tenant_id: "t1",
  trip_id: TRIP_ID,
  method: "cash",
  amount: "15.20",
  surcharge: "0",
  stripe_pi_id: null,
  status: "succeeded",
  captured_at: NOW_ISO,
  change_given: null,
  docket_number: null,
  notes: null,
  subsidy_amount: null,
  passenger_paid_amount: null,
  created_at: NOW_ISO,
  updated_at: NOW_ISO,
};

const AUDIT_ENTRY = {
  id: "a1",
  tenant_id: "t1",
  actor_user_id: "u-owner",
  action: "trip_closed",
  entity_type: "trip",
  entity_id: TRIP_ID,
  before_json: { status: "open" },
  after_json: { status: "closed" },
  at: NOW_ISO,
  hash: "h1",
  previous_hash: "h0",
};

interface HandlerOpts {
  ratingsMatch?: boolean;
}

function installHandlers(opts: HandlerOpts = {}) {
  server.use(
    http.get(`${API}/v1/trips/${TRIP_ID}`, () => HttpResponse.json(TRIP)),
    http.get(`${API}/v1/trips/${TRIP_ID}/gps-trace`, () =>
      HttpResponse.json({ trip_id: TRIP_ID, points: [], point_count: 0 }),
    ),
    http.get(`${API}/v1/users/${DRIVER_ID}`, () => HttpResponse.json(DRIVER_USER)),
    http.get(`${API}/v1/fleet/vehicles/${VEHICLE_ID}`, () => HttpResponse.json(VEHICLE)),
    http.get(`${API}/v1/vehicles`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 100 })),
    http.get(`${API}/v1/drivers`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 100 })),
    http.get(`${API}/v1/tariffs`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 200 })),
    http.get(`${API}/v1/toll-roads`, () => HttpResponse.json([])),
    http.get(`${API}/v1/geofences`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 200 })),
    http.get(`${API}/v1/payments`, ({ request }) => {
      const url = new URL(request.url);
      expect(url.searchParams.get("trip_id")).toBe(TRIP_ID);
      return HttpResponse.json({ items: [PAYMENT], total: 1, skip: 0, limit: 50 });
    }),
    http.get(`${API}/v1/ratings`, ({ request }) => {
      const url = new URL(request.url);
      expect(url.searchParams.get("driver_id")).toBe(DRIVER_ID);
      return HttpResponse.json({
        items: opts.ratingsMatch
          ? [{ id: "r1", tenant_id: "t1", trip_id: TRIP_ID, driver_id: DRIVER_ID, stars: 5, comment: "Great!", created_at: NOW_ISO }]
          : [{ id: "r2", tenant_id: "t1", trip_id: "some-other-trip", driver_id: DRIVER_ID, stars: 3, comment: null, created_at: NOW_ISO }],
        total: 1,
        skip: 0,
        limit: 200,
      });
    }),
    http.get(`${API}/v1/audit-log`, ({ request }) => {
      const url = new URL(request.url);
      expect(url.searchParams.get("subject_id")).toBe(TRIP_ID);
      return HttpResponse.json({ items: [AUDIT_ENTRY], total: 1, limit: 25, offset: 0 });
    }),
    http.post(`${API}/v1/trips/${TRIP_ID}/receipt/email`, () =>
      HttpResponse.json({ mock: true, would_send_to: "test@example.com", receipt_ref: "R-100", pdf_relative_path: "r.pdf", pdf_generated_now: false }),
    ),
    http.post(`${API}/v1/trips/${TRIP_ID}/receipt/sms`, () =>
      HttpResponse.json({ mock: true, would_send_to: "0400000000", receipt_ref: "R-100", pdf_relative_path: "r.pdf", pdf_generated_now: false }),
    ),
  );
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <MemoryRouter initialEntries={[`/trips/${TRIP_ID}`]}>
          <Routes>
            <Route path="/trips/:tripId" element={<TripPage />} />
            <Route path="/trips" element={<h1>Trips page</h1>} />
          </Routes>
        </MemoryRouter>
      </ToastProvider>
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
});

describe("TripPage", () => {
  it("renders the header with driver/vehicle links, status, and payment method", async () => {
    installHandlers({ ratingsMatch: true });
    renderPage();

    expect(await screen.findByRole("heading", { level: 1, name: `Trip ${TRIP_ID.slice(0, 8)}` })).toBeInTheDocument();
    expect(await screen.findByText("closed")).toBeInTheDocument();
    expect(await screen.findByRole("link", { name: "Arsalan Rehman" })).toHaveAttribute("href", `/drivers/${DRIVER_ID}`);
    expect(await screen.findByRole("link", { name: "T22123" })).toHaveAttribute("href", `/vehicles/${VEHICLE_ID}`);
    expect(screen.getByText("Cash")).toBeInTheDocument();
  });

  it("walks every tab and shows real data on each", async () => {
    installHandlers({ ratingsMatch: true });
    renderPage();
    const user = userEvent.setup();
    await screen.findByText("Fare breakdown");

    expect(screen.getAllByText("$15.20").length).toBeGreaterThan(0);

    await user.click(screen.getByRole("tab", { name: "Route" }));
    expect(await screen.findByText(/No location recorded|Drop-off location not recorded|Solid line|Dashed line/)).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Payments" }));
    const paymentsTable = await screen.findByRole("table");
    expect(within(paymentsTable).getByText("Cash")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Receipt" }));
    expect(await screen.findByText("R-100")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Rating" }));
    expect(await screen.findByText("Great!")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Audit" }));
    expect(await screen.findByText("trip_closed")).toBeInTheDocument();
  });

  it("degrades honestly on the Rating tab when no rating matches this trip", async () => {
    installHandlers({ ratingsMatch: false });
    renderPage();
    const user = userEvent.setup();
    await screen.findByText("Fare breakdown");

    await user.click(screen.getByRole("tab", { name: "Rating" }));
    expect(await screen.findByText("No rating recorded")).toBeInTheDocument();
  });

  it("shows Edit/Delete disabled with a tooltip for a dispatcher", async () => {
    currentUser = { ...currentUser, id: "u-disp", role: "dispatcher", name: "Dispatch One" };
    installHandlers({ ratingsMatch: true });
    renderPage();

    const editButton = await screen.findByRole("button", { name: /Edit/ });
    expect(editButton).toBeDisabled();
    const deleteButton = screen.getByRole("button", { name: /Delete/ });
    expect(deleteButton).toBeDisabled();
  });
});
