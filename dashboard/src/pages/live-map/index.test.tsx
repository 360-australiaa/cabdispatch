import { render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { API, server, startMockServer } from "@/test/server";
import LiveMapPage from "./index";

startMockServer();

/**
 * The two panel fixes from the admin-panel plan (§1.4, §2): the Vehicles
 * table says where each position came from (Live / Estimated / Stale / No
 * fix) with the fix age, and the "Active duress events" panel names the
 * driver instead of printing a UUID -- from the event's own `driver_name`
 * when the API sends one, else from the vehicle's current driver.
 *
 * No VITE_MAPBOX_TOKEN under test, so the map is the plain-SVG fallback; no
 * access token, so the live socket never opens and the page runs on REST.
 */

vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ user: { id: "u1", role: "dispatcher", name: "Dispatch One" } }),
}));

const NOW_ISO = new Date().toISOString();
const STALE_ISO = new Date(Date.now() - 5 * 60 * 1000).toISOString();
const THIRTEEN_HOURS_AGO = new Date(Date.now() - 13 * 60 * 60 * 1000).toISOString();

function vehicle(overrides: Record<string, unknown>) {
  return {
    tenant_id: "t1",
    vehicle_class: "sedan",
    vehicle_status: "active",
    device_id: null,
    device_last_seen_at: null,
    battery: null,
    network: null,
    speed_kmh: null,
    heading: null,
    current_trip_id: null,
    current_driver_id: null,
    current_driver_name: null,
    current_shift_id: null,
    current_shift_start_at: null,
    planned_dest_lat: null,
    planned_dest_lng: null,
    ...overrides,
  };
}

const VEHICLES = [
  vehicle({
    id: "v-live",
    rego: "LIVE01",
    lat: -33.86,
    lng: 151.2,
    live_status: "on_trip",
    position_updated_at: NOW_ISO,
    position_source: "live",
    current_driver_id: "d-arsalan",
    current_driver_name: "Arsalan Rehman",
  }),
  vehicle({
    id: "v-est",
    rego: "TUNNEL1",
    lat: -33.87,
    lng: 151.21,
    live_status: "on_trip",
    position_updated_at: NOW_ISO,
    position_source: "estimated",
  }),
  vehicle({
    id: "v-stale",
    rego: "STALE1",
    lat: -33.88,
    lng: 151.22,
    live_status: "available",
    position_updated_at: STALE_ISO,
    position_source: "trip",
  }),
  vehicle({
    id: "v-none",
    rego: "NOFIX1",
    lat: null,
    lng: null,
    live_status: "offline",
    position_updated_at: null,
    position_source: "none",
  }),
];

const DURESS = {
  items: [
    {
      id: "e-named",
      tenant_id: "t1",
      vehicle_id: "v-est",
      driver_id: "d-named-uuid-0000",
      driver_name: "Benn Named",
      stale: false,
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
      // No driver_name from the API: resolved through the vehicle's current driver.
      id: "e-lookup",
      tenant_id: "t1",
      vehicle_id: "v-live",
      driver_id: "d-arsalan",
      trigger: "gesture",
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

function installHandlers() {
  server.use(
    http.get(`${API}/v1/vehicles`, () =>
      HttpResponse.json({ items: VEHICLES, total: VEHICLES.length, skip: 0, limit: 100 }),
    ),
    http.get(`${API}/v1/duress`, () => HttpResponse.json(DURESS)),
    http.get(`${API}/v1/fleet/devices`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 100 })),
    http.get(`${API}/v1/geofences`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 200 })),
    http.get(`${API}/v1/fleet/compliance-expiry`, () =>
      HttpResponse.json({ items: [], total: 0, skip: 0, limit: 100 }),
    ),
    http.get(`${API}/v1/tenants/me`, () => HttpResponse.json({ id: "t1", name: "Test Tenant", theme_json: null })),
  );
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/live-map"]}>
        <LiveMapPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  localStorage.clear();
  installHandlers();
});

/** The row of the Vehicles table that names `rego`. The rego also appears in
 * the locate list and on the plain-SVG map, so only a table cell counts. */
async function vehicleRow(rego: string): Promise<HTMLElement> {
  const cells = await screen.findAllByText(rego, { selector: "td *" });
  const row = cells[0]?.closest("tr");
  if (!row) throw new Error(`no table row for ${rego}`);
  return row;
}

describe("LiveMapPage vehicles table", () => {
  it("badges each position by source and shows the fix age", async () => {
    renderPage();

    const live = await vehicleRow("LIVE01");
    expect(within(live).getByText("Live")).toBeInTheDocument();
    expect(within(live).getByText("Live")).toHaveAttribute("title", expect.stringContaining("live position feed"));
    expect(within(live).getAllByText("just now").length).toBeGreaterThan(0);

    const estimated = await vehicleRow("TUNNEL1");
    expect(within(estimated).getByText("Estimated")).toBeInTheDocument();

    const stale = await vehicleRow("STALE1");
    expect(within(stale).getByText("Stale")).toBeInTheDocument();
    expect(within(stale).getAllByText("5m ago").length).toBeGreaterThan(0);

    const none = await vehicleRow("NOFIX1");
    expect(within(none).getByText("No fix")).toBeInTheDocument();
  });
});

describe("LiveMapPage active duress panel", () => {
  it("names the driver from the event, else from the vehicle's current driver, never a bare UUID", async () => {
    renderPage();

    expect(await screen.findByText("Benn Named")).toBeInTheDocument();
    const lookedUp = await screen.findByText("Arsalan Rehman", { selector: "td span[title]" });
    expect(lookedUp).toHaveAttribute("title", "d-arsalan");
    expect(screen.queryByText("d-named-uuid-0000")).not.toBeInTheDocument();
  });

  it("marks an event open for more than 12 hours as stale", async () => {
    renderPage();

    const dispatched = await screen.findByText("dispatched");
    const row = dispatched.closest("tr") as HTMLElement;
    expect(within(row).getByText("Stale")).toBeInTheDocument();

    const open = screen.getByText("open");
    expect(within(open.closest("tr") as HTMLElement).queryByText("Stale")).not.toBeInTheDocument();
  });
});
