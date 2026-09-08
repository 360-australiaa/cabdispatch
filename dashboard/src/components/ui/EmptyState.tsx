import { type ComponentType, type ReactNode } from "react";
import { cn } from "@/lib/utils";

export interface EmptyStateProps {
  /** A lucide icon component, e.g. `Inbox`. Optional but recommended. */
  icon?: ComponentType<{ className?: string }>;
  title: ReactNode;
  /**
   * Why it is empty. Operating rule 11 ("an empty state must say why it is
   * empty") makes this the load-bearing part: "No trips" is a shrug, "No
   * trips closed in this date range" tells the operator what to change.
   */
  description?: ReactNode;
  /** Optional call to action -- usually the `Button` that fixes the emptiness. */
  action?: ReactNode;
  className?: string;
}

/** Centred placeholder for a list, table or panel with nothing to show. */
export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  className,
}: EmptyStateProps) {
  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-border px-6 py-10 text-center",
        className,
      )}
    >
      {Icon && <Icon className="h-8 w-8 text-muted-foreground opacity-60" />}
      <p className="text-sm font-medium text-foreground">{title}</p>
      {description && (
        <p className="max-w-md text-sm text-muted-foreground">{description}</p>
      )}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}
