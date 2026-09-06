import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import { PLATFORM_PAGE_LIMIT, type Page } from "./usePlatformConsole";

/**
 * Data layer for the platform-owner "App Releases" section of `/platform`
 * (`src/pages/platform/index.tsx`) — publishing a new Android APK build and
 * seeing the history of what's been published. Mirrors
 * `app/schemas/app_releases.py` / `app/api/v1/app_releases.py` on the
 * backend. Every route here is gated server-side by `require_platform_owner`,
 * same as the rest of `usePlatformConsole.ts`.
 */

export interface AppRelease {
  id: string;
  version_code: number;
  version_name: string;
  release_notes: string | null;
  is_active: boolean;
  sha256: string;
  created_at: string;
  updated_at: string;
}

export interface PublishReleaseValues {
  version_code: number;
  version_name: string;
  release_notes: string;
  file: File;
}

export function useAppReleases(skip: number) {
  return useQuery({
    queryKey: ["platform", "app-releases", skip],
    queryFn: async () => {
      const { data } = await apiClient.get<Page<AppRelease>>("/v1/platform/app-releases", {
        params: { skip, limit: PLATFORM_PAGE_LIMIT },
      });
      return data;
    },
    placeholderData: (prev) => prev,
  });
}

export function usePublishAppRelease() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (values: PublishReleaseValues) => {
      const formData = new FormData();
      formData.append("version_code", String(values.version_code));
      formData.append("version_name", values.version_name.trim());
      if (values.release_notes.trim()) {
        formData.append("release_notes", values.release_notes.trim());
      }
      formData.append("file", values.file);
      const { data } = await apiClient.post<AppRelease>("/v1/platform/app-releases", formData);
      return data;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["platform", "app-releases"] }),
  });
}

/** Unpublish/republish toggle — a bad build stops being handed out by
 * GET /v1/app-releases/latest without deleting its history row (see
 * AppRelease.is_active's backend doc). */
export function useSetAppReleaseActive() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, isActive }: { id: string; isActive: boolean }) => {
      const { data } = await apiClient.patch<AppRelease>(`/v1/platform/app-releases/${id}`, {
        is_active: isActive,
      });
      return data;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["platform", "app-releases"] }),
  });
}
