import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "@/components/ui";
import { API, server, startMockServer } from "@/test/server";
import type { Trip } from "@/hooks/useTrips";
import TripPage from "./TripPage";

startMockServer();

/**
 * Full-page tests for `/trips/:tripId` (dashboard command-centre plan §7).
 * Covers: header render (driver/vehicle links, status, payment method),
 * every tab's happy path with realistic MSW data, a defensive fallback
 * (a trip with no rating: the Rating tab must degrade to an honest "no
 * rating recorded" rather than crash), and a non-owner
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

const TRIP: Trip = {
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
  gps_blackout_events: null,
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

const DEVICE_SEGMENT = {
  client_uuid: "seg-1",
  started_at: "2026-09-09T10:00:00.000Z",
  ended_at: "2026-09-09T10:02:30.000Z",
  entry_lat: -33.865,
  entry_lng: 151.205,
  exit_lat: -33.868,
  exit_lng: 151.208,
  entry_was_moving: true,
  resolution: "INERTIAL",
  billed_distance_km: "1.42",
  corridor_road_id: null,
  estimated_distance_km: "1.50",
  reference_distance_km: "1.42",
  correction_km: "-0.08",
  reference_source: "road_path",
  confidence: "high",
  zupt_count: 2,
};

interface HandlerOpts {
  ratingsMatch?: boolean;
  tripOverrides?: Partial<typeof TRIP>;
  tollRoads?: Array<{ id: string; name: string; toll_points: Array<{ id: string; name: string }> }>;
  /** Captures the body of a fare-correction POST; the response is the trip with the new total. */
  onFareCorrection?: (body: unknown) => void;
}

