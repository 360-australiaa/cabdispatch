import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";

/**
 * Data layer for the three driver-engagement admin pages
 * (`src/pages/driver-engagement`): Announcements, Incentives, Driver Wallets.
 * Mirrors `/v1/announcements`, `/v1/incentives`, `/v1/wallet/*` and the
 * `/v1/users?role=driver` lookup (see `backend/app/api/v1/announcements.py`,
 * `incentives.py`, `wallet.py`, `users.py`).
 *
 * Colocated here, same as `src/pages/vouchers/hooks.ts`. Money fields are
 * decimal strings straight off the wire — never coerced here.
 */

export interface Page<T> {
  items: T[];
  total: number;
  skip: number;
  limit: number;
}

// --- Announcements ------------------------------------------------------------

export type AnnouncementKind = "info" | "maintenance" | "surge" | "feature";

export interface Announcement {
  id: string;
  tenant_id: string;
  title: string;
  body: string;
  kind: AnnouncementKind;
  starts_at: string;
  ends_at: string | null;
  active: boolean;
  created_at: string;
  updated_at: string;
}

export interface AnnouncementCreateInput {
  title: string;
  body: string;
  kind: AnnouncementKind;
  starts_at: string;
  ends_at?: string | null;
  active?: boolean;
}

export type AnnouncementUpdateInput = Partial<AnnouncementCreateInput>;

export interface AnnouncementListFilters {
  active?: boolean | "";
  kind?: AnnouncementKind | "";
  skip?: number;
  limit?: number;
}

const ANNOUNCEMENTS_KEY = "announcements";

export function useAnnouncementsQuery(filters: AnnouncementListFilters) {
  return useQuery({
    queryKey: [ANNOUNCEMENTS_KEY, filters],
    queryFn: async () => {
      const params: Record<string, string | number | boolean> = {
        skip: filters.skip ?? 0,
        limit: filters.limit ?? 50,
      };
      if (filters.active !== "" && filters.active !== undefined) params.active = filters.active;
      if (filters.kind) params.kind = filters.kind;
      const res = await apiClient.get<Page<Announcement>>("/v1/announcements", { params });
      return res.data;
    },
    placeholderData: (prev) => prev,
  });
}

export function useCreateAnnouncementMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: AnnouncementCreateInput) => {
      const res = await apiClient.post<Announcement>("/v1/announcements", input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [ANNOUNCEMENTS_KEY] });
    },
  });
}

export function useUpdateAnnouncementMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: AnnouncementUpdateInput }) => {
      const res = await apiClient.patch<Announcement>(`/v1/announcements/${id}`, input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [ANNOUNCEMENTS_KEY] });
    },
  });
}

export function useDeleteAnnouncementMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/v1/announcements/${id}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [ANNOUNCEMENTS_KEY] });
    },
  });
}

// --- Incentives ---------------------------------------------------------------

export interface Incentive {
  id: string;
  tenant_id: string;
  title: string;
  description: string | null;
  target_trips: number;
  reward_aud: string;
  starts_at: string;
  ends_at: string;
  active: boolean;
  created_at: string;
  updated_at: string;
}

export interface IncentiveCreateInput {
  title: string;
  description?: string | null;
  target_trips: number;
  reward_aud: string;
  starts_at: string;
  ends_at: string;
  active?: boolean;
}

export type IncentiveUpdateInput = Partial<IncentiveCreateInput>;

export interface IncentiveListFilters {
  active?: boolean | "";
  skip?: number;
  limit?: number;
}

const INCENTIVES_KEY = "incentives";

export function useIncentivesQuery(filters: IncentiveListFilters) {
  return useQuery({
    queryKey: [INCENTIVES_KEY, filters],
    queryFn: async () => {
      const params: Record<string, string | number | boolean> = {
        skip: filters.skip ?? 0,
        limit: filters.limit ?? 50,
      };
      if (filters.active !== "" && filters.active !== undefined) params.active = filters.active;
      const res = await apiClient.get<Page<Incentive>>("/v1/incentives", { params });
      return res.data;
    },
    placeholderData: (prev) => prev,
  });
}

export function useCreateIncentiveMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: IncentiveCreateInput) => {
      const res = await apiClient.post<Incentive>("/v1/incentives", input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [INCENTIVES_KEY] });
    },
  });
}

export function useUpdateIncentiveMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: IncentiveUpdateInput }) => {
      const res = await apiClient.patch<Incentive>(`/v1/incentives/${id}`, input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [INCENTIVES_KEY] });
    },
  });
}

export function useDeleteIncentiveMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/v1/incentives/${id}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [INCENTIVES_KEY] });
    },
  });
}

// --- Wallet -------------------------------------------------------------------

export type WalletKind = "trip_earning" | "top_up" | "adjustment" | "payout";
export type OperatorWalletKind = Exclude<WalletKind, "trip_earning">;

