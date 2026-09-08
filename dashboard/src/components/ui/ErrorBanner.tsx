import { AlertTriangle } from "lucide-react";
import { type ReactNode } from "react";
import { cn } from "@/lib/utils";

export interface ErrorBannerProps {
  /** The failure, in plain words. Usually `apiErrorMessage(error, fallback)`. */
  message: ReactNode;
  /** Optional retry button or similar. */
  action?: ReactNode;
  className?: string;
}

/**
 * Inline failure notice for a panel or a page section.
 *
 * Promoted verbatim in behaviour from the private copy in
 * `pages/billing/index.tsx`, which was the only place in the dashboard that
 * had one; everywhere else a failed query rendered as unstyled body text
 * indistinguishable from real content.
 *
 * `role="alert"` (implicitly `aria-live="assertive"`): a request the operator
 * just triggered has failed, which is worth interrupting for -- unlike the
 * polite `Spinner`/`LiveRegion` announcements.
 */
export function ErrorBanner({ message, action, className }: ErrorBannerProps) {
  return (
    <div
      role="alert"
      className={cn(
        "flex items-start gap-2 rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive",
        className,
      )}
    >
      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
      <span className="flex-1">{message}</span>
      {action}
    </div>
  );
}
