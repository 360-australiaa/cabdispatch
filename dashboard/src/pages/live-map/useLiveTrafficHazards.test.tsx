import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { API, server, startMockServer } from "@/test/server";
import { useLiveTrafficHazards } from "./useLiveTrafficHazards";
import type { TrafficHazard } from "./trafficTypes";

startMockServer();

/**
 * `useLiveTrafficHazards` -- Live Map's read of
 * `GET /v1/traffic/hazards?bbox=...&category=...&active_only=...`, matching
 * the real `backend/app/schemas/traffic.py` shape. Same MSW posture as
 * useLiveTrafficCameras.test.tsx.
 */

const BBOX = { minLng: 150.9, minLat: -34.0, maxLng: 151.3, maxLat: -33.7 };

function makeHazard(overrides: Partial<TrafficHazard> = {}): TrafficHazard {
  return {
    id: "hz-1",
    category: "roadwork",
    latitude: -33.87,
    longitude: 151.2,
    headline: "Lane closure on Parramatta Rd",
    closure_type: "Single lane closed",
    direction: "Eastbound",
    speed_limit: 40,
    expected_delay_minutes: 15,
    ended: false,
    ...overrides,
  };
}

function renderWithClient<T>(hookFn: () => T) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return renderHook(hookFn, {
    wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
  });
}

describe("useLiveTrafficHazards", () => {
  it("stays disabled -- fires no request -- until a bbox is known", () => {
    const { result } = renderWithClient(() => useLiveTrafficHazards(null));
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("stays disabled when the caller passes enabled=false, even with a real bbox", () => {
    const { result } = renderWithClient(() => useLiveTrafficHazards(BBOX, { enabled: false }));
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("defaults active_only to true, sends no category filter when none is given, and asks for the backend's max page size", async () => {
    const seen: Record<string, string | null> = {};
    server.use(
      http.get(`${API}/v1/traffic/hazards`, ({ request }) => {
        const params = new URL(request.url).searchParams;
        seen.bbox = params.get("bbox");
        seen.category = params.get("category");
        seen.active_only = params.get("active_only");
        seen.limit = params.get("limit");
        return HttpResponse.json({ items: [], total: 0, skip: 0, limit: 100 });
      }),
    );

    const { result } = renderWithClient(() => useLiveTrafficHazards(BBOX));

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(seen.bbox).toBe("150.9,-34,151.3,-33.7");
    expect(seen.category).toBeNull();
    expect(seen.active_only).toBe("true");
    expect(seen.limit).toBe("200");
  });

  it("passes an explicit category through to the request", async () => {
    let seenCategory: string | null = null;
    server.use(
      http.get(`${API}/v1/traffic/hazards`, ({ request }) => {
        seenCategory = new URL(request.url).searchParams.get("category");
        return HttpResponse.json({ items: [], total: 0, skip: 0, limit: 100 });
      }),
    );

    const { result } = renderWithClient(() => useLiveTrafficHazards(BBOX, { category: "flood" }));

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(seenCategory).toBe("flood");
  });

  it("unwraps the page to the flat item list every consumer reads", async () => {
    const hazards = [makeHazard({ id: "hz-1" }), makeHazard({ id: "hz-2", category: "flood", ended: true })];
    server.use(
      http.get(`${API}/v1/traffic/hazards`, () =>
        HttpResponse.json({ items: hazards, total: 2, skip: 0, limit: 100 }),
      ),
    );

    const { result } = renderWithClient(() => useLiveTrafficHazards(BBOX));

    await waitFor(() => expect(result.current.data).toEqual(hazards));
  });
});
