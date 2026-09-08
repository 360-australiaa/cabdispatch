import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "@/components/ui";
import { API, server, startMockServer } from "@/test/server";
import VehiclePage from "./VehiclePage";

startMockServer();

const NOW_ISO = new Date().toISOString();

const OWNER = {
  id: "u-owner",
  tenant_id: "t1",
  role: "owner",
  name: "Owner",
  email: "owner@example.com",
  status: "active",
  mfa_enabled: false,
};
const DISPATCHER = { ...OWNER, id: "u-dispatch", role: "dispatcher", name: "Dispatcher" };

let currentUser: typeof OWNER = OWNER;
vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ user: currentUser, tenant: null }),
}));

const VEHICLE = {
  id: "v1",
  tenant_id: "t1",
  rego: "T22123",
  vin: "VIN123",
  make: "Toyota",
  model: "Camry",
  vehicle_class: "standard",
  camera_serial: null,
  tracking_device_id: null,
  meter_device_id: null,
  status: "active",
  registration_expiry: "2027-01-01",
  insurance_expiry: "0028-02-09", // the real "year 0028" data bug the plan flags
  created_at: NOW_ISO,
  updated_at: NOW_ISO,
};

const VEHICLE_LIVE = {
  id: "v1",
  tenant_id: "t1",
  rego: "T22123",
  vehicle_class: "standard",
  vehicle_status: "active",
  device_id: "dev1",
  device_last_seen_at: NOW_ISO,
  battery: 80,
  network: "wifi",
  lat: -33.86,
  lng: 151.2,
  speed_kmh: 0,
  heading: null,
  live_status: "available",
  position_updated_at: NOW_ISO,
  position_source: "live",
  current_trip_id: null,
  current_driver_id: "d1",
  current_driver_name: "Arsalan",
  current_shift_id: "s1",
  current_shift_start_at: NOW_ISO,
  planned_dest_lat: null,
  planned_dest_lng: null,
};

const DEVICE = {
  id: "dev1",
  tenant_id: "t1",
  android_id: "abcdef1234567890",
  model: "SM-T575",
  app_version: "1.0",
  vehicle_id: "v1",
  kiosk_locked: false,
  force_update_pending: false,
  locate_requested: false,
  reboot_requested: false,
  last_seen_at: NOW_ISO,
  battery: 80,
  network: "wifi",
  calibration_due: null,
  paired_at: NOW_ISO,
  revoked_at: null,
  last_locate_lat: null,
  last_locate_lng: null,
  last_locate_accuracy_m: null,
  last_locate_at: null,
  command_acked_at: null,
  last_acked_command: null,
  created_at: NOW_ISO,
  updated_at: NOW_ISO,
};

