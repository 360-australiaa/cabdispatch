import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "@/components/ui";
import { API, server, startMockServer } from "@/test/server";
import { PLATFORM_TENANT_ID } from "@/lib/platformAdmin";
import type { Geofence, GeofenceCreateInput } from "@/hooks/useGeofences";
import { TollZonesPanel } from "./TollZonesPanel";

startMockServer();

/**
 * Airport pickup zones in Tariff Studio's "Toll & Airport Zones" tab.
 *
 * The business rule under test is in the copy, not the maths: the airport
 * access fee is charged once when a hiring STARTS inside the zone, never on a
 * drop-off, and never on top of the Sydney Airport fixed fare. The panel
 * must say so, and the presets button must create exactly the three Sydney
 * Airport terminal ranks with the $6.43 fee the backend hands it.
 *
 * `VITE_MAPBOX_TOKEN` is unset here, so any form modal that opens renders the
 * plain lat/lng fallback -- no Mapbox GL under jsdom.
 */

const OWNER = {
  id: "u-owner",
  tenant_id: PLATFORM_TENANT_ID,
  role: "owner",
  name: "Platform Owner",
  email: "owner@example.com",
  status: "active",
  mfa_enabled: false,
};
const FLEET_ADMIN = { ...OWNER, id: "u-admin", tenant_id: "t1", role: "admin", name: "Fleet Admin" };

let currentUser = OWNER;
vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ user: currentUser, tenant: null }),
}));

const PRESETS = [
  { name: "Sydney Airport T1 International", kind: "airport", center_lat: -33.9365, center_lng: 151.1665, radius_m: 400, toll_amount: 6.43 },
  { name: "Sydney Airport T2 Domestic", kind: "airport", center_lat: -33.9335, center_lng: 151.1808, radius_m: 400, toll_amount: 6.43 },
  { name: "Sydney Airport T3 Domestic", kind: "airport", center_lat: -33.9349, center_lng: 151.1836, radius_m: 400, toll_amount: 6.43 },
];

function zone(overrides: Partial<Geofence> & { id: string; name: string }): Geofence {
  return {
    tenant_id: null,
    kind: "airport",
    center_lat: -33.9365,
    center_lng: 151.1665,
    radius_m: 400,
    toll_amount: "6.43",
    created_at: "2026-09-01T00:00:00Z",
    updated_at: "2026-09-01T00:00:00Z",
    ...overrides,
  };
}

interface Store {
  toll: Geofence[];
  airport: Geofence[];
  posted: GeofenceCreateInput[];
}

/** One MSW handler set per test: `GET /v1/geofences?kind=` serves from the
 * store, `POST` appends to it, so the list the panel refetches after the
 * presets mutation reflects what was created. */
function installHandlers(store: Store, opts: { failPresetNamed?: string } = {}) {
  server.use(
    http.get(`${API}/v1/geofences`, ({ request }) => {
      const kind = new URL(request.url).searchParams.get("kind");
      const items = kind === "airport" ? store.airport : kind === "toll" ? store.toll : [];
      return HttpResponse.json({ items, total: items.length, skip: 0, limit: 100 });
    }),
    http.get(`${API}/v1/geofences/presets/airport`, () => HttpResponse.json(PRESETS)),
    http.post(`${API}/v1/geofences`, async ({ request }) => {
      const body = (await request.json()) as GeofenceCreateInput;
      store.posted.push(body);
      if (opts.failPresetNamed && body.name === opts.failPresetNamed) {
        return HttpResponse.json({ detail: "duplicate name" }, { status: 409 });
      }
      const created = zone({
        id: `g-${store.posted.length}`,
        name: body.name,
        kind: body.kind,
        center_lat: body.center_lat,
        center_lng: body.center_lng,
        radius_m: body.radius_m,
        toll_amount: body.toll_amount ?? null,
      });
      (body.kind === "airport" ? store.airport : store.toll).push(created);
      return HttpResponse.json(created, { status: 201 });
    }),
  );
}

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <TollZonesPanel />
      </ToastProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  currentUser = OWNER;
});

