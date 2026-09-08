import { lazy, Suspense, type ReactNode } from "react";
import { createBrowserRouter, Navigate } from "react-router-dom";
import { AppShell } from "@/components/layout/AppShell";
import { ErrorBoundary } from "@/components/ErrorBoundary";
import NotFound from "@/components/NotFound";
import { ProtectedRoute } from "@/components/ProtectedRoute";
import { Spinner } from "@/components/ui";
import { PlatformOwnerRoute } from "@/components/PlatformOwnerRoute";
import LoginPage from "@/pages/login";

// Every authenticated page is loaded on demand.
//
// These were 23 static imports, which is why the production bundle was a
// single 3.1 MB chunk (audit sec 6): opening /login pulled in mapbox-gl and
// recharts and all 22 other pages before it could render a username field.
// `React.lazy` gives each route its own chunk, and `vite.config.ts`'s
// `manualChunks` splits the heavy third-party libraries out so that the two
// pages using mapbox share one copy of it rather than inlining it twice.
//
// `LoginPage` is deliberately NOT lazy: it is the first thing an
// unauthenticated visitor sees, and making it a second round-trip would
// trade the win back on the one route where latency is most visible.
const GettingStartedPage = lazy(() => import("@/pages/getting-started"));
const LiveMapPage = lazy(() => import("@/pages/live-map"));
const DispatchPage = lazy(() => import("@/pages/dispatch"));
const MessagesPage = lazy(() => import("@/pages/messages"));
const DuressPage = lazy(() => import("@/pages/duress"));
const AuditLogPage = lazy(() => import("@/pages/audit-log"));
const TripsPage = lazy(() => import("@/pages/trips"));
const ShiftsPage = lazy(() => import("@/pages/shifts"));
const TariffsPage = lazy(() => import("@/pages/tariffs"));
const ZonesPage = lazy(() => import("@/pages/zones"));
const PslPage = lazy(() => import("@/pages/psl"));
const FleetPage = lazy(() => import("@/pages/fleet"));
const CompliancePage = lazy(() => import("@/pages/compliance"));
const BillingPage = lazy(() => import("@/pages/billing"));
const VouchersPage = lazy(() => import("@/pages/vouchers"));
const AnnouncementsPage = lazy(() => import("@/pages/driver-engagement/AnnouncementsPage"));
const IncentivesPage = lazy(() => import("@/pages/driver-engagement/IncentivesPage"));
const WalletPage = lazy(() => import("@/pages/driver-engagement/WalletPage"));
const RatingsPage = lazy(() => import("@/pages/driver-engagement/RatingsPage"));
const WhiteLabelPage = lazy(() => import("@/pages/settings/white-label"));
const SecuritySettingsPage = lazy(() => import("@/pages/settings/security"));
const PlatformConsolePage = lazy(() => import("@/pages/platform"));
const PaymentReconciliationPage = lazy(() => import("@/pages/payment-recon"));
// D10: password-reset-by-email landing pages, reached from a "forgot
// password?" click or a freshly-clicked email link — a secondary
// pre-auth flow, not the first thing a visitor sees, so these are lazy
// like every other route rather than joining LoginPage's exception.
const ForgotPasswordPage = lazy(() => import("@/pages/login/ForgotPasswordPage"));
const ResetPasswordPage = lazy(() => import("@/pages/login/ResetPasswordPage"));

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
 *  - Every authenticated page is code-split (`React.lazy`) and rendered
 *    inside a `Suspense` boundary that shows a centred spinner while its
 *    chunk downloads. The boundary sits per-route rather than once around
 *    `AppShell`, so a route change swaps only the content area and leaves the
 *    sidebar on screen instead of blanking the whole shell on every
 *    navigation.
 */
/** Wraps a lazily-loaded page in its own Suspense boundary. Per-route rather
 * than one boundary around the shell, so only the content area shows the
 * fallback during a navigation -- the sidebar never flickers. */
function lazyRoute(element: ReactNode): ReactNode {
  return (
    <Suspense
      fallback={
        <div className="flex min-h-64 items-center justify-center p-8" role="status" aria-live="polite">
          <Spinner />
          <span className="sr-only">Loading page</span>
        </div>
      }
    >
      {element}
    </Suspense>
  );
}

export const router = createBrowserRouter([
  {
    path: "/login",
    element: <LoginPage />,
  },
  {
    path: "/forgot-password",
    element: lazyRoute(<ForgotPasswordPage />),
  },
  {
    path: "/reset-password",
    element: lazyRoute(<ResetPasswordPage />),
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
      { path: "getting-started", element: lazyRoute(<GettingStartedPage />) },
      { path: "live-map", element: lazyRoute(<LiveMapPage />) },
      { path: "dispatch", element: lazyRoute(<DispatchPage />) },
      { path: "messages", element: lazyRoute(<MessagesPage />) },
      { path: "duress", element: lazyRoute(<DuressPage />) },
      { path: "trips", element: lazyRoute(<TripsPage />) },
      { path: "shifts", element: lazyRoute(<ShiftsPage />) },
      { path: "tariffs", element: lazyRoute(<TariffsPage />) },
      { path: "zones", element: lazyRoute(<ZonesPage />) },
      { path: "psl", element: lazyRoute(<PslPage />) },
      { path: "fleet", element: lazyRoute(<FleetPage />) },
      { path: "compliance", element: lazyRoute(<CompliancePage />) },
      { path: "billing", element: lazyRoute(<BillingPage />) },
      { path: "payment-recon", element: lazyRoute(<PaymentReconciliationPage />) },
      { path: "vouchers", element: lazyRoute(<VouchersPage />) },
      { path: "announcements", element: lazyRoute(<AnnouncementsPage />) },
      { path: "incentives", element: lazyRoute(<IncentivesPage />) },
      { path: "wallet", element: lazyRoute(<WalletPage />) },
      { path: "ratings", element: lazyRoute(<RatingsPage />) },
      { path: "audit-log", element: lazyRoute(<AuditLogPage />) },
      { path: "settings/white-label", element: lazyRoute(<WhiteLabelPage />) },
      { path: "settings/security", element: lazyRoute(<SecuritySettingsPage />) },
      {
        path: "platform",
        element: (
          <PlatformOwnerRoute>{lazyRoute(<PlatformConsolePage />)}</PlatformOwnerRoute>
        ),
      },
      { path: "*", element: <NotFound /> },
    ],
  },
]);
