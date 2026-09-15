import { render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { API, server, startMockServer } from "@/test/server";
import OverviewPage from "./index";

startMockServer();

beforeEach(() => {
  // No access token: useFleetLiveSocket reports "error" and opens nothing, so
  // the page runs on its REST polls alone under test.
  localStorage.clear();
});

const NOW_ISO = new Date().toISOString();
const STALE_ISO = new Date(Date.now() - 60 * 60 * 1000).toISOString();

const VEHICLES = {
  items: [
    {
      id: "v1",
      tenant_id: "t1",
      rego: "T22123",
      vehicle_class: "sedan",
      vehicle_status: "active",
      device_id: "dev1",
      device_last_seen_at: NOW_ISO,
      battery: 15,
      network: "4g",
      lat: -33.86,
      lng: 151.2,
      speed_kmh: 42,
      heading: 90,
      live_status: "on_trip",
      position_updated_at: NOW_ISO,
      position_source: "live",
      current_trip_id: "trip1",
      current_driver_id: "d1",
      current_driver_name: "Arsalan",
      current_shift_id: "s1",
      current_shift_start_at: NOW_ISO,
      planned_dest_lat: null,
      planned_dest_lng: null,
    },
    {
      id: "v2",
      tenant_id: "t1",
      rego: "T99001",
      vehicle_class: "sedan",
      vehicle_status: "active",
      device_id: null,
      device_last_seen_at: null,
      battery: null,
      network: null,
      lat: null,
      lng: null,
      speed_kmh: null,
      heading: null,
      live_status: "available",
      position_updated_at: STALE_ISO,
      position_source: "none",
      current_trip_id: null,
      current_driver_id: null,
      current_driver_name: null,
      current_shift_id: null,
      current_shift_start_at: null,
      planned_dest_lat: null,
      planned_dest_lng: null,
    },
    {
      id: "v3",
      tenant_id: "t1",
      rego: "T55555",
      vehicle_class: "maxi",
      vehicle_status: "active",
      device_id: null,
      device_last_seen_at: null,
      battery: null,
      network: null,
      lat: null,
      lng: null,
      speed_kmh: null,
      heading: null,
      live_status: "offline",
      position_updated_at: null,
      position_source: "none",
      current_trip_id: null,
      current_driver_id: null,
      current_driver_name: null,
      current_shift_id: null,
      current_shift_start_at: null,
      planned_dest_lat: null,
      planned_dest_lng: null,
    },
  ],
  total: 3,
  skip: 0,
  limit: 100,
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
  tolls: "0",
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
    { ...TRIP_BASE, id: "trip1", status: "open", end_at: null, total: "0.00" },
    { ...TRIP_BASE, id: "trip2", status: "closed", end_at: NOW_ISO, total: "30.00" },
    { ...TRIP_BASE, id: "trip3", status: "closed", end_at: NOW_ISO, total: "12.50" },
  ],
  total: 3,
  skip: 0,
  limit: 200,
};

const DEVICES = {
  items: [
    {
      id: "dev1",
      tenant_id: "t1",
      android_id: "abcdef1234567890",
      model: "SM-T575",
      app_version: "1",
      vehicle_id: "v1",
      kiosk_locked: true,
      force_update_pending: false,
      locate_requested: false,
      reboot_requested: false,
      last_seen_at: NOW_ISO,
      battery: 15,
      network: "4g",
      paired_at: NOW_ISO,
      revoked_at: null,
      last_locate_lat: null,
      last_locate_lng: null,
      last_locate_accuracy_m: null,
      last_locate_at: null,
      command_acked_at: null,
      created_at: NOW_ISO,
      updated_at: NOW_ISO,
    },
    {
      id: "dev2",
      tenant_id: "t1",
      android_id: "0000000011111111",
      model: null,
      app_version: "1",
      vehicle_id: null,
      kiosk_locked: false,
      force_update_pending: true,
      locate_requested: false,
      reboot_requested: false,
      last_seen_at: STALE_ISO,
      battery: 90,
      network: "wifi",
      paired_at: NOW_ISO,
      revoked_at: null,
      last_locate_lat: null,
      last_locate_lng: null,
      last_locate_accuracy_m: null,
      last_locate_at: null,
      command_acked_at: null,
      created_at: NOW_ISO,
      updated_at: NOW_ISO,
    },
  ],
  total: 2,
  skip: 0,
  limit: 100,
};

