import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { Modal } from "./Modal";

/**
 * Modal's contract is its three exits -- the X, the backdrop, and Escape --
 * plus rendering nothing at all when closed. Those are what a caller relies
 * on; the classes are D4's business.
 *
 * UPDATED BY D4. This block previously read: "Note what is NOT asserted here:
 * focus. `Modal` has no focus trap and no focus restore (dashboard audit §6,
 * Modal.tsx:44) ... fixing it belongs to the design-system workstream (D4), so
 * these tests describe the component as it is and the gap is reported rather
 * than papered over with a passing test."
 *
 * D4 is that workstream and has now added the trap and the restore, so the gap
 * D1 deliberately left open is closed and described by the "Modal focus
 * management" block at the bottom of this file. Nothing D1 asserted was
 * weakened to get there -- every original expectation below still stands
 * unchanged; the focus tests are purely additive.
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

/**
 * ADDED BY D4. The dashboard audit (§6) called out `Modal` as having
 * `role="dialog"` and `aria-modal` but neither a focus trap nor a focus
 * restore. `aria-modal` only tells a screen reader to ignore the background --
 * it does nothing for the sighted keyboard user, who could Tab straight out of
 * the dialog and into the form the dialog was covering. These are the tests for
 * the fix.
 */
describe("Modal focus management", () => {
  it("moves focus onto the dialog panel when it opens", () => {
    render(
      <Modal open onClose={() => {}} title="Confirm">
        <button type="button">First</button>
        <button type="button">Second</button>
      </Modal>,
    );

    // The panel rather than the first control: the first control is the X
    // button, and focusing it would let a stray Enter close the dialog the
    // operator just opened. It also lets the title be announced first. This
    // is why D1's "ignores other keys" test still passes unchanged.
    expect(screen.getByRole("dialog")).toHaveFocus();
  });

  it("Tabs from the panel into the dialog's first control", async () => {
    render(
      <Modal open onClose={() => {}} title="Confirm">
        <button type="button">First</button>
      </Modal>,
    );

    await userEvent.tab();

    expect(screen.getByRole("button", { name: "Close" })).toHaveFocus();
  });

  it("keeps focus inside the dialog when it has no caller content", () => {
    render(
      <Modal open onClose={() => {}}>
        <p>Read-only notice</p>
      </Modal>,
    );

    // The X button is always present, so this asserts the general guarantee:
    // focus lands inside the dialog, never left behind on the page underneath.
    expect(screen.getByRole("dialog").contains(document.activeElement)).toBe(true);
  });

  it("restores focus to the element that opened it", async () => {
    function Harness() {
      const [open, setOpen] = useState(false);
      return (
        <>
          <button type="button" onClick={() => setOpen(true)}>
            Open
          </button>
          <Modal open={open} onClose={() => setOpen(false)} title="T" />
        </>
      );
    }
    render(<Harness />);
    const trigger = screen.getByRole("button", { name: "Open" });

    await userEvent.click(trigger);
    expect(trigger).not.toHaveFocus();
    await userEvent.click(screen.getByRole("button", { name: "Close" }));

    // Without this, dismissing a confirm dialog dumps the keyboard user back
    // at the top of the document instead of on the row they were acting on.
    expect(trigger).toHaveFocus();
  });

  it("wraps Tab from the last focusable control back to the first", async () => {
    render(
      <Modal open onClose={() => {}} title="T" footer={<button type="button">Save</button>}>
        <button type="button">Body</button>
      </Modal>,
    );

    // Order in the DOM: Close (header), Body, Save (footer).
    const close = screen.getByRole("button", { name: "Close" });
    screen.getByRole("button", { name: "Save" }).focus();

    await userEvent.tab();

    expect(close).toHaveFocus();
  });

  it("wraps Shift+Tab from the first focusable control to the last", async () => {
    render(
      <Modal open onClose={() => {}} title="T" footer={<button type="button">Save</button>}>
        <button type="button">Body</button>
      </Modal>,
    );
    screen.getByRole("button", { name: "Close" }).focus();

    await userEvent.tab({ shift: true });

    expect(screen.getByRole("button", { name: "Save" })).toHaveFocus();
  });

  it("keeps Tab inside the dialog rather than reaching the page behind it", async () => {
    render(
      <>
        <button type="button">Behind</button>
        <Modal open onClose={() => {}} title="T">
          <button type="button">Inside</button>
        </Modal>
      </>,
    );

    // Three Tabs around a two-control dialog must never land on "Behind" --
    // walking out of the dialog is exactly the defect being fixed.
    await userEvent.tab();
    await userEvent.tab();
    await userEvent.tab();

    expect(screen.getByRole("button", { name: "Behind" })).not.toHaveFocus();
    expect(screen.getByRole("dialog").contains(document.activeElement)).toBe(true);
  });
});
