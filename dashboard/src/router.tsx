import { createBrowserRouter, Navigate } from "react-router-dom";
import { AppShell } from "@/components/layout/AppShell";
import { ProtectedRoute } from "@/components/ProtectedRoute";
import LoginPage from "@/pages/login";
import LiveMapPage from "@/pages/live-map";
import DispatchPage from "@/pages/dispatch";
import DuressPage from "@/pages/duress";
import TripsPage from "@/pages/trips";
import ShiftsPage from "@/pages/shifts";
import TariffsPage from "@/pages/tariffs";
import PslPage from "@/pages/psl";
import FleetPage from "@/pages/fleet";
import CompliancePage from "@/pages/compliance";
import BillingPage from "@/pages/billing";
import WhiteLabelPage from "@/pages/settings/white-label";
import SecuritySettingsPage from "@/pages/settings/security";

/**
 * Route table for the fleet-ops dashboard. Public: /login. Everything else
 * requires auth and renders inside AppShell (brand sidebar + content area).
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
      { path: "duress", element: <DuressPage /> },
      { path: "trips", element: <TripsPage /> },
      { path: "shifts", element: <ShiftsPage /> },
      { path: "tariffs", element: <TariffsPage /> },
      { path: "psl", element: <PslPage /> },
      { path: "fleet", element: <FleetPage /> },
      { path: "compliance", element: <CompliancePage /> },
      { path: "billing", element: <BillingPage /> },
      { path: "settings/white-label", element: <WhiteLabelPage /> },
      { path: "settings/security", element: <SecuritySettingsPage /> },
      { path: "*", element: <Navigate to="/live-map" replace /> },
    ],
  },
]);