const THIRTEEN_HOURS_AGO = new Date(Date.now() - 13 * 60 * 60 * 1000).toISOString();

const DURESS = {
  items: [
    {
      id: "e1",
      tenant_id: "t1",
      vehicle_id: "v1",
      driver_id: "d1",
      trigger: "button",
      status: "open",
      opened_at: NOW_ISO,
      closed_at: null,
      gps_stream_ref: "ref",
      audio_ref: null,
      escalation_log_json: {},
      created_at: NOW_ISO,
      updated_at: NOW_ISO,
    },
    {
      // Dispatched 13 hours ago and never closed: stale.
      id: "e-stale",
      tenant_id: "t1",
      vehicle_id: "v2",
      driver_id: "d-other",
      driver_name: "Benn Farid",
      trigger: "button",
      status: "dispatched",
      opened_at: THIRTEEN_HOURS_AGO,
      closed_at: null,
      gps_stream_ref: "ref",
      audio_ref: null,
      escalation_log_json: {},
      created_at: THIRTEEN_HOURS_AGO,
      updated_at: THIRTEEN_HOURS_AGO,
    },
  ],
  total: 2,
  limit: 50,
  offset: 0,
};

const FLAGGED_TRIPS = {
  items: [
    {
      ...TRIP_BASE,
      id: "trip-flag",
      status: "closed",
      end_at: STALE_ISO,
      total: "32.52",
      max_fare_check_passed: false,
      variance_pct: "44.90",
      flagged_for_review: true,
      review_notes: "Device charged 59.03 through the tunnel",
    },
  ],
  total: 1,
  skip: 0,
  limit: 100,
};

const SHIFTS = {
  items: [
    {
      id: "s-todo",
      tenant_id: "t1",
      driver_id: "d1",
      vehicle_id: "v1",
      start_at: STALE_ISO,
      end_at: STALE_ISO,
      inspection_json: null,
      trips_count: 4,
      km_total: "0",
      cash_total: "0",
      card_total: "0",
      psl_owed: "0",
      reconciled: false,
      break_started_at: null,
      break_taken: false,
      created_at: STALE_ISO,
      updated_at: STALE_ISO,
    },
    {
      id: "s-open",
      tenant_id: "t1",
      driver_id: "d1",
      vehicle_id: "v1",
      start_at: NOW_ISO,
      end_at: null,
      inspection_json: null,
      trips_count: 0,
      km_total: "0",
      cash_total: "0",
      card_total: "0",
      psl_owed: "0",
      reconciled: false,
      break_started_at: null,
      break_taken: false,
      created_at: NOW_ISO,
      updated_at: NOW_ISO,
    },
  ],
  total: 2,
  limit: 100,
  offset: 0,
};