const TRIP_BASE = {
  tenant_id: "t1",
  client_uuid: "c",
  vehicle_id: "v1",
  driver_id: "d1",
  shift_id: null,
  tariff_id: "tar",
  type: "rank_hail",
  simulated: false,
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
  start_lat: 0,
  start_lng: 0,
  end_lat: null,
  end_lng: null,
  distance_m: 0,
  moving_s: 0,
  waiting_s: 0,
  flag_fall: "0",
  dist_amount: "0",
  wait_amount: "0",
  peak_amount: "0",
  psl: "0",
  extras: "0",
  subtotal: "0",
  surcharge: "0",
  gst_component: "0",
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

const TRIPS = {
  items: [
    {
      ...TRIP_BASE,
      id: "trip-airport",
      status: "closed",
      end_at: NOW_ISO,
      total: "20.43",
      tolls: "6.43",
      auto_tolls_applied: ["geo-airport-1"],
      auto_tolled_roads: {},
    },
    {
      ...TRIP_BASE,
      id: "trip-m2",
      status: "closed",
      end_at: NOW_ISO,
      total: "30.10",
      tolls: "8.10",
      auto_tolls_applied: [],
      auto_tolled_roads: { "toll-m2": "8.10" },
    },
    {
      ...TRIP_BASE,
      id: "trip-manual",
      status: "closed",
      end_at: NOW_ISO,
      total: "15.00",
      tolls: "5.00",
      auto_tolls_applied: [],
      auto_tolled_roads: {},
    },
  ],
  total: 3,
  skip: 0,
  limit: 100,
};

const SHIFT_HISTORY = {
  items: [
    { shift_id: "shift-1", driver_id: "d1", driver_name: "Arsalan", start_at: NOW_ISO, end_at: null, distance_km: "42.5", fare_total: "120.00" },
  ],
  total: 1,
  skip: 0,
  limit: 100,
};

const GEOFENCES = {
  items: [
    { id: "geo-airport-1", tenant_id: null, name: "Sydney Airport T1", kind: "airport", center_lat: -33.9, center_lng: 151.18, radius_m: 400, toll_amount: "6.43", created_at: NOW_ISO, updated_at: NOW_ISO },
  ],
  total: 1,
  skip: 0,
  limit: 200,
};

const TOLL_ROADS = [
  { id: "toll-m2", api_code: "M2", name: "Hills M2", operator: "Transurban", pricing_model: "flat", charging_policy: "once_per_road", network_group: null, directional: "both", description: null, derived_corridor_km: null, source_note: null, gantry_count: 1, current_price: null, toll_points: [] },
];

const DOSSIER = {
  tenant_id: "t1",
  vehicle_id: "v1",
  generated_at: NOW_ISO,
  items: [
    { key: "registration", label: "Vehicle registration", doc_types: ["cl14_checklist"], satisfied: true, document_count: 1, documents: [] },
    { key: "camera", label: "In-vehicle camera register", doc_types: ["camera_register"], satisfied: false, document_count: 0, documents: [] },
  ],
  overall_compliant: false,
  missing_items: ["camera"],
};

const COMPLIANCE_EXPIRY = {
  items: [
    { entity_type: "vehicle", entity_id: "v1", label: "T22123", field: "insurance_expiry", expiry_date: "0028-02-09", status: "expired", days_remaining: -1 },
  ],
  total: 1,
  skip: 0,
  limit: 3650,
};

const LIFETIME_TOTALS = {
  vehicle_id: "v1",
  trip_count: 42,
  total_fares: "1234.50",
  total_psl: "42.00",
  total_tolls: "19.53",
  total_tips: null,
  total_km: "980",
  generated_at: NOW_ISO,
};

function installHandlers() {
  server.use(
    http.get(`${API}/v1/fleet/vehicles/v1`, () => HttpResponse.json(VEHICLE)),
    http.get(`${API}/v1/vehicles/v1`, () => HttpResponse.json(VEHICLE_LIVE)),
    http.get(`${API}/v1/fleet/devices/dev1`, () => HttpResponse.json(DEVICE)),
    http.get(`${API}/v1/compliance/vehicles/v1/dossier`, () => HttpResponse.json(DOSSIER)),
    http.get(`${API}/v1/vehicles/v1/position-history`, () =>
      HttpResponse.json({ items: [], harsh_brake_events: 2, rapid_accel_events: 1, threshold_kmh_per_s: 8 }),
    ),
    http.get(`${API}/v1/geofences`, () => HttpResponse.json(GEOFENCES)),
    http.get(`${API}/v1/duress`, () => HttpResponse.json({ items: [], total: 0, limit: 10, offset: 0 })),
    http.get(`${API}/v1/trips`, () => HttpResponse.json(TRIPS)),
    http.get(`${API}/v1/fleet/vehicles/v1/shift-history`, () => HttpResponse.json(SHIFT_HISTORY)),
    http.get(`${API}/v1/fleet/compliance-expiry`, () => HttpResponse.json(COMPLIANCE_EXPIRY)),
    http.get(`${API}/v1/compliance/documents`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 50 })),
    http.get(`${API}/v1/fleet/vehicles/v1/lifetime-totals`, () => HttpResponse.json(LIFETIME_TOTALS)),
    http.get(`${API}/v1/fleet/vehicles/v1/pilot-report`, () =>
      HttpResponse.json({
        vehicle_id: "v1",
        from_date: "2026-08-01",
        to_date: "2026-09-01",
        trip_count: 5,
        avg_fare_accuracy_variance_pct: "1.2",
        device_uptime_estimate_pct: "99.0",
        duress_test_activation_count: null,
        duress_event_count_total: 0,
        flagged_for_review_count: 0,
        generated_at: NOW_ISO,
      }),
    ),
    http.get(`${API}/v1/toll-roads`, () => HttpResponse.json(TOLL_ROADS)),
    http.get(`${API}/v1/audit-log`, () => HttpResponse.json({ items: [], total: 0, limit: 25, offset: 0 })),
  );
}

beforeEach(() => {
  localStorage.clear();
  currentUser = OWNER;
  installHandlers();
});

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <MemoryRouter initialEntries={["/vehicles/v1"]}>
          <Routes>
            <Route path="/vehicles/:vehicleId" element={<VehiclePage />} />
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

