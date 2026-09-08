import { useEffect, useRef, type ComponentType, type KeyboardEvent, type ReactNode } from "react";
import { cn } from "@/lib/utils";

export interface TabItem<T extends string> {
  value: T;
  label: ReactNode;
  /** A lucide icon component, e.g. `Car`. Rendered before the label. */
  icon?: ComponentType<{ className?: string }>;
  /** Trailing content -- typically a count `Badge`. */
  badge?: ReactNode;
  disabled?: boolean;
}

export interface TabsProps<T extends string> {
  items: TabItem<T>[];
  value: T;
  onChange: (value: T) => void;
  /**
   * `underline` for a full-width page-level bar, `pill` for a compact
   * segmented control. These are the two styles the dashboard had already
   * grown by hand; keeping both (rather than picking one) means the migration
   * is a pure consolidation and no page visually changes.
   */
  variant?: "underline" | "pill";
  /** Accessible name for the tablist, e.g. "Fleet sections". */
  label?: string;
  className?: string;
}

const LIST_STYLES = {
  underline: "flex gap-1 border-b border-border",
  pill: "inline-flex rounded-md border border-border bg-muted p-1",
} as const;

const TAB_STYLES = {
  underline:
    "flex items-center gap-2 border-b-2 px-4 py-2 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset",
  pill: "inline-flex items-center gap-1.5 rounded-sm px-3 py-1.5 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
} as const;

const ACTIVE_STYLES = {
  // The `dark:` half is load-bearing, not decoration: --brand-primary is the
  // dark indigo used for brand chrome, so on a dark page an active tab drawn
  // in it would be invisible against the background -- the same failure mode
  // as the --brand-lavender bug this workstream fixed. In dark the accent
  // (gold) is the readable brand colour. These utilities only work because
  // `darkMode` now points at the `data-theme` attribute that is actually set.
  underline: "border-brand-primary text-brand-primary dark:border-brand-accent dark:text-brand-accent",
  pill: "bg-card text-foreground shadow-sm",
} as const;

const INACTIVE_STYLES = {
  underline: "border-transparent text-muted-foreground hover:text-foreground",
  pill: "text-muted-foreground hover:text-foreground",
} as const;

/**
 * Tab bar with real `tablist`/`tab` semantics.
 *
 * Replaces ten hand-rolled bars across the pages (two of which built their
 * class strings by concatenation rather than `cn()`, and none of which set a
 * single ARIA attribute -- to a screen reader they were an undifferentiated
 * row of buttons).
 *
 * Keyboard model is the WAI-ARIA "tabs with automatic activation" pattern:
 * only the selected tab is in the tab order (roving `tabIndex`), so Tab moves
 * *past* the bar into the panel instead of walking through every tab, and
 * Left/Right/Home/End move between tabs, selecting as they go. Selection
 * follows focus because every panel in this dashboard is already rendered
 * client-side -- there is no fetch to make eager activation expensive.
 */
export function Tabs<T extends string>({
  items,
  value,
  onChange,
  variant = "underline",
  label,
  className,
}: TabsProps<T>) {
  const listRef = useRef<HTMLDivElement | null>(null);
  /** Set by a keyboard move; consumed once the parent commits the new value. */
  const pendingFocus = useRef<T | null>(null);

  /** Move selection by `delta` (or to an absolute index) over the enabled tabs, wrapping. */
  function move(fromIndex: number, delta: number) {
    const enabled = items.filter((i) => !i.disabled);
    if (enabled.length === 0) return;
    const currentEnabled = enabled.findIndex((i) => i.value === items[fromIndex]?.value);
    const base = currentEnabled === -1 ? 0 : currentEnabled;
    const next = enabled[(base + delta + enabled.length) % enabled.length];
    focusAndSelect(next.value);
  }

  function focusAndSelect(next: T) {
    // The newly selected tab is the only one in the tab order, so focus has to
    // follow it or the roving tabIndex strands the keyboard user on an element
    // that just became tabIndex={-1}. `value` is controlled by the parent, so
    // the tab to focus does not exist with tabIndex=0 until after that parent
    // re-renders -- hence the flag here and the effect below, rather than
    // focusing inline.
    pendingFocus.current = next;
    onChange(next);
  }

  useEffect(() => {
    const pending = pendingFocus.current;
    if (pending == null || pending !== value) return;
    pendingFocus.current = null;
    listRef.current
      ?.querySelector<HTMLButtonElement>(`[data-tab-value="${CSS.escape(value)}"]`)
      ?.focus();
  }, [value]);

  function onKeyDown(e: KeyboardEvent<HTMLButtonElement>, index: number) {
    switch (e.key) {
      case "ArrowRight":
      case "ArrowDown":
        e.preventDefault();
        move(index, 1);
        break;
      case "ArrowLeft":
      case "ArrowUp":
        e.preventDefault();
        move(index, -1);
        break;
      case "Home": {
        e.preventDefault();
        const first = items.find((i) => !i.disabled);
        if (first) focusAndSelect(first.value);
        break;
      }
      case "End": {
        e.preventDefault();
        const last = [...items].reverse().find((i) => !i.disabled);
        if (last) focusAndSelect(last.value);
        break;
      }
      default:
        break;
    }
  }

  return (
    <div
      ref={listRef}
      role="tablist"
      aria-label={label}
      className={cn(LIST_STYLES[variant], className)}
    >
      {items.map((item, index) => {
        const { value: itemValue, label: itemLabel, icon: Icon, badge, disabled } = item;
        const selected = itemValue === value;
        return (
          <button
            key={itemValue}
            type="button"
            role="tab"
            id={`tab-${itemValue}`}
            data-tab-value={itemValue}
            aria-selected={selected}
            aria-controls={`tabpanel-${itemValue}`}
            tabIndex={selected ? 0 : -1}
            disabled={disabled}
            onClick={() => onChange(itemValue)}
            onKeyDown={(e) => onKeyDown(e, index)}
            className={cn(
              TAB_STYLES[variant],
              selected ? ACTIVE_STYLES[variant] : INACTIVE_STYLES[variant],
              disabled && "cursor-not-allowed opacity-50",
            )}
          >
            {Icon && <Icon className="h-4 w-4" />}
            {itemLabel}
            {badge}
          </button>
        );
      })}
    </div>
  );
}

export interface TabPanelProps<T extends string> {
  /** The tab this panel belongs to; must match a `TabItem.value`. */
  value: T;
  children: ReactNode;
  className?: string;
}

/**
 * Wrapper that ties a panel back to its tab (`aria-labelledby`) and puts it in
 * the tab order, so Tab from the selected tab lands in the content it selected.
 * Optional -- callers that render their panel inline still work -- but using it
 * is what makes the tablist navigable rather than merely labelled.
 */
export function TabPanel<T extends string>({ value, children, className }: TabPanelProps<T>) {
  return (
    <div
      role="tabpanel"
      id={`tabpanel-${value}`}
      aria-labelledby={`tab-${value}`}
      tabIndex={0}
      className={cn("focus-visible:outline-none", className)}
    >
      {children}
    </div>
  );
}