export interface WalletTransaction {
  id: string;
  tenant_id: string;
  driver_id: string;
  /** Signed decimal string: positive = credit to the driver, negative = debit. */
  amount_aud: string;
  kind: WalletKind;
  reference: string | null;
  note: string | null;
  created_by_user_id: string | null;
  created_at: string;
}

export interface DriverWallet {
  driver_id: string;
  /** Derived server-side as SUM(amount_aud) — never stored. */
  balance_aud: string;
  recent: WalletTransaction[];
}

export interface WalletTransactionCreateInput {
  driver_id: string;
  amount_aud: string;
  kind: OperatorWalletKind;
  reference?: string | null;
  note?: string | null;
}

const WALLET_KEY = "wallet";

export function useDriverWalletQuery(driverId: string | null, limit = 50) {
  return useQuery({
    queryKey: [WALLET_KEY, "driver", driverId, limit],
    queryFn: async () => {
      const res = await apiClient.get<DriverWallet>(`/v1/wallet/drivers/${driverId}`, {
        params: { limit },
      });
      return res.data;
    },
    enabled: driverId != null && driverId !== "",
  });
}

export interface WalletTransactionListFilters {
  driver_id?: string;
  kind?: string;
  skip?: number;
  limit?: number;
}

/** Real server-side paged ledger — `GET /v1/wallet/transactions`
 * (`backend/app/api/v1/wallet.py`) already computes `skip`/`limit`/`total`
 * against the whole tenant, filtered by `driver_id` when one is given. This
 * is genuine server-side paging, not a client slice over a capped fetch —
 * unlike the five patterns the dashboard audit flags elsewhere (§4), this
 * endpoint's `total` is a real `SELECT count(*)`. Used both for one driver's
 * ledger (WalletPage's detail view) and, with no `driver_id`, the fleet-wide
 * transaction feed. */
export function useWalletTransactionsQuery(filters: WalletTransactionListFilters) {
  return useQuery({
    queryKey: [WALLET_KEY, "transactions", filters],
    queryFn: async () => {
      const params: Record<string, string | number> = {
        skip: filters.skip ?? 0,
        limit: filters.limit ?? 20,
      };
      if (filters.driver_id) params.driver_id = filters.driver_id;
      if (filters.kind) params.kind = filters.kind;
      const res = await apiClient.get<Page<WalletTransaction>>("/v1/wallet/transactions", { params });
      return res.data;
    },
    placeholderData: (prev) => prev,
  });
}

export interface DriverWalletBalance {
  driver: DriverOption;
  /** `null` while loading or on error — never a fabricated "$0.00" for a
   * balance that has not come back yet (see D5's fix for the same bug on the
   * revenue console: an empty value is missing, not zero). */
  balance_aud: string | null;
  isLoading: boolean;
  isError: boolean;
}

/** Fleet-wide balances for a Driver Wallets landing view.
 *
 * There is no bulk "every driver's balance in one call" endpoint — only
 * `GET /v1/wallet/drivers/{driver_id}` (one driver at a time, per the
 * dashboard audit line 30). Rather than leave the page showing nothing until
 * an operator picks a name from a dropdown (the exact "why is it empty"
 * complaint this workstream exists to fix), this issues one real request per
 * driver currently loaded (`useDriverOptionsQuery`'s first 100 — the same
 * cap that lookup already has) and renders each balance as it arrives. It is
 * N+1 by construction and the page says so; a true bulk aggregate is backend
 * work outside this workstream's `pages/driver-engagement/**` ownership. */
export function useFleetWalletBalancesQuery(drivers: DriverOption[]): DriverWalletBalance[] {
  const results = useQueries({
    queries: drivers.map((driver) => ({
      queryKey: [WALLET_KEY, "driver", driver.id, "balance-only"],
      queryFn: async () => {
        const res = await apiClient.get<DriverWallet>(`/v1/wallet/drivers/${driver.id}`, {
          params: { limit: 1 },
        });
        return res.data.balance_aud;
      },
      staleTime: 30_000,
    })),
  });

  return drivers.map((driver, i) => ({
    driver,
    balance_aud: results[i]?.data ?? null,
    isLoading: results[i]?.isLoading ?? false,
    isError: results[i]?.isError ?? false,
  }));
}

export function useCreateWalletTransactionMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: WalletTransactionCreateInput) => {
      const res = await apiClient.post<WalletTransaction>("/v1/wallet/transactions", input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [WALLET_KEY] });
    },
  });
}

// --- Ratings (GET /v1/ratings, POST /v1/trips/{id}/rating) --------------------
//
// The passenger's 1-5 star rating of the driver for one closed trip
// (backend/app/api/v1/ratings.py, backend/app/models/driver_engagement.py's
// TripRating). Captured on the driver tablet at the end of Close & Pay;
// `GET /v1/ratings` is the owner/admin dashboard-facing list this page reads
// (a driver reads only their own aggregate via the separate `GET /v1/me/rating`,
// not used here). No fleet-wide average-per-driver endpoint exists server-side
// (only a per-driver one, `app.services.driver_engagement.driver_rating`,
// wired to `/v1/me/rating` and not exposed for arbitrary driver ids) — so the
// per-driver averages this page shows are computed client-side over whatever
// page of ratings is currently loaded, same "compute the rollup from the list
// you already have" convention as `pages/psl/RemittanceReport.tsx`.

