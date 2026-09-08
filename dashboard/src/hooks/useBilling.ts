import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";

/** ---- Types (mirrors shared/openapi.json billing + fleet vehicle schemas) ---- */

export type BillingPlan = "basic" | "pro" | "enterprise";
export type SubscriptionStatus = "trialing" | "active" | "past_due" | "canceled" | "incomplete";
export type InvoiceStatus = "draft" | "open" | "paid" | "void" | "uncollectible";

export interface SubscriptionRead {
  id: string;
  tenant_id: string;
  vehicle_id: string;
  plan: BillingPlan;
  status: SubscriptionStatus;
  price_aud: string;
  stripe_subscription_id: string | null;
  created_at: string;
  updated_at: string;
}

export interface SubscriptionListResponse {
  items: SubscriptionRead[];
  total: number;
  skip: number;
  limit: number;
}

export interface SubscriptionCreate {
  vehicle_id: string;
  plan: BillingPlan;
}

export interface SubscriptionUpdate {
  plan?: BillingPlan | null;
  status?: SubscriptionStatus | null;
}

export interface InvoiceRead {
  id: string;
  subscription_id: string;
  stripe_invoice_id: string | null;
  amount_aud: string;
  status: InvoiceStatus;
  period_start: string;
  period_end: string;
  mock: boolean;
}

export interface InvoiceListResponse {
  items: InvoiceRead[];
  total: number;
  mock: boolean;
}

export interface VehicleRead {
  id: string;
  rego: string;
  vehicle_class?: string;
  status?: string;
}

interface VehicleListResponse {
  items: VehicleRead[];
  total: number;
  skip: number;
  limit: number;
}

export interface ConnectOnboardResponse {
  mock: boolean;
  url: string;
  stripe_account_id: string | null;
}

/** ---- Formatting ---- */

/** Money fields come back as decimal strings; format explicitly for display only. */
export function formatAud(amount: string | null | undefined): string {
  if (amount == null || amount === "") return "—";
  const n = Number(amount);
  if (Number.isNaN(n)) return amount;
  return new Intl.NumberFormat("en-AU", { style: "currency", currency: "AUD" }).format(n);
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString("en-AU", { year: "numeric", month: "short", day: "numeric" });
}

/** ---- Subscriptions ---- */

export interface SubscriptionFilters {
  skip: number;
  limit: number;
  plan?: BillingPlan;
  status?: SubscriptionStatus;
}

export function useSubscriptions(filters: SubscriptionFilters) {
  return useQuery({
    queryKey: ["billing", "subscriptions", filters],
    queryFn: async () => {
      const { data } = await apiClient.get<SubscriptionListResponse>("/v1/billing/subscriptions", {
        params: {
          skip: filters.skip,
          limit: filters.limit,
          plan: filters.plan || undefined,
          status: filters.status || undefined,
        },
      });
      return data;
    },
    placeholderData: keepPreviousData,
  });
}

export function useCreateSubscription() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: SubscriptionCreate) => {
      const { data } = await apiClient.post<SubscriptionRead>("/v1/billing/subscriptions", body);
      return data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["billing", "subscriptions"] });
    },
  });
}

export function useUpdateSubscription() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, body }: { id: string; body: SubscriptionUpdate }) => {
      const { data } = await apiClient.patch<SubscriptionRead>(`/v1/billing/subscriptions/${id}`, body);
      return data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["billing", "subscriptions"] });
    },
  });
}

export function useCancelSubscription() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const { data } = await apiClient.delete<SubscriptionRead>(`/v1/billing/subscriptions/${id}`);
      return data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["billing", "subscriptions"] });
    },
  });
}

/** ---- Stripe Connect onboarding ---- */

export function useConnectOnboard() {
  return useMutation({
    mutationFn: async () => {
      const { data } = await apiClient.post<ConnectOnboardResponse>("/v1/billing/connect/onboard");
      return data;
    },
  });
}

/** ---- Invoices ---- */

export function useInvoices(subscriptionId?: string) {
  return useQuery({
    queryKey: ["billing", "invoices", subscriptionId ?? "all"],
    queryFn: async () => {
      const { data } = await apiClient.get<InvoiceListResponse>("/v1/billing/invoices", {
        params: subscriptionId ? { subscription_id: subscriptionId } : undefined,
      });
      return data;
    },
  });
}

/** ---- Vehicles (for the subscription create form + rego lookups) ---- */

export function useVehiclesForBilling() {
  return useQuery({
    queryKey: ["fleet", "vehicles", "for-billing"],
    queryFn: async () => {
      const { data } = await apiClient.get<VehicleListResponse>("/v1/fleet/vehicles", {
        params: { limit: 100 },
      });
      return data.items;
    },
    staleTime: 60_000,
  });
}
