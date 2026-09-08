import { createRef } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Select } from "./Select";

const OPTIONS = [
  { value: "cash", label: "Cash" },
  { value: "card", label: "Card" },
  { value: "voucher", label: "Voucher" },
];

describe("Select", () => {
  it("renders one option per entry, in order", () => {
    render(<Select aria-label="Payment" options={OPTIONS} />);

    expect(screen.getAllByRole("option").map((o) => o.textContent)).toEqual([
      "Cash",
      "Card",
      "Voucher",
    ]);
  });

  it("renders no placeholder option when none is given", () => {
    render(<Select aria-label="Payment" options={OPTIONS} />);
    expect(screen.getAllByRole("option")).toHaveLength(3);
    // With no placeholder an uncontrolled select lands on the first real
    // option, so a filter defaults to "cash" rather than to "everything".
    expect(screen.getByLabelText("Payment")).toHaveValue("cash");
  });

  it("puts the placeholder first, with an empty value, and marks it disabled", () => {
    render(<Select aria-label="Payment" options={OPTIONS} placeholder="Any method" />);

    const options = screen.getAllByRole("option");
    expect(options).toHaveLength(4);
    expect(options[0]).toHaveTextContent("Any method");
    expect(options[0]).toHaveValue("");
    expect(options[0]).toBeDisabled();
  });

  /**
   * KNOWN DEFECT, asserted as-is rather than as it should be.
   *
   * The placeholder option is rendered `disabled`, and a disabled option is
   * skipped when the browser picks an uncontrolled select's initial
   * selection. So the placeholder is never actually displayed: the control
   * opens showing "Cash", the first real option. Every uncontrolled
   * `<Select placeholder=...>` in the dashboard therefore looks like a filter
   * that is already set, and submitting the form without touching it sends
   * "cash" -- a value the operator never chose.
   *
   * The fix lives in Select.tsx (add `defaultValue=""`, or drop `disabled`
   * and let the empty value mean "no filter"), which this workstream does not
   * own -- D4 owns the design system. Locked in here so the fix is visible
   * when it lands.
   */
  it("does NOT show its placeholder by default, falling through to the first real option", () => {
    render(<Select aria-label="Payment" options={OPTIONS} placeholder="Any method" />);

    expect(screen.getByLabelText("Payment")).toHaveValue("cash");
  });

  it("does show the placeholder when the caller controls it with an empty value", () => {
    render(
      <Select
        aria-label="Payment"
        options={OPTIONS}
        placeholder="Any method"
        value=""
        onChange={() => {}}
      />,
    );

    // The workaround callers must currently use.
    expect(screen.getByLabelText("Payment")).toHaveValue("");
  });

  it("reports the chosen value to the caller", async () => {
    const onChange = vi.fn();
    render(
      <Select aria-label="Payment" options={OPTIONS} placeholder="Any" onChange={onChange} />,
    );

    await userEvent.selectOptions(screen.getByLabelText("Payment"), "voucher");

    expect(screen.getByLabelText("Payment")).toHaveValue("voucher");
    expect(onChange).toHaveBeenCalledTimes(1);
  });

  it("honours a controlled value", () => {
    render(<Select aria-label="Payment" options={OPTIONS} value="card" onChange={() => {}} />);
    expect(screen.getByLabelText("Payment")).toHaveValue("card");
  });

  it("renders an empty option list without crashing", () => {
    render(<Select aria-label="Payment" options={[]} placeholder="No methods configured" />);
    expect(screen.getAllByRole("option")).toHaveLength(1);
  });

  it("blocks interaction when disabled", () => {
    render(<Select aria-label="Payment" options={OPTIONS} disabled />);
    expect(screen.getByLabelText("Payment")).toBeDisabled();
  });

  it("merges a caller's className and forwards a ref", () => {
    const ref = createRef<HTMLSelectElement>();
    render(<Select aria-label="Payment" options={OPTIONS} className="w-40" ref={ref} />);

    expect(screen.getByLabelText("Payment")).toHaveClass("w-40");
    expect(ref.current).toBeInstanceOf(HTMLSelectElement);
  });
});
