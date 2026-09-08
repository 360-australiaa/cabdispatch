import { useEffect, useState } from "react";
import { AlertTriangle } from "lucide-react";
import { Button, Input, Modal, Select } from "@/components/ui";
import {
  useCreateAnnouncementMutation,
  useUpdateAnnouncementMutation,
  type Announcement,
  type AnnouncementCreateInput,
  type AnnouncementKind,
} from "./hooks";
import {
  ANNOUNCEMENT_KIND_LABELS,
  extractErrorMessage,
  fromDatetimeLocalValue,
  toDatetimeLocalValue,
} from "./format";

export interface AnnouncementFormModalProps {
  open: boolean;
  onClose: () => void;
  mode: "create" | "edit";
  announcement?: Announcement;
}

interface FormState {
  title: string;
  body: string;
  kind: AnnouncementKind;
  starts_at: string; // datetime-local
  ends_at: string; // datetime-local, "" = open-ended
  active: boolean;
}

const KIND_OPTIONS = (Object.keys(ANNOUNCEMENT_KIND_LABELS) as AnnouncementKind[]).map((k) => ({
  value: k,
  label: ANNOUNCEMENT_KIND_LABELS[k],
}));

function emptyForm(): FormState {
  return {
    title: "",
    body: "",
    kind: "info",
    starts_at: toDatetimeLocalValue(new Date().toISOString()),
    ends_at: "",
    active: true,
  };
}

function formFromAnnouncement(a: Announcement): FormState {
  return {
    title: a.title,
    body: a.body,
    kind: a.kind,
    starts_at: toDatetimeLocalValue(a.starts_at),
    ends_at: toDatetimeLocalValue(a.ends_at),
    active: a.active,
  };
}

/** Create/edit modal for one Announcement — `POST`/`PATCH /v1/announcements[/{id}]`.
 * Drivers see it on their tablet while `active` and inside the window. */
export function AnnouncementFormModal({ open, onClose, mode, announcement }: AnnouncementFormModalProps) {
  const [form, setForm] = useState<FormState>(announcement ? formFromAnnouncement(announcement) : emptyForm());
  const [error, setError] = useState<string | null>(null);

  const createMutation = useCreateAnnouncementMutation();
  const updateMutation = useUpdateAnnouncementMutation();
  const isPending = createMutation.isPending || updateMutation.isPending;

  useEffect(() => {
    if (open) {
      setForm(announcement ? formFromAnnouncement(announcement) : emptyForm());
      setError(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, announcement?.id]);

  function update<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    if (!form.title.trim()) {
      setError("Title is required.");
      return;
    }
    if (!form.body.trim()) {
      setError("Body is required.");
      return;
    }
    const startsAt = fromDatetimeLocalValue(form.starts_at);
    if (!startsAt) {
      setError("Start time is required.");
      return;
    }
    const endsAt = fromDatetimeLocalValue(form.ends_at) ?? null;
    if (endsAt && new Date(endsAt).getTime() <= new Date(startsAt).getTime()) {
      setError("End time must be after the start time.");
      return;
    }

    const payload: AnnouncementCreateInput = {
      title: form.title.trim(),
      body: form.body.trim(),
      kind: form.kind,
      starts_at: startsAt,
      ends_at: endsAt,
      active: form.active,
    };

    try {
      if (mode === "create") {
        await createMutation.mutateAsync(payload);
      } else {
        if (!announcement) return;
        await updateMutation.mutateAsync({ id: announcement.id, input: payload });
      }
      onClose();
    } catch (err) {
      setError(extractErrorMessage(err));
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={mode === "create" ? "New announcement" : `Edit announcement — ${announcement?.title}`}
      description="Shown on every driver tablet in your fleet while active and inside the start/end window."
      footer={
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={isPending}>
            Cancel
          </Button>
          <Button type="submit" form="announcement-form" disabled={isPending}>
            {isPending ? "Saving…" : mode === "create" ? "Publish announcement" : "Save changes"}
          </Button>
        </>
      }
    >
      <form id="announcement-form" onSubmit={handleSubmit} className="flex flex-col gap-4">
        <div className="grid grid-cols-3 gap-4">
          <div className="col-span-2 flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Title</label>
            <Input
              value={form.title}
              onChange={(e) => update("title", e.target.value)}
              placeholder="e.g. Airport rank closed Sunday"
              maxLength={200}
              required
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Kind</label>
            <Select
              options={KIND_OPTIONS}
              value={form.kind}
              onChange={(e) => update("kind", e.target.value as AnnouncementKind)}
            />
          </div>
        </div>

        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Body</label>
          <textarea
            className="flex min-h-[96px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            value={form.body}
            onChange={(e) => update("body", e.target.value)}
            placeholder="What drivers need to know…"
            maxLength={5000}
            required
          />
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Starts</label>
            <Input
              type="datetime-local"
              value={form.starts_at}
              onChange={(e) => update("starts_at", e.target.value)}
              required
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Ends (optional)</label>
            <Input type="datetime-local" value={form.ends_at} onChange={(e) => update("ends_at", e.target.value)} />
          </div>
        </div>

        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            className="h-4 w-4 rounded border-input"
            checked={form.active}
            onChange={(e) => update("active", e.target.checked)}
          />
          Active (uncheck to hide from drivers without deleting)
        </label>

        {error && (
          <div className="flex items-start gap-2 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{error}</span>
          </div>
        )}
      </form>
    </Modal>
  );
}
