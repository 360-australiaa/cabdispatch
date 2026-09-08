import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { API, server, startMockServer } from "@/test/server";
import { FareEstimatePanel } from "./FareEstimatePanel";

startMockServer();

function renderWithQuery(ui: React.ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
}

/**
 * This panel is the guardrail against the fare-estimate trap: `/v1/tariffs/
 * suggest` (`hooks/useTariffStudio.ts`) never returns a dollar figure, only
 * which tariff/time-class applies, so the panel must never synthesize a
 * fare from it -- see the component's header.
 */
describe("FareEstimatePanel", () => {
  it("renders nothing until a pickup point is chosen", () => {
    const { container } = renderWithQuery(<FareEstimatePanel lat={null} lng={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("shows the matched tariff's name, time class and reason -- never a dollar amount", async () => {
    server.use(
      http.get(`${API}/v1/tariffs/suggest`, () =>
        HttpResponse.json({
          tariff_id: "tar1",
          tariff_name: "Urban standard",
          time_class: "day",
          reason: "No vehicle- or location-specific match; using the currently effective tariff.",
        }),
      ),
    );

    renderWithQuery(<FareEstimatePanel lat={-33.8688} lng={151.2093} />);

    expect(await screen.findByText("Urban standard")).toBeInTheDocument();
    expect(screen.getByText("(day)")).toBeInTheDocument();
    expect(screen.getByText(/not a fare quote/i)).toBeInTheDocument();
    expect(screen.queryByText(/\$\d/)).not.toBeInTheDocument();
  });

  it("shows a plain message, not a guessed figure, when nothing resolves (404)", async () => {
    server.use(
      http.get(`${API}/v1/tariffs/suggest`, () => HttpResponse.json({ detail: "not found" }, { status: 404 })),
    );

    renderWithQuery(<FareEstimatePanel lat={-33.8688} lng={151.2093} />);

    expect(await screen.findByText(/no tariff resolves for this location/i)).toBeInTheDocument();
  });

  it("shows a plain message, not a guessed figure, on a request error", async () => {
    server.use(http.get(`${API}/v1/tariffs/suggest`, () => HttpResponse.error()));

    renderWithQuery(<FareEstimatePanel lat={-33.8688} lng={151.2093} />);

    expect(await screen.findByText(/couldn't resolve a tariff/i)).toBeInTheDocument();
  });
});
