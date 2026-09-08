import { createBrowserRouter, Navigate } from "react-router-dom";
import { AppShell } from "@/components/layout/AppShell";
import { ErrorBoundary } from "@/components/ErrorBoundary";
import NotFound from "@/components/NotFound";
import { ProtectedRoute } from "@/components/ProtectedRoute";
import { PlatformOwnerRoute } from "@/components/PlatformOwnerRoute";
import LoginPage from "@/pages/login";
import GettingStartedPage from "@/pages/getting-started";
import LiveMapPage from "@/pages/live-map";
import DispatchPage from "@/pages/dispatch";
import MessagesPage from "@/pages/messages";
import DuressPage from "@/pages/duress";
import AuditLogPage from "@/pages/audit-log";
import TripsPage from "@/pages/trips";
import ShiftsPage from "@/pages/shifts";
import TariffsPage from "@/pages/tariffs";
import ZonesPage from "@/pages/zones";
import PslPage from "@/pages/psl";
import FleetPage from "@/pages/fleet";
import CompliancePage from "@/pages/compliance";
import BillingPage from "@/pages/billing";
import VouchersPage from "@/pages/vouchers";
import AnnouncementsPage from "@/pages/driver-engagement/AnnouncementsPage";
import IncentivesPage from "@/pages/driver-engagement/IncentivesPage";
import WalletPage from "@/pages/driver-engagement/WalletPage";
import RatingsPage from "@/pages/driver-engagement/RatingsPage";
import WhiteLabelPage from "@/pages/settings/white-label";
import SecuritySettingsPage from "@/pages/settings/security";
import PlatformConsolePage from "@/pages/platform";
import PaymentReconciliationPage from "@/pages/payment-recon";

/**
 * Route table for the fleet-ops dashboard. Public: /login. Everything else
 * requires auth and renders inside AppShell (brand sidebar + content area).
 * /platform is additionally gated by PlatformOwnerRoute (see
 * src/lib/platformAdmin.ts) so only the platform owner can reach it.
 *
 * Two structural guards wrap the authenticated area:
 *
 *  - `ErrorBoundary` wraps the authenticated area, so a render throw on any
 *    page shows a recoverable panel instead of blanking the whole app (there
 *    was no boundary anywhere before -- dashboard audit §6). It sits inside
 *    `ProtectedRoute`, so a crash never costs the user their session.
 *
 *    It wraps `AppShell` rather than AppShell's `<Outlet>`, which means a
 *    throwing page currently takes the sidebar down with it and the user
 *    recovers via the panel's own two buttons rather than via the nav.
 *    Wrapping the Outlet instead would be strictly better and is a one-line
 *    change -- but it is a change to `AppShell.tsx`, which this workstream
 *    does not own. Flagged for whoever owns the layout.
 *  - The wildcard renders a real 404. It used to be
 *    `<Navigate to="/live-map" replace />`, which turned every mistyped or
 *    stale URL into a silent, un-undoable redirect to the map (see
 *    components/NotFound.tsx for the full reasoning).
 */
export const router = createBrowserRouter([
  {
    path: "/login",
    element: <LoginPage />,
  },
  {
    path: "/",
    element: (
      <ProtectedRoute>
        <ErrorBoundary>
          <AppShell />
        </ErrorBoundary>
      </ProtectedRoute>
    ),
    children: [
      { index: true, element: <Navigate to="/live-map" replace /> },
      { path: "getting-started", element: <GettingStartedPage /> },
      { path: "live-map", element: <LiveMapPage /> },
      { path: "dispatch", element: <DispatchPage /> },
      { path: "messages", element: <MessagesPage /> },
      { path: "duress", element: <DuressPage /> },
      { path: "trips", element: <TripsPage /> },
      { path: "shifts", element: <ShiftsPage /> },
      { path: "tariffs", element: <TariffsPage /> },
      { path: "zones", element: <ZonesPage /> },
      { path: "psl", element: <PslPage /> },
      { path: "fleet", element: <FleetPage /> },
      { path: "compliance", element: <CompliancePage /> },
      { path: "billing", element: <BillingPage /> },
      { path: "payment-recon", element: <PaymentReconciliationPage /> },
      { path: "vouchers", element: <VouchersPage /> },
      { path: "announcements", element: <AnnouncementsPage /> },
      { path: "incentives", element: <IncentivesPage /> },
      { path: "wallet", element: <WalletPage /> },
      { path: "ratings", element: <RatingsPage /> },
      { path: "audit-log", element: <AuditLogPage /> },
      { path: "settings/white-label", element: <WhiteLabelPage /> },
      { path: "settings/security", element: <SecuritySettingsPage /> },
      {
        path: "platform",
        element: (
          <PlatformOwnerRoute>
            <PlatformConsolePage />
          </PlatformOwnerRoute>
        ),
      },
      { path: "*", element: <NotFound /> },
    ],
  },
]);
