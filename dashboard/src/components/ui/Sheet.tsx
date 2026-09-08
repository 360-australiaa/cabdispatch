import { type ReactNode, useEffect } from "react";
import { createPortal } from "react-dom";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";

export interface SheetProps {
  open: boolean;
  onClose: () => void;
  title?: ReactNode;
  description?: ReactNode;
  children?: ReactNode;
  footer?: ReactNode;
  className?: string;
}

/**
 * A panel that slides in from the right edge and leaves the page behind it usable.
 *
 * Deliberately NOT a Modal. `Modal` centres itself over the viewport behind a
 * `bg-black/50` scrim, which is right for "confirm this" and wrong for "look at
 * this vehicle": on Live Map it covered the very map the operator had just flown
 * to, so choosing a vehicle hid the vehicle. A sheet keeps the map on screen, and
 * with no scrim at all the operator can keep panning it, click a second vehicle to
 * swap the sheet's subject, and watch a marker move while reading its detail.
 *
 * The cost of no scrim is that there is no click-outside-to-close, so both of the
 * remaining exits have to be obvious: the X in the header, and Escape.
 *
 * On narrow screens it takes the full width -- there is no useful map left beside
 * a 380px panel on a phone, so it degrades to what Modal was doing anyway.
 */
export function Sheet({ open, onClose, title, description, children, footer, className }: SheetProps) {
  useEffect(() => {
    if (!open) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [open, onClose]);

  if (!open) return null;

  return createPortal(
    <div
      role="dialog"
      aria-modal={false}
      aria-label={typeof title === "string" ? title : undefined}
      className={cn(
        "fixed inset-y-0 right-0 z-50 flex w-full max-w-md flex-col border-l border-border bg-card text-card-foreground shadow-2xl",
        className,
      )}
    >
      <div className="flex items-start justify-between gap-4 border-b border-border p-4">
        <div className="min-w-0">
          {title && <h2 className="truncate text-base font-semibold">{title}</h2>}
          {description && <p className="mt-1 break-all text-sm text-muted-foreground">{description}</p>}
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close"
          className="shrink-0 rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
        >
          <X className="h-4 w-4" />
        </button>
      </div>
      {/* The body scrolls, the header and footer do not -- a vehicle sheet is
          taller than any screen once shift history and driving signals are in it,
          and losing the close button off the top of a scroll is how a panel with
          no scrim becomes a trap. */}
      <div className="min-h-0 flex-1 overflow-y-auto p-4">{children}</div>
      {footer && <div className="flex justify-end gap-2 border-t border-border p-4">{footer}</div>}
    </div>,
    document.body,
  );
}
