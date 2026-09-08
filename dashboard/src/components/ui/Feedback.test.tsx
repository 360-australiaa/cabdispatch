import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Inbox } from "lucide-react";
import { EmptyState } from "./EmptyState";
import { ErrorBanner } from "./ErrorBanner";
import { LiveRegion } from "./LiveRegion";
import { Skeleton, SkeletonText } from "./Skeleton";
import { Spinner } from "./Spinner";
import { Tooltip } from "./Tooltip";

describe("Spinner", () => {
  it("announces itself as a status with a default label", () => {
    render(<Spinner />);

    // Loading used to be the literal string "Loading…" or "..."; a bare
    // spinning border with no role would have been a regression for AT.
    expect(screen.getByRole("status")).toHaveTextContent("Loading");
  });

  it("takes a caller's label for a specific wait", () => {
    render(<Spinner label="Loading trips" />);

    // The announcement is the visually-hidden text inside the status region,
    // so that is what has to carry the caller's wording.
    expect(screen.getByRole("status")).toHaveTextContent("Loading trips");
  });

  it("scales without changing its semantics", () => {
    const { rerender } = render(<Spinner size="sm" />);
    expect(screen.getByRole("status")).toBeInTheDocument();
    rerender(<Spinner size="lg" />);
    expect(screen.getByRole("status")).toBeInTheDocument();
  });
});

describe("Skeleton", () => {
  it("is hidden from assistive tech", () => {
    const { container } = render(<Skeleton className="h-10" />);

    // A dozen blank regions read aloud is noise; the "still loading"
    // announcement belongs to one Spinner or LiveRegion instead.
    expect(container.firstChild).toHaveAttribute("aria-hidden", "true");
  });

  it("stacks the requested number of lines", () => {
    const { container } = render(<SkeletonText lines={4} />);

    expect(container.querySelectorAll('[aria-hidden="true"]')).toHaveLength(4);
  });
});

describe("EmptyState", () => {
  it("renders a title, the reason it is empty, and an optional action", async () => {
    const onClick = vi.fn();
    render(
      <EmptyState
        icon={Inbox}
        title="No trips"
        description="No trips closed in this date range."
        action={
          <button type="button" onClick={onClick}>
            Clear filters
          </button>
        }
      />,
    );

    expect(screen.getByText("No trips")).toBeInTheDocument();
    // Operating rule 11: an empty state must say WHY it is empty. "No trips"
    // alone is a shrug; the date-range sentence tells the operator what to change.
    expect(screen.getByText("No trips closed in this date range.")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("renders without an icon, description or action", () => {
    render(<EmptyState title="Nothing here" />);

    expect(screen.getByText("Nothing here")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});

describe("ErrorBanner", () => {
  it("is an alert, so a failure the operator caused interrupts", () => {
    render(<ErrorBanner message="Failed to load subscriptions." />);

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Failed to load subscriptions.");
  });

  it("renders a retry action beside the message", async () => {
    const onRetry = vi.fn();
    render(
      <ErrorBanner
        message="Failed to load."
        action={
          <button type="button" onClick={onRetry}>
            Retry
          </button>
        }
      />,
    );

    await userEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});

describe("LiveRegion", () => {
  it("stays mounted while empty so a later change is actually announced", () => {
    const { container, rerender } = render(<LiveRegion />);

    // A live region has to exist BEFORE its content changes for AT to notice.
    // Unmounting it while idle is the classic way to make one silent.
    const region = container.querySelector("[aria-live]");
    expect(region).toBeInTheDocument();
    expect(region).toHaveAttribute("aria-live", "polite");

    rerender(<LiveRegion message="3 trips found" />);
    expect(container.querySelector("[aria-live]")).toHaveTextContent("3 trips found");
  });

  it("is visually hidden by default and visible on request", () => {
    const { container, rerender } = render(<LiveRegion message="Saved" />);
    expect(container.querySelector("[aria-live]")).toHaveClass("sr-only");

    rerender(<LiveRegion message="Saved" visible className="text-sm" />);
    expect(container.querySelector("[aria-live]")).not.toHaveClass("sr-only");
  });

  it("can be made assertive for something that must interrupt", () => {
    const { container } = render(<LiveRegion message="Duress alert" politeness="assertive" />);

    expect(container.querySelector("[aria-live]")).toHaveAttribute("aria-live", "assertive");
  });
});

describe("Tooltip", () => {
  it("shows on hover and hides again", async () => {
    render(
      <Tooltip content="Confirmed 2026-09-01">
        <button type="button">source</button>
      </Tooltip>,
    );

    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();

    await userEvent.hover(screen.getByRole("button", { name: "source" }));
    expect(screen.getByRole("tooltip")).toHaveTextContent("Confirmed 2026-09-01");

    await userEvent.unhover(screen.getByRole("button", { name: "source" }));
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
  });

  it("shows on keyboard focus, which native title= never did", async () => {
    render(
      <Tooltip content="Rotate the device secret">
        <button type="button">Rotate</button>
      </Tooltip>,
    );

    await userEvent.tab();

    // This is the whole reason for replacing `title=`: the keyboard path
    // reaches the same information the mouse path does.
    expect(screen.getByRole("tooltip")).toHaveTextContent("Rotate the device secret");
  });

  it("describes its trigger only while open", async () => {
    render(
      <Tooltip content="Extra detail">
        <button type="button">Info</button>
      </Tooltip>,
    );
    const trigger = screen.getByRole("button", { name: "Info" });

    await userEvent.hover(trigger);
    const tip = screen.getByRole("tooltip");
    expect(trigger.parentElement).toHaveAttribute("aria-describedby", tip.id);
  });

  it("dismisses on Escape", async () => {
    render(
      <Tooltip content="Extra detail">
        <button type="button">Info</button>
      </Tooltip>,
    );

    await userEvent.tab();
    expect(screen.getByRole("tooltip")).toBeInTheDocument();

    await userEvent.keyboard("{Escape}");

    // WAI-ARIA requires a tooltip be dismissible without moving focus, so it
    // cannot sit on top of something the operator is trying to read.
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
  });
});
