import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Sheet } from "./Sheet";

/**
 * Sheet is deliberately not a Modal: no scrim, so the page behind it stays
 * usable and there is no click-outside-to-close. Its own doc comment is
 * explicit that this leaves exactly two exits -- the X and Escape -- which
 * makes "both exits work" the contract most worth pinning down. If a future
 * change breaks one of them, a sheet with no scrim becomes a trap.
 */
describe("Sheet", () => {
  it("renders nothing while closed", () => {
    render(
      <Sheet open={false} onClose={() => {}} title="Vehicle">
        Detail
      </Sheet>,
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("renders a non-modal dialog labelled by its string title", () => {
    render(
      <Sheet open onClose={() => {}} title="Vehicle T-4471" description="VIN 12345">
        <p>Shift history</p>
      </Sheet>,
    );

    const dialog = screen.getByRole("dialog");
    // aria-modal=false is the point: the map behind stays reachable.
    expect(dialog).toHaveAttribute("aria-modal", "false");
    expect(dialog).toHaveAttribute("aria-label", "Vehicle T-4471");
    expect(screen.getByText("VIN 12345")).toBeInTheDocument();
    expect(screen.getByText("Shift history")).toBeInTheDocument();
  });

  it("omits aria-label when the title is not a plain string", () => {
    render(
      <Sheet open onClose={() => {}} title={<span>Rich title</span>}>
        Body
      </Sheet>,
    );
    expect(screen.getByRole("dialog")).not.toHaveAttribute("aria-label");
  });

  it("closes on the X button", async () => {
    const onClose = vi.fn();
    render(<Sheet open onClose={onClose} title="Vehicle" />);

    await userEvent.click(screen.getByRole("button", { name: "Close" }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("closes on Escape", async () => {
    const onClose = vi.fn();
    render(<Sheet open onClose={onClose} title="Vehicle" />);

    await userEvent.keyboard("{Escape}");

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("does not close when the body is clicked", async () => {
    const onClose = vi.fn();
    render(
      <Sheet open onClose={onClose} title="Vehicle">
        <button type="button">Locate</button>
      </Sheet>,
    );

    await userEvent.click(screen.getByRole("button", { name: "Locate" }));

    expect(onClose).not.toHaveBeenCalled();
  });

  it("removes its Escape listener once closed", async () => {
    const onClose = vi.fn();
    const { rerender } = render(<Sheet open onClose={onClose} title="Vehicle" />);

    rerender(<Sheet open={false} onClose={onClose} title="Vehicle" />);
    await userEvent.keyboard("{Escape}");

    expect(onClose).not.toHaveBeenCalled();
  });

  it("renders the footer slot only when given one", () => {
    const { rerender } = render(<Sheet open onClose={() => {}} title="Vehicle" />);
    expect(screen.queryByRole("button", { name: "Restart" })).not.toBeInTheDocument();

    rerender(
      <Sheet
        open
        onClose={() => {}}
        title="Vehicle"
        footer={<button type="button">Restart</button>}
      />,
    );
    expect(screen.getByRole("button", { name: "Restart" })).toBeInTheDocument();
  });
});
