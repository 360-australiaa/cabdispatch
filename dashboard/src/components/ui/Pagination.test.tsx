import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CursorPagination, Pagination } from "./Pagination";

describe("Pagination", () => {
  it("shows a default 1-based summary from a 0-based page index", () => {
    render(<Pagination page={0} pageCount={4} onPageChange={() => {}} />);

    // Zero-based in the props (matching every call site's `useState(0)`),
    // one-based on screen (matching what a human counts).
    expect(screen.getByText("Page 1 of 4")).toBeInTheDocument();
  });

  it("prefers the caller's summary when given one", () => {
    render(
      <Pagination
        page={1}
        pageCount={4}
        onPageChange={() => {}}
        summary={<>128 trips — page 2 of 4</>}
      />,
    );

    expect(screen.getByText("128 trips — page 2 of 4")).toBeInTheDocument();
    expect(screen.queryByText("Page 2 of 4")).not.toBeInTheDocument();
  });

  it("steps forward and back by one page", async () => {
    const onPageChange = vi.fn();
    render(<Pagination page={1} pageCount={4} onPageChange={onPageChange} />);

    await userEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(onPageChange).toHaveBeenCalledWith(2);

    await userEvent.click(screen.getByRole("button", { name: "Previous" }));
    expect(onPageChange).toHaveBeenCalledWith(0);
  });

  it("disables Previous on the first page and Next on the last", () => {
    const { rerender } = render(<Pagination page={0} pageCount={3} onPageChange={() => {}} />);
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Next" })).toBeEnabled();

    rerender(<Pagination page={2} pageCount={3} onPageChange={() => {}} />);
    // The copies this replaces disagreed about this: some left Next live on
    // the final page, so clicking it fetched an empty slice.
    expect(screen.getByRole("button", { name: "Previous" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
  });

  it("clamps a nonsense pageCount to one page rather than rendering 'of 0'", () => {
    render(<Pagination page={0} pageCount={0} onPageChange={() => {}} />);

    expect(screen.getByText("Page 1 of 1")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
  });

  it("cannot be driven out of range by rapid clicks", async () => {
    const onPageChange = vi.fn();
    render(<Pagination page={0} pageCount={2} onPageChange={onPageChange} />);

    // Previous is disabled at the boundary, so no negative page can escape.
    await userEvent.click(screen.getByRole("button", { name: "Previous" }));
    expect(onPageChange).not.toHaveBeenCalled();
  });

  it("is a labelled nav whose summary announces the page change", () => {
    render(<Pagination page={0} pageCount={4} onPageChange={() => {}} label="Trips pagination" />);

    expect(screen.getByRole("navigation", { name: "Trips pagination" })).toBeInTheDocument();
    // None of the hand-rolled copies announced anything, so a screen-reader
    // user pressing Next got no confirmation the page had moved.
    expect(screen.getByText("Page 1 of 4")).toHaveAttribute("aria-live", "polite");
  });
});

describe("CursorPagination", () => {
  it("drives from has-previous/has-next flags instead of a page count", async () => {
    const onNext = vi.fn();
    const onPrevious = vi.fn();
    render(
      <CursorPagination
        hasPrevious={false}
        hasNext
        onPrevious={onPrevious}
        onNext={onNext}
        summary={<>Showing 1–20</>}
      />,
    );

    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Next" }));

    expect(onNext).toHaveBeenCalledTimes(1);
    expect(screen.getByText("Showing 1–20")).toBeInTheDocument();
  });
});
