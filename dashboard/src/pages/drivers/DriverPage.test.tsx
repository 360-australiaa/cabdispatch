import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse, ws } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "@/components/ui";
import { API, server, startMockServer } from "@/test/server";
import DriverPage from "./DriverPage";

startMockServer();

// jsdom has no layout engine and does not implement `Element.scrollTo` at
// runtime (despite the DOM lib types declaring it) -- `ThreadPanel` (reused
// verbatim by the Messages tab below) calls it to keep the thread scrolled
// to the newest message. Polyfilled locally rather than in the shared
// `setupTests.ts` since no other test exercises this component yet.
Element.prototype.scrollTo = vi.fn();

/**
 * Full-page tests for `/drivers/:driverId` (dashboard command-centre plan
 * §4). Covers: header render (identity/status/facts/links), every tab's
 * happy path with realistic MSW data, two defensive fallbacks (a missing
 * endpoint and a 403-gated one -- proving the page degrades to an honest
 * message instead of crashing), and a non-owner (dispatcher) seeing the
 * owner/admin-only actions disabled with a tooltip.
 */

// `DriverAvatar` fetches `GET /v1/users/{id}/photo` with axios
// `responseType: "blob"`. That combination hits a real msw/undici
// incompatibility in this Node version ("object.stream is not a function",
// thrown as an unhandled rejection once the request settles) regardless of
// the mocked response's status or body -- confirmed by trying a 404 with a
// plain-text body, same result. Mocked away here rather than chasing a
// library bug outside this page's scope; the avatar's own fallback-to-
// initials behaviour is that component's concern, not this page's.
vi.mock("@/pages/fleet/DriverAvatar", () => ({
  DriverAvatar: ({ name }: { name: string }) => <div data-testid="driver-avatar">{name}</div>,
}));

const NOW_ISO = "2026-09-09T10:00:00.000Z";

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

const DRIVER_ID = "d1";
const VEHICLE_ID = "v1";
const TRIP_ID = "trip1";
const SHIFT_ID = "shift1";

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

const DRIVER_LIVE = {
  id: DRIVER_ID,
  tenant_id: "t1",
  name: "Arsalan Rehman",
  phone: "0400000000",
  user_status: "active",
  on_shift: true,
  shift_id: SHIFT_ID,
  vehicle_id: VEHICLE_ID,
  shift_start_at: NOW_ISO,
  current_trip_id: TRIP_ID,
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

const OPEN_SHIFT = {
  id: SHIFT_ID,
  tenant_id: "t1",
  driver_id: DRIVER_ID,
  vehicle_id: VEHICLE_ID,
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

function tripsPage(items: unknown[] = [TRIP]) {
  return { items, total: items.length, skip: 0, limit: 200 };
}

const RATINGS_SUMMARY = { total: 3, average: 4.67, distribution: { "1": 0, "2": 0, "3": 0, "4": 1, "5": 2 } };

const RATING = {
  id: "r1",
  tenant_id: "t1",
  trip_id: TRIP_ID,
  driver_id: DRIVER_ID,
  stars: 5,
  comment: "Great driver",
  created_at: NOW_ISO,
};

interface HandlerOpts {
  ratingsSummaryForbidden?: boolean;
  fatigueAlertsMissing?: boolean;
}

// The Messages tab reuses `ThreadPanel` verbatim, which opens
// `WS /v1/messages/live?driver_id=` unconditionally. Intercepting it here
// (accepting the connection and doing nothing) keeps the socket inside MSW
// instead of falling through to a real, refused TCP connection -- which is
// otherwise harmless to the assertions below but throws inside msw's
// interceptors on this Node/undici combination once the test tears down.
const messagesLive = ws.link(`ws://localhost:8001/v1/messages/live`);

function installHandlers(opts: HandlerOpts = {}) {
  server.use(
    messagesLive.addEventListener("connection", () => {}),
    http.get(`${API}/v1/users/${DRIVER_ID}`, () => HttpResponse.json(DRIVER_USER)),
    http.patch(`${API}/v1/users/${DRIVER_ID}`, async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json({ ...DRIVER_USER, ...body });
    }),
    http.post(`${API}/v1/users/${DRIVER_ID}/reset-pin`, () => HttpResponse.json({ pin: "654321" })),
    http.get(`${API}/v1/drivers/${DRIVER_ID}`, () => HttpResponse.json(DRIVER_LIVE)),
    http.get(`${API}/v1/fleet/vehicles`, () => HttpResponse.json({ items: [VEHICLE], total: 1, skip: 0, limit: 100 })),
    http.get(`${API}/v1/shifts`, ({ request }) => {
      const url = new URL(request.url);
      if (url.searchParams.get("active_only") === "true") {
        return HttpResponse.json({ items: [OPEN_SHIFT], total: 1, limit: 1, offset: 0 });
      }
      return HttpResponse.json({ items: [OPEN_SHIFT], total: 1, limit: 50, offset: 0 });
    }),
    http.post(`${API}/v1/shifts/${SHIFT_ID}/break/start`, () =>
      HttpResponse.json({ ...OPEN_SHIFT, break_started_at: NOW_ISO }),
    ),
    http.post(`${API}/v1/shifts/${SHIFT_ID}/break/end`, () => HttpResponse.json({ ...OPEN_SHIFT, break_taken: true })),
    http.get(`${API}/v1/trips`, () => HttpResponse.json(tripsPage())),
    http.get(`${API}/v1/ratings/summary`, () =>
      opts.ratingsSummaryForbidden
        ? HttpResponse.json({ detail: "Forbidden" }, { status: 403 })
        : HttpResponse.json(RATINGS_SUMMARY),
    ),
    http.get(`${API}/v1/ratings`, () => HttpResponse.json({ items: [RATING], total: 1, skip: 0, limit: 50 })),
    http.get(`${API}/v1/fatigue-alerts`, () =>
      opts.fatigueAlertsMissing
        ? HttpResponse.json({ detail: "not found" }, { status: 404 })
        : HttpResponse.json({ items: [], total: 0 }),
    ),
    http.get(`${API}/v1/wallet/drivers/${DRIVER_ID}`, () =>
      HttpResponse.json({ driver_id: DRIVER_ID, balance_aud: "42.50", recent: [] }),
    ),
    http.get(`${API}/v1/wallet/transactions`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 20 })),
    http.get(`${API}/v1/incentives`, () => HttpResponse.json({ items: [], total: 0, skip: 0, limit: 10 })),
    http.get(`${API}/v1/messages`, ({ request }) => {
      const url = new URL(request.url);
      const limit = Number(url.searchParams.get("limit") ?? "50");
      return HttpResponse.json({ items: [], total: 0, skip: 0, limit });
    }),
    http.get(`${API}/v1/messages/templates`, () => HttpResponse.json([])),
    http.get(`${API}/v1/audit-log`, ({ request }) => {
      const url = new URL(request.url);
      expect(url.searchParams.get("subject_id")).toBe(DRIVER_ID);
      return HttpResponse.json({
        items: [
          {
            id: "a1",
            tenant_id: "t1",
            actor_user_id: "u-owner",
            action: "status_change",
            entity_type: "user",
            entity_id: DRIVER_ID,
            before_json: { status: "active" },
            after_json: { status: "inactive" },
            at: NOW_ISO,
            hash: "h1",
            previous_hash: "h0",
          },
        ],
        total: 1,
        limit: 25,
        offset: 0,
      });
    }),
  );
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <MemoryRouter initialEntries={[`/drivers/${DRIVER_ID}`]}>
          <Routes>
            <Route path="/drivers/:driverId" element={<DriverPage />} />
            <Route path="/fleet" element={<h1>Fleet page</h1>} />
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  localStorage.clear();
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

