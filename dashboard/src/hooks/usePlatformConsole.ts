import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";

/**
 * Data layer for the platform-owner admin console (`src/pages/platform`).
 * Mirrors `app/schemas/platform.py` / `app/api/v1/platform.py` on the
 * backend. Every route here is gated server-side by `require_platform_owner`
 * (role == "owner" AND tenant_id == PLATFORM_TENANT_ID); the page itself
 * additionally hides/redirects client-side (see `src/lib/platformAdmin.ts`
 * and `src/pages/platform/index.tsx`) so an ordinary tenant owner never sees
 * the nav item or renders the page body.
 */

export interface Page<T> {
  items: T[];
  total: number;
  skip: number;
  limit: number;
}

export type TenantStatus = "active" | "trial" | "suspended";

export interface PlatformTenant {
  id: string;
  name: string;
  plan: string;
  status: TenantStatus;
  created_at: string;
}

export interface TenantSummary {
  tenant_id: string;
  tenant_name: string;
  vehicle_count: number;
  driver_count: number;
  trip_count_last_30_days: number;
  active_duress_count: number;
}

export interface PlatformHealth {
  total_tenants: number;
  total_vehicles: number;
  total_trips_today: number;
}

export interface CreateTenantValues {
  name: string;
  abn?: string | null;
  tsp_number?: string | null;
  bsp_number?: string | null;
  plan?: string;
}

export interface PlatformBillingSummary {
  mrr_aud: string;
  plan_counts: Record<string, number>;
  status_counts: Record<string, number>;
}

export interface TenantSubscription {
  id: string;
  vehicle_id: string;
  plan: string;
  status: string;
  stripe_subscription_id: string | null;
}

export const PLATFORM_PAGE_LIMIT = 20;

export function usePlatformTenants(skip: number) {
  return useQuery({
    queryKey: ["platform", "tenants", skip],
    queryFn: async () => {
      const { data } = await apiClient.get<Page<PlatformTenant>>("/v1/platform/tenants", {
        params: { skip, limit: PLATFORM_PAGE_LIMIT },
      });
      return data;
    },
    placeholderData: (prev) => prev,
  });
}

export function usePlatformHealth() {
  return useQuery({
    queryKey: ["platform", "health"],
    queryFn: async () => {
      const { data } = await apiClient.get<PlatformHealth>("/v1/platform/health");
      return data;
    },
  });
}

export function useTenantSummary(tenantId: string | null) {
  return useQuery({
    queryKey: ["platform", "tenants", tenantId, "summary"],
    queryFn: async () => {
      const { data } = await apiClient.get<TenantSummary>(
        `/v1/platform/tenants/${tenantId}/summary`,
      );
      return data;
    },
    enabled: Boolean(tenantId),
  });
}

export function useCreateTenant() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (values: CreateTenantValues) => {
      const payload = {
        name: values.name.trim(),
        abn: values.abn?.trim() || null,
        tsp_number: values.tsp_number?.trim() || null,
        bsp_number: values.bsp_number?.trim() || null,
        plan: values.plan?.trim() || "standard",
      };
      const { data } = await apiClient.post<PlatformTenant>("/v1/platform/tenants", payload);
      return data;
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["platform", "tenants"] });
      qc.invalidateQueries({ queryKey: ["platform", "health"] });
    },
  });
}

/** Cross-tenant MRR rollup + plan/status breakdowns. mrr_aud/counts are
 * always server-computed (GET /v1/platform/billing/summary) — never derived
 * from anything client-supplied. */
export function usePlatformBillingSummary() {
  return useQuery({
    queryKey: ["platform", "billing", "summary"],
    queryFn: async () => {
      const { data } = await apiClient.get<PlatformBillingSummary>("/v1/platform/billing/summary");
      return data;
    },
  });
}

/** One tenant's subscriptions — the support-triage view inside
 * `TenantDetailModal`'s Billing sub-section. */
export function useTenantBilling(tenantId: string | null) {
  return useQuery({
    queryKey: ["platform", "tenants", tenantId, "billing"],
    queryFn: async () => {
      const { data } = await apiClient.get<TenantSubscription[]>(
        `/v1/platform/tenants/${tenantId}/billing`,
      );
      return data;
    },
    enabled: Boolean(tenantId),
  });
}

/** Suspend/reactivate/trial-flag a tenant from the tenants table. Refetches
 * the tenant list (and its summary, in case it's open) on success. */
export function useUpdateTenantStatus() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ tenantId, status }: { tenantId: string; status: TenantStatus }) => {
      const { data } = await apiClient.patch<PlatformTenant>(`/v1/platform/tenants/${tenantId}`, {
        status,
      });
      return data;
    },
    onSuccess: (_data, { tenantId }) => {
      qc.invalidateQueries({ queryKey: ["platform", "tenants"] });
      qc.invalidateQueries({ queryKey: ["platform", "tenants", tenantId, "summary"] });
    },
  });
}
