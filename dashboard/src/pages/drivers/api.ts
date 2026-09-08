import axios from "axios";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import { POLL, pollingQueryOptions } from "@/lib/pollIntervals";

/**
 * Data layer specific to `/drivers/:driverId` (dashboard command-centre
 * plan §4). Everything a tab can get from an existing hook elsewhere in the
 * app (shifts, trips, wallet, ratings list, messages, audit log) is reused
 * from that page's own module instead of being re-declared here -- this file
 * only holds the handful of driver-detail-specific reads/writes nothing else
 * in the dashboard already exposes: the full user record, the live on-shift
 * rollup for one driver, the PIN reset action, and a driver-scoped fatigue
 * alerts / ratings-summary read.
 */

// ---------------------------------------------------------------------------
// Driver "full record" -- GET/PATCH /v1/users/{id} (UserRead/UserUpdate).
//
// `fleet/api`'s `useDriverCompliance` already reads this same endpoint but
// narrows the response to the four compliance-facing fields DriversPanel's
// modal needs (see that hook's own doc comment). The header and the Edit
// sheet on this page need the rest of `UserRead` too (email, driver_code,
// photo_url, status, wat_endorsed, driver_licence_no) -- rather than fork a
// second narrowed type, this declares the full shape once and every part of
// this page reads from the one query.
// ---------------------------------------------------------------------------

export type UserRole = "owner" | "admin" | "dispatcher" | "driver" | string;
export type UserStatus = "active" | "inactive" | "suspended" | string;

/** `UserRead` -- see backend/app/schemas/user.py. */
export interface DriverUser {
  id: string;
  tenant_id: string | null;
  role: UserRole;
  name: string;
  email: string;
  phone: string | null;
  driver_licence_no: string | null;
  wat_endorsed: boolean;
  status: UserStatus;
  driver_license_expiry: string | null;
  driver_authority_expiry: string | null;
  driver_code: string | null;
  photo_url: string | null;
  suitability_status: string | null;
  created_at: string;
  updated_at: string;
}

/** `UserUpdate` -- every field optional; only send what changed. */
export interface DriverUserUpdate {
  name?: string;
  phone?: string | null;
  driver_licence_no?: string | null;
  wat_endorsed?: boolean;
  status?: UserStatus;
  driver_license_expiry?: string | null;
  driver_authority_expiry?: string | null;
}

const DRIVER_USER_KEY = "driver-user";

export function useDriverUser(driverId: string | null) {
  return useQuery({
    queryKey: [DRIVER_USER_KEY, driverId],
    queryFn: async () => {
      const { data } = await apiClient.get<DriverUser>(`/v1/users/${driverId}`);
      return data;
    },
    enabled: Boolean(driverId),
  });
}

/** `PATCH /v1/users/{id}` -- owner/admin only server-side (`_require_admin`
 * in `backend/app/api/v1/users.py`). Used by the Edit sheet, the
 * Deactivate/Reactivate action, and Compliance tab's date fields, so a
 * single mutation invalidates this page's own query plus the two other
 * surfaces that show the same record (`DriversPanel`'s list/compliance
 * query, and the sidebar's compliance-expiry badge) -- otherwise editing a
 * driver here would leave those showing stale data until their own next
 * poll. */
export function useUpdateDriverUser() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, values }: { id: string; values: DriverUserUpdate }) => {
      const { data } = await apiClient.patch<DriverUser>(`/v1/users/${id}`, values);
      return data;
    },
    onSuccess: (_data, variables) => {
      qc.invalidateQueries({ queryKey: [DRIVER_USER_KEY, variables.id] });
      qc.invalidateQueries({ queryKey: ["fleet", "drivers"] });
      qc.invalidateQueries({ queryKey: ["fleet", "compliance-expiry"] });
    },
  });
}

/** `POST /v1/users/{id}/reset-pin` -- owner/admin only, driver accounts
 * only (400 on any other role -- see the endpoint's own doc comment). Mints
 * and returns a new plaintext meter PIN exactly once; nothing else about
 * the account changes, so no query needs invalidating. */
export function useResetDriverPin() {
  return useMutation({
    mutationFn: async (driverId: string) => {
      const { data } = await apiClient.post<{ pin: string }>(`/v1/users/${driverId}/reset-pin`);
      return data.pin;
    },
  });
}

