import { cn } from "@/lib/utils";

export interface SkeletonProps {
  className?: string;
}

/**
 * Grey placeholder block for content that is still loading.
 *
 * Deliberately `aria-hidden`: a skeleton is a visual reassurance that the
 * layout is about to fill in, and reading a dozen of them out as blank
 * regions is noise. The accessible "still loading" announcement is the job of
 * a single `Spinner` (`role="status"`) or the `aria-live` region beside the
 * skeletons -- not of every individual bar.
 */
export function Skeleton({ className }: SkeletonProps) {
  return <div aria-hidden="true" className={cn("animate-pulse rounded-md bg-muted", className)} />;
}

export interface SkeletonTextProps {
  /** Number of stacked lines. The last one renders short, like real prose. */
  lines?: number;
  className?: string;
}

/** Several `Skeleton` bars stacked to stand in for a paragraph or a list. */
export function SkeletonText({ lines = 3, className }: SkeletonTextProps) {
  return (
    <div className={cn("flex flex-col gap-2", className)}>
      {Array.from({ length: lines }, (_, i) => (
        <Skeleton key={i} className={cn("h-4", i === lines - 1 && "w-2/3")} />
      ))}
    </div>
  );
}
