import { useId, useState, type ReactNode } from "react";
import { cn } from "@/lib/utils";

export interface TooltipProps {
  /** The tip text. Keep it short -- this is a hint, not documentation. */
  content: ReactNode;
  children: ReactNode;
  side?: "top" | "bottom";
  className?: string;
}

/**
 * Hover/focus tooltip replacing the native `title=` attribute.
 *
 * `title=` looked free but is not usable: it never appears on keyboard focus,
 * is invisible on touch, cannot be styled, and its ~1s browser delay means an
 * operator scanning a row of icon buttons never sees it. This renders a real
 * element, associates it with `aria-describedby`, and -- crucially -- shows on
 * focus as well as hover, so the keyboard path reaches the same information
 * the mouse path does.
 *
 * The trigger is a `<span tabIndex={0}>` only when its child is not already
 * focusable; callers wrapping a `Button` should pass the button as the child
 * and it keeps its own focus behaviour.
 */
export function Tooltip({ content, children, side = "top", className }: TooltipProps) {
  const [open, setOpen] = useState(false);
  const id = useId();

  return (
    <span
      className="relative inline-flex"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onFocus={() => setOpen(true)}
      onBlur={() => setOpen(false)}
      // Escape dismisses a tooltip that is covering something the operator
      // wants to read -- WAI-ARIA requires it and `title=` never offered it.
      onKeyDown={(e) => {
        if (e.key === "Escape") setOpen(false);
      }}
    >
      <span aria-describedby={open ? id : undefined} className="inline-flex">
        {children}
      </span>
      {open && (
        <span
          role="tooltip"
          id={id}
          className={cn(
            "pointer-events-none absolute left-1/2 z-50 w-max max-w-xs -translate-x-1/2 rounded-md border border-border bg-card px-2 py-1 text-xs text-card-foreground shadow-md",
            side === "top" ? "bottom-full mb-1.5" : "top-full mt-1.5",
            className,
          )}
        >
          {content}
        </span>
      )}
    </span>
  );
}
