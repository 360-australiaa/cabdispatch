import { createRef } from "react";
import { render, screen } from "@testing-library/react";
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from "./Card";

/**
 * Card is six thin forwardRef wrappers. The two things worth asserting are
 * the ones a caller can actually break: that refs really reach the DOM node
 * (several pages measure or scroll a card), and that `CardTitle` renders a
 * real heading -- if it degraded to a `<div>`, every page's heading outline
 * would silently disappear for screen-reader users.
 */
describe("Card", () => {
  it("composes into a single tree with every slot rendered", () => {
    render(
      <Card>
        <CardHeader>
          <CardTitle>Fleet</CardTitle>
          <CardDescription>All vehicles</CardDescription>
        </CardHeader>
        <CardContent>42 active</CardContent>
        <CardFooter>Updated just now</CardFooter>
      </Card>,
    );

    expect(screen.getByRole("heading", { name: "Fleet", level: 3 })).toBeInTheDocument();
    expect(screen.getByText("All vehicles")).toBeInTheDocument();
    expect(screen.getByText("42 active")).toBeInTheDocument();
    expect(screen.getByText("Updated just now")).toBeInTheDocument();
  });

  it("gives the card its surface classes and merges a caller's", () => {
    render(<Card className="col-span-2">Body</Card>);
    const card = screen.getByText("Body");
    expect(card).toHaveClass("rounded-lg");
    expect(card).toHaveClass("border");
    expect(card).toHaveClass("col-span-2");
  });

  it("forwards a ref to the underlying element", () => {
    const ref = createRef<HTMLDivElement>();
    render(<Card ref={ref}>Body</Card>);
    expect(ref.current).toBeInstanceOf(HTMLDivElement);
    expect(ref.current).toHaveTextContent("Body");
  });

  it("names every part for React DevTools", () => {
    expect([
      Card.displayName,
      CardHeader.displayName,
      CardTitle.displayName,
      CardDescription.displayName,
      CardContent.displayName,
      CardFooter.displayName,
    ]).toEqual([
      "Card",
      "CardHeader",
      "CardTitle",
      "CardDescription",
      "CardContent",
      "CardFooter",
    ]);
  });
});