/** True for a 404/501-shaped "this route doesn't exist yet" response --
 * used across this page's defensive reads to tell "the endpoint is missing"
 * apart from "the endpoint exists and genuinely failed", per the workstream
 * brief's instruction to degrade to an honest empty/error state rather than
 * throw when a parallel backend workstream hasn't landed a filter yet. */
export function isMissingEndpoint(err: unknown): boolean {
  return axios.isAxiosError(err) && (err.response?.status === 404 || err.response?.status === 501);
}

/** True for a 403 -- "this exists but your role can't call it", the other
 * shape a defensively-coded card needs to tell apart from a real failure
 * (e.g. `GET /v1/ratings/summary` is owner/admin only server-side). */
export function isForbidden(err: unknown): boolean {
  return axios.isAxiosError(err) && err.response?.status === 403;
}

// ---------------------------------------------------------------------------
// Live on-shift rollup for one driver -- GET /v1/drivers/{id} (DriverLiveRead).
//
// F6 (the shared realtime hub over `useLiveMap`/`useMessagesLive`/
// `useDuressLiveGps`) is out of scope for this page -- wiring a fourth
// consumer onto `WS /v1/fleet/live` is a bigger change than one page's
// header needs. This polls the single-driver REST rollup instead, on the
// ROSTER band (30s) DriversPanel's own list already polls at, so a header
// left open catches an on/off-shift change or a new current trip within the
// same window an operator watching the Drivers list would.
// ---------------------------------------------------------------------------

export interface DriverLive {
  id: string;
  tenant_id: string;
  name: string;
  phone: string | null;
  user_status: string;
  on_shift: boolean;
  shift_id: string | null;
  vehicle_id: string | null;
  shift_start_at: string | null;
  current_trip_id: string | null;
}

export function useDriverLive(driverId: string | null) {
  return useQuery({
    queryKey: ["driver-live", driverId],
    queryFn: async () => {
      const { data } = await apiClient.get<DriverLive>(`/v1/drivers/${driverId}`);
      return data;
    },
    enabled: Boolean(driverId),
    ...pollingQueryOptions(POLL.ROSTER),
  });
}

// ---------------------------------------------------------------------------
// Driver-scoped fatigue alerts -- GET /v1/fatigue-alerts?driver_id=&acknowledged=false.
// Confirmed real and driver_id-filtered server-side
// (`backend/app/api/v1/fatigue_alerts.py`); the Overview tab renders open
// alerts as a card and omits it entirely if this ever fails, per the plan.
// ---------------------------------------------------------------------------

export type FatigueAlertKind = "shift_duration_exceeded" | "no_break_taken" | "speed_exceeded";

export interface DriverFatigueAlert {
  id: string;
  driver_id: string;
  shift_id: string | null;
  kind: FatigueAlertKind;
  triggered_at: string;
  acknowledged: boolean;
}

export function useDriverFatigueAlerts(driverId: string | null) {
  return useQuery({
    queryKey: ["driver-fatigue-alerts", driverId],
    queryFn: async () => {
      const { data } = await apiClient.get<{ items: DriverFatigueAlert[]; total: number }>(
        "/v1/fatigue-alerts",
        { params: { driver_id: driverId, acknowledged: false, skip: 0, limit: 20 } },
      );
      return data;
    },
    enabled: Boolean(driverId),
    ...pollingQueryOptions(POLL.AMBIENT),
  });
}

// ---------------------------------------------------------------------------
// Ratings summary, scoped to one driver -- GET /v1/ratings/summary?driver_id=.
// Real SQL aggregate (see backend/app/api/v1/ratings.py), but owner/admin
// only server-side -- a dispatcher viewing this page gets a 403, which the
// Overview/Ratings tabs must show as "not available for your role", not a
// crash. See `isForbidden` above.
// ---------------------------------------------------------------------------

export interface DriverRatingsSummary {
  total: number;
  average: number | null;
  distribution: Record<string, number>;
}

export function useDriverRatingsSummary(driverId: string | null) {
  return useQuery({
    queryKey: ["driver-ratings-summary", driverId],
    queryFn: async () => {
      const { data } = await apiClient.get<DriverRatingsSummary>("/v1/ratings/summary", {
        params: { driver_id: driverId },
      });
      return data;
    },
    enabled: Boolean(driverId),
    retry: false,
  });
}
