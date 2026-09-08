import { type ReactNode, useCallback, useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";

export interface ModalProps {
  open: boolean;
  onClose: () => void;
  title?: ReactNode;
  description?: ReactNode;
  children?: ReactNode;
  footer?: ReactNode;
  className?: string;
}

/** Everything that can hold keyboard focus, in DOM order. */
const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Centered dialog rendered via portal. Closes on backdrop click or Escape.
 *
 * Focus management (added by D4; the dialog previously had `role="dialog"` and
 * `aria-modal` but neither a trap nor a restore, so opening one left the
 * keyboard behind on the page underneath and Tab walked straight out of the
 * dialog into the form the operator had just covered up). Three parts:
 *
 * 1. **Restore** -- the element that was focused when the modal opened is
 *    remembered and re-focused on close, so dismissing a confirm dialog puts
 *    the caret back on the button that opened it rather than at the top of
 *    the document.
 * 2. **Initial focus** -- moves into the dialog on open, preferring the first
 *    focusable control and falling back to the panel itself.
 * 3. **Trap** -- Tab/Shift+Tab wrap within the dialog. `aria-modal` alone only
 *    tells a screen reader to ignore the background; it does nothing for the
 *    sighted keyboard user, which is the case that actually broke here.
 */
export function Modal({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  className,
}: ModalProps) {
  const panelRef = useRef<HTMLDivElement | null>(null);
  const restoreRef = useRef<HTMLElement | null>(null);

  const focusables = useCallback((): HTMLElement[] => {
    const panel = panelRef.current;
    if (!panel) return [];
    return Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((el) => {
      // A hidden control must not swallow a Tab stop. Deliberately NOT
      // `offsetParent !== null`: that is a layout query, and jsdom does no
      // layout, so under test every control would look hidden and the trap
      // would have nothing to cycle through. These two checks are the ones
      // that mean the same thing in a real browser and in jsdom.
      if (el.closest("[hidden]")) return false;
      if (el.getAttribute("aria-hidden") === "true") return false;
      return true;
    });
  }, []);

  // Remember the trigger and move focus in. Runs only on the open transition,
  // not on every re-render, so typing in the dialog does not yank focus back
  // to the first field.
  useEffect(() => {
    if (!open) return;
    restoreRef.current = document.activeElement as HTMLElement | null;
    // Focus the panel, not the first control. The first control is usually the
    // X button, and focusing it would mean an immediate Enter closes the
    // dialog the operator just opened. Landing on the panel also lets a screen
    // reader announce the dialog's title and description before the operator
    // Tabs into the form.
    panelRef.current?.focus();

    return () => {
      // Guard against restoring to a node that has since been removed from
      // the document (e.g. the row whose Delete button opened this dialog).
      const target = restoreRef.current;
      if (target && document.contains(target)) target.focus();
    };
  }, [open, focusables]);

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        onClose();
        return;
      }
      if (e.key !== "Tab") return;

      const items = focusables();
      if (items.length === 0) {
        // Nothing to cycle through: keep focus on the panel rather than
        // letting Tab escape to the page behind.
        e.preventDefault();
        panelRef.current?.focus();
        return;
      }
      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement;

      if (e.shiftKey && (active === first || active === panelRef.current)) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      } else if (active instanceof Node && !panelRef.current?.contains(active)) {
        // Focus somehow left the dialog (browser chrome, a stray
        // programmatic focus) -- pull it back to the top of the cycle.
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [open, onClose, focusables]);

  if (!open) return null;

  return createPortal(
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      onClick={onClose}
      role="presentation"
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        // -1 so the panel can hold focus as a fallback when the dialog has no
        // focusable content, without joining the Tab order itself.
        tabIndex={-1}
        className={cn(
          "w-full max-w-lg rounded-lg border border-border bg-card text-card-foreground shadow-lg focus:outline-none",
          className,
        )}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-4 p-4">
          <div>
            {title && <h2 className="text-base font-semibold">{title}</h2>}
            {description && (
              <p className="mt-1 text-sm text-muted-foreground">{description}</p>
            )}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="px-4 pb-4">{children}</div>
        {footer && (
          <div className="flex justify-end gap-2 border-t border-border p-4">{footer}</div>
        )}
      </div>
    </div>,
    document.body,
  );
}
