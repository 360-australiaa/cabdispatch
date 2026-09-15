import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { API, server, startMockServer } from "@/test/server";
import { IncentiveProgressModal } from "./IncentiveProgressModal";
import type { Incentive } from "./hooks";

startMockServer();

const INCENTIVE: Incentive = {
  id: "inc1",
  tenant_id: "t1",
  title: "September sprint",
  description: null,
  target_trips: 10,
  reward_aud: "50.00",
  starts_at: "2026-09-01T00:00:00Z",
  ends_at: "2026-10-01T00:00:00Z",
  active: true,
  created_at: "2026-09-01T00:00:00Z",
  updated_at: "2026-09-01T00:00:00Z",
};

let tripsCalls = 0;

function renderModal() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IncentiveProgressModal open onClose={() => {}} incentive={INCENTIVE} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  tripsCalls = 0;
  server.use(
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
    http.get(`${API}/v1/trips`, () => {
      tripsCalls += 1;
      return HttpResponse.json({ items: [], total: 0, skip: 0, limit: 200 });
    }),
  );
});

describe("IncentiveProgressModal", () => {
  it("shows every driver's server-computed progress and filters by driver", async () => {
    server.use(
      http.get(`${API}/v1/incentives/inc1/progress`, () =>
        HttpResponse.json({
          items: [
            { driver_id: "d1", driver_name: "Benn", completed_trips: 10, target_trips: 10, earned: "50.00" },
            { driver_id: "d2", driver_name: "Sara", completed_trips: 3, target_trips: 10, earned: "0.00" },
          ],
        }),
      ),
    );
    renderModal();
    const user = userEvent.setup();

    expect(await screen.findByText("10 of 10 trips")).toBeInTheDocument();
    expect(screen.getByText("3 of 10 trips")).toBeInTheDocument();
    expect(screen.getByText("$50.00")).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("Driver"), "d2");
    await waitFor(() => expect(screen.queryByText("10 of 10 trips")).not.toBeInTheDocument());
    expect(screen.getByText("3 of 10 trips")).toBeInTheDocument();
    // Never recomputed in the browser when the server route exists.
    expect(tripsCalls).toBe(0);
  });

  it("falls back to the per-driver client-side count when the progress route is missing", async () => {
    server.use(http.get(`${API}/v1/incentives/inc1/progress`, () => HttpResponse.json({ detail: "Not Found" }, { status: 404 })));
    renderModal();
    const user = userEvent.setup();

    expect(await screen.findByText("Pick a driver to see their progress.")).toBeInTheDocument();
    await user.selectOptions(await screen.findByLabelText("Driver"), "d1");

    expect(await screen.findByText("0 of 10 trips")).toBeInTheDocument();
    expect(screen.getByText("10 trips to go.")).toBeInTheDocument();
    expect(tripsCalls).toBe(1);
  });
});
