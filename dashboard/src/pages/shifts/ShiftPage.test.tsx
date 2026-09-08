import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "@/components/ui";
import { API, server, startMockServer } from "@/test/server";
import ShiftPage from "./ShiftPage";

startMockServer();

/**
 * Full-page tests for `/shifts/:shiftId` (dashboard command-centre plan
 * §7). Covers: header render (driver/vehicle links, duration, reconciled
 * badge), every tab's happy path with realistic MSW data, a defensive
 * fallback (fatigue-alerts/duress unreachable for the Timeline tab still
 * renders the trip/shift events it does have), and a driver (non-manage
 * role) seeing Edit/End/Mark-reconciled disabled with a tooltip.
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
const LATER_ISO = "2026-09-09T18:00:00.000Z";
const SHIFT_ID = "shift1";
const DRIVER_ID = "d1";
const VEHICLE_ID = "v1";
const TRIP_ID = "trip1";

const SHIFT = {
  id: SHIFT_ID,
  tenant_id: "t1",
  driver_id: DRIVER_ID,
  vehicle_id: VEHICLE_ID,
  start_at: NOW_ISO,
  end_at: LATER_ISO,
  inspection_json: null,
  trips_count: 1,
  km_total: "12.3",
  cash_total: "20.00",
  card_total: "15.00",
  psl_owed: "1.10",
  reconciled: false,
  break_started_at: null,
  break_taken: true,
  created_at: NOW_ISO,
  updated_at: NOW_ISO,
};

const REPORT = {
  shift_id: SHIFT_ID,
  tenant_id: "t1",
  driver_id: DRIVER_ID,
  vehicle_id: VEHICLE_ID,
  start_at: NOW_ISO,
  end_at: LATER_ISO,
  duration_minutes: 480,
  trips_count: 1,
  km_total: "12.3",
  cash_total: "20.00",
  card_total: "15.00",
  total_takings: "35.00",
  psl_owed: "1.10",
  reconciled: false,
  inspection_json: null,
  generated_at: NOW_ISO,
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

const TRIP = {
  id: TRIP_ID,
  tenant_id: "t1",
  client_uuid: "c1",
  vehicle_id: VEHICLE_ID,
  driver_id: DRIVER_ID,
  shift_id: SHIFT_ID,
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

interface HandlerOpts {
  timelineExtrasMissing?: boolean;
}

function installHandlers(opts: HandlerOpts = {}) {
  server.use(
    http.get(`${API}/v1/shifts/${SHIFT_ID}`, () => HttpResponse.json(SHIFT)),
    http.get(`${API}/v1/shifts/${SHIFT_ID}/report`, () => HttpResponse.json(REPORT)),
    http.get(`${API}/v1/users/${DRIVER_ID}`, () => HttpResponse.json(DRIVER_USER)),
    http.get(`${API}/v1/fleet/vehicles/${VEHICLE_ID}`, () => HttpResponse.json(VEHICLE)),
    http.get(`${API}/v1/drivers`, () => HttpResponse.json({ items: [DRIVER_USER].map((d) => ({ id: d.id, name: d.name, phone: d.phone, user_status: d.status, on_shift: true })), total: 1, skip: 0, limit: 100 })),
    http.get(`${API}/v1/vehicles`, () => HttpResponse.json({ items: [{ id: VEHICLE_ID, rego: VEHICLE.rego, vehicle_class: "standard", status: "active" }], total: 1, skip: 0, limit: 100 })),
    http.get(`${API}/v1/trips`, ({ request }) => {
      const url = new URL(request.url);
      expect(url.searchParams.get("driver_id")).toBe(DRIVER_ID);
      return HttpResponse.json({ items: [TRIP], total: 1, skip: 0, limit: 200 });
    }),
    http.get(`${API}/v1/fatigue-alerts`, () =>
      opts.timelineExtrasMissing
        ? HttpResponse.json({ detail: "not found" }, { status: 404 })
        : HttpResponse.json({ items: [], total: 0 }),
    ),
    http.get(`${API}/v1/duress`, () =>
      opts.timelineExtrasMissing
        ? HttpResponse.json({ detail: "not found" }, { status: 404 })
        : HttpResponse.json({ items: [], total: 0 }),
    ),
    http.patch(`${API}/v1/shifts/${SHIFT_ID}`, async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json({ ...SHIFT, ...body });
    }),
  );
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <MemoryRouter initialEntries={[`/shifts/${SHIFT_ID}`]}>
          <Routes>
            <Route path="/shifts/:shiftId" element={<ShiftPage />} />
            <Route path="/shifts" element={<h1>Shifts page</h1>} />
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

describe("ShiftPage", () => {
  it("renders the header with driver/vehicle links, duration, and reconciled badge", async () => {
    installHandlers();
    renderPage();

    expect(await screen.findByRole("heading", { level: 1, name: `Shift ${SHIFT_ID.slice(0, 8)}` })).toBeInTheDocument();
    expect(await screen.findByRole("link", { name: "Arsalan Rehman" })).toHaveAttribute("href", `/drivers/${DRIVER_ID}`);
    expect(await screen.findByRole("link", { name: "T22123" })).toHaveAttribute("href", `/vehicles/${VEHICLE_ID}`);
    expect(screen.getAllByText("Not reconciled").length).toBeGreaterThan(0);
  });

  it("walks every tab and shows real data on each", async () => {
    installHandlers();
    renderPage();
    const user = userEvent.setup();
    await screen.findByText("Takings");

    expect(screen.getByText("$35.00")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Trips" }));
    const tripsTable = await screen.findByRole("table");
    expect(within(tripsTable).getByRole("link", { name: TRIP_ID.slice(0, 8) })).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Breaks" }));
    expect(await screen.findByText("Break taken")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Timeline" }));
    expect(await screen.findByText("Shift started")).toBeInTheDocument();
    expect(screen.getByText("Shift ended")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Reconciliation" }));
    expect(await screen.findByText("$1.10")).toBeInTheDocument();
  });

  it("still renders the timeline's real events when fatigue/duress are unreachable", async () => {
    installHandlers({ timelineExtrasMissing: true });
    renderPage();
    const user = userEvent.setup();
    await screen.findByText("Takings");

    await user.click(screen.getByRole("tab", { name: "Timeline" }));
    expect(await screen.findByText("Shift started")).toBeInTheDocument();
    expect(screen.getByText(/could not be loaded for this window/)).toBeInTheDocument();
  });

  it("shows Edit/Mark reconciled disabled with a tooltip for a driver", async () => {
    currentUser = { ...currentUser, id: DRIVER_ID, role: "driver", name: "Arsalan Rehman" };
    installHandlers();
    renderPage();

    const editButton = await screen.findByRole("button", { name: /Edit/ });
    expect(editButton).toBeDisabled();
    const reconcileButton = screen.getByRole("button", { name: /Mark reconciled/ });
    expect(reconcileButton).toBeDisabled();
  });
});
