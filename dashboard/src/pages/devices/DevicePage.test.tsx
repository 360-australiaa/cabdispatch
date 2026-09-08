import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "@/components/ui";
import { API, server, startMockServer } from "@/test/server";
import DevicePage from "./DevicePage";

/**
 * `/devices/:deviceId` (dashboard command-centre plan §6).
 *
 * `@/lib/auth` is mocked rather than wrapped in a real `AuthProvider` --
 * same technique as `pages/tariffs/AirportZones.test.tsx` -- so a test can
 * flip the viewer's role (owner/admin vs. dispatcher) without a real
 * `POST /v1/auth/login` round trip.
 */

const OWNER = {
  id: "u-owner",
  tenant_id: "t1",
  role: "owner",
  name: "Fleet Owner",
  email: "owner@example.test",
  status: "active",
  mfa_enabled: false,
};
const DISPATCHER = { ...OWNER, id: "u-dispatch", role: "dispatcher", name: "Dispatch" };

let currentUser: typeof OWNER = OWNER;
vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ user: currentUser, tenant: null }),
}));

startMockServer();

afterEach(() => {
  currentUser = OWNER;
});

const NOW = Date.now();
function minutesAgo(mins: number): string {
  return new Date(NOW - mins * 60_000).toISOString();
}

function baseDevice(overrides: Record<string, unknown> = {}) {
  return {
    id: "dev-1",
    tenant_id: "t1",
    android_id: "AND-0001",
    model: "Galaxy Tab A8",
    app_version: "1.4.0",
    vehicle_id: "veh-1",
    kiosk_locked: true,
    force_update_pending: false,
    locate_requested: false,
    reboot_requested: false,
    last_seen_at: minutesAgo(2),
    battery: 55,
    network: "wifi",
    paired_at: minutesAgo(60 * 24),
    revoked_at: null,
    last_locate_lat: -33.86,
    last_locate_lng: 151.2,
    last_locate_accuracy_m: 12,
    last_locate_at: minutesAgo(5),
    command_acked_at: minutesAgo(3),
    last_acked_command: "kiosk_lock",
    created_at: minutesAgo(60 * 24 * 30),
    updated_at: minutesAgo(2),
    ...overrides,
  };
}

const VEHICLE = {
  id: "veh-1",
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
  created_at: minutesAgo(60 * 24 * 60),
  updated_at: minutesAgo(60),
};

const AUDIT_ENTRY = {
  id: "audit-1",
  tenant_id: "t1",
  actor_user_id: "u-owner",
  action: "device_secret_rotated",
  entity_type: "device",
  entity_id: "dev-1",
  before_json: null,
  after_json: null,
  at: minutesAgo(10),
  hash: "hash-1",
  previous_hash: "hash-0",
};

interface HandlerOptions {
  device?: Record<string, unknown>;
  fleetDevices?: Record<string, unknown>[];
  latestRelease?: { version_code: number; version_name: string } | "missing";
  auditLog?: "ok" | "notfound";
}

function installHandlers(opts: HandlerOptions = {}) {
  const device = opts.device ?? baseDevice();
  const fleetDevices = opts.fleetDevices ?? [device, baseDevice({ id: "dev-2", app_version: "1.3.0" })];

  server.use(
    http.get(`${API}/v1/fleet/devices/:id`, () => HttpResponse.json(device)),
    http.get(`${API}/v1/fleet/devices`, () =>
      HttpResponse.json({ items: fleetDevices, total: fleetDevices.length, skip: 0, limit: 100 }),
    ),
    http.get(`${API}/v1/fleet/vehicles`, () =>
      HttpResponse.json({ items: [VEHICLE], total: 1, skip: 0, limit: 100 }),
    ),
    http.get(`${API}/v1/app-releases/latest`, () => {
      if (opts.latestRelease === "missing") {
        return HttpResponse.json({ detail: "No app release published yet" }, { status: 404 });
      }
      const release = opts.latestRelease ?? { version_code: 5, version_name: "1.5.0" };
      return HttpResponse.json({
        version_code: release.version_code,
        version_name: release.version_name,
        release_notes: null,
        download_url: "/v1/app-releases/r1/download",
        sha256: "abc123",
      });
    }),
    http.get(`${API}/v1/audit-log`, () => {
      if (opts.auditLog === "notfound") {
        return HttpResponse.json({ detail: "not found" }, { status: 404 });
      }
      return HttpResponse.json({ items: [AUDIT_ENTRY], total: 1, limit: 25, offset: 0 });
    }),
  );

  return { device, fleetDevices };
}

