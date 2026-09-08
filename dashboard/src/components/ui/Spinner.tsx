import { cn } from "@/lib/utils";

export interface SpinnerProps {
  /** Matches the surrounding type scale: sm ~ inline in a button, lg ~ a whole panel. */
  size?: "sm" | "md" | "lg";
  className?: string;
  /**
   * Screen-reader label. A spinner is the one thing on the page that says
   * "something is happening", so it must say so out loud too -- the literal
   * "Loading…" / "..." strings this replaces were at least readable text; a
   * bare spinning border would be silence.
   */
  label?: string;
}

const SIZES = {
  sm: "h-3.5 w-3.5 border-2",
  md: "h-5 w-5 border-2",
  lg: "h-8 w-8 border-[3px]",
} as const;

/**
 * Indeterminate activity indicator.
 *
 * `role="status"` (not `role="progressbar"`): there is no known completion
 * ratio, and `status` carries an implicit `aria-live="polite"`, so the label
 * is announced when the spinner appears without interrupting whatever the
 * operator is reading.
 */
export function Spinner({ size = "md", className, label = "Loading" }: SpinnerProps) {
  return (
    <span role="status" className={cn("inline-flex items-center", className)}>
      <span
        aria-hidden="true"
        className={cn(
          "inline-block animate-spin rounded-full border-current border-r-transparent align-[-0.125em] opacity-70",
          SIZES[size],
        )}
      />
      <span className="sr-only">{label}</span>
    </span>
  );
}
