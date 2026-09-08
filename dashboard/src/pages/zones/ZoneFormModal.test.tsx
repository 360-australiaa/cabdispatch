import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import { ZoneFormModal } from "./ZoneFormModal";
import { formatHumanCoords, looksSwapped } from "./coordinateHints";

function renderModal() {
  const client = new QueryClient();
  return render(
    <QueryClientProvider client={client}>
      <ZoneFormModal open onClose={() => {}} mode="create" />
    </QueryClientProvider>,
  );
}

describe("coordinateHints", () => {
  it("flags the Norway case and leaves real Australian/Asian coordinates alone", () => {
    expect(looksSwapped(67.0, 24.86)).toBe(true);
    expect(looksSwapped(24.86, 67.0)).toBe(false);
    expect(looksSwapped(-33.8688, 151.2093)).toBe(false);
    // Genuinely high-latitude *and* high-longitude is not the swap pattern.
    expect(looksSwapped(64.1, 150.0)).toBe(false);
  });

  it("renders hemisphere letters", () => {
    expect(formatHumanCoords(24.86, 67.0)).toBe("24.8600° N, 67.0000° E");
    expect(formatHumanCoords(-33.8688, 151.2093)).toBe("33.8688° S, 151.2093° E");
    expect(formatHumanCoords(40.7, -74.0)).toBe("40.7000° N, 74.0000° W");
  });
});

describe("ZoneFormModal coordinate hint", () => {
  it("reads the coordinates back, warns when they look swapped, and Swap fixes them", async () => {
    const user = userEvent.setup();
    renderModal();

    const lat = screen.getByLabelText("Center latitude");
    const lng = screen.getByLabelText("Center longitude");

    // Sydney default: no hint.
    expect(screen.getByTestId("zone-coords-readback")).toHaveTextContent("33.8688° S, 151.2093° E");
    expect(screen.queryByText(/these look swapped/i)).not.toBeInTheDocument();

    await user.clear(lat);
    await user.type(lat, "67.0");
    await user.clear(lng);
    await user.type(lng, "24.86");

    expect(screen.getByTestId("zone-coords-readback")).toHaveTextContent("67.0000° N, 24.8600° E");
    expect(screen.getByText(/these look swapped/i)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Swap" }));

    expect(lat).toHaveValue(24.86);
    expect(lng).toHaveValue(67);
    expect(screen.getByTestId("zone-coords-readback")).toHaveTextContent("24.8600° N, 67.0000° E");
    expect(screen.queryByText(/these look swapped/i)).not.toBeInTheDocument();
  });
});
