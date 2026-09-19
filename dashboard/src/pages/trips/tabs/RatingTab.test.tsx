import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse, delay } from "msw";
import { describe, expect, it } from "vitest";
import { API, server, startMockServer } from "@/test/server";
import { RatingTab } from "./RatingTab";

startMockServer();

/**
 * The Rating tab's two ways of showing the WRONG trip's rating under this
 * trip's header -- the exact failure the server-side `trip_id` filter was
 * added to end:
 *
 * 1. the server answers with a row that is not this trip's (the filter
 *    dropped, renamed or simply not implemented on an older backend, which
 *    answers 200 with the driver's most recent rating rather than 422);
 * 2. React Query's `placeholderData: (prev) => prev` on the shared
 *    `useRatingsQuery` holds the PREVIOUS trip's page on screen while the new
 *    trip's query is in flight, because the query key varies by trip id.
 *
 * Both must render something other than a confident answer about this trip.
 */

const DRIVER_ID = "d1";
const TRIP_A = "trip-aaaa";
const TRIP_B = "trip-bbbb";
const NOW_ISO = "2026-09-09T10:05:00.000Z";

function rating(tripId: string, stars: number, comment: string) {
  return {
    id: `r-${tripId}`,
    tenant_id: "t1",
    trip_id: tripId,
    driver_id: DRIVER_ID,
    stars,
    comment,
    created_at: NOW_ISO,
  };
}

function renderTab(tripId: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={client}>
      <RatingTab tripId={tripId} driverId={DRIVER_ID} />
    </QueryClientProvider>,
  );
  return {
    ...view,
    navigateTo(nextTripId: string) {
      view.rerender(
        <QueryClientProvider client={client}>
          <RatingTab tripId={nextTripId} driverId={DRIVER_ID} />
        </QueryClientProvider>,
      );
    },
  };
}

describe("RatingTab", () => {
  it("refuses to show a returned row that is not this trip's rating", async () => {
    // A backend that ignores (or never knew) `trip_id`: it answers 200 with
    // the driver's most recent rating. Rendering it would be exactly the
    // wrong answer the filter was added to eliminate.
    server.use(
      http.get(`${API}/v1/ratings`, () =>
        HttpResponse.json({
          items: [rating("some-other-trip", 5, "Great driver!")],
          total: 1,
          skip: 0,
          limit: 1,
        }),
      ),
    );

    renderTab(TRIP_A);

    expect(await screen.findByText(/answered with the rating for trip some-other-trip/)).toBeInTheDocument();
    expect(screen.queryByText("Great driver!")).not.toBeInTheDocument();
  });

  it("does not show the previous trip's rating while the next trip's is loading", async () => {
    server.use(
      http.get(`${API}/v1/ratings`, async ({ request }) => {
        const tripId = new URL(request.url).searchParams.get("trip_id") ?? "";
        if (tripId === TRIP_B) {
          // The new trip's answer is in flight: the window in which the stale
          // placeholder used to be rendered under the new trip's header.
          await delay(40);
        }
        const rows = [rating(TRIP_A, 5, "Trip A comment"), rating(TRIP_B, 2, "Trip B comment")].filter(
          (row) => row.trip_id === tripId,
        );
        return HttpResponse.json({ items: rows, total: rows.length, skip: 0, limit: 1 });
      }),
    );

    const view = renderTab(TRIP_A);
    expect(await screen.findByText("Trip A comment")).toBeInTheDocument();

    view.navigateTo(TRIP_B);
    expect(screen.queryByText("Trip A comment")).not.toBeInTheDocument();

    expect(await screen.findByText("Trip B comment")).toBeInTheDocument();
    expect(screen.queryByText("Trip A comment")).not.toBeInTheDocument();
  });

  it("shows this trip's rating when the server answers with it", async () => {
    server.use(
      http.get(`${API}/v1/ratings`, ({ request }) => {
        expect(new URL(request.url).searchParams.get("trip_id")).toBe(TRIP_A);
        return HttpResponse.json({ items: [rating(TRIP_A, 4, "On time")], total: 1, skip: 0, limit: 1 });
      }),
    );

    renderTab(TRIP_A);

    expect(await screen.findByText("On time")).toBeInTheDocument();
  });
});
