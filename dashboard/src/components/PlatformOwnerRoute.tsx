import { type ReactNode } from "react";
import { Navigate } from "react-router-dom";
import { useAuth } from "@/lib/auth";
import { isPlatformOwner } from "@/lib/platformAdmin";

/**
 * Guards the platform admin console (`/platform`). Renders inside
 * `ProtectedRoute` (so auth is already resolved by the time this runs) and
 * additionally requires `isPlatformOwner(user)` — an ordinary tenant's
 * owner navigating to /platform directly is redirected away, same pattern
 * as `ProtectedRoute`'s own auth redirect.
 */
export function PlatformOwnerRoute({ children }: { children: ReactNode }) {
  const { user } = useAuth();

  if (!isPlatformOwner(user)) {
    return <Navigate to="/live-map" replace />;
  }

  return <>{children}</>;
}
