import { render, screen } from "@testing-library/react";
import { Badge } from "./Badge";

/**
 * Badge is the kit's status vocabulary: a dozen pages map a domain status
 * (`resolved`, `escalating`, `reconciled`) onto one of these six variants, so
 * the variant -> class mapping is a contract those `*BadgeVariant` helpers
 * depend on.
 */
describe("Badge", () => {
  it("uses the default variant when none is given", () => {
    render(<Badge>Open</Badge>);
    expect(screen.getByText("Open")).toHaveClass("bg-brand-lavender");
  });

  it.each([
    ["primary", "bg-brand-primary"],
    ["accent", "bg-brand-accent"],
    ["success", "bg-success"],
    ["destructive", "bg-destructive"],
  ] as const)("renders the %s variant", (variant, expected) => {
    render(<Badge variant={variant}>Status</Badge>);
    expect(screen.getByText("Status")).toHaveClass(expected);
  });

  it("renders the outline variant as a border rather than a fill", () => {
    render(<Badge variant="outline">Cancelled</Badge>);
    const badge = screen.getByText("Cancelled");
    expect(badge).toHaveClass("border");
    expect(badge).not.toHaveClass("bg-brand-lavender");
  });

  it("keeps the pill base classes on every variant", () => {
    render(<Badge variant="success">Done</Badge>);
    const badge = screen.getByText("Done");
    expect(badge).toHaveClass("rounded-full");
    expect(badge).toHaveClass("inline-flex");
  });

  it("merges a caller's className and forwards span props", () => {
    render(
      <Badge className="ml-2" title="Full status">
        Open
      </Badge>,
    );
    const badge = screen.getByText("Open");
    expect(badge).toHaveClass("ml-2");
    expect(badge).toHaveAttribute("title", "Full status");
  });
});
