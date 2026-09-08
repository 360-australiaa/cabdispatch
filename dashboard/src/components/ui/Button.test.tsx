import { render, screen } from "@testing-library/react";
import { Button } from "./Button";

/**
 * The dashboard's first test.
 *
 * Its job is to prove the runner is real -- jsdom renders, the `@`/relative
 * imports resolve, jest-dom's matchers are registered, and a React component
 * mounts -- by asserting something that would actually break if Button's
 * variant logic broke, rather than by asserting `true === true`.
 *
 * Button is the right subject: `buttonVariants` (class-variance-authority) is
 * the one place in the kit where a prop maps to concrete output, so the
 * variant/size/default/override behaviour below is a genuine contract other
 * components rely on, not a tautology.
 */
describe("Button", () => {
  it("applies the default variant and size when neither is given", () => {
    render(<Button>Save</Button>);
    const button = screen.getByRole("button", { name: "Save" });
    // defaultVariants in buttonVariants: primary + md.
    expect(button).toHaveClass("bg-brand-primary");
    expect(button).toHaveClass("h-10");
  });

  it("applies the requested variant instead of the default", () => {
    render(<Button variant="destructive">Wipe</Button>);
    const button = screen.getByRole("button", { name: "Wipe" });
    expect(button).toHaveClass("bg-destructive");
    expect(button).not.toHaveClass("bg-brand-primary");
  });

  it("applies the requested size instead of the default", () => {
    render(<Button size="sm">Filter</Button>);
    expect(screen.getByRole("button", { name: "Filter" })).toHaveClass("h-8");
  });

  it("keeps the shared base classes across every variant", () => {
    render(<Button variant="ghost">Cancel</Button>);
    const button = screen.getByRole("button", { name: "Cancel" });
    expect(button).toHaveClass("inline-flex");
    expect(button).toHaveClass("rounded-md");
  });

  it("merges a caller's className and forwards native button props", () => {
    render(
      <Button className="w-full" disabled type="submit">
        Submit
      </Button>,
    );
    const button = screen.getByRole("button", { name: "Submit" });
    expect(button).toHaveClass("w-full");
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("type", "submit");
  });
});