describe("Airport pickup zones section", () => {
  it("renders airport rows with centre, radius, fee and scope, under the business-rule copy", async () => {
    const store: Store = {
      toll: [zone({ id: "t1", name: "Harbour Bridge", kind: "toll", toll_amount: "4.50", radius_m: 300 })],
      airport: [
        zone({ id: "a1", name: "Sydney Airport T1 International", tenant_id: null }),
        zone({ id: "a2", name: "Sydney Airport T2 Domestic", tenant_id: "t1", center_lat: -33.9335, center_lng: 151.1808 }),
      ],
      posted: [],
    };
    installHandlers(store);

    renderPanel();

    expect(await screen.findByRole("heading", { name: /toll zones \(charged on entry\)/i })).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: /airport pickup zones \(charged when a hiring starts inside\)/i }),
    ).toBeInTheDocument();
    expect(screen.getByText(/charged once when a hiring starts inside the zone/i)).toBeInTheDocument();
    expect(screen.getByText(/never on a drop-off/i)).toBeInTheDocument();
    expect(screen.getByText(/never added under the Sydney Airport fixed fare/i)).toBeInTheDocument();
    expect(screen.getByText(/amount set by Sydney Airport; update it here/i)).toBeInTheDocument();

    const table = screen.getByRole("table", { name: "Airport pickup zones" });
    const t1Row = (await within(table).findByText("Sydney Airport T1 International")).closest("tr")!;
    expect(within(t1Row).getByText("-33.9365, 151.1665")).toBeInTheDocument();
    expect(within(t1Row).getByText("400 m")).toBeInTheDocument();
    expect(within(t1Row).getByText("$6.43")).toBeInTheDocument();
    expect(within(t1Row).getByText("Global")).toBeInTheDocument();

    const t2Row = within(table).getByText("Sydney Airport T2 Domestic").closest("tr")!;
    expect(within(t2Row).getByText("This fleet")).toBeInTheDocument();

    // The toll table is untouched and still lists its own rows.
    const tollTable = screen.getByRole("table", { name: "Toll zones" });
    expect(within(tollTable).getByText("Harbour Bridge")).toBeInTheDocument();

    // Presets are only partly present (T3 missing), so the button is offered enabled.
    expect(
      await screen.findByRole("button", { name: /add sydney airport terminals \(t1, t2, t3\)/i }),
    ).toBeEnabled();
    expect(screen.getByRole("button", { name: /add airport zone/i })).toBeEnabled();
  });

  it("shows the presets button in the empty state and creates the three terminals with the 6.43 fee", async () => {
    const store: Store = { toll: [], airport: [], posted: [] };
    installHandlers(store);
    const user = userEvent.setup();

    renderPanel();

    expect(await screen.findByText(/no airport pickup zones yet/i)).toBeInTheDocument();
    const presetsButton = screen.getByRole("button", { name: /add sydney airport terminals \(t1, t2, t3\)/i });
    expect(presetsButton).toBeEnabled();
    expect(screen.getByRole("button", { name: /add airport zone/i })).toBeInTheDocument();

    await user.click(presetsButton);

    await waitFor(() => expect(store.posted).toHaveLength(3));
    expect(store.posted.map((b) => b.kind)).toEqual(["airport", "airport", "airport"]);
    expect(store.posted.map((b) => b.toll_amount)).toEqual(["6.43", "6.43", "6.43"]);
    expect(store.posted.map((b) => b.name)).toEqual(PRESETS.map((p) => p.name));
    expect(store.posted.map((b) => b.radius_m)).toEqual([400, 400, 400]);

    // The list is invalidated and the three new rows appear.
    const table = await screen.findByRole("table", { name: "Airport pickup zones" });
    for (const preset of PRESETS) {
      expect(await within(table).findByText(preset.name)).toBeInTheDocument();
    }
    expect(screen.getByRole("status")).toHaveTextContent(/added sydney airport t1 international/i);

    // All three now exist: the presets button is kept but disabled.
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /add sydney airport terminals \(t1, t2, t3\)/i })).toBeDisabled(),
    );
    expect(screen.getByRole("button", { name: /add airport zone/i })).toBeEnabled();
  });

  it("names the terminal that failed when one POST is rejected, and keeps the ones that landed", async () => {
    const store: Store = { toll: [], airport: [], posted: [] };
    installHandlers(store, { failPresetNamed: "Sydney Airport T2 Domestic" });
    const user = userEvent.setup();

    renderPanel();

    await user.click(await screen.findByRole("button", { name: /add sydney airport terminals \(t1, t2, t3\)/i }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/2 of 3 terminal zones added/i);
    expect(alert).toHaveTextContent(/Sydney Airport T2 Domestic — duplicate name/);
    expect(alert).not.toHaveTextContent(/T1 International/);

    const table = await screen.findByRole("table", { name: "Airport pickup zones" });
    expect(await within(table).findByText("Sydney Airport T1 International")).toBeInTheDocument();
    expect(within(table).getByText("Sydney Airport T3 Domestic")).toBeInTheDocument();
    expect(within(table).queryByText("Sydney Airport T2 Domestic")).not.toBeInTheDocument();
  });

  it("opens the airport form with its own copy, fee label and Sydney Airport seed", async () => {
    const store: Store = { toll: [], airport: [], posted: [] };
    installHandlers(store);
    const user = userEvent.setup();

    renderPanel();

    await user.click(await screen.findByRole("button", { name: /add airport zone/i }));

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("Airport pickup zone")).toBeInTheDocument();
    expect(within(dialog).getByLabelText("Airport access fee (AUD)")).toBeInTheDocument();
    expect(within(dialog).getByText("Charged once at pickup, never on drop-off")).toBeInTheDocument();
    expect(within(dialog).getByLabelText("Radius (meters)")).toHaveValue(400);

    await user.click(within(dialog).getByRole("button", { name: "Start from Sydney Airport" }));
    expect(within(dialog).getByLabelText("Center latitude")).toHaveValue(-33.9399);
    expect(within(dialog).getByLabelText("Center longitude")).toHaveValue(151.1753);

    // A swapped pair is flagged, not silently accepted.
    await user.click(within(dialog).getByRole("button", { name: "Swap" }));
    expect(within(dialog).getByRole("status")).toHaveTextContent(/look swapped/i);
  });

  it("shows a non-owner the airport rows with edit and delete disabled", async () => {
    currentUser = FLEET_ADMIN;
    const store: Store = {
      toll: [],
      airport: [zone({ id: "a1", name: "Sydney Airport T1 International" })],
      posted: [],
    };
    installHandlers(store);

    renderPanel();

    const table = await screen.findByRole("table", { name: "Airport pickup zones" });
    expect(await within(table).findByText("Sydney Airport T1 International")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit Sydney Airport T1 International" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Delete Sydney Airport T1 International" })).toBeDisabled();
    expect(screen.getByRole("button", { name: /add airport zone/i })).toBeDisabled();
    expect(screen.queryByRole("button", { name: /new toll zone/i })).not.toBeInTheDocument();
  });
});