function installHandlers(opts: HandlerOpts = {}) {
  server.use(
    http.get(`${API}/v1/trips/${TRIP_ID}`, () => HttpResponse.json({ ...TRIP, ...opts.tripOverrides })),
    http.get(`${API}/v1/trips/${TRIP_ID}/gps-trace`, () =>
      HttpResponse.json({ trip_id: TRIP_ID, points: [], point_count: 0 }),
    ),
    http.post(`${API}/v1/trips/${TRIP_ID}/fare-correction`, async ({ request }) => {
      const body = (await request.json()) as { total: string; reason: string };
      opts.onFareCorrection?.(body);
      return HttpResponse.json({ ...TRIP, ...opts.tripOverrides, total: body.total });
    }),
    http.get(`${API}/v1/users/${DRIVER_ID}`, () => HttpResponse.json(DRIVER_USER)),
    http.get(`${API}/v1/fleet/vehicles/${VEHICLE_ID}`, () => HttpResponse.json(VEHICLE)),
    http.get(`${API}/v1/vehicles`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 100 })),
    http.get(`${API}/v1/drivers`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 100 })),
    http.get(`${API}/v1/tariffs`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 200 })),
    http.get(`${API}/v1/toll-roads`, () => HttpResponse.json(opts.tollRoads ?? [])),
    http.get(`${API}/v1/geofences`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 200 })),
    http.get(`${API}/v1/payments`, ({ request }) => {
      const url = new URL(request.url);
      expect(url.searchParams.get("trip_id")).toBe(TRIP_ID);
      return HttpResponse.json({ items: [PAYMENT], total: 1, skip: 0, limit: 50 });
    }),
    // The Rating tab must ask the SERVER for this trip's rating
    // (GET /v1/ratings?trip_id=, added 2026-09-19) rather than pull a page of
    // the driver's ratings and match in the browser -- the old client-side
    // match silently reported "No rating recorded" once a rating fell past
    // the 200-row fetch. This handler therefore behaves like the endpoint:
    // it filters on the trip_id it is given, so a tab that forgot to send it,
    // or sent the wrong one, gets an empty page and fails the assertions.
    http.get(`${API}/v1/ratings`, ({ request }) => {
      const url = new URL(request.url);
      expect(url.searchParams.get("driver_id")).toBe(DRIVER_ID);
      const requestedTripId = url.searchParams.get("trip_id");
      expect(requestedTripId).toBe(TRIP_ID);
      const rows = opts.ratingsMatch
        ? [{ id: "r1", tenant_id: "t1", trip_id: TRIP_ID, driver_id: DRIVER_ID, stars: 5, comment: "Great!", created_at: NOW_ISO }]
        : [{ id: "r2", tenant_id: "t1", trip_id: "some-other-trip", driver_id: DRIVER_ID, stars: 3, comment: null, created_at: NOW_ISO }];
      const items = rows.filter((row) => row.trip_id === requestedTripId);
      return HttpResponse.json({ items, total: items.length, skip: 0, limit: 200 });
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

  it("shows the GPS blackout audit trail on the Fare tab when the trip has one", async () => {
    installHandlers({
      ratingsMatch: true,
      tripOverrides: {
        gps_blackout_events: [
          { start: "2026-09-09T10:00:00.000Z", end: "2026-09-09T10:01:30.000Z", elapsed_s: 90, matched_km: "1.23" },
          { start: "2026-09-09T10:05:00.000Z", end: "2026-09-09T10:06:00.000Z", elapsed_s: 60, matched_km: null },
        ],
      },
    });
    renderPage();
    await screen.findByText("Fare breakdown");

    expect(screen.getByText("GPS blackouts")).toBeInTheDocument();
    expect(screen.getByText("1.23 km via known corridor")).toBeInTheDocument();
    expect(screen.getByText("No known corridor — billed $0 for this gap")).toBeInTheDocument();
  });

  it("lists the meter's own blackout segments with the inertial figures next to the server's account", async () => {
    installHandlers({
      ratingsMatch: true,
      tripOverrides: {
        gps_blackout_events: [
          { start: "2026-09-09T10:00:00.000Z", end: "2026-09-09T10:02:30.000Z", elapsed_s: 150, matched_km: null },
        ],
        device_gps_blackout_segments: [DEVICE_SEGMENT],
        blackout_reconciliation: [{ type: "inertial_correction_large", correction_km: "-0.08" }],
      },
    });
    renderPage();
    await screen.findByText("Fare breakdown");

    const table = screen.getByRole("table", { name: "GPS blackouts" });
    expect(within(table).getByText("Meter")).toBeInTheDocument();
    expect(within(table).getByText("Server")).toBeInTheDocument();
    expect(within(table).getByText("Inertial dead-reckoning")).toBeInTheDocument();
    // Both accounts recorded the same 150s gap -- one row each.
    expect(within(table).getAllByText("2m 30s")).toHaveLength(2);
    expect(within(table).getByText("1.42 km")).toBeInTheDocument();
    expect(within(table).getByText("1.50 km / 1.42 km")).toBeInTheDocument();
    expect(within(table).getByText("-0.08 km")).toBeInTheDocument();
    expect(within(table).getByText("high")).toBeInTheDocument();
    expect(within(table).getByText("2")).toBeInTheDocument();
    expect(within(table).getByText("No known corridor — billed $0 for this gap")).toBeInTheDocument();
    expect(screen.getByText(/Inertial estimate needed a large correction/)).toBeInTheDocument();
  });

  it("shows the fare check: both totals, the variance against the threshold, and the flag reason", async () => {
    installHandlers({
      ratingsMatch: true,
      tripOverrides: {
        total: "32.52",
        device_total: "59.03",
        variance_pct: "44.90",
        max_fare_check_passed: false,
        flagged_for_review: true,
        review_notes: "Device charged 59.03 through the tunnel",
      },
    });
    renderPage();
    await screen.findByText("Fare check");

    expect(screen.getByText("Server total (fare of record)")).toBeInTheDocument();
    expect(screen.getByText("Device total")).toBeInTheDocument();
    expect(screen.getByText("$59.03")).toBeInTheDocument();
    expect(screen.getByText("44.90% (threshold 1.00%)")).toBeInTheDocument();
    expect(screen.getByText("Failed")).toBeInTheDocument();
    expect(screen.getByText("Device charged 59.03 through the tunnel")).toBeInTheDocument();
  });

  it("says the device total is not stored rather than back-computing one", async () => {
    installHandlers({ ratingsMatch: true });
    renderPage();
    await screen.findByText("Fare check");

    expect(screen.getByText("Not stored on this trip")).toBeInTheDocument();
    expect(screen.getByText("0.20% (threshold 1.00%)")).toBeInTheDocument();
    expect(screen.getByText("Passed")).toBeInTheDocument();
  });

  it("names toll roads the trip crossed but that carry no price", async () => {
    installHandlers({
      ratingsMatch: true,
      tollRoads: [{ id: "road-rozelle", name: "Rozelle Interchange", toll_points: [] }],
      tripOverrides: { tolls: "0", unpriced_toll_road_ids: ["road-rozelle", "road-unknown"] },
    });
    renderPage();
    await screen.findByText("Fare breakdown");

    expect(await screen.findByText(/Crossed but not priced/)).toBeInTheDocument();
    expect(screen.getByText("Rozelle Interchange")).toBeInTheDocument();
    expect(screen.getByText(/road-unknown/)).toBeInTheDocument();
  });

  it("captions the route map's dashed blackout stretch and lists the blackout under it", async () => {
    installHandlers({ ratingsMatch: true, tripOverrides: { device_gps_blackout_segments: [DEVICE_SEGMENT] } });
    renderPage();
    const user = userEvent.setup();
    await screen.findByText("Fare breakdown");

    await user.click(screen.getByRole("tab", { name: "Route" }));
    expect(await screen.findByText(/Amber dashed stretch is a GPS blackout/)).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "GPS blackouts" })).toBeInTheDocument();
  });

  it("lets an owner correct the fare of record and announces the result", async () => {
    const onFareCorrection = vi.fn();
    installHandlers({ ratingsMatch: true, onFareCorrection });
    renderPage();
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: /Correct fare/ }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/stored total \$15\.20/)).toBeInTheDocument();

    const save = within(dialog).getByRole("button", { name: "Save correction" });
    expect(save).toBeDisabled();
    await user.type(within(dialog).getByLabelText("New total (AUD, GST-inclusive)"), "59.03");
    await user.type(within(dialog).getByLabelText("Reason (required)"), "Passenger paid the metered tunnel fare");
    expect(save).toBeEnabled();
    await user.click(save);

    expect(await screen.findByText("Fare corrected: $15.20 → $59.03")).toBeInTheDocument();
    expect(onFareCorrection).toHaveBeenCalledWith({
      total: "59.03",
      reason: "Passenger paid the metered tunnel fare",
    });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("surfaces the server's refusal inside the correct-fare dialog", async () => {
    installHandlers({ ratingsMatch: true });
    server.use(
      http.post(`${API}/v1/trips/${TRIP_ID}/fare-correction`, () =>
        HttpResponse.json({ detail: "Only a closed trip's fare can be corrected" }, { status: 409 }),
      ),
    );
    renderPage();
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: /Correct fare/ }));
    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByLabelText("New total (AUD, GST-inclusive)"), "20");
    await user.type(within(dialog).getByLabelText("Reason (required)"), "typo");
    await user.click(within(dialog).getByRole("button", { name: "Save correction" }));

    expect(await within(dialog).findByText("Only a closed trip's fare can be corrected")).toBeInTheDocument();
  });

  it("keeps Correct fare disabled for an open trip", async () => {
    installHandlers({ ratingsMatch: true, tripOverrides: { status: "open", end_at: null } });
    renderPage();

    expect(await screen.findByRole("button", { name: /Correct fare/ })).toBeDisabled();
  });

  it("degrades honestly on the Rating tab when no rating matches this trip", async () => {
    installHandlers({ ratingsMatch: false });
    renderPage();
    const user = userEvent.setup();
    await screen.findByText("Fare breakdown");

    await user.click(screen.getByRole("tab", { name: "Rating" }));
    expect(await screen.findByText("No rating recorded")).toBeInTheDocument();
  });

  it("asks the server for THIS trip's rating rather than filtering a driver page", async () => {
    // Regression guard for the wrong answer operators saw: the tab used to
    // request the driver's 200 most recent ratings and match trip_id in the
    // browser, so a rated trip further back read "No rating recorded". The
    // handler above filters by the trip_id query param exactly as the
    // endpoint does, so the rating only renders if the tab sent it.
    installHandlers({ ratingsMatch: true });
    renderPage();
    const user = userEvent.setup();
    await screen.findByText("Fare breakdown");

    await user.click(screen.getByRole("tab", { name: "Rating" }));
    expect(await screen.findByText("Great!")).toBeInTheDocument();
    expect(screen.queryByText("No rating recorded")).not.toBeInTheDocument();
  });

  it("shows Edit/Delete disabled with a tooltip for a dispatcher", async () => {
    currentUser = { ...currentUser, id: "u-disp", role: "dispatcher", name: "Dispatch One" };
    installHandlers({ ratingsMatch: true });
    renderPage();

    const editButton = await screen.findByRole("button", { name: /Edit/ });
    expect(editButton).toBeDisabled();
    const deleteButton = screen.getByRole("button", { name: /Delete/ });
    expect(deleteButton).toBeDisabled();
    // A fare correction is a financial-record change: owner/admin only, same
    // as the endpoint's own 403.
    expect(screen.getByRole("button", { name: /Correct fare/ })).toBeDisabled();
  });
});
