import { forwardRef, useEffect, useRef, type InputHTMLAttributes, type ReactNode } from "react";
import { cn } from "@/lib/utils";

export interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "type"> {
  /**
   * Visible label rendered beside the box and wired to it by the `<label>`
   * wrapper, so the whole row is a hit target and screen readers announce the
   * text with the control. The 20 raw `<input type="checkbox">` sites this
   * replaces mostly had a sibling `<span>` with no association at all.
   */
  label?: ReactNode;
  /** Smaller helper line under the label. */
  description?: ReactNode;
  /**
   * Tri-state "some but not all" for a select-all header box. It is a DOM
   * property, not an attribute, so it can only be set imperatively -- which
   * is exactly why every hand-rolled select-all in this codebase either
   * skipped it or re-implemented the same ref/useEffect dance.
   */
  indeterminate?: boolean;
  className?: string;
  /** Class for the outer `<label>` wrapper, when a caller needs row layout. */
  wrapperClassName?: string;
}

/**
 * Checkbox primitive wrapping a real `<input type="checkbox">`.
 *
 * A real input (rather than a styled `<div role="checkbox">`) keeps native
 * form participation, the native Space toggle, and browser/AT checkbox
 * semantics for free. It is styled with `accent-color` so the check mark
 * follows the tenant's brand without a custom SVG.
 */
export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(
  ({ label, description, indeterminate, className, wrapperClassName, disabled, ...props }, ref) => {
    const innerRef = useRef<HTMLInputElement | null>(null);

    useEffect(() => {
      if (innerRef.current) innerRef.current.indeterminate = Boolean(indeterminate);
    }, [indeterminate]);

    const input = (
      <input
        type="checkbox"
        ref={(node) => {
          innerRef.current = node;
          if (typeof ref === "function") ref(node);
          else if (ref) ref.current = node;
        }}
        disabled={disabled}
        // `aria-checked="mixed"` is what actually reaches a screen reader for
        // the indeterminate state; the DOM property alone only changes pixels.
        aria-checked={indeterminate ? "mixed" : undefined}
        className={cn(
          "h-4 w-4 shrink-0 cursor-pointer rounded border-border bg-background accent-[var(--brand-primary)]",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
          "disabled:cursor-not-allowed disabled:opacity-50",
          className,
        )}
        {...props}
      />
    );

    if (!label && !description) return input;

    return (
      <label
        className={cn(
          "flex cursor-pointer items-start gap-2 text-sm",
          disabled && "cursor-not-allowed opacity-60",
          wrapperClassName,
        )}
      >
        <span className="flex h-5 items-center">{input}</span>
        <span className="flex flex-col">
          {/* Plain weight, not `font-medium`: every one of the ~17 raw
              checkboxes this replaced rendered its label at body weight, and
              bolding them all would have been a visible restyle of 15 forms
              smuggled in under a consolidation. */}
          {label && <span className="leading-5">{label}</span>}
          {description && <span className="text-xs text-muted-foreground">{description}</span>}
        </span>
      </label>
    );
  },
);
Checkbox.displayName = "Checkbox";
