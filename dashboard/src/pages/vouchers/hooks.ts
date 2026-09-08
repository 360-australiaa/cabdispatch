import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";

/**
 * Data layer for the Vouchers & Corporate Accounts page (`src/pages/vouchers`).
 * Mirrors `/v1/vouchers` and `/v1/corporate-accounts` (see
 * `backend/app/api/v1/vouchers.py` / `corporate_accounts.py`).
 *
 * Colocated here (rather than `src/hooks/useVouchers.ts`, where every other
 * domain's data layer lives — see `src/hooks/useTariffStudio.ts`) because
 * this pass's task brief scopes edits to `dashboard/src/pages/vouchers/*`.
 * A later pass can freely move this file without changing its exports.
 *
 * Money fields are decimal strings straight off the wire — never coerced
 * here, same convention as `useTariffStudio.ts`.
 */

export interface Page<T> {
  items: T[];
  total: number;
  skip: number;
  limit: number;
}

// --- Vouchers ---------------------------------------------------------------

export interface Voucher {
  id: string;
  tenant_id: string;
  code: string;
  value_aud: string;
  expires_at: string | null;
  redeemed_at: string | null;
  redeemed_by_trip_id: string | null;
  created_at: string;
  updated_at: string;
}

export interface VoucherCreateInput {
  code: string;
  value_aud: string;
  expires_at?: string | null;
}

export type VoucherUpdateInput = Partial<Omit<VoucherCreateInput, "code">>;

export interface VoucherListFilters {
  redeemed?: boolean | "";
  skip?: number;
  limit?: number;
}

const VOUCHERS_KEY = "vouchers";

export function useVouchersQuery(filters: VoucherListFilters) {
  return useQuery({
    queryKey: [VOUCHERS_KEY, filters],
    queryFn: async () => {
      const params: Record<string, string | number | boolean> = {
        skip: filters.skip ?? 0,
        limit: filters.limit ?? 50,
      };
      if (filters.redeemed !== "" && filters.redeemed !== undefined) params.redeemed = filters.redeemed;
      const res = await apiClient.get<Page<Voucher>>("/v1/vouchers", { params });
      return res.data;
    },
    placeholderData: (prev) => prev,
  });
}

export function useCreateVoucherMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: VoucherCreateInput) => {
      const res = await apiClient.post<Voucher>("/v1/vouchers", input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [VOUCHERS_KEY] });
    },
  });
}

export function useUpdateVoucherMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: VoucherUpdateInput }) => {
      const res = await apiClient.patch<Voucher>(`/v1/vouchers/${id}`, input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [VOUCHERS_KEY] });
    },
  });
}

export function useDeleteVoucherMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/v1/vouchers/${id}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [VOUCHERS_KEY] });
    },
  });
}

// --- Corporate accounts -------------------------------------------------------

export interface CorporateAccount {
  id: string;
  tenant_id: string;
  reference: string;
  company_name: string;
  active: boolean;
  created_at: string;
  updated_at: string;
}

export interface CorporateAccountCreateInput {
  reference: string;
  company_name: string;
  active?: boolean;
}

export type CorporateAccountUpdateInput = Partial<Omit<CorporateAccountCreateInput, "reference">>;

export interface CorporateAccountListFilters {
  active?: boolean | "";
  skip?: number;
  limit?: number;
}

const CORPORATE_ACCOUNTS_KEY = "corporate-accounts";

export function useCorporateAccountsQuery(filters: CorporateAccountListFilters) {
  return useQuery({
    queryKey: [CORPORATE_ACCOUNTS_KEY, filters],
    queryFn: async () => {
      const params: Record<string, string | number | boolean> = {
        skip: filters.skip ?? 0,
        limit: filters.limit ?? 50,
      };
      if (filters.active !== "" && filters.active !== undefined) params.active = filters.active;
      const res = await apiClient.get<Page<CorporateAccount>>("/v1/corporate-accounts", { params });
      return res.data;
    },
    placeholderData: (prev) => prev,
  });
}

export function useCreateCorporateAccountMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: CorporateAccountCreateInput) => {
      const res = await apiClient.post<CorporateAccount>("/v1/corporate-accounts", input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [CORPORATE_ACCOUNTS_KEY] });
    },
  });
}

export function useUpdateCorporateAccountMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: CorporateAccountUpdateInput }) => {
      const res = await apiClient.patch<CorporateAccount>(`/v1/corporate-accounts/${id}`, input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [CORPORATE_ACCOUNTS_KEY] });
    },
  });
}

export function useDeleteCorporateAccountMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/v1/corporate-accounts/${id}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [CORPORATE_ACCOUNTS_KEY] });
    },
  });
}
