import { render, screen } from "@testing-library/react";
import { PageHeader } from "./PageHeader";

/**
 * PageHeader sits at the top of all 22 module pages, which makes it the
 * dashboard's only source of an `<h1>`. That -- one level-1 heading per page,
 * every page -- is the assertion worth having: it is the first thing a screen
 * reader reaches and the thing most easily lost in a restyle.
 */
describe("PageHeader", () => {
  it("renders the title as the page's h1", () => {
    render(<PageHeader title="Live Map" />);
    expect(screen.getByRole("heading", { level: 1, name: "Live Map" })).toBeInTheDocument();
  });

  it("renders the description only when given one", () => {
    const { rerender } = render(<PageHeader title="Trips" />);
    expect(screen.queryByText("Every closed trip")).not.toBeInTheDocument();

    rerender(<PageHeader title="Trips" description="Every closed trip" />);
    expect(screen.getByText("Every closed trip")).toBeInTheDocument();
  });

  it("accepts a rich description, not just a string", () => {
    render(<PageHeader title="Trips" description={<em>7 shown</em>} />);
    expect(screen.getByText("7 shown").tagName).toBe("EM");
  });

  it("renders the actions slot only when given one", () => {
    const { rerender } = render(<PageHeader title="Fleet" />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();

    rerender(<PageHeader title="Fleet" actions={<button type="button">Add vehicle</button>} />);
    expect(screen.getByRole("button", { name: "Add vehicle" })).toBeInTheDocument();
  });

  it("merges a caller's className onto the wrapper", () => {
    const { container } = render(<PageHeader title="Fleet" className="mb-0" />);
    expect(container.firstChild).toHaveClass("mb-0");
  });
});