describe("DriverPage", () => {
  it("renders the header with identity, live status, and links to the vehicle and trip", async () => {
    installHandlers();
    renderPage();

    expect(await screen.findByRole("heading", { level: 1, name: "Arsalan Rehman" })).toBeInTheDocument();
    expect(screen.getByText("Driver code AB12")).toBeInTheDocument();
    expect(screen.getByText("On trip")).toBeInTheDocument();
    expect(screen.getByText("0400000000")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "T22123" })).toHaveAttribute("href", `/vehicles/${VEHICLE_ID}`);
    expect(screen.getByRole("link", { name: TRIP_ID.slice(0, 8) })).toHaveAttribute("href", `/trips/${TRIP_ID}`);
  });

  it("Overview tab shows today's trip/fare/km/hours stats and the rating summary", async () => {
    installHandlers();
    renderPage();

    expect(await screen.findByText("Fares today")).toBeInTheDocument();
    expect(await screen.findByText("4.67")).toBeInTheDocument();
    expect(screen.getByText(/average over 3 ratings/)).toBeInTheDocument();
    expect(screen.getByText("No open fatigue alerts for this driver.")).toBeInTheDocument();
  });

  it("walks every tab and shows real data on each", async () => {
    installHandlers();
    renderPage();
    const user = userEvent.setup();
    await screen.findByText("Fares today");

    await user.click(screen.getByRole("tab", { name: "Shifts" }));
    expect(await screen.findByRole("link", { name: SHIFT_ID.slice(0, 8) })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /End break|Record break/ })).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Trips" }));
    const tripsTable = await screen.findByRole("table");
    expect(within(tripsTable).getByRole("link", { name: TRIP_ID.slice(0, 8) })).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Earnings & wallet" }));
    expect(await screen.findByText("$42.50")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Ratings" }));
    expect(await screen.findByText("Great driver")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Compliance" }));
    expect(await screen.findByText(/NSW Point to Point driver authority/)).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Messages" }));
    expect(await screen.findByPlaceholderText("Message Arsalan Rehman…")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Activity" }));
    expect(await screen.findByText("status_change")).toBeInTheDocument();
  });

  it("degrades honestly when ratings summary (403) and fatigue alerts (404) aren't available", async () => {
    installHandlers({ ratingsSummaryForbidden: true, fatigueAlertsMissing: true });
    renderPage();

    expect(
      await screen.findByText("Not available for your role (GET /v1/ratings/summary is owner/admin only)."),
    ).toBeInTheDocument();
    expect(screen.getByText(/Fatigue alerts unavailable right now/)).toBeInTheDocument();
  });

  it("shows the owner/admin-only actions disabled with a tooltip for a dispatcher", async () => {
    currentUser = { ...currentUser, id: "u-disp", role: "dispatcher", name: "Dispatch One" };
    installHandlers();
    renderPage();

    const editButton = await screen.findByRole("button", { name: "Edit" });
    expect(editButton).toBeDisabled();
    const resetPinButton = screen.getByRole("button", { name: "Reset meter PIN" });
    expect(resetPinButton).toBeDisabled();
    const deactivateButton = screen.getByRole("button", { name: /Deactivate/ });
    expect(deactivateButton).toBeDisabled();

    // Message and Start shift stay enabled for a dispatcher.
    expect(screen.getByRole("button", { name: "Message" })).toBeEnabled();

    const tooltipWrapper = editButton.parentElement?.parentElement;
    expect(tooltipWrapper).not.toBeNull();
    if (tooltipWrapper) {
      fireEvent.mouseEnter(tooltipWrapper);
      expect(await within(tooltipWrapper).findByText("Owner/admin only")).toBeInTheDocument();
    }
  });
});
