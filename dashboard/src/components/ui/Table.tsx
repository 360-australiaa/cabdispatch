import { type ReactNode, useMemo, useState } from "react";
import { ChevronDown, ChevronUp, ChevronsUpDown } from "lucide-react";
import { cn } from "@/lib/utils";
import { useI18n } from "@/lib/i18n";
import { LiveRegion } from "./LiveRegion";
import { Pagination } from "./Pagination";
import { Spinner } from "./Spinner";

export interface TableColumn<T> {
  /** Unique key; also used to look up the cell value when `render` is omitted. */
  key: string;
  header: ReactNode;
  render?: (row: T) => ReactNode;
  /** Enables client-side sort on this column (uses row[key] by default). */
  sortable?: boolean;
  sortAccessor?: (row: T) => string | number | Date | null | undefined;
  className?: string;
}

export interface TableProps<T> {
  columns: TableColumn<T>[];
  data: T[];
  rowKey: (row: T) => string;
  /** Client-side pagination. Omit `pageSize` to disable pagination. */
  pageSize?: number;
  emptyState?: ReactNode;
  isLoading?: boolean;
  onRowClick?: (row: T) => void;
  /**
   * Accessible name for the table, e.g. "Trips". Worth setting on any page
   * with more than one table so the screen-reader table list is navigable.
   */
  label?: string;
  /** Renders the header row sticky within a scrolling container. */
  stickyHeader?: boolean;
  className?: string;
}

type SortDirection = "asc" | "desc" | null;

