import { useEffect, useState } from "react";
import { Outlet } from "react-router-dom";
import { Menu } from "lucide-react";
import { ToastProvider } from "@/components/ui/Toast";
import { useTenantQuery } from "@/hooks/useWhite-labelSettings";
import { Sidebar } from "./Sidebar";

/**
 * Applies the tenant's white-label theme (blueprint 7.2.10/9.1/13.1) globally, not just on the
 * White-label Settings page's own preview card. Every themed surface in this app (Sidebar, Badge,
 * Button, chart accents — see `tailwind.config.js`'s `brand.primary`/`brand.accent` -> `var(--brand-primary)`/
 * `var(--brand-accent)` mapping and `src/index.css`'s `:root` defaults) already reads these two
 * CSS custom properties, so overriding them once here at the app-frame level re-themes the whole
 * dashboard with zero changes needed to any individual page/component. `null`/missing values (no
 * customization set, or the tenant explicitly reset to default) call `removeProperty` rather than
 * setting an empty string, so the cascade falls back to the stylesheet's own default — not "stuck
 * on whatever was set during a previous session's render". Note that falling back to the
 * stylesheet means falling back to whichever `data-theme` block is in effect, so a tenant with no
 * branding set now gets the correct light *or* dark brand colour rather than the light one always.
 */
function useApplyTenantTheme() {
  const { data: tenant } = useTenantQuery();

  useEffect(() => {
    const root = document.documentElement.style;
    const primary = tenant?.theme_json?.primary_color;
    const accent = tenant?.theme_json?.accent_color;
    if (primary) root.setProperty("--brand-primary", primary);
    else root.removeProperty("--brand-primary");
    if (accent) root.setProperty("--brand-accent", accent);
    else root.removeProperty("--brand-accent");
  }, [tenant?.theme_json?.primary_color, tenant?.theme_json?.accent_color]);
}

/** Authenticated app frame: brand sidebar + scrollable content area for the active route. */
export function AppShell() {
  useApplyTenantTheme();
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  // If the viewport grows past the drawer breakpoint while the drawer is open,
  // drop the open state -- otherwise the scrim's state lingers and the next
  // shrink back below `lg` shows a drawer nobody asked for.
  useEffect(() => {
    if (typeof window === "undefined" || !window.matchMedia) return;
    const query = window.matchMedia("(min-width: 1024px)");
    const onChange = (e: MediaQueryListEvent) => {
      if (e.matches) setMobileNavOpen(false);
    };
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);

  return (
    // bg-background (not bg-brand-lavender): --brand-lavender is a surface tint,
    // and page titles rendered straight onto it were unreadable in dark mode
    // before D4 gave it a dark value. --background is the token that has always
    // been maintained for both themes (the same one Card/Modal/Input use), so
    // the shell stays consistent with the rest of the app.
    <div className="flex h-screen bg-background">
      {/* First tab stop on every page: jump past 22 nav links to the content. */}
      <a href="#main-content" className="skip-link rounded-md bg-brand-accent px-3 py-2 text-sm font-medium text-brand-accent-foreground">
        Skip to main content
      </a>

      <Sidebar mobileOpen={mobileNavOpen} onMobileClose={() => setMobileNavOpen(false)} />

      <div className="flex min-w-0 flex-1 flex-col">
        {/* Drawer trigger. Hidden at lg, where the sidebar is always visible. */}
        <header className="flex items-center gap-3 border-b border-border px-4 py-3 lg:hidden">
          <button
            type="button"
            onClick={() => setMobileNavOpen(true)}
            aria-label="Open navigation"
            aria-expanded={mobileNavOpen}
            className="rounded-md p-1.5 text-foreground hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <Menu className="h-5 w-5" />
          </button>
          <span className="text-sm font-semibold">Cab Dispatch</span>
        </header>

        <ToastProvider>
          <main id="main-content" tabIndex={-1} className="flex-1 overflow-y-auto p-4 focus:outline-none sm:p-6">
            <Outlet />
          </main>
        </ToastProvider>
      </div>
    </div>
  );
}
