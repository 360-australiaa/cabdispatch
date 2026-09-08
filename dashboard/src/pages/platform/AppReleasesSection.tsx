/* One section of the platform console. Split out of `pages/platform/index.tsx`
 * by D5, which was 774 lines carrying five independent sections plus the page
 * itself (audit sec 6 lists it among the files over 500 lines). Each section
 * owns its own queries, so they move out cleanly; `index.tsx` keeps only the
 * page shell, the tenants table and the create-tenant form.
 */

import { useState } from "react";
import { AlertTriangle, Upload } from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Input,
  Modal,
  Pagination,
  Table,
  useToast,
  type TableColumn,
} from "@/components/ui";
import { PLATFORM_PAGE_LIMIT } from "@/hooks/usePlatformConsole";
import {
  useAppReleases,
  usePublishAppRelease,
  useSetAppReleaseActive,
  type AppRelease,
} from "@/hooks/useAppReleases";
import { errorMessage, formatDateTime } from "./format";

const EMPTY_RELEASE_FORM = { version_code: "", version_name: "", release_notes: "" };

/** Publish a new Android build + browse release history — the dashboard side
 * of the OTA self-update pipeline (`domain/AppUpdateChecker.kt` on the
 * device, `app/api/v1/app_releases.py` on the backend). Replaces the earlier
 * "publish via a raw curl call, no UI" gap: this is the only place a real
 * APK ever reaches a tablet, so it lives on the platform-owner console next
 * to the tenant list, not inside any one tenant's fleet page. */