/** `YYYY-MM-DD` of an instant in the local calendar (the one the page counts in). */
function localDate(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

const FLEET_VEHICLES = {
  items: [
    {
      id: "v1",
      tenant_id: "t1",
      rego: "T22123",
      vin: null,
      make: null,
      model: null,
      vehicle_class: "standard",
      camera_serial: null,
      tracking_device_id: null,
      meter_device_id: null,
      status: "active",
      registration_expiry: localDate(Date.now() + 10 * 24 * 60 * 60 * 1000),
      insurance_expiry: null,
      created_at: NOW_ISO,
      updated_at: NOW_ISO,
    },
  ],
  total: 1,
  skip: 0,
  limit: 100,
};

function installHandlers(opts: { revenueFails?: boolean } = {}) {
  server.use(
    http.get(`${API}/v1/vehicles`, () => HttpResponse.json(VEHICLES)),
    http.get(`${API}/v1/trips`, ({ request }) => {
      // The attention queue asks for flagged trips; the KPI strip for today's.
      const flagged = new URL(request.url).searchParams.get("flagged_for_review") === "true";
      return HttpResponse.json(flagged ? FLAGGED_TRIPS : TRIPS);
    }),
    http.get(`${API}/v1/shifts`, () => HttpResponse.json(SHIFTS)),
    http.get(`${API}/v1/fleet/vehicles`, () => HttpResponse.json(FLEET_VEHICLES)),
    http.get(`${API}/v1/drivers`, () =>
      HttpResponse.json({ items: [{ id: "d1", name: "Arsalan", phone: null, user_status: "active", on_shift: true }], total: 1, skip: 0, limit: 100 }),
    ),
    http.get(`${API}/v1/fleet/devices`, () => HttpResponse.json(DEVICES)),
    http.get(`${API}/v1/duress`, () => HttpResponse.json(DURESS)),
    http.get(`${API}/v1/tenants/me`, () => HttpResponse.json({ id: "t1", name: "Test Tenant", theme_json: null })),
    http.get(`${API}/v1/reports/revenue`, () =>
      opts.revenueFails
        ? HttpResponse.json({ detail: "boom" }, { status: 500 })
        : HttpResponse.json({
            tenant_id: "t1",
            from_date: "2026-09-08",
            to_date: "2026-09-08",
            group_by: "day",
            note: "",
            groups: [],
            totals: {
              trip_count: 2,
              gross_revenue: "42.50",
              subtotal: "0",
              surcharge: "0",
              gst_component: "0",
              tolls: "0",
              psl: "0",
              extras: "0",
            },
          }),
    ),
  );
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/"]}>
        <Routes>
          <Route path="/" element={<OverviewPage />} />
          <Route path="/live-map" element={<h1>Live map page</h1>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("OverviewPage", () => {
  it("shows live fleet counts, today's trips and revenue, open duress and tablets needing attention", async () => {
    installHandlers();
    renderPage();

    expect(await screen.findByRole("link", { name: "On trip: 1" })).toHaveAttribute("href", "/live-map");
    expect(screen.getByRole("link", { name: "Available: 1" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "On break: 0" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Offline: 1" })).toBeInTheDocument();
    // v2 is available but its last position is an hour old; v3 is offline and
    // is not double-counted as stale.
    expect(screen.getByRole("link", { name: "Stale signal: 1" })).toBeInTheDocument();

    expect(await screen.findByRole("link", { name: "Trips today: 3" })).toHaveAttribute("href", "/trips");
    expect(screen.getByText("1 open · 2 closed")).toBeInTheDocument();

    expect(await screen.findByRole("link", { name: "Revenue today: $42.50" })).toBeInTheDocument();
    expect(screen.getByText("Revenue report, gross")).toBeInTheDocument();

    expect(await screen.findByRole("link", { name: "Open duress: 2" })).toHaveAttribute("href", "/duress");

    // dev1: battery 15%. dev2: no heartbeat for an hour AND a force-update
    // pending -- one tablet, counted once.
    expect(await screen.findByRole("link", { name: "Tablets needing attention: 2" })).toHaveAttribute("href", "/fleet");
    expect(screen.getByText("1 low battery · 1 offline · 1 update pending")).toBeInTheDocument();
  });

  it("falls back to summing today's closed trips when the revenue report is unavailable", async () => {
    installHandlers({ revenueFails: true });
    renderPage();

    expect(await screen.findByRole("link", { name: "Revenue today: $42.50" })).toBeInTheDocument();
    expect(screen.getByText("Sum of today's closed trips (report unavailable)")).toBeInTheDocument();
  });

  it("lists every vehicle with driver, status, speed, battery and position age", async () => {
    installHandlers();
    renderPage();

    const table = await screen.findByRole("table", { name: "Fleet right now" });
    // The plain-SVG map fallback labels its markers with the rego too, so
    // look inside the table only.
    const rego = await within(table).findByText("T22123");
    const row = rego.closest("tr");
    expect(row).not.toBeNull();
    expect(within(row as HTMLElement).getByText("Arsalan")).toBeInTheDocument();
    expect(within(row as HTMLElement).getByText("on_trip")).toBeInTheDocument();
    expect(within(row as HTMLElement).getByText("42 km/h")).toBeInTheDocument();
    expect(within(row as HTMLElement).getByText("15%")).toBeInTheDocument();
    expect(within(table).getByText("T99001")).toBeInTheDocument();
    expect(within(table).getByText("T55555")).toBeInTheDocument();
  });

  it("lists what needs attention, each row linking into the page with its filter applied", async () => {
    installHandlers();
    renderPage();

    const flagged = await screen.findByRole("region", { name: "Flagged trips" });
    expect(within(flagged).getByRole("link", { name: /Flagged trips/ })).toHaveAttribute("href", "/trips?review=flagged");
    // The group card renders before its query resolves (a loading group still shows its
    // heading), so the row itself has to be awaited.
    const flaggedRow = await within(flagged).findByRole("link", { name: /Trip trip-fla/ });
    expect(flaggedRow).toHaveAttribute("href", "/trips/trip-flag");
    expect(flaggedRow).toHaveTextContent("T22123 · Arsalan");
    expect(flaggedRow).toHaveTextContent("Device charged 59.03 through the tunnel");

    const duress = screen.getByRole("region", { name: "Stale duress events" });
    expect(within(duress).getByRole("link", { name: /Stale duress events/ })).toHaveAttribute("href", "/duress?status=open");
    const staleRow = within(duress).getByRole("link", { name: /Benn Farid/ });
    expect(staleRow).toHaveAttribute("href", "/duress?event=e-stale&status=open");
    expect(staleRow).toHaveTextContent("T99001 — Benn Farid");
    expect(staleRow).toHaveTextContent("dispatched for 13h");
    // The fresh event is not on the list.
    expect(within(duress).getAllByRole("listitem")).toHaveLength(1);

    const shifts = screen.getByRole("region", { name: "Unreconciled shifts" });
    expect(within(shifts).getByRole("link", { name: /Unreconciled shifts/ })).toHaveAttribute("href", "/shifts?reconciled=false");
    const shiftRow = within(shifts).getByRole("link", { name: /4 trips/ });
    expect(shiftRow).toHaveAttribute("href", "/shifts/s-todo");
    // The still-open shift is not "unreconciled" yet.
    expect(within(shifts).getAllByRole("listitem")).toHaveLength(1);

    const tablets = screen.getByRole("region", { name: "Tablets needing attention" });
    expect(within(tablets).getByRole("link", { name: /Tablets needing attention/ })).toHaveAttribute("href", "/fleet?tab=devices");
    expect(within(tablets).getByRole("link", { name: /Battery 15%/ })).toHaveAttribute("href", "/devices/dev1");
    expect(within(tablets).getByRole("link", { name: /Update pending/ })).toHaveAttribute("href", "/devices/dev2");

    const documents = screen.getByRole("region", { name: "Documents expiring within 30 days" });
    expect(within(documents).getByRole("link", { name: /Documents expiring/ })).toHaveAttribute("href", "/compliance");
    const docRow = within(documents).getByRole("link", { name: /Registration expires in 10 days/ });
    expect(docRow).toHaveAttribute("href", "/vehicles/v1");
  });

  it("says so when nothing needs attention", async () => {
    installHandlers();
    server.use(
      http.get(`${API}/v1/trips`, ({ request }) => {
        const flagged = new URL(request.url).searchParams.get("flagged_for_review") === "true";
        return HttpResponse.json(flagged ? { items: [], total: 0, skip: 0, limit: 100 } : TRIPS);
      }),
      http.get(`${API}/v1/shifts`, () => HttpResponse.json({ items: [], total: 0, limit: 100, offset: 0 })),
      http.get(`${API}/v1/fleet/vehicles`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 100 })),
      http.get(`${API}/v1/fleet/devices`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 100 })),
      http.get(`${API}/v1/duress`, () => HttpResponse.json({ items: [DURESS.items[0]], total: 1, limit: 50, offset: 0 })),
    );
    renderPage();

    expect(await screen.findByText("Nothing needs attention right now.")).toBeInTheDocument();
  });

  it("shows the socket state and an honest empty activity feed on first load", async () => {
    installHandlers();
    renderPage();

    // No token under test, so the socket never opens.
    expect(screen.getByRole("status", { name: "Live feed status" })).toHaveTextContent("Reconnecting");
    await screen.findByRole("link", { name: "On trip: 1" });
    expect(screen.getByText("Nothing has changed yet")).toBeInTheDocument();
  });
});
