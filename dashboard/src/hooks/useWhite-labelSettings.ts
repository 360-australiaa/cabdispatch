import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";

/**
 * Data layer for the White-label Settings page
 * (`src/pages/settings/white-label`).
 *
 * NOTE FOR DOWNSTREAM AGENTS / REVIEWERS: `shared/openapi.json` and
 * `shared/API_SUMMARY.md` do not currently list a `/v1/tenants` router —
 * only `app/models/tenant.py` exists server-side (with a `theme_json: dict
 * | None` JSON column), and there is no `app/api/v1/tenants.py`. This hook
 * calls `GET/PATCH /v1/tenants/me`, mirroring the existing `GET /v1/auth/me`
 * "current caller" convention, as the most likely intended shape for a
 * "current tenant" settings endpoint. Until that backend route is added,
 * both calls will 404 in a live environment — the page surfaces that via
 * its normal React Query error state rather than falling back to mock data
 * (per this module's brief). Swap the URLs below if the backend lands on a
 * different path (e.g. `/v1/tenants/{tenant_id}`).
 */

export interface TenantTheme {
  logo_url: string | null;
  primary_color: string | null;
  accent_color: string | null;
}

export interface TenantRead {
  id: string;
  name: string;
  abn: string | null;
  tsp_number: string | null;
  bsp_number: string | null;
  theme_json: TenantTheme | null;
  plan: string;
}

export interface TenantThemeUpdateInput {
  theme_json: TenantTheme | null;
}

const TENANT_KEY = "tenant-me";

export function useTenantQuery() {
  return useQuery({
    queryKey: [TENANT_KEY],
    queryFn: async () => {
      const res = await apiClient.get<TenantRead>("/v1/tenants/me");
      return res.data;
    },
  });
}

export function useUpdateTenantThemeMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: TenantThemeUpdateInput) => {
      const res = await apiClient.patch<TenantRead>("/v1/tenants/me", input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [TENANT_KEY] });
    },
  });
}
