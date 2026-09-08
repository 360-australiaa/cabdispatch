import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Modal } from "./Modal";

/**
 * Modal's contract is its three exits -- the X, the backdrop, and Escape --
 * plus rendering nothing at all when closed. Those are what a caller relies
 * on; the classes are D4's business.
 *
 * Note what is NOT asserted here: focus. `Modal` has no focus trap and no
 * focus restore (dashboard audit §6, Modal.tsx:44), so opening one leaves
 * keyboard focus behind on the page underneath and Tab walks straight out of
 * the dialog. That is a real defect, but fixing it belongs to the design-
 * system workstream (D4), so these tests describe the component as it is and
 * the gap is reported rather than papered over with a passing test.
 */
describe("Modal", () => {
  it("renders nothing while closed", () => {
    render(
      <Modal open={false} onClose={() => {}} title="Confirm">
        Body
      </Modal>,
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByText("Body")).not.toBeInTheDocument();
  });

  it("portals a dialog with its title, description and children when open", () => {
    render(
      <Modal open onClose={() => {}} title="Confirm wipe" description="This cannot be undone">
        <p>Are you sure?</p>
      </Modal>,
    );

    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(screen.getByRole("heading", { name: "Confirm wipe" })).toBeInTheDocument();
    expect(screen.getByText("This cannot be undone")).toBeInTheDocument();
    expect(screen.getByText("Are you sure?")).toBeInTheDocument();
    // Portalled to document.body, not nested in the caller's tree.
    expect(dialog.closest("body")).toBe(document.body);
  });

  it("closes on the X button", async () => {
    const onClose = vi.fn();
    render(<Modal open onClose={onClose} title="T" />);

    await userEvent.click(screen.getByRole("button", { name: "Close" }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("closes on a backdrop click", async () => {
    const onClose = vi.fn();
    render(<Modal open onClose={onClose} title="T" />);
    // The backdrop is the dialog's parent -- the scrim element that owns the
    // click handler.
    const backdrop = screen.getByRole("presentation");

    await userEvent.click(backdrop);

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("does NOT close when the click is inside the dialog body", async () => {
    const onClose = vi.fn();
    render(
      <Modal open onClose={onClose} title="T">
        <button type="button">Inner</button>
      </Modal>,
    );

    await userEvent.click(screen.getByRole("button", { name: "Inner" }));

    // stopPropagation on the panel is what makes a form usable inside a modal:
    // without it, every click on a field would dismiss the dialog.
    expect(onClose).not.toHaveBeenCalled();
  });

  it("closes on Escape", async () => {
    const onClose = vi.fn();
    render(<Modal open onClose={onClose} title="T" />);

    await userEvent.keyboard("{Escape}");

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("ignores other keys", async () => {
    const onClose = vi.fn();
    render(<Modal open onClose={onClose} title="T" />);

    await userEvent.keyboard("{Enter}");
    await userEvent.keyboard("a");

    expect(onClose).not.toHaveBeenCalled();
  });

  it("removes its Escape listener once closed", async () => {
    const onClose = vi.fn();
    const { rerender } = render(<Modal open onClose={onClose} title="T" />);

    rerender(<Modal open={false} onClose={onClose} title="T" />);
    await userEvent.keyboard("{Escape}");

    // A leaked keydown listener would fire onClose for a dialog that is
    // already gone -- and, with several modals on a page, close the wrong one.
    expect(onClose).not.toHaveBeenCalled();
  });

  it("renders the footer slot only when given one", () => {
    const { rerender } = render(<Modal open onClose={() => {}} title="T" />);
    expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();

    rerender(
      <Modal open onClose={() => {}} title="T" footer={<button type="button">Save</button>} />,
    );
    expect(screen.getByRole("button", { name: "Save" })).toBeInTheDocument();
  });
});