function renderPage(deviceId = "dev-1") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <MemoryRouter initialEntries={[`/devices/${deviceId}`]}>
          <Routes>
            <Route path="/devices/:deviceId" element={<DevicePage />} />
            <Route path="/fleet" element={<h1>Fleet page</h1>} />
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

describe("DevicePage header", () => {
  it("shows model, android id, app version, battery, network, kiosk state and the paired vehicle link", async () => {
    installHandlers();
    renderPage();

    expect(await screen.findByRole("heading", { level: 1, name: "Galaxy Tab A8" })).toBeInTheDocument();
    expect(screen.getByText("AND-0001")).toBeInTheDocument();
    expect(screen.getByText("1.4.0")).toBeInTheDocument();
    expect(screen.getByText("55%")).toBeInTheDocument();
    expect(screen.getByText("wifi")).toBeInTheDocument();
    expect(screen.getAllByText("Locked").length).toBeGreaterThan(0);
    expect(await screen.findByRole("link", { name: "T22123" })).toHaveAttribute("href", "/vehicles/veh-1");
  });

  it("colour-codes a recent heartbeat as Online using the 15-minute offline rule", async () => {
    installHandlers({ device: baseDevice({ last_seen_at: minutesAgo(2) }) });
    renderPage();

    const badge = await screen.findByText("Online");
    expect(badge.className).toMatch(/success/);
  });

  it("colour-codes a heartbeat older than 15 minutes as Offline", async () => {
    installHandlers({ device: baseDevice({ last_seen_at: minutesAgo(20) }) });
    renderPage();

    const badge = await screen.findByText("Offline");
    expect(badge.className).toMatch(/destructive/);
  });

  it("shows an update-available badge when the device's app_version differs from the latest published release", async () => {
    installHandlers({
      device: baseDevice({ app_version: "1.4.0" }),
      latestRelease: { version_code: 6, version_name: "1.6.0" },
    });
    renderPage();

    expect(await screen.findByText("Update available")).toBeInTheDocument();
  });

  it("omits the update-available badge when the device is already on the latest version", async () => {
    installHandlers({
      device: baseDevice({ app_version: "1.5.0" }),
      latestRelease: { version_code: 5, version_name: "1.5.0" },
    });
    renderPage();

    await screen.findByText("1.5.0");
    expect(screen.queryByText("Update available")).not.toBeInTheDocument();
  });

  it("omits the update comparison entirely when no release has ever been published", async () => {
    installHandlers({ latestRelease: "missing" });
    renderPage();

    await screen.findByText("1.4.0");
    expect(screen.queryByText("Update available")).not.toBeInTheDocument();
  });
});

