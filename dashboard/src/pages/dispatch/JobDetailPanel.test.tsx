import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { API, server, startMockServer } from "@/test/server";
import { JobDetailPanel } from "./JobDetailPanel";

startMockServer();

/**
 * Accept/decline on a driver's behalf (admin plan §3) --
 * `POST /v1/jobs/{job}/offers/{offer}/accept|decline` from the dispatch
 * desk, sending the target `driver_id` for the widened backend route, and
 * showing the identity-scoped 403 honestly on an older one.
 */

let currentUser = { id: "u-owner", role: "owner", tenant_id: "t1", name: "Owner", email: "o@x", status: "active", mfa_enabled: false };
vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ user: currentUser }),
}));

const JOB = {
  id: "j1",
  tenant_id: "t1",
  origin_lat: -33.8,
  origin_lng: 151.2,
  origin_address: "1 Pitt St",
  dest_lat: -33.9,
  dest_lng: 151.1,
  dest_address: "Airport",
  status: "offered",
  fare_estimate_low: "40.00",
  fare_estimate_high: "55.00",
  requested_at: "2026-09-15T01:00:00Z",
  created_by_user_id: "u-owner",
  accepted_by_driver_id: null,
  created_at: "2026-09-15T01:00:00Z",
  updated_at: "2026-09-15T01:00:00Z",
};

let offers = [
  { id: "o1", job_id: "j1", tenant_id: "t1", driver_id: "d1", status: "pending", offered_at: "2026-09-15T01:00:00Z", expires_at: "2099-01-01T00:00:00Z", responded_at: null },
  { id: "o2", job_id: "j1", tenant_id: "t1", driver_id: "d2", status: "declined", offered_at: "2026-09-15T01:00:00Z", expires_at: "2099-01-01T00:00:00Z", responded_at: "2026-09-15T01:00:10Z" },
];
let answerCalls: { url: string; body: unknown }[] = [];

function installHandlers() {
  server.use(
    http.get(`${API}/v1/jobs/j1`, () => HttpResponse.json(JOB)),
    http.get(`${API}/v1/jobs/j1/offers`, () => HttpResponse.json(offers)),
    http.get(`${API}/v1/users`, () =>
      HttpResponse.json({
        items: [
          { id: "d1", name: "Benn", email: "b@x", driver_code: "B1", status: "active" },
          { id: "d2", name: "Sara", email: "s@x", driver_code: null, status: "active" },
        ],
        total: 2,
        skip: 0,
        limit: 100,
      }),
    ),
    http.post(`${API}/v1/jobs/j1/offers/:offerId/:answer`, async ({ params, request }) => {
      answerCalls.push({ url: `${params.offerId}/${params.answer}`, body: await request.json() });
      const status = params.answer === "accept" ? "accepted" : "declined";
      offers = offers.map((o) => (o.id === params.offerId ? { ...o, status } : o));
      return HttpResponse.json({ ...offers[0], status });
    }),
  );
}

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <JobDetailPanel jobId="j1" onClose={() => {}} live />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  currentUser = { id: "u-owner", role: "owner", tenant_id: "t1", name: "Owner", email: "o@x", status: "active", mfa_enabled: false };
  offers = [
    { id: "o1", job_id: "j1", tenant_id: "t1", driver_id: "d1", status: "pending", offered_at: "2026-09-15T01:00:00Z", expires_at: "2099-01-01T00:00:00Z", responded_at: null },
    { id: "o2", job_id: "j1", tenant_id: "t1", driver_id: "d2", status: "declined", offered_at: "2026-09-15T01:00:00Z", expires_at: "2099-01-01T00:00:00Z", responded_at: "2026-09-15T01:00:10Z" },
  ];
  answerCalls = [];
  installHandlers();
});

describe("JobDetailPanel on-behalf accept/decline", () => {
  it("offers Accept/Decline only on pending offers and sends the target driver_id", async () => {
    renderPanel();
    const user = userEvent.setup();

    expect(await screen.findByText("Benn")).toBeInTheDocument();
    // One pending offer -> exactly one pair of buttons (the declined one gets none).
    expect(screen.getAllByRole("button", { name: "Accept on behalf" })).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: "Decline" })).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "Accept on behalf" }));

    await waitFor(() => expect(answerCalls).toHaveLength(1));
    expect(answerCalls[0]).toEqual({ url: "o1/accept", body: { driver_id: "d1" } });
    // Refetched: the offer is accepted now, so the buttons are gone.
    await waitFor(() => expect(screen.queryByRole("button", { name: "Accept on behalf" })).not.toBeInTheDocument());
  });

  it("declines via the decline route", async () => {
    renderPanel();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Decline" }));
    await waitFor(() => expect(answerCalls).toEqual([{ url: "o1/decline", body: { driver_id: "d1" } }]));
  });

  it("shows the backend's identity-scoped refusal with the on-behalf hint", async () => {
    server.use(
      http.post(`${API}/v1/jobs/j1/offers/:offerId/:answer`, () =>
        HttpResponse.json({ detail: "This offer is not addressed to you" }, { status: 403 }),
      ),
    );
    renderPanel();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Accept on behalf" }));

    expect(await screen.findByText(/This offer is not addressed to you/)).toBeInTheDocument();
    expect(screen.getByText(/lets only the driver answer their own offer/)).toBeInTheDocument();
  });

  it("hides the on-behalf controls from a driver account", async () => {
    currentUser = { ...currentUser, role: "driver" };
    renderPanel();
    expect(await screen.findByText("Benn")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Accept on behalf" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Cancel job" })).not.toBeInTheDocument();
  });
});
