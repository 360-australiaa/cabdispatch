import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
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
