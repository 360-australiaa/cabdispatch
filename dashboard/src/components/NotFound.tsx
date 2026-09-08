import { Link, useLocation } from "react-router-dom";
import { buttonVariants } from "@/components/ui";

/**
 * A real 404 page.
 *
 * What this replaces: `router.tsx`'s wildcard was
 * `<Navigate to="/live-map" replace />`, so every unmatched path silently
 * became the live map. A typo in a URL, a link to a page that has since been
 * renamed, or a bookmark from an older build all looked like a *successful*
 * navigation to a different page than the one asked for -- and because the
 * redirect was `replace`, the wrong URL was gone from history too, so there
 * was nothing to read back and no Back button to undo it. An operator sent a
 * deep link to a specific trip would land on the map and reasonably conclude
 * the trip did not exist.
 *
 * Showing the path that was not found is the point: it is the one piece of
 * information that tells the user whether they mistyped it or whether the
 * link they were given is wrong.
 */
export default function NotFound() {
  const { pathname } = useLocation();

  return (
    <div
      className="flex min-h-[60vh] flex-col items-center justify-center gap-4 p-8 text-center"
      data-testid="not-found"
    >
      <div className="max-w-md">
        <p className="text-sm font-semibold text-muted-foreground">404</p>
        <h1 className="mt-1 text-xl font-semibold text-foreground">Page not found</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          There is no page at this address. Check the link, or pick a section from the sidebar.
        </p>
        <p className="mt-4 break-all rounded-md border border-border bg-muted p-3 font-mono text-xs text-muted-foreground">
          {pathname}
        </p>
      </div>
      {/* A real <Link>, not a Button with an onClick: this is navigation, so
          it must be middle-clickable and copyable like any other link. The
          kit has no `asChild`, so it borrows `buttonVariants` for the look. */}
      <Link to="/live-map" className={buttonVariants()}>
        Go to live map
      </Link>
    </div>
  );
}
