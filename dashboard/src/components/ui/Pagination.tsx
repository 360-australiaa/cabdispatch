import { type ReactNode } from "react";
import { cn } from "@/lib/utils";
import { useI18n } from "@/lib/i18n";
import { Button } from "./Button";

export interface PaginationProps {
  /** Zero-based current page index. */
  page: number;
  /** Total number of pages. Values below 1 are treated as 1. */
  pageCount: number;
  onPageChange: (page: number) => void;
  /**
   * Replaces the default "Page 1 of 4" summary. Most call sites want the row
   * count in there too ("128 trips — page 1 of 7"), which is why this takes a
   * node rather than the component trying to guess a noun for every list.
   */
  summary?: ReactNode;
  /** Accessible name, when a page shows more than one pager. */
  label?: string;
  className?: string;
}

/**
 * Previous/next pager.
 *
 * Replaces eleven copy-pasted prev/next blocks plus `fleet/PaginationBar.tsx`.
 * Beyond the duplication, the copies disagreed on details that matter: some
 * disabled Next on the last page and some did not, and none of them were a
 * `<nav>` or announced the page change, so a screen-reader user pressing Next
 * got no confirmation that anything had happened.
 *
 * `aria-live="polite"` on the summary is what closes that gap: the new "page 2
 * of 7" is read out after the click.
 */
export function Pagination({
  page,
  pageCount,
  onPageChange,
  summary,
  label = "Pagination",
  className,
}: PaginationProps) {
  const total = Math.max(1, pageCount);
  const isFirst = page <= 0;
  const isLast = page >= total - 1;
  const { t } = useI18n();

  return (
    <nav
      aria-label={label}
      className={cn(
        "mt-3 flex items-center justify-between gap-4 text-sm text-muted-foreground",
        className,
      )}
    >
      <span aria-live="polite">{summary ?? t("ui.pagination.pageOf", { page: page + 1, total })}</span>
      <div className="flex gap-2">
        <Button
          variant="outline"
          size="sm"
          disabled={isFirst}
          onClick={() => onPageChange(Math.max(0, page - 1))}
        >
          {t("common.previous")}
        </Button>
        <Button
          variant="outline"
          size="sm"
          disabled={isLast}
          onClick={() => onPageChange(Math.min(total - 1, page + 1))}
        >
          {t("common.next")}
        </Button>
      </div>
    </nav>
  );
}

export interface CursorPaginationProps {
  /** True while a previous page exists (offset > 0, or a back cursor is held). */
  hasPrevious: boolean;
  /** True while the server says more rows follow. */
  hasNext: boolean;
  onPrevious: () => void;
  onNext: () => void;
  summary?: ReactNode;
  label?: string;
  className?: string;
}

/**
 * Pager for lists that page by cursor or offset and therefore do not know
 * their total page count -- several dashboard endpoints return only a
 * `has_more` flag. Same shape and same controls as `Pagination` so the two
 * read identically on screen.
 */
export function CursorPagination({
  hasPrevious,
  hasNext,
  onPrevious,
  onNext,
  summary,
  label = "Pagination",
  className,
}: CursorPaginationProps) {
  const { t } = useI18n();
  return (
    <nav
      aria-label={label}
      className={cn(
        "mt-3 flex items-center justify-between gap-4 text-sm text-muted-foreground",
        className,
      )}
    >
      <span aria-live="polite">{summary}</span>
      <div className="flex gap-2">
        <Button variant="outline" size="sm" disabled={!hasPrevious} onClick={onPrevious}>
          {t("common.previous")}
        </Button>
        <Button variant="outline" size="sm" disabled={!hasNext} onClick={onNext}>
          {t("common.next")}
        </Button>
      </div>
    </nav>
  );
}
