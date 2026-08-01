import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";

/**
 * Data layer for the PSL Centre module (`src/pages/psl`). Mirrors
 * `shared/openapi.json` schemas PSLLedgerRead / PSLLedgerCreate /
 * PSLLedgerUpdate / PSLTopUpCreate / PSLTopUpRead / PSLReport /
 * PSLReportDriverLine and the `/v1/psl/...` endpoints (see
 * shared/API_SUMMARY.md).
 *
 * Money fields (amount_owed, amount_collected, amount_outstanding, amount,
 * total_owed, total_collected, total_outstanding) are decimal strings
 * straight off the wire — never coerced here. Formatting happens explicitly
 * at render time in `src/pages/psl/format.ts`.
 *
 * `GET /v1/psl/ledger` and `GET /v1/psl/topups` return a plain array (no
 * envelope/total — server caps at `limit`, max 200), unlike `/v1/trips`.
 */

export interface PSLLedgerEntry {
  id: string;
  tenant_id: string;
  driver_id: string;
  period: string;
  trips_count: number;
  amount_owed: string;
  amount_collected: string;
  remitted_at: string | null;
  created_at: string;
  updated_at: string;
}

export interface PSLLedgerFilters {
  driver_id?: string;
  period?: string;
  skip?: number;
  limit?: number;
}

export interface PSLLedgerCreateInput {
  driver_id: string;
  period: string;
  trips_count?: number;
  amount_owed?: string | number;
  amount_collected?: string | number;
  remitted_at?: string | null;
}

export interface PSLLedgerUpdateInput {
  trips_count?: number | null;
  amount_owed?: string | number | null;
  amount_collected?: string | number | null;
  remitted_at?: string | null;
}

export interface PSLTopUp {
  id: string;
  tenant_id: string;
  driver_id: string;
  period: string;
  amount: string;
  payment_method: string;
  stripe_charge_id: string;
  status: string;
  created_at: string;
}

export interface PSLTopUpFilters {
  driver_id?: string;
  period?: string;
  skip?: number;
  limit?: number;
}

export interface PSLTopUpCreateInput {
  driver_id: string;
  period: string;
  amount: string | number;
  payment_method?: string;
}

export interface PSLReportDriverLine {
  driver_id: string;
  driver_name: string | null;
  trips_count: number;
  amount_owed: string;
  amount_collected: string;
  amount_outstanding: string;
  remitted: boolean;
}

export interface PSLReport {
  tenant_id: string;
  period: string;
  driver_count: number;
  total_trips: number;
  total_owed: string;
  total_collected: string;
  total_outstanding: string;
  drivers: PSLReportDriverLine[];
}

const PSL_LEDGER_KEY = "psl-ledger";
const PSL_TOPUPS_KEY = "psl-topups";
const PSL_REPORT_KEY = "psl-report";

export function usePSLLedgerQuery(filters: PSLLedgerFilters) {
  return useQuery({
    queryKey: [PSL_LEDGER_KEY, filters],
    queryFn: async () => {
      const res = await apiClient.get<PSLLedgerEntry[]>("/v1/psl/ledger", { params: filters });
      return res.data;
    },
    placeholderData: (prev) => prev,
  });
}

export function useCreateLedgerEntryMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: PSLLedgerCreateInput) => {
      const res = await apiClient.post<PSLLedgerEntry>("/v1/psl/ledger", input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [PSL_LEDGER_KEY] });
      queryClient.invalidateQueries({ queryKey: [PSL_REPORT_KEY] });
    },
  });
}

export function useUpdateLedgerEntryMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: PSLLedgerUpdateInput }) => {
      const res = await apiClient.patch<PSLLedgerEntry>(`/v1/psl/ledger/${id}`, input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [PSL_LEDGER_KEY] });
      queryClient.invalidateQueries({ queryKey: [PSL_REPORT_KEY] });
    },
  });
}

export function useDeleteLedgerEntryMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/v1/psl/ledger/${id}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [PSL_LEDGER_KEY] });
      queryClient.invalidateQueries({ queryKey: [PSL_REPORT_KEY] });
    },
  });
}

export function useTopUpsQuery(filters: PSLTopUpFilters) {
  return useQuery({
    queryKey: [PSL_TOPUPS_KEY, filters],
    queryFn: async () => {
      const res = await apiClient.get<PSLTopUp[]>("/v1/psl/topups", { params: filters });
      return res.data;
    },
    placeholderData: (prev) => prev,
  });
}

export function useCreateTopUpMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: PSLTopUpCreateInput) => {
      const res = await apiClient.post<PSLTopUp>("/v1/psl/topup", input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [PSL_TOPUPS_KEY] });
      queryClient.invalidateQueries({ queryKey: [PSL_LEDGER_KEY] });
      queryClient.invalidateQueries({ queryKey: [PSL_REPORT_KEY] });
    },
  });
}

/** period must match `YYYY-MM` (backend validates with a regex) — caller gates `enabled` on that. */
export function usePSLReportQuery(period: string, enabled: boolean) {
  return useQuery({
    queryKey: [PSL_REPORT_KEY, period],
    queryFn: async () => {
      const res = await apiClient.get<PSLReport>("/v1/psl/report", { params: { period } });
      return res.data;
    },
    enabled,
    placeholderData: (prev) => prev,
  });
}
