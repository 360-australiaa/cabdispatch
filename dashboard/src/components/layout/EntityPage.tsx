import { type ReactNode } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { ChevronLeft } from "lucide-react";
import { cn } from "@/lib/utils";
import { ErrorBanner, Skeleton, Tabs, type TabItem } from "@/components/ui";

export type EntityKind = "driver" | "vehicle" | "device" | "trip" | "shift";

export interface EntityFact {
  label: string;
  value: ReactNode;
}

export interface EntityTab {
  value: string;
  label: string;
  content: ReactNode;
}

export interface EntityPageProps {
  kind: EntityKind;
  title: string;
  subtitle?: string;
  avatar?: ReactNode;
  statusBadge?: ReactNode;
  facts?: EntityFact[];
  actions?: ReactNode;
  tabs?: EntityTab[];
  /** Back link, doubling as the breadcrumb trail (see this component's own
   * doc for why a separate `Breadcrumbs` component was skipped). `label`
   * should read as the trail down to (not including) this entity, e.g.
   * "Fleet & Drivers › Drivers" -- the page's own `<h1>{title}</h1>` below
   * is the final crumb. */
  backTo: { label: string; to: string };
  rightRail?: ReactNode;
  isLoading?: boolean;
  error?: ReactNode;
  className?: string;
}

/**
 * Shell for every entity detail page (`/drivers/:id`, `/vehicles/:id`,
 * `/devices/:id`, `/trips/:id`, `/shifts/:id` -- dashboard command-centre
 * plan, F1). Mirrors `PageHeader`'s look (same title/description/actions
 * layout) rather than diverging, plus a back link, a status badge and facts
 * row next to the title, an optional tab bar synced to `?tab=` in the URL,
 * and an optional right rail for "at a glance" cards on wide screens.
 *
 * Breadcrumbs: the plan offered either a standalone `Breadcrumbs` component
 * or folding the trail into the back link. This folds it in -- `backTo`
 * already needs a label and a target, and every crumb before the current
 * page collapses to one "go back to where you'd expect" link. A multi-segment
 * breadcrumb bar would repeat information the sidebar already shows (which
 * section you're in) for a control that, on every other page in this app,
 * is a single "back to the list" link. `backTo.label` carries the trail text
 * (e.g. "Fleet & Drivers › Drivers") so the visual result is the same.
 *
 * Right rail on narrow screens: hidden below `xl:` rather than folded into a
 * tab, per the plan's fallback -- there wasn't a page yet to prove a
 * "Details" tab reads better than simply not showing the rail, and every
 * entity page's real right-rail content is deferred to the workstreams that
 * fill these shells in.
 */
export function EntityPage({
  kind,
  title,
  subtitle,
  avatar,
  statusBadge,
  facts,
  actions,
  tabs,
  backTo,
  rightRail,
  isLoading,
  error,
  className,
}: EntityPageProps) {
  const [searchParams, setSearchParams] = useSearchParams();

  const hasTabs = !!tabs && tabs.length > 0;
  const firstTabValue = tabs?.[0]?.value;
  const requestedTab = searchParams.get("tab");
  const activeTab =
    hasTabs && requestedTab && tabs.some((t) => t.value === requestedTab) ? requestedTab : firstTabValue;

  function handleTabChange(value: string) {
    const next = new URLSearchParams(searchParams);
    next.set("tab", value);
    setSearchParams(next, { replace: true });
  }

  const tabItems: TabItem<string>[] = (tabs ?? []).map((t) => ({ value: t.value, label: t.label }));
  const activeContent = tabs?.find((t) => t.value === activeTab)?.content;

  return (
    <div className={cn("flex flex-col gap-4", className)} data-entity-kind={kind}>
      <Link
        to={backTo.to}
        className="inline-flex w-fit items-center gap-1 text-sm text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        <ChevronLeft className="h-4 w-4" aria-hidden="true" />
        {backTo.label}
      </Link>

      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex items-start gap-3">
          {avatar}
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="text-xl font-semibold text-foreground">{title}</h1>
              {statusBadge}
            </div>
            {subtitle && <p className="mt-1 text-sm text-muted-foreground">{subtitle}</p>}
            {facts && facts.length > 0 && (
              <dl className="mt-2 flex flex-wrap gap-x-6 gap-y-1 text-sm">
                {facts.map((fact, i) => (
                  <div key={i} className="flex items-baseline gap-1.5">
                    <dt className="text-muted-foreground">{fact.label}</dt>
                    <dd className="font-medium text-foreground">{fact.value}</dd>
                  </div>
                ))}
              </dl>
            )}
          </div>
        </div>
        {actions && <div className="flex items-center gap-2">{actions}</div>}
      </div>

      {error ? (
        <ErrorBanner message={error} />
      ) : isLoading ? (
        <div className="flex flex-col gap-3" aria-hidden="true">
          <Skeleton className="h-8 w-64" />
          <Skeleton className="h-40 w-full" />
          <Skeleton className="h-40 w-full" />
        </div>
      ) : (
        <div className={cn("grid grid-cols-1 gap-4", rightRail && "xl:grid-cols-[minmax(0,1fr)_320px]")}>
          <div className="flex min-w-0 flex-col gap-4">
            {hasTabs && (
              <Tabs
                items={tabItems}
                value={activeTab ?? ""}
                onChange={handleTabChange}
                variant="underline"
                label={`${title} sections`}
              />
            )}
            {hasTabs ? activeContent : null}
          </div>
          {rightRail && <div className="hidden xl:block">{rightRail}</div>}
        </div>
      )}
    </div>
  );
}
