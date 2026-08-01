import { Button } from "@/components/ui";

export interface PaginationBarProps {
  skip: number;
  limit: number;
  total: number;
  onSkipChange: (skip: number) => void;
}

/** Server-side pagination footer — mirrors the shared Table's own pager styling. */
export function PaginationBar({ skip, limit, total, onSkipChange }: PaginationBarProps) {
  if (total <= limit) return null;
  const page = Math.floor(skip / limit) + 1;
  const pageCount = Math.max(1, Math.ceil(total / limit));
  const rangeStart = total === 0 ? 0 : skip + 1;
  const rangeEnd = Math.min(total, skip + limit);

  return (
    <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
      <span>
        {rangeStart}–{rangeEnd} of {total} (page {page} of {pageCount})
      </span>
      <div className="flex gap-2">
        <Button
          variant="outline"
          size="sm"
          disabled={skip === 0}
          onClick={() => onSkipChange(Math.max(0, skip - limit))}
        >
          Previous
        </Button>
        <Button
          variant="outline"
          size="sm"
          disabled={skip + limit >= total}
          onClick={() => onSkipChange(skip + limit)}
        >
          Next
        </Button>
      </div>
    </div>
  );
}
