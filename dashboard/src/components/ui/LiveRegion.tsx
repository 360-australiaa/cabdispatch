import { type ReactNode } from "react";

export interface LiveRegionProps {
  /**
   * The message to announce. Rendering an empty string (rather than
   * unmounting the region) is deliberate: a live region has to be in the DOM
   * *before* its content changes for assistive tech to notice the change, so
   * this component must stay mounted across the whole async cycle.
   */
  message?: ReactNode;
  /** `polite` waits for a pause; `assertive` interrupts. Default polite. */
  politeness?: "polite" | "assertive";
  /** Render the message on screen too, instead of visually hiding it. */
  visible?: boolean;
  className?: string;
}

/**
 * Visually-hidden announcement region for async results.
 *
 * The dashboard had none: a query that finished, a save that succeeded, a
 * filter that reduced a table to nothing were all silent to a screen reader,
 * because the only signal was a visual swap of one block of text for another.
 */
export function LiveRegion({
  message,
  politeness = "polite",
  visible = false,
  className,
}: LiveRegionProps) {
  return (
    <div
      aria-live={politeness}
      // `atomic` so the whole sentence is re-read rather than only the words
      // that changed -- "3" on its own is not an announcement.
      aria-atomic="true"
      className={visible ? className : "sr-only"}
    >
      {message}
    </div>
  );
}
