import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { Checkbox } from "./Checkbox";

describe("Checkbox", () => {
  it("renders a real checkbox input, not a div with a role", () => {
    render(<Checkbox label="Reconciled" />);

    const box = screen.getByRole("checkbox", { name: "Reconciled" });
    // A real input keeps native form participation and the native Space
    // toggle; a styled div would have to re-implement both.
    expect(box.tagName).toBe("INPUT");
    expect(box).toHaveAttribute("type", "checkbox");
  });

  it("associates its label so clicking the text toggles the box", async () => {
    function Harness() {
      const [checked, setChecked] = useState(false);
      return (
        <Checkbox
          label="Driver's counted cash matches"
          checked={checked}
          onChange={(e) => setChecked(e.target.checked)}
        />
      );
    }
    render(<Harness />);

    // The raw inputs this replaces mostly had an unassociated sibling span,
    // so the text was neither a hit target nor an accessible name.
    await userEvent.click(screen.getByText("Driver's counted cash matches"));

    expect(screen.getByRole("checkbox")).toBeChecked();
  });

  it("toggles with the keyboard", async () => {
    function Harness() {
      const [checked, setChecked] = useState(false);
      return (
        <Checkbox label="Active" checked={checked} onChange={(e) => setChecked(e.target.checked)} />
      );
    }
    render(<Harness />);

    await userEvent.tab();
    expect(screen.getByRole("checkbox")).toHaveFocus();
    await userEvent.keyboard(" ");

    expect(screen.getByRole("checkbox")).toBeChecked();
  });

  it("renders a description under the label", () => {
    render(<Checkbox label="Booked" description="Pre-arranged, unregulated by the Fares Order" />);

    expect(
      screen.getByText("Pre-arranged, unregulated by the Fares Order"),
    ).toBeInTheDocument();
  });

  it("supports the disabled state used by the destructive call sites", async () => {
    const onChange = vi.fn();
    render(<Checkbox label="Also destroy evidence" disabled onChange={onChange} />);

    const box = screen.getByRole("checkbox");
    expect(box).toBeDisabled();
    await userEvent.click(box);
    expect(onChange).not.toHaveBeenCalled();
  });

  it("exposes the indeterminate state as both the DOM property and aria-checked=mixed", () => {
    render(<Checkbox label="Select all" indeterminate checked={false} onChange={() => {}} />);

    const box = screen.getByRole("checkbox") as HTMLInputElement;
    // The DOM property is what draws the dash; `aria-checked="mixed"` is what
    // actually reaches a screen reader. A select-all needs both, which is why
    // hand-rolling one kept going wrong.
    expect(box.indeterminate).toBe(true);
    expect(box).toHaveAttribute("aria-checked", "mixed");
  });

  it("clears the indeterminate DOM property when the prop goes away", () => {
    const { rerender } = render(<Checkbox label="Select all" indeterminate />);
    expect((screen.getByRole("checkbox") as HTMLInputElement).indeterminate).toBe(true);

    rerender(<Checkbox label="Select all" />);

    expect((screen.getByRole("checkbox") as HTMLInputElement).indeterminate).toBe(false);
  });

  it("renders a bare input when given neither label nor description", () => {
    const { container } = render(<Checkbox aria-label="Row 3" />);

    // Table-cell usages want just the control, with no wrapper to fight.
    expect(container.querySelector("label")).toBeNull();
    expect(screen.getByRole("checkbox", { name: "Row 3" })).toBeInTheDocument();
  });
});
