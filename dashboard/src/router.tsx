import { createBrowserRouter, Navigate } from "react-router-dom";
import { AppShell } from "@/components/layout/AppShell";
import { ProtectedRoute } from "@/components/ProtectedRoute";
import { PlatformOwnerRoute } from "@/components/PlatformOwnerRoute";
import LoginPage from "@/pages/login";
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
import WhiteLabelPage from "@/pages/settings/white-label";
import SecuritySettingsPage from "@/pages/settings/security";
import PlatformConsolePage from "@/pages/platform";

/**
 * Route table for the fleet-ops dashboard. Public: /login. Everything else
 * requires auth and renders inside AppShell (brand sidebar + content area).
 * /platform is additionally gated by PlatformOwnerRoute (see
 * src/lib/platformAdmin.ts) so only the platform owner can reach it.
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
        <AppShell />
      </ProtectedRoute>
    ),
    children: [
      { index: true, element: <Navigate to="/live-map" replace /> },
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
      { path: "vouchers", element: <VouchersPage /> },
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
      { path: "*", element: <Navigate to="/live-map" replace /> },
    ],
  },
]);