describe("VehiclePage", () => {
  it("renders the header with the vehicle's real rego, badges and facts", async () => {
    renderPage();

    expect(await screen.findByRole("heading", { level: 1, name: "T22123" })).toBeInTheDocument();
    expect(screen.getByText("Standard")).toBeInTheDocument();
    expect(screen.getByText("active")).toBeInTheDocument();
    expect(screen.getByText("available")).toBeInTheDocument();
    expect(screen.getByText("Toyota Camry")).toBeInTheDocument();
    expect(screen.getByText("Arsalan")).toBeInTheDocument();
    expect(screen.getByText("abcdef1234567890")).toBeInTheDocument();
  });

  it("Live tab shows the driving-signals readout, captioned as informational", async () => {
    renderPage();
    expect(await screen.findByText(/2 events? \(delta greater than 8 km\/h\/s\)/)).toBeInTheDocument();
    expect(screen.getByText(/informational telematics signal, not a certified safety score/i)).toBeInTheDocument();
  });

  it("Trips tab lists this vehicle's trips with an entity link and a tolls column", async () => {
    renderPage();
    await userEvent.click(await screen.findByRole("tab", { name: "Trips" }));

    expect(await screen.findByRole("link", { name: "trip-air" })).toBeInTheDocument();
    expect(screen.getByText("$6.43")).toBeInTheDocument();
    expect(screen.getByText("$8.10")).toBeInTheDocument();
  });

  it("Shifts tab lists shift history with driver/shift links and distance/fare columns", async () => {
    renderPage();
    await userEvent.click(await screen.findByRole("tab", { name: "Shifts" }));

    expect(await screen.findByRole("link", { name: "shift-1" })).toBeInTheDocument();
    // "Arsalan" also names the header's current-driver EntityLink, which
    // stays mounted across tab switches -- at least one more instance
    // (the shift row's own driver link) confirms the table rendered.
    expect(screen.getAllByRole("link", { name: "Arsalan" }).length).toBeGreaterThan(1);
    expect(screen.getByText("42.5 km")).toBeInTheDocument();
  });

  it("Compliance tab flags the implausible expiry year and shows the dossier checklist", async () => {
    renderPage();
    await userEvent.click(await screen.findByRole("tab", { name: "Compliance" }));

    expect(await screen.findByText("Implausible date")).toBeInTheDocument();
    expect(screen.getByText("In-vehicle camera register")).toBeInTheDocument();
    expect(screen.getByText("Missing")).toBeInTheDocument();
  });

  it("Reports tab shows lifetime totals ported from VehicleReportsModal", async () => {
    renderPage();
    await userEvent.click(await screen.findByRole("tab", { name: "Reports" }));

    expect(await screen.findByText("42")).toBeInTheDocument();
    expect(screen.getByText("$1,234.50")).toBeInTheDocument();
  });

  it("Tolls tab splits the airport access fee from the NSW toll-road charge", async () => {
    renderPage();
    await userEvent.click(await screen.findByRole("tab", { name: "Tolls" }));

    expect(await screen.findByText("Airport access fees")).toBeInTheDocument();
    expect(screen.getByText("$6.43")).toBeInTheDocument();
    expect(await screen.findByText("Hills M2")).toBeInTheDocument();
    expect(screen.getByText("$8.10")).toBeInTheDocument();
    expect(screen.getByText("Other / unitemised")).toBeInTheDocument();
    expect(screen.getByText("$5.00")).toBeInTheDocument();
  });

  it("Activity tab shows an honest empty state (vehicle mutations aren't audited yet)", async () => {
    renderPage();
    await userEvent.click(await screen.findByRole("tab", { name: "Activity" }));

    expect(await screen.findByText("No audit-log entries")).toBeInTheDocument();
    expect(screen.getByText(/not currently written to the audit trail/i)).toBeInTheDocument();
  });

  it("defensive fallback: disables the Compliance dossier action when the dossier endpoint fails", async () => {
    server.use(http.get(`${API}/v1/compliance/vehicles/v1/dossier`, () => HttpResponse.json({ detail: "not found" }, { status: 404 })));
    renderPage();

    const button = await screen.findByRole("button", { name: /compliance dossier/i });
    await waitFor(() => expect(button).toBeDisabled());
  });

  it("a non-owner sees the Delete action disabled", async () => {
    currentUser = DISPATCHER;
    renderPage();

    const deleteButton = await screen.findByRole("button", { name: /delete/i });
    expect(deleteButton).toBeDisabled();
  });

  it("an owner sees the Delete action enabled", async () => {
    currentUser = OWNER;
    renderPage();

    const deleteButton = await screen.findByRole("button", { name: /delete/i });
    await waitFor(() => expect(deleteButton).not.toBeDisabled());
  });
});
