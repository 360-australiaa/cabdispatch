import { createRef } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Input } from "./Input";

describe("Input", () => {
  it("defaults to type=text but lets the caller override it", () => {
    const { rerender } = render(<Input aria-label="Search" />);
    expect(screen.getByLabelText("Search")).toHaveAttribute("type", "text");

    rerender(<Input aria-label="Search" type="password" />);
    expect(screen.getByLabelText("Search")).toHaveAttribute("type", "password");
  });

  it("accepts typing and reports the value to the caller", async () => {
    const onChange = vi.fn();
    render(<Input aria-label="Plate" onChange={onChange} />);
    const input = screen.getByLabelText("Plate");

    await userEvent.type(input, "T4471");

    expect(input).toHaveValue("T4471");
    expect(onChange).toHaveBeenCalledTimes(5);
  });

  it("works as a controlled input", async () => {
    const onChange = vi.fn();
    render(<Input aria-label="Plate" value="fixed" onChange={onChange} />);
    const input = screen.getByLabelText("Plate");

    await userEvent.type(input, "x");

    // Value stays put because the parent owns it -- the caller got the event.
    expect(input).toHaveValue("fixed");
    expect(onChange).toHaveBeenCalled();
  });

  it("blocks input and shows the disabled affordance when disabled", async () => {
    render(<Input aria-label="Plate" disabled />);
    const input = screen.getByLabelText("Plate");

    await userEvent.type(input, "nope");

    expect(input).toBeDisabled();
    expect(input).toHaveValue("");
    expect(input).toHaveClass("disabled:cursor-not-allowed");
  });

  it("passes placeholder and other native props through", () => {
    render(<Input aria-label="Plate" placeholder="Rego" required maxLength={8} />);
    const input = screen.getByPlaceholderText("Rego");
    expect(input).toBeRequired();
    expect(input).toHaveAttribute("maxLength", "8");
  });

  it("merges a caller's className with the kit's base styles", () => {
    render(<Input aria-label="Plate" className="w-32" />);
    const input = screen.getByLabelText("Plate");
    expect(input).toHaveClass("w-32");
    expect(input).toHaveClass("rounded-md");
  });

  it("forwards a ref so callers can focus it", () => {
    const ref = createRef<HTMLInputElement>();
    render(<Input aria-label="Plate" ref={ref} />);

    ref.current?.focus();

    expect(screen.getByLabelText("Plate")).toHaveFocus();
  });
});
