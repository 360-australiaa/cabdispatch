import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import { PLATFORM_PAGE_LIMIT, type Page } from "./usePlatformConsole";

/**
 * Data layer for the platform-owner "App Releases" section of `/platform`
 * (`src/pages/platform/index.tsx`) — publishing a new Android APK build and
 * seeing the history of what's been published. Mirrors
 * `app/schemas/app_releases.py` / `app/api/v1/app_releases.py` on the
 * backend. `useAppReleases`/`usePublishAppRelease`/`useSetAppReleaseActive`
 * below hit the `/v1/platform/app-releases` routes, gated server-side by
 * `require_platform_owner`, same as the rest of `usePlatformConsole.ts`.
 *
 * `useLatestAppRelease` is different on purpose: it calls the sibling
 * `GET /v1/app-releases/latest` (no `/platform` in the path) — any
 * authenticated tenant/device user, not platform-owner-gated (see that
 * route's own docstring: "any authenticated tenant/device user, not
 * platform-owner-gated -- this is a read, not a publish"). The device page
 * (dashboard command-centre plan §6) needs "is this tablet on the latest
 * build" for every ordinary owner/admin viewer, not just the platform
 * owner, so it uses this narrower endpoint rather than the platform list.
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

/** `GET /v1/app-releases/latest` response -- deliberately narrower than
 * `AppRelease` (see `LatestAppReleaseRead` on the backend): no `id` or
 * `is_active`, just what a caller needs to decide "is this build current". */
export interface LatestAppRelease {
  version_code: number;
  version_name: string;
  release_notes: string | null;
  download_url: string;
  sha256: string;
}

/**
 * The current published build, for the device page's "app version vs
 * latest release" header fact. 404 means no release has ever been
 * published (or every one has been unpublished) -- treated the same as any
 * other failure by the caller: omit the comparison, never guess. `retry:
 * false` so a real 404 resolves to `isError` promptly instead of retrying a
 * response that will not change.
 */
export function useLatestAppRelease() {
  return useQuery({
    queryKey: ["app-releases", "latest"],
    queryFn: async () => {
      const { data } = await apiClient.get<LatestAppRelease>("/v1/app-releases/latest");
      return data;
    },
    retry: false,
  });
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

/** [onProgress] fires with a real 0-100 (never fabricated smoothing/estimate)
 * on every `onUploadProgress` tick axios reports for the multipart POST — a
 * 144MB APK over a real connection takes real minutes, and the caller (the
 * Publish release modal) renders this as a literal progress bar instead of
 * the old, silent "Uploading…" with no sense of whether it's stuck. */
export function usePublishAppRelease(onProgress?: (percent: number) => void) {
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
      const { data } = await apiClient.post<AppRelease>("/v1/platform/app-releases", formData, {
        onUploadProgress: (event) => {
          if (onProgress && event.total) {
            onProgress(Math.round((event.loaded / event.total) * 100));
          }
        },
      });
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
