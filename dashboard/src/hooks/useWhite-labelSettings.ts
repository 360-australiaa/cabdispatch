import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";

/**
 * Data layer for the White-label Settings page
 * (`src/pages/settings/white-label`).
 *
 * `GET`/`PATCH /v1/tenants/me` are real, live backend routes
 * (`backend/app/api/v1/tenants.py`) — confirmed against both source and the
 * deployed server. `shared/openapi.json`/`shared/API_SUMMARY.md` predate
 * that router and are stale on this point; don't take their absence there
 * as evidence the route doesn't exist.
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
  status: string;
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