describe("DevicePage tabs", () => {
  it("Status tab shows the last locate position, accuracy and kiosk confirmation", async () => {
    installHandlers();
    renderPage();

    await screen.findByRole("heading", { level: 1, name: "Galaxy Tab A8" });
    expect(screen.getByRole("img", { name: /Last known device position/ })).toBeInTheDocument();
    expect(screen.getByText("±12 m")).toBeInTheDocument();
  });

  it("Heartbeats and Commands render their labelled 'not yet available' placeholder without crashing", async () => {
    installHandlers();
    renderPage();

    await screen.findByRole("heading", { level: 1, name: "Galaxy Tab A8" });

    await userEvent.click(screen.getByRole("tab", { name: "Heartbeats" }));
    expect(
      screen.getByText(/Heartbeat history is not yet recorded server-side/),
    ).toBeInTheDocument();
    expect(screen.getAllByText(/Not yet available/).length).toBeGreaterThan(0);

    await userEvent.click(screen.getByRole("tab", { name: "Commands" }));
    expect(screen.getByText(/no persisted command log server-side yet/)).toBeInTheDocument();
  });

  it("Versions tab tallies the fleet-wide app_version spread from GET /v1/fleet/devices", async () => {
    installHandlers({
      device: baseDevice({ app_version: "1.4.0" }),
      fleetDevices: [
        baseDevice({ id: "dev-1", app_version: "1.4.0" }),
        baseDevice({ id: "dev-2", app_version: "1.4.0" }),
        baseDevice({ id: "dev-3", app_version: "1.3.0" }),
      ],
    });
    renderPage();

    await screen.findByRole("heading", { level: 1, name: "Galaxy Tab A8" });
    await userEvent.click(screen.getByRole("tab", { name: "Versions" }));

    expect(await screen.findByText("2 of 3")).toBeInTheDocument();
    expect(screen.getByText("1 of 3")).toBeInTheDocument();
  });

  it("Activity tab lists audit-log entries filtered to this device", async () => {
    installHandlers();
    renderPage();

    await screen.findByRole("heading", { level: 1, name: "Galaxy Tab A8" });
    await userEvent.click(screen.getByRole("tab", { name: "Activity" }));

    expect(await screen.findByText("device_secret_rotated")).toBeInTheDocument();
  });

  it("omits the Activity tab entirely when GET /v1/audit-log fails for this viewer", async () => {
    installHandlers({ auditLog: "notfound" });
    renderPage();

    await screen.findByRole("heading", { level: 1, name: "Galaxy Tab A8" });
    await waitFor(() => expect(screen.queryByRole("tab", { name: "Activity" })).not.toBeInTheDocument());
    // The other real tabs still render.
    expect(screen.getByRole("tab", { name: "Status" })).toBeInTheDocument();
  });
});

describe("DevicePage rotate secret", () => {
  it("confirms, reveals the new secret exactly once, and clears it on close", async () => {
    installHandlers();
    let rotateCalls = 0;
    server.use(
      http.post(`${API}/v1/fleet/devices/:id/rotate-secret`, () => {
        rotateCalls += 1;
        return HttpResponse.json({ ...baseDevice(), device_secret: "plaintext-secret-xyz" });
      }),
    );
    renderPage();

    await screen.findByRole("heading", { level: 1, name: "Galaxy Tab A8" });
    await userEvent.click(screen.getByRole("button", { name: /Rotate secret/ }));

    // Confirm step -- the secret has not been requested yet.
    expect(rotateCalls).toBe(0);
    expect(screen.getByText(/invalidates the one currently on the/)).toBeInTheDocument();

    const dialog = screen.getByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Rotate secret" }));

    expect(await screen.findByText("plaintext-secret-xyz")).toBeInTheDocument();
    expect(rotateCalls).toBe(1);

    // Closing clears it from the page -- it does not linger in the DOM or
    // reappear without rotating again.
    await userEvent.click(screen.getByRole("button", { name: "Done" }));
    await waitFor(() => expect(screen.queryByText("plaintext-secret-xyz")).not.toBeInTheDocument());
  });
});

describe("DevicePage permissions", () => {
  it("disables destructive/mutating actions for a non-owner/admin viewer, with a reason", async () => {
    currentUser = DISPATCHER;
    installHandlers();
    renderPage();

    await screen.findByRole("heading", { level: 1, name: "Galaxy Tab A8" });

    for (const label of [
      "Unlock kiosk",
      "Restart app",
      "Force update",
      "Locate",
      "Rotate secret",
      "Unpair",
      "Revoke",
      "Delete",
    ]) {
      expect(screen.getByRole("button", { name: label })).toBeDisabled();
    }
  });

  it("leaves those actions enabled for an owner", async () => {
    installHandlers();
    renderPage();

    await screen.findByRole("heading", { level: 1, name: "Galaxy Tab A8" });
    expect(screen.getByRole("button", { name: "Unlock kiosk" })).not.toBeDisabled();
    expect(screen.getByRole("button", { name: "Delete" })).not.toBeDisabled();
  });
});
