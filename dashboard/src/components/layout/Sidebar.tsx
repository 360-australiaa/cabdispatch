import { useEffect, useState } from "react";
import { NavLink, useLocation } from "react-router-dom";
import {
  ListChecks,
  Map,
  Send,
  MessageSquare,
  ShieldAlert,
  Route,
  Clock,
  Receipt,
  Wallet,
  Car,
  FileCheck2,
  CreditCard,
  Palette,
  ShieldCheck,
  Landmark,
  LogOut,
  Building2,
  MapPinned,
  ScrollText,
  Ticket,
  Megaphone,
  Trophy,
  Coins,
  Star,
  ChevronDown,
  X,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { useAuth } from "@/lib/auth";
import { isPlatformOwner } from "@/lib/platformAdmin";
import { ThemeToggle } from "@/components/ui/ThemeToggle";
import { useTenantQuery } from "@/hooks/useWhite-labelSettings";
import { useI18n, getJurisdictionCapabilities, type I18nKey } from "@/lib/i18n";
// Same rollup Fleet & Drivers' own ComplianceExpiryBanner reads
// (`GET /v1/fleet/compliance-expiry`) -- surfaced here too as an ambient
// sidebar count so an expiring licence/rego/insurance is visible from every
// page, not just to a dispatcher who happens to open Fleet & Drivers.
import { useComplianceExpiry } from "@/pages/fleet/api";
import { useResetOnChange } from "@/lib/useResetOnChange";

interface NavItem {
  to: string;
  labelKey: I18nKey;
  icon: typeof Map;
}

interface NavGroup {
  id: string;
  labelKey: I18nKey;
  items: NavItem[];
}

/**
 * Pinned above the groups: a plain checklist (real live checks, no fabricated
 * progress) that's most useful the moment a new tenant logs in, before they've
 * ever added a vehicle/driver/tariff. Never gated: a returning operator can
 * ignore or revisit it same as any other nav item.
 */
const GETTING_STARTED: NavItem = {
  to: "/getting-started",
  labelKey: "nav.gettingStarted",
  icon: ListChecks,
};

/**
 * Route/label/icon list for the fleet-ops modules, grouped by the job the
 * operator is doing. Keep in sync with router.tsx.
 *
 * The grouping is the point: 22 flat items is past the length where a nav list
 * can be scanned, and it put "Ratings" and "Duress Desk" at the same visual
 * weight. Six labelled groups of three to five is scannable, and each group is
 * collapsible so a dispatcher who never touches Billing can fold it away.
 */
const NAV_GROUPS: NavGroup[] = [
  {
    id: "operations",
    labelKey: "nav.group.operations",
    items: [
      { to: "/live-map", labelKey: "nav.liveMap", icon: Map },
      { to: "/dispatch", labelKey: "nav.dispatch", icon: Send },
      { to: "/messages", labelKey: "nav.messages", icon: MessageSquare },
      { to: "/duress", labelKey: "nav.duressDesk", icon: ShieldAlert },
    ],
  },
  {
    id: "trips",
    labelKey: "nav.group.trips",
    // "PSL Centre" is filtered out of `items` at render time for a tenant
    // without the `psl` jurisdiction capability -- see
    // `getJurisdictionCapabilities` (`lib/i18n/jurisdiction.ts`).
    items: [
      { to: "/trips", labelKey: "nav.trips", icon: Route },
      { to: "/shifts", labelKey: "nav.shifts", icon: Clock },
      { to: "/tariffs", labelKey: "nav.tariffStudio", icon: Receipt },
      { to: "/zones", labelKey: "nav.zones", icon: MapPinned },
      { to: "/psl", labelKey: "nav.pslCentre", icon: Wallet },
    ],
  },
  {
    id: "fleet",
    labelKey: "nav.group.fleet",
    items: [
      { to: "/fleet", labelKey: "nav.fleetDrivers", icon: Car },
      { to: "/compliance", labelKey: "nav.complianceVault", icon: FileCheck2 },
    ],
  },
  {
    id: "revenue",
    labelKey: "nav.group.revenue",
    items: [
      { to: "/billing", labelKey: "nav.billing", icon: CreditCard },
      { to: "/payment-recon", labelKey: "nav.paymentRecon", icon: Landmark },
      { to: "/vouchers", labelKey: "nav.vouchers", icon: Ticket },
    ],
  },
  {
    id: "engagement",
    labelKey: "nav.group.engagement",
    // The tablet's Announcements / Incentive Progress / Wallet tiles.
    // Announcement/incentive list+get are open to any tenant user server-side,
    // writes are owner/admin gated in-page (`canWrite`), same as Vouchers.
    // /wallet is owner/admin server-side and renders a notice for other roles
    // (see pages/driver-engagement/WalletPage.tsx). GET /v1/ratings is
    // owner/admin only server-side (app/api/v1/ratings.py's `_require_admin`)
    // -- the nav item stays visible to every role, same "let the page itself
    // render the access notice" convention as Wallet.
    items: [
      { to: "/announcements", labelKey: "nav.announcements", icon: Megaphone },
      { to: "/incentives", labelKey: "nav.incentives", icon: Trophy },
      { to: "/wallet", labelKey: "nav.driverWallets", icon: Coins },
      { to: "/ratings", labelKey: "nav.ratings", icon: Star },
    ],
  },
  {
    id: "admin",
    labelKey: "nav.group.admin",
    // GET /v1/audit-log has no role gate server-side (any authenticated tenant
    // user may read the trail) -- so this nav item stays visible to every
    // role, same as every other item above. Only the in-page "Verify chain"
    // action is owner/admin gated (see pages/audit-log/index.tsx's `canVerify`).
    items: [
      { to: "/audit-log", labelKey: "nav.auditLog", icon: ScrollText },
      { to: "/settings/white-label", labelKey: "nav.whiteLabel", icon: Palette },
      { to: "/settings/security", labelKey: "nav.security", icon: ShieldCheck },
    ],
  },
];

/** Platform-owner-only nav item — see src/lib/platformAdmin.ts and
 * src/components/PlatformOwnerRoute.tsx for the matching route guard. */
const PLATFORM_NAV_ITEM: NavItem = { to: "/platform", labelKey: "nav.platformAdmin", icon: Building2 };

const COLLAPSED_STORAGE_KEY = "cabdispatch.sidebar.collapsedGroups";

/** Shown until a tenant's own name/logo (`theme_json`) loads — see the
 * `logoUrl` branch below. Not itself translated: it's the platform's own
 * name, not a UI label, same reasoning as the login page's title. */
const BRAND_DEFAULT_NAME = "Cab Dispatch";

export interface SidebarProps {
  /** Drawer visibility on small screens. Ignored at `lg` and up. */
  mobileOpen?: boolean;
  onMobileClose?: () => void;
}

/**
 * Fleet-ops navigation.
 *
 * Responsive behaviour, which the app previously had none of (it was a fixed
 * `w-64 h-screen` with no breakpoint, so on a phone or a tablet in portrait
 * the sidebar ate most of the viewport and the content was unusable):
 * at `lg` and up it is the static column it always was; below `lg` it becomes
 * an off-canvas drawer opened from the header's menu button, with a scrim,
 * Escape to dismiss, and auto-close on navigation.
 */
export function Sidebar({ mobileOpen = false, onMobileClose }: SidebarProps) {
  const { user, logout } = useAuth();
  const location = useLocation();
  // White-label branding (blueprint 7.2.10/9.1/13.1) — the same tenant record
  // AppShell's useApplyTenantTheme() reads for --brand-primary/--brand-accent; React Query's
  // cache means this second useTenantQuery() call here doesn't refetch, just re-reads the same
  // cached result. A real `logo_url` replaces the "CD" placeholder badge + fixed "Cab Dispatch"
  // label with the tenant's actual name; falls back to the platform default when unset, same
  // "no customization -> platform default" convention as the theme colors themselves.
  const { data: tenant } = useTenantQuery();
  const logoUrl = tenant?.theme_json?.logo_url;
  const { t } = useI18n();
  const jurisdiction = getJurisdictionCapabilities(tenant);

  const baseGroups: NavGroup[] = NAV_GROUPS.map((group) =>
    group.id === "trips" && !jurisdiction.psl
      ? { ...group, items: group.items.filter((item) => item.to !== "/psl") }
      : group,
  );

  const groups: NavGroup[] = isPlatformOwner(user)
    ? [
        ...baseGroups.slice(0, -1),
        {
          ...baseGroups[baseGroups.length - 1],
          items: [...baseGroups[baseGroups.length - 1].items, PLATFORM_NAV_ITEM],
        },
      ]
    : baseGroups;

  const [collapsed, setCollapsed] = useState<Set<string>>(readCollapsed);

  function toggleGroup(id: string) {
    setCollapsed((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      try {
        window.localStorage.setItem(COLLAPSED_STORAGE_KEY, JSON.stringify([...next]));
      } catch {
        // Storage unavailable: the fold still applies for this session.
      }
      return next;
    });
  }

  // Sidebar renders on every authenticated page, so this one query (60s
  // refetch, same interval the banner itself uses) is how the count reaches
  // every screen, not just Fleet & Drivers/Live Map.
  const complianceExpiryQuery = useComplianceExpiry();
  const complianceExpiryCount = complianceExpiryQuery.data?.items.length ?? 0;

  // Navigating from the drawer should close it -- otherwise the operator taps a
  // link on a phone and the page they asked for is behind the panel.
  //
  // Not a form reset, but exactly the same hazard `useResetOnChange` exists
  // for: this must run on a route change *only*. Listing `mobileOpen` and
  // `onMobileClose` as dependencies -- which is what the rule wants -- would
  // close the drawer the instant it opened, because opening it is itself a
  // change to `mobileOpen`. The hook keeps the callback in a ref, so the
  // route is the only thing that can trigger it.
  useResetOnChange(location.pathname, () => {
    if (mobileOpen) onMobileClose?.();
  });

  useEffect(() => {
    if (!mobileOpen) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") onMobileClose?.();
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [mobileOpen, onMobileClose]);

  return (
    <>
      {/* Scrim, drawer-only. `lg:hidden` so it can never trap clicks on desktop. */}
      {mobileOpen && (
        <div
          className="fixed inset-0 z-40 bg-black/50 lg:hidden"
          onClick={onMobileClose}
          role="presentation"
        />
      )}

      <aside
        aria-label="Main navigation"
        className={cn(
          "flex w-64 shrink-0 flex-col bg-brand-primary text-brand-primary-foreground",
          // Drawer below lg: fixed, full height, slid off-canvas when closed.
          // `transition-transform` rather than mounting/unmounting so the panel
          // keeps its scroll position and the animation is GPU-cheap.
          "fixed inset-y-0 left-0 z-50 transition-transform duration-200 lg:static lg:h-screen lg:translate-x-0",
          mobileOpen ? "translate-x-0" : "-translate-x-full",
        )}
      >
        <div className="flex items-center gap-2 px-5 py-5">
          {logoUrl ? (
            <img
              src={logoUrl}
              alt={`${tenant?.name ?? "Fleet"} logo`}
              className="h-8 max-w-[140px] object-contain"
            />
          ) : (
            <>
              <div className="flex h-8 w-8 items-center justify-center rounded-md bg-brand-accent text-brand-accent-foreground font-bold">
                CD
              </div>
              <span className="text-sm font-semibold tracking-wide">
                {tenant?.name ?? BRAND_DEFAULT_NAME}
              </span>
            </>
          )}
          <button
            type="button"
            onClick={onMobileClose}
            aria-label="Close navigation"
            className="ml-auto rounded-md p-1 text-white/70 hover:bg-white/10 hover:text-white lg:hidden"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <nav className="flex-1 overflow-y-auto px-3 py-2">
          <ul className="flex flex-col gap-1">
            <li>
              <NavItemLink item={GETTING_STARTED} complianceExpiryCount={complianceExpiryCount} />
            </li>
          </ul>

          {groups.map((group) => {
            const isCollapsed = collapsed.has(group.id);
            return (
              <div key={group.id} className="mt-4">
                <button
                  type="button"
                  onClick={() => toggleGroup(group.id)}
                  aria-expanded={!isCollapsed}
                  aria-controls={`nav-group-${group.id}`}
                  className="flex w-full items-center gap-1 rounded-md px-3 py-1 text-[11px] font-semibold uppercase tracking-wider text-white/50 transition-colors hover:text-white/80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-accent"
                >
                  <ChevronDown
                    className={cn(
                      "h-3 w-3 transition-transform",
                      isCollapsed && "-rotate-90",
                    )}
                    aria-hidden="true"
                  />
                  {t(group.labelKey)}
                </button>
                {!isCollapsed && (
                  <ul id={`nav-group-${group.id}`} className="mt-1 flex flex-col gap-1">
                    {group.items.map((item) => (
                      <li key={item.to}>
                        <NavItemLink item={item} complianceExpiryCount={complianceExpiryCount} />
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            );
          })}
        </nav>

        <div className="border-t border-white/10 px-3 py-3">
          <div className="mb-2 flex items-center gap-2 px-2">
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium">{user?.name ?? "—"}</p>
              <p className="truncate text-xs text-white/60">{user?.role ?? ""}</p>
            </div>
            <ThemeToggle />
          </div>
          <button
            type="button"
            onClick={logout}
            className="flex w-full items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-white/80 transition-colors hover:bg-white/10 hover:text-white"
          >
            <LogOut className="h-4 w-4" />
            {t("nav.logout")}
          </button>
        </div>
      </aside>
    </>
  );
}

function NavItemLink({
  item: { to, labelKey, icon: Icon },
  complianceExpiryCount,
}: {
  item: NavItem;
  complianceExpiryCount: number;
}) {
  const { t } = useI18n();
  const label = t(labelKey);
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        cn(
          "flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-white/80 transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-accent",
          isActive && "bg-brand-accent text-brand-accent-foreground hover:bg-brand-accent",
        )
      }
    >
      <Icon className="h-4 w-4 shrink-0" aria-hidden="true" />
      <span className="truncate">{label}</span>
      {to === "/fleet" && complianceExpiryCount > 0 && (
        <span
          className="ml-auto inline-flex h-5 min-w-[1.25rem] shrink-0 items-center justify-center rounded-full bg-destructive px-1.5 text-[11px] font-semibold text-destructive-foreground"
          aria-label={`${complianceExpiryCount} accreditation/registration item${complianceExpiryCount === 1 ? "" : "s"} expiring or expired`}
        >
          {complianceExpiryCount}
        </span>
      )}
    </NavLink>
  );
}

function readCollapsed(): Set<string> {
  try {
    const raw = window.localStorage.getItem(COLLAPSED_STORAGE_KEY);
    if (raw) {
      const parsed: unknown = JSON.parse(raw);
      if (Array.isArray(parsed)) return new Set(parsed.filter((v): v is string => typeof v === "string"));
    }
  } catch {
    // Corrupt or unavailable storage: start with everything expanded.
  }
  return new Set();
}
