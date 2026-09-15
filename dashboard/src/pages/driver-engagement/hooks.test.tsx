import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import type { ReactNode } from "react";
import { API, server, startMockServer } from "@/test/server";
import { useFleetWalletBalancesQuery, useIncentiveProgressListQuery, type DriverOption } from "./hooks";

startMockServer();

/**
 * The two admin-plan §4 endpoints and their graceful degradation: the bulk
 * `GET /v1/wallet/balances` replaces the N+1 per-driver loop, and
 * `GET /v1/incentives/{id}/progress` replaces the browser-side recount --
 * but an older backend (404 on either) must still get the old behaviour,
 * not an error.
 */

const DRIVERS: DriverOption[] = [
  { id: "d1", name: "Benn", email: "b@x", driver_code: "B1", status: "active" },
  { id: "d2", name: "Sara", email: "s@x", driver_code: null, status: "active" },
];

let perDriverCalls: string[] = [];

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

beforeEach(() => {
  perDriverCalls = [];
  server.use(
    http.get(`${API}/v1/wallet/drivers/:id`, ({ params }) => {
      perDriverCalls.push(String(params.id));
      return HttpResponse.json({ driver_id: params.id, balance_aud: "5.00", recent: [] });
    }),
  );
});

describe("useFleetWalletBalancesQuery", () => {
  it("uses the bulk endpoint and never issues a per-driver request", async () => {
    server.use(
      http.get(`${API}/v1/wallet/balances`, () =>
        HttpResponse.json({
          items: [
            { driver_id: "d1", driver_name: "Benn", driver_code: "B1", balance: "120.50" },
            { driver_id: "d2", driver_name: "Sara", driver_code: null, balance: "-3.00" },
            // A driver outside the first-100 users lookup still gets a row.
            { driver_id: "d3", driver_name: "Extra", driver_code: "E3", balance: "1.00" },
          ],
        }),
      ),
    );

    const { result } = renderHook(() => useFleetWalletBalancesQuery(DRIVERS), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.source).toBe("bulk");
    expect(result.current.rows.map((r) => [r.driver.name, r.balance_aud])).toEqual([
      ["Benn", "120.50"],
      ["Sara", "-3.00"],
      ["Extra", "1.00"],
    ]);
    expect(perDriverCalls).toEqual([]);
  });

  it("falls back to one request per driver when the bulk route is missing (404)", async () => {
    server.use(http.get(`${API}/v1/wallet/balances`, () => HttpResponse.json({ detail: "Not Found" }, { status: 404 })));

    const { result } = renderHook(() => useFleetWalletBalancesQuery(DRIVERS), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.source).toBe("per-driver"));
    await waitFor(() => expect(result.current.rows.every((r) => r.balance_aud === "5.00")).toBe(true));
    expect(perDriverCalls.sort()).toEqual(["d1", "d2"]);
    expect(result.current.isError).toBe(false);
  });

  it("reports a non-404 bulk failure as an error instead of fanning out", async () => {
    server.use(http.get(`${API}/v1/wallet/balances`, () => HttpResponse.json({ detail: "boom" }, { status: 500 })));

    const { result } = renderHook(() => useFleetWalletBalancesQuery(DRIVERS), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.source).toBe("bulk");
    expect(perDriverCalls).toEqual([]);
  });

  it("does nothing while disabled (a driver is selected)", async () => {
    let bulkCalls = 0;
    server.use(
      http.get(`${API}/v1/wallet/balances`, () => {
        bulkCalls += 1;
        return HttpResponse.json({ items: [] });
      }),
    );
    const { result } = renderHook(() => useFleetWalletBalancesQuery(DRIVERS, false), { wrapper: wrapper() });
    await new Promise((r) => setTimeout(r, 20));
    expect(bulkCalls).toBe(0);
    expect(perDriverCalls).toEqual([]);
    expect(result.current.rows).toHaveLength(2);
  });
});

describe("useIncentiveProgressListQuery", () => {
  it("returns the server rows", async () => {
    server.use(
      http.get(`${API}/v1/incentives/inc1/progress`, () =>
        HttpResponse.json({
          items: [{ driver_id: "d1", driver_name: "Benn", completed_trips: 4, target_trips: 10, earned: "0.00" }],
        }),
      ),
    );
    const { result } = renderHook(() => useIncentiveProgressListQuery("inc1"), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual({
      missing: false,
      items: [{ driver_id: "d1", driver_name: "Benn", completed_trips: 4, target_trips: 10, earned: "0.00" }],
    });
  });

  it("resolves to missing:true on a 404 so the caller can use the client-side fallback", async () => {
    server.use(http.get(`${API}/v1/incentives/inc1/progress`, () => HttpResponse.json({ detail: "Not Found" }, { status: 404 })));
    const { result } = renderHook(() => useIncentiveProgressListQuery("inc1"), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual({ missing: true, items: [] });
  });
});
