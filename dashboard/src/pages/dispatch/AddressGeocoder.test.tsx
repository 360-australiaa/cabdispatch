import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AddressGeocoder } from "./AddressGeocoder";

/**
 * `VITE_MAPBOX_TOKEN` is unset in the test environment (no `.env` is loaded
 * for vitest), so every test here exercises the no-token path deliberately:
 * the component must say search is unavailable, never silently return zero
 * matches as though the address didn't exist (see the component's header).
 */
describe("AddressGeocoder (no Mapbox token configured)", () => {
  it("says search is unavailable instead of silently finding nothing", async () => {
    const user = userEvent.setup();
    render(<AddressGeocoder label="Pickup address" value="" onSelect={vi.fn()} />);

    await user.type(screen.getByLabelText("Pickup address"), "123 George St");

    expect(
      await screen.findByText(/address search is unavailable.*no map token/i),
    ).toBeInTheDocument();
  });

  it("never calls onSelect on its own -- the field stays plain manual entry", async () => {
    const onSelect = vi.fn();
    const user = userEvent.setup();
    render(<AddressGeocoder label="Drop-off address" value="" onSelect={onSelect} />);

    await user.type(screen.getByLabelText("Drop-off address"), "Sydney Airport");
    await waitFor(() => expect(screen.getByText(/unavailable/i)).toBeInTheDocument());

    expect(onSelect).not.toHaveBeenCalled();
  });

  it("clears the unavailable message once the field is emptied", async () => {
    const user = userEvent.setup();
    render(<AddressGeocoder label="Pickup address" value="" onSelect={vi.fn()} />);

    const input = screen.getByLabelText("Pickup address");
    await user.type(input, "abc");
    await screen.findByText(/unavailable/i);

    await user.clear(input);
    expect(screen.queryByText(/unavailable/i)).not.toBeInTheDocument();
  });

  it("syncs its displayed value when the parent resets the form", () => {
    const { rerender } = render(
      <AddressGeocoder label="Pickup address" value="" onSelect={vi.fn()} />,
    );
    expect(screen.getByLabelText("Pickup address")).toHaveValue("");

    rerender(<AddressGeocoder label="Pickup address" value="123 George St" onSelect={vi.fn()} />);
    expect(screen.getByLabelText("Pickup address")).toHaveValue("123 George St");

    rerender(<AddressGeocoder label="Pickup address" value="" onSelect={vi.fn()} />);
    expect(screen.getByLabelText("Pickup address")).toHaveValue("");
  });
});