export function AppReleasesSection() {
  // This card sits several screens below the fold on the platform console, so
  // the inline formError alone reaches nobody scrolled elsewhere.
  const toast = useToast();
  const [skip, setSkip] = useState(0);
  const releasesQuery = useAppReleases(skip);
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);
  const publishRelease = usePublishAppRelease(setUploadProgress);
  const setActive = useSetAppReleaseActive();

  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState(EMPTY_RELEASE_FORM);
  const [file, setFile] = useState<File | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  function openPublish() {
    setForm(EMPTY_RELEASE_FORM);
    setFile(null);
    setFormError(null);
    setUploadProgress(null);
    setFormOpen(true);
  }

  async function submitPublish() {
    setFormError(null);
    const versionCode = Number(form.version_code);
    if (!Number.isInteger(versionCode) || versionCode <= 0) {
      setFormError("Version code must be a positive whole number (Android's own versionCode).");
      return;
    }
    if (!form.version_name.trim()) {
      setFormError("Version name is required (e.g. \"1.2.0\").");
      return;
    }
    if (!file) {
      setFormError("Choose the built .apk file to upload.");
      return;
    }
    setUploadProgress(0);
    try {
      await publishRelease.mutateAsync({
        version_code: versionCode,
        version_name: form.version_name,
        release_notes: form.release_notes,
        file,
      });
      setFormOpen(false);
      toast.success("Release published", { description: form.version_name.trim() || undefined });
    } catch (err) {
      // A session that expired mid-upload now retries once against a freshly
      // refreshed token (see apiClient.ts) instead of losing the upload
      // outright — this branch is a real remaining failure (network drop,
      // duplicate version_code, refresh token itself expired), not that.
      setFormError(errorMessage(err));
      toast.error("Failed to publish release", { description: errorMessage(err) });
    } finally {
      setUploadProgress(null);
    }
  }

  const columns: TableColumn<AppRelease>[] = [
    { key: "version_name", header: "Version", render: (r) => <span className="font-medium">{r.version_name}</span> },
    { key: "version_code", header: "Version code", render: (r) => r.version_code },
    {
      key: "is_active",
      header: "Status",
      render: (r) => <Badge variant={r.is_active ? "success" : "outline"}>{r.is_active ? "Active" : "Unpublished"}</Badge>,
    },
    { key: "created_at", header: "Published", sortable: true, sortAccessor: (r) => new Date(r.created_at), render: (r) => formatDateTime(r.created_at) },
    { key: "sha256", header: "SHA-256", render: (r) => <span className="font-mono text-xs text-muted-foreground">{r.sha256.slice(0, 12)}…</span> },
    {
      key: "actions",
      header: "",
      render: (r) => (
        <Button
          variant="outline"
          size="sm"
          disabled={setActive.isPending}
          onClick={(e) => {
            e.stopPropagation();
            setActive.mutate(
              { id: r.id, isActive: !r.is_active },
              {
                onSuccess: () =>
                  toast.success(r.is_active ? "Release unpublished" : "Release republished", {
                    description: r.version_name,
                  }),
                onError: (err) =>
                  toast.error(
                    r.is_active ? "Failed to unpublish release" : "Failed to republish release",
                    { description: errorMessage(err) },
                  ),
              },
            );
          }}
        >
          {r.is_active ? "Unpublish" : "Republish"}
        </Button>
      ),
    },
  ];

  const total = releasesQuery.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PLATFORM_PAGE_LIMIT));
  const page = Math.floor(skip / PLATFORM_PAGE_LIMIT);

  return (
    <Card className="mb-6">
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>App Releases</CardTitle>
        <Button onClick={openPublish}>
          <Upload className="h-4 w-4" />
          Publish release
        </Button>
      </CardHeader>
      <CardContent>
        <p className="mb-3 text-sm text-muted-foreground">
          Every tablet checks the highest active release here — this is the only way a real update
          reaches a tablet; there is no Play Store involved. See a device's own update state on the
          Fleet → Devices table.
        </p>
        {releasesQuery.isError && (
          <p className="mb-3 flex items-center gap-2 text-sm text-destructive">
            <AlertTriangle className="h-4 w-4 shrink-0" />
            Failed to load releases. Check the backend connection and try again.
          </p>
        )}
        <Table
          columns={columns}
          data={releasesQuery.data?.items ?? []}
          rowKey={(r) => r.id}
          isLoading={releasesQuery.isLoading}
          emptyState={releasesQuery.isError ? "Couldn't load releases." : "No releases published yet."}
        />
        {pageCount > 1 && (
          <Pagination
            page={page}
            pageCount={pageCount}
            onPageChange={(p) => setSkip(p * PLATFORM_PAGE_LIMIT)}
          />
        )}
      </CardContent>

      <Modal
        open={formOpen}
        onClose={() => {
          // Closing this doesn't cancel the in-flight axios request, but
          // hiding the modal mid-upload would lose the progress readout and
          // invite a confused second attempt — keep it open until this one
          // actually resolves.
          if (!publishRelease.isPending) setFormOpen(false);
        }}
        title="Publish release"
        description="Uploads a real APK to our own server — nothing goes through the Play Store. Every tablet flagged for update downloads and SHA-256-verifies this exact file before installing it."
        footer={
          <>
            <Button variant="outline" onClick={() => setFormOpen(false)} disabled={publishRelease.isPending}>
              Cancel
            </Button>
            <Button onClick={submitPublish} disabled={publishRelease.isPending}>
              {publishRelease.isPending ? `Uploading… ${uploadProgress ?? 0}%` : "Publish"}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          {formError && (
            <p className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">{formError}</p>
          )}
          {publishRelease.isPending && (
            <div className="flex flex-col gap-1">
              <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
                <div
                  className="h-full rounded-full bg-brand-primary transition-[width] duration-150"
                  style={{ width: `${uploadProgress ?? 0}%` }}
                />
              </div>
              <p className="text-xs text-muted-foreground">
                {uploadProgress === 100
                  ? "Upload complete — verifying on the server…"
                  : `Uploading — ${uploadProgress ?? 0}% (large APKs can take a few minutes; don't close this).`}
              </p>
            </div>
          )}
          <label className="flex flex-col gap-1 text-sm">
            <span className="font-medium text-foreground">APK file</span>
            <Input
              type="file"
              accept=".apk"
              disabled={publishRelease.isPending}
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="font-medium text-foreground">Version code</span>
            <Input
              type="number"
              value={form.version_code}
              disabled={publishRelease.isPending}
              onChange={(e) => setForm((v) => ({ ...v, version_code: e.target.value }))}
              placeholder="Must be higher than every tablet's current versionCode"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="font-medium text-foreground">Version name</span>
            <Input
              value={form.version_name}
              disabled={publishRelease.isPending}
              onChange={(e) => setForm((v) => ({ ...v, version_name: e.target.value }))}
              placeholder="1.2.0"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="font-medium text-foreground">Release notes (optional)</span>
            <Input
              value={form.release_notes}
              disabled={publishRelease.isPending}
              onChange={(e) => setForm((v) => ({ ...v, release_notes: e.target.value }))}
              placeholder="What changed in this build"
            />
          </label>
        </div>
      </Modal>
    </Card>
  );
}
