import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { API, server, startMockServer } from "@/test/server";
import type { PlottedShift } from "@/hooks/useZones";
import { ZoneStatsPanel } from "./ZoneStatsPanel";

startMockServer();

/**
 * Zones & Demand -- plot/unplot from the dispatcher's side (admin plan §3:
 * `POST /v1/zones/{id}/plot` and `/unplot` existed but nothing on the
 * dashboard called them, so every zone showed 0 plotted).
 */

let currentUser = { id: "u-owner", role: "owner", tenant_id: "t1", name: "Owner", email: "o@x", status: "active", mfa_enabled: false };
vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ user: currentUser }),
}));

const ZONE_A = {
  zone_id: "z-a",
  zone_name: "Airport",
  zone_number: "17",
  plotted_vehicles: 1,
  vacant_vehicles: 0,
  busy_vehicles: 0,
  jobs_holding: 0,
  bookings_last_hour: 0,
  street_hails_last_hour: 0,
};
const ZONE_B = { ...ZONE_A, zone_id: "z-b", zone_name: "City", zone_number: "2", plotted_vehicles: 0 };

let shifts: PlottedShift[] = [
  { id: "s1", driver_id: "d1", vehicle_id: "v1", start_at: "2026-09-15T00:00:00Z", plotted_zone_id: "z-a", plotted_at: "2026-09-15T01:00:00Z" },
  { id: "s2", driver_id: "d2", vehicle_id: "v2", start_at: "2026-09-15T00:00:00Z", plotted_zone_id: null, plotted_at: null },
];
let plotCalls: { zoneId: string; body: unknown }[] = [];
let unplotCalls: unknown[] = [];

function installHandlers() {
  server.use(
    http.get(`${API}/v1/zones/stats`, () => HttpResponse.json([ZONE_A, ZONE_B])),
    http.get(`${API}/v1/shifts`, ({ request }) => {
      const url = new URL(request.url);
      expect(url.searchParams.get("active_only")).toBe("true");
      return HttpResponse.json({ items: shifts, total: shifts.length, limit: 200, offset: 0 });
    }),
    http.get(`${API}/v1/drivers`, () =>
      HttpResponse.json({ items: [{ id: "d1", name: "Benn" }, { id: "d2", name: "Sara" }] }),
    ),
    http.get(`${API}/v1/vehicles`, () =>
      HttpResponse.json({ items: [{ id: "v1", rego: "T5453" }, { id: "v2", rego: "PRCH01" }] }),
    ),
    http.post(`${API}/v1/zones/:zoneId/plot`, async ({ params, request }) => {
      const body = (await request.json()) as { shift_id: string };
      plotCalls.push({ zoneId: String(params.zoneId), body });
      shifts = shifts.map((s) => (s.id === body.shift_id ? { ...s, plotted_zone_id: String(params.zoneId) } : s));
      return HttpResponse.json({ shift_id: body.shift_id, driver_id: "d2", vehicle_id: "v2", plotted_zone_id: params.zoneId, plotted_at: "2026-09-15T02:00:00Z" });
    }),
    http.post(`${API}/v1/zones/unplot`, async ({ request }) => {
      const body = (await request.json()) as { shift_id: string };
      unplotCalls.push(body);
      shifts = shifts.map((s) => (s.id === body.shift_id ? { ...s, plotted_zone_id: null } : s));
      return HttpResponse.json({ shift_id: body.shift_id, driver_id: "d1", vehicle_id: "v1", plotted_zone_id: null, plotted_at: null });
    }),
  );
}

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ZoneStatsPanel />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  currentUser = { id: "u-owner", role: "owner", tenant_id: "t1", name: "Owner", email: "o@x", status: "active", mfa_enabled: false };
  shifts = [
    { id: "s1", driver_id: "d1", vehicle_id: "v1", start_at: "2026-09-15T00:00:00Z", plotted_zone_id: "z-a", plotted_at: "2026-09-15T01:00:00Z" },
    { id: "s2", driver_id: "d2", vehicle_id: "v2", start_at: "2026-09-15T00:00:00Z", plotted_zone_id: null, plotted_at: null },
  ];
  plotCalls = [];
  unplotCalls = [];
  installHandlers();
});

describe("ZoneStatsPanel plot/unplot", () => {
  it("lists who is plotted into each zone by rego and driver name", async () => {
    renderPanel();
    const roster = await screen.findByRole("list", { name: "Vehicles plotted into Airport" });
    expect(within(roster).getByText("T5453 · Benn")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Vehicles plotted into City" })).not.toBeInTheDocument();
  });

  it("plots an on-shift vehicle into a zone and the roster refreshes", async () => {
    renderPanel();
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Plot vehicle into City" }));
    const select = await screen.findByLabelText("Vehicle");
    // The shift already plotted into Airport is offered as "plotted elsewhere".
    expect(within(select).getByRole("option", { name: "T5453 · Benn (plotted elsewhere)" })).toBeInTheDocument();
    await user.selectOptions(select, "s2");
    await user.click(screen.getByRole("button", { name: "Plot vehicle" }));

    await waitFor(() => expect(plotCalls).toHaveLength(1));
    expect(plotCalls[0]).toEqual({ zoneId: "z-b", body: { shift_id: "s2", driver_id: "d2", vehicle_id: "v2" } });

    const roster = await screen.findByRole("list", { name: "Vehicles plotted into City" });
    expect(within(roster).getByText("PRCH01 · Sara")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("unplots from the roster row", async () => {
    renderPanel();
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Unplot T5453 · Benn" }));

    await waitFor(() => expect(unplotCalls).toEqual([{ shift_id: "s1", driver_id: "d1", vehicle_id: "v1" }]));
    await waitFor(() =>
      expect(screen.queryByRole("list", { name: "Vehicles plotted into Airport" })).not.toBeInTheDocument(),
    );
  });

  it("surfaces the backend's refusal verbatim, with the on-behalf hint on the identity-scoped 409", async () => {
    server.use(
      http.post(`${API}/v1/zones/:zoneId/plot`, () =>
        HttpResponse.json({ detail: "You have no currently-open shift to plot" }, { status: 409 }),
      ),
    );
    renderPanel();
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Plot vehicle into City" }));
    await user.selectOptions(await screen.findByLabelText("Vehicle"), "s2");
    await user.click(screen.getByRole("button", { name: "Plot vehicle" }));

    expect(await screen.findByText(/You have no currently-open shift to plot/)).toBeInTheDocument();
    expect(screen.getByText(/plots the calling user's own shift only/)).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("hides plot/unplot from a driver account", async () => {
    currentUser = { ...currentUser, role: "driver" };
    renderPanel();
    await screen.findByText("T5453 · Benn");
    expect(screen.queryByRole("button", { name: /Plot vehicle into/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Unplot/ })).not.toBeInTheDocument();
  });
});
