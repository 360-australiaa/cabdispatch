import { useEffect } from "react";
import { Outlet } from "react-router-dom";
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
 * setting an empty string, so the cascade falls back to `:root`'s own default — not "stuck on
 * whatever was set during a previous session's render".
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

  return (
    <div className="flex h-screen bg-brand-lavender">
      <Sidebar />
      <main className="flex-1 overflow-y-auto p-6">
        <Outlet />
      </main>
    </div>
  );
}