export type RatingStars = 1 | 2 | 3 | 4 | 5;

export interface TripRating {
  id: string;
  tenant_id: string;
  trip_id: string;
  driver_id: string;
  stars: RatingStars;
  comment: string | null;
  created_at: string;
}

export interface RatingListFilters {
  driver_id?: string;
  skip?: number;
  limit?: number;
}

const RATINGS_KEY = "ratings";

export function useRatingsQuery(filters: RatingListFilters) {
  return useQuery({
    queryKey: [RATINGS_KEY, filters],
    queryFn: async () => {
      const params: Record<string, string | number> = {
        skip: filters.skip ?? 0,
        limit: filters.limit ?? 50,
      };
      if (filters.driver_id) params.driver_id = filters.driver_id;
      const res = await apiClient.get<Page<TripRating>>("/v1/ratings", { params });
      return res.data;
    },
    placeholderData: (prev) => prev,
  });
}

// --- Incentive progress (derived client-side from GET /v1/trips) -------------
//
// The driver tablet's own progress number comes from
// `app.services.driver_engagement.incentive_progress_for_driver`, which
// counts CLOSED trips whose `end_at` falls inside the incentive's window —
// but that logic is only reachable through `GET /v1/me/incentives`, scoped
// to the CALLER's own driver id (`app.api.v1.me`). There is no owner/admin
// route that runs it for an arbitrary driver, and adding one is backend
// work outside `pages/driver-engagement/**`. `GET /v1/trips` also has no
// `end_at` range filter (`backend/app/api/v1/trips.py` takes only
// status/type/vehicle_id/driver_id + skip/limit), so this reproduces the
// same count from the driver's most recent closed trips and says plainly
// when that page might not cover the whole window.

export interface IncentiveProgress {
  completedTrips: number;
  targetTrips: number;
  remainingTrips: number;
  progressPct: number;
  achieved: boolean;
  /** True when the trip page fetched may not include every closed trip
   * inside the incentive's window (more closed trips exist than were
   * fetched, and the oldest one fetched is still inside the window) — the
   * count below is then a floor, not an exact figure. */
  maybeIncomplete: boolean;
}

const TRIP_WINDOW_FETCH_LIMIT = 200; // backend's own per-request cap

export function useIncentiveProgressQuery(
  driverId: string | null,
  incentive: Pick<Incentive, "target_trips" | "starts_at" | "ends_at"> | null,
) {
  return useQuery({
    queryKey: ["incentive-progress", driverId, incentive?.starts_at, incentive?.ends_at, incentive?.target_trips],
    queryFn: async (): Promise<IncentiveProgress> => {
      if (!driverId || !incentive) throw new Error("driver and incentive are required");
      const res = await apiClient.get<Page<{ id: string; end_at: string | null; status: string }>>(
        "/v1/trips",
        { params: { driver_id: driverId, status: "closed", limit: TRIP_WINDOW_FETCH_LIMIT, skip: 0 } },
      );
      const startMs = new Date(incentive.starts_at).getTime();
      const endMs = new Date(incentive.ends_at).getTime();
      const rows = res.data.items;
      const completed = rows.filter((row) => {
        if (!row.end_at) return false;
        const t = new Date(row.end_at).getTime();
        return t >= startMs && t < endMs;
      }).length;
      const target = Number(incentive.target_trips);
      const oldestFetched = rows.at(-1)?.end_at ?? null;
      const moreExist = res.data.total > rows.length;
      const maybeIncomplete =
        moreExist && (oldestFetched == null || new Date(oldestFetched).getTime() >= startMs);
      return {
        completedTrips: completed,
        targetTrips: target,
        remainingTrips: Math.max(target - completed, 0),
        progressPct: target > 0 ? Math.min(100, Math.floor((completed * 100) / target)) : 0,
        achieved: completed >= target,
        maybeIncomplete,
      };
    },
    enabled: driverId != null && driverId !== "" && incentive != null,
  });
}

// --- Driver lookup (GET /v1/users?role=driver) --------------------------------

export interface DriverOption {
  id: string;
  name: string;
  email: string;
  driver_code: string | null;
  status: string;
}

/** First 100 drivers in the tenant (same server-side cap as `GET /v1/users`
 * itself) — same lightweight-lookup pattern as `pages/audit-log/api.ts`'s
 * `listActorOptions`. */
export function useDriverOptionsQuery() {
  return useQuery({
    queryKey: ["users", "driver-options"],
    queryFn: async () => {
      const res = await apiClient.get<Page<DriverOption>>("/v1/users", {
        params: { role: "driver", skip: 0, limit: 100 },
      });
      return res.data.items;
    },
    staleTime: 60_000,
  });
}