/** Data table with optional client-side column sorting and pagination. */
export function Table<T>({
  columns,
  data,
  rowKey,
  pageSize,
  emptyState,
  isLoading,
  onRowClick,
  label,
  stickyHeader,
  className,
}: TableProps<T>) {
  const [sortKey, setSortKey] = useState<string | null>(null);
  const [sortDir, setSortDir] = useState<SortDirection>(null);
  const [page, setPage] = useState(0);
  const { t } = useI18n();

  const sorted = useMemo(() => {
    if (!sortKey || !sortDir) return data;
    const col = columns.find((c) => c.key === sortKey);
    if (!col) return data;
    const accessor =
      col.sortAccessor ?? ((row: T) => (row as Record<string, unknown>)[col.key] as string | number);
    const copy = [...data];
    copy.sort((a, b) => {
      const av = accessor(a);
      const bv = accessor(b);
      if (av == null && bv == null) return 0;
      if (av == null) return -1;
      if (bv == null) return 1;
      if (av < bv) return sortDir === "asc" ? -1 : 1;
      if (av > bv) return sortDir === "asc" ? 1 : -1;
      return 0;
    });
    return copy;
  }, [data, sortKey, sortDir, columns]);

  const pageCount = pageSize ? Math.max(1, Math.ceil(sorted.length / pageSize)) : 1;

  // Clamp the requested page into range on every render, rather than storing a
  // corrected page in an effect. Without this a caller who filters a list while
  // the operator is on page 3 leaves the table on an out-of-range slice: an
  // empty body under a "Page 3 of 1" pager, Previous enabled and Next disabled.
  // Every page in the dashboard that pairs a filter input with a paginated
  // Table could reproduce it.
  //
  // Derived rather than an effect for two reasons: it renders the corrected
  // page immediately instead of painting the broken frame first and fixing it
  // on the next commit, and clamping (rather than resetting to 0) keeps the
  // operator where they were whenever that page still exists.
  const safePage = Math.min(Math.max(0, page), pageCount - 1);
  const paged = pageSize
    ? sorted.slice(safePage * pageSize, safePage * pageSize + pageSize)
    : sorted;

  function toggleSort(col: TableColumn<T>) {
    if (!col.sortable) return;
    if (sortKey !== col.key) {
      setSortKey(col.key);
      setSortDir("asc");
    } else if (sortDir === "asc") {
      setSortDir("desc");
    } else {
      setSortKey(null);
      setSortDir(null);
    }
  }

  /** `aria-sort` for a header, so the current sort is announced, not just drawn. */
  function ariaSort(col: TableColumn<T>): "ascending" | "descending" | "none" | undefined {
    if (!col.sortable) return undefined;
    if (sortKey !== col.key || !sortDir) return "none";
    return sortDir === "asc" ? "ascending" : "descending";
  }

  return (
    <div className={cn("w-full", className)}>
      {/*
        The async result, announced. A table swapping from a loading row to
        rows (or to an empty state after a filter) was previously a purely
        visual change: nothing told a screen-reader user that the fetch had
        finished, how many rows came back, or that their filter had matched
        nothing. Polite, so it waits for a pause rather than interrupting.
      */}
      <LiveRegion
        message={
          isLoading
            ? t("ui.table.loadingAnnouncement")
            : `${sorted.length} ${sorted.length === 1 ? "row" : "rows"}`
        }
      />
      <div className="overflow-x-auto rounded-lg border border-border">
        <table className="w-full text-sm" aria-label={label}>
          <thead className={cn("bg-muted text-muted-foreground", stickyHeader && "sticky top-0 z-10")}>
            <tr>
              {columns.map((col) => (
                <th
                  key={col.key}
                  scope="col"
                  aria-sort={ariaSort(col)}
                  className={cn("px-4 py-2 text-left font-medium", col.className)}
                >
                  {col.sortable ? (
                    // A real <button> inside the <th>, not an onClick on the
                    // <th> itself: the header was previously mouse-only --
                    // no role, no tabIndex, no key handler -- so column sort
                    // was simply unavailable to a keyboard or screen-reader
                    // user. A button gets Enter/Space, focus styling and the
                    // right role for free.
                    <button
                      type="button"
                      onClick={() => toggleSort(col)}
                      className="inline-flex items-center gap-1 rounded-sm font-medium hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                    >
                      {col.header}
                      {sortKey === col.key ? (
                        sortDir === "asc" ? (
                          <ChevronUp className="h-3.5 w-3.5" aria-hidden="true" />
                        ) : (
                          <ChevronDown className="h-3.5 w-3.5" aria-hidden="true" />
                        )
                      ) : (
                        <ChevronsUpDown className="h-3.5 w-3.5 opacity-40" aria-hidden="true" />
                      )}
                    </button>
                  ) : (
                    col.header
                  )}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {isLoading ? (
              <tr>
                <td colSpan={columns.length} className="px-4 py-6 text-center text-muted-foreground">
                  <span className="inline-flex items-center gap-2">
                    <Spinner size="sm" label={t("ui.table.loadingAnnouncement")} />
                    {t("common.loading")}
                  </span>
                </td>
              </tr>
            ) : paged.length === 0 ? (
              <tr>
                <td colSpan={columns.length} className="px-4 py-6 text-center text-muted-foreground">
                  {emptyState ?? t("ui.table.noResults")}
                </td>
              </tr>
            ) : (
              paged.map((row) => (
                <tr
                  key={rowKey(row)}
                  className={cn(
                    "border-t border-border",
                    onRowClick &&
                      "cursor-pointer hover:bg-muted/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring",
                  )}
                  // Row activation was mouse-only too. A clickable row joins
                  // the tab order and answers Enter/Space, the same contract
                  // the button in the header now has. Rows without an
                  // onRowClick stay inert and out of the tab order.
                  tabIndex={onRowClick ? 0 : undefined}
                  onClick={() => onRowClick?.(row)}
                  onKeyDown={
                    onRowClick
                      ? (e) => {
                          if (e.key === "Enter" || e.key === " ") {
                            // Space would otherwise scroll the page out from
                            // under the row the operator just activated.
                            e.preventDefault();
                            onRowClick(row);
                          }
                        }
                      : undefined
                  }
                >
                  {columns.map((col) => (
                    <td key={col.key} className={cn("px-4 py-2", col.className)}>
                      {col.render
                        ? col.render(row)
                        : String((row as Record<string, unknown>)[col.key] ?? "")}
                    </td>
                  ))}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
      {pageSize && pageCount > 1 && (
        <Pagination page={safePage} pageCount={pageCount} onPageChange={setPage} />
      )}
    </div>
  );
}
