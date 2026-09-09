import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { API, server, startMockServer } from "@/test/server";
import { useLiveTrafficCameras } from "./useLiveTrafficCameras";
import type { TrafficCamera } from "./trafficTypes";

startMockServer();

/**
 * `useLiveTrafficCameras` -- Live Map's read of `GET /v1/traffic/cameras?bbox=...`.
 * Still MSW-mocked (no test here should ever touch a real server), matching
 * the real `backend/app/schemas/traffic.py` shape exactly (see
 * trafficTypes.ts's own doc comment on the one field name that moved --
 * `name`, not the originally-planned `title` -- while this was in progress).
 */

const BBOX = { minLng: 150.9, minLat: -34.0, maxLng: 151.3, maxLat: -33.7 };

function makeCamera(overrides: Partial<TrafficCamera> = {}): TrafficCamera {
  return {
    id: "cam-1",
    name: "M4 Motorway at Church St",
    latitude: -33.815,
    longitude: 150.995,
    direction: "Westbound",
    image_url: "https://api.transport.nsw.gov.au/cameras/cam-1.jpg",
    region: "Sydney",
    ...overrides,
  };
}

function renderWithClient<T>(hookFn: () => T) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return renderHook(hookFn, {
    wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
  });
}

describe("useLiveTrafficCameras", () => {
  it("stays disabled -- fires no request -- until a bbox is known", async () => {
    // No handler installed at all: MSW's `onUnhandledRequest: "error"` (see
    // test/server.ts) means any request this fires would fail the test.
    const { result } = renderWithClient(() => useLiveTrafficCameras(null, true));
    expect(result.current.fetchStatus).toBe("idle");
    expect(result.current.data).toBeUndefined();
  });

  it("stays disabled when the caller passes enabled=false, even with a real bbox", async () => {
    const { result } = renderWithClient(() => useLiveTrafficCameras(BBOX, false));
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("requests the bbox in the exact minLng,minLat,maxLng,maxLat order the contract specifies, at the backend's max page size", async () => {
    let receivedBbox: string | null = null;
    let receivedLimit: string | null = null;
    server.use(
      http.get(`${API}/v1/traffic/cameras`, ({ request }) => {
        const params = new URL(request.url).searchParams;
        receivedBbox = params.get("bbox");
        receivedLimit = params.get("limit");
        return HttpResponse.json({ items: [makeCamera()], total: 1, skip: 0, limit: 100 });
      }),
    );

    const { result } = renderWithClient(() => useLiveTrafficCameras(BBOX, true));

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(receivedBbox).toBe("150.9,-34,151.3,-33.7");
    // The backend caps `limit` at 200 (`Query(..., le=200)`) -- requested
    // explicitly so a busy viewport isn't silently truncated to its default of 50.
    expect(receivedLimit).toBe("200");
  });

  it("unwraps the page to the flat item list every consumer reads", async () => {
    const cameras = [makeCamera({ id: "cam-1" }), makeCamera({ id: "cam-2", name: "Anzac Bridge" })];
    server.use(
      http.get(`${API}/v1/traffic/cameras`, () =>
        HttpResponse.json({ items: cameras, total: 2, skip: 0, limit: 100 }),
      ),
    );

    const { result } = renderWithClient(() => useLiveTrafficCameras(BBOX, true));

    await waitFor(() => expect(result.current.data).toEqual(cameras));
  });
});
