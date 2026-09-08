import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { DispatchMapPicker } from "./DispatchMapPicker";

/**
 * `VITE_MAPBOX_TOKEN` is unset in the test environment, so this always
 * exercises the plain lat/lng fallback -- the same no-token degrade path as
 * `pages/tariffs/TollZoneMapPicker.tsx`. It must render usable inputs, not
 * break the form, per the component's header.
 */
describe("DispatchMapPicker (no Mapbox token configured)", () => {
  it("renders plain lat/lng inputs for both pickup and drop-off", () => {
    render(
      <DispatchMapPicker
        pickup={null}
        dropoff={null}
        active="pickup"
        onActiveChange={vi.fn()}
        onPick={vi.fn()}
      />,
    );

    expect(screen.getByText(/no vite_mapbox_token configured/i)).toBeInTheDocument();
    expect(screen.getByLabelText("Pickup latitude")).toBeInTheDocument();
    expect(screen.getByLabelText("Pickup longitude")).toBeInTheDocument();
    expect(screen.getByLabelText("Drop-off latitude")).toBeInTheDocument();
    expect(screen.getByLabelText("Drop-off longitude")).toBeInTheDocument();
  });

  it("calls onPick('pickup', ...) once both pickup coordinates are typed", async () => {
    const onPick = vi.fn();
    const user = userEvent.setup();
    render(
      <DispatchMapPicker
        pickup={null}
        dropoff={null}
        active="pickup"
        onActiveChange={vi.fn()}
        onPick={onPick}
      />,
    );

    await user.type(screen.getByLabelText("Pickup latitude"), "-33.8688");
    await user.type(screen.getByLabelText("Pickup longitude"), "151.2093");

    expect(onPick).toHaveBeenLastCalledWith("pickup", -33.8688, 151.2093);
  });

  it("calls onPick('dropoff', ...) once both drop-off coordinates are typed", async () => {
    const onPick = vi.fn();
    const user = userEvent.setup();
    render(
      <DispatchMapPicker
        pickup={null}
        dropoff={null}
        active="dropoff"
        onActiveChange={vi.fn()}
        onPick={onPick}
      />,
    );

    await user.type(screen.getByLabelText("Drop-off latitude"), "-33.9399");
    await user.type(screen.getByLabelText("Drop-off longitude"), "151.1753");

    expect(onPick).toHaveBeenLastCalledWith("dropoff", -33.9399, 151.1753);
  });

  it("does not call onPick while a coordinate is only half-typed", async () => {
    const onPick = vi.fn();
    const user = userEvent.setup();
    render(
      <DispatchMapPicker
        pickup={null}
        dropoff={null}
        active="pickup"
        onActiveChange={vi.fn()}
        onPick={onPick}
      />,
    );

    await user.type(screen.getByLabelText("Pickup latitude"), "-33.8688");
    expect(onPick).not.toHaveBeenCalled();
  });
});
