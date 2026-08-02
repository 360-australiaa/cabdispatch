import { NavLink } from "react-router-dom";
import {
  Map,
  Send,
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
  LogOut,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { useAuth } from "@/lib/auth";

interface NavItem {
  to: string;
  label: string;
  icon: typeof Map;
}

/** Route/label/icon list for the 10 fleet-ops modules. Keep in sync with router.tsx. */
const NAV_ITEMS: NavItem[] = [
  { to: "/live-map", label: "Live Map", icon: Map },
  { to: "/dispatch", label: "Dispatch", icon: Send },
  { to: "/duress", label: "Duress Desk", icon: ShieldAlert },
  { to: "/trips", label: "Trips", icon: Route },
  { to: "/shifts", label: "Shifts & Reconciliation", icon: Clock },
  { to: "/tariffs", label: "Tariff Studio", icon: Receipt },
  { to: "/psl", label: "PSL Centre", icon: Wallet },
  { to: "/fleet", label: "Fleet & Drivers", icon: Car },
  { to: "/compliance", label: "Compliance Vault", icon: FileCheck2 },
  { to: "/billing", label: "Billing", icon: CreditCard },
  { to: "/settings/white-label", label: "White-label", icon: Palette },
  { to: "/settings/security", label: "Security", icon: ShieldCheck },
];

export function Sidebar() {
  const { user, logout } = useAuth();

  return (
    <aside className="flex h-screen w-64 shrink-0 flex-col bg-brand-primary text-brand-primary-foreground">
      <div className="flex items-center gap-2 px-5 py-5">
        <div className="flex h-8 w-8 items-center justify-center rounded-md bg-brand-accent text-brand-accent-foreground font-bold">
          CD
        </div>
        <span className="text-sm font-semibold tracking-wide">Cab Dispatch</span>
      </div>

      <nav className="flex-1 overflow-y-auto px-3 py-2">
        <ul className="flex flex-col gap-1">
          {NAV_ITEMS.map(({ to, label, icon: Icon }) => (
            <li key={to}>
              <NavLink
                to={to}
                className={({ isActive }) =>
                  cn(
                    "flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-white/80 transition-colors hover:bg-white/10 hover:text-white",
                    isActive && "bg-brand-accent text-brand-accent-foreground hover:bg-brand-accent",
                  )
                }
              >
                <Icon className="h-4 w-4 shrink-0" />
                <span className="truncate">{label}</span>
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>

      <div className="border-t border-white/10 px-3 py-3">
        <div className="mb-2 px-2">
          <p className="truncate text-sm font-medium">{user?.name ?? "—"}</p>
          <p className="truncate text-xs text-white/60">{user?.role ?? ""}</p>
        </div>
        <button
          type="button"
          onClick={logout}
          className="flex w-full items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-white/80 transition-colors hover:bg-white/10 hover:text-white"
        >
          <LogOut className="h-4 w-4" />
          Log out
        </button>
      </div>
    </aside>
  );
}
