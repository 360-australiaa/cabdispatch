import { Badge, Modal } from "@/components/ui";
import { ANNOUNCEMENT_KIND_LABELS, formatDateTime, windowStatus } from "./format";
import type { Announcement } from "./hooks";

export interface AnnouncementPreviewModalProps {
  open: boolean;
  onClose: () => void;
  announcement: Announcement | null;
}

/** An approximation of how this announcement renders on the driver tablet's
 * "Announcements" tile (`GET /v1/me/announcements`), so an operator can
 * check wording and timing before it goes out rather than guessing. This is
 * a dashboard-side mock of that layout, not a live embed of the Android
 * Compose screen — the badge/status/timestamp values are the real fields
 * this row will carry, but the surrounding chrome is illustrative. */
export function AnnouncementPreviewModal({ open, onClose, announcement }: AnnouncementPreviewModalProps) {
  if (!announcement) {
    return <Modal open={open} onClose={onClose} title="Preview" />;
  }
  const { label, variant } = windowStatus(announcement);
  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Driver tablet preview"
      description="Approximate — the tile's real layout lives in the Android app; this mirrors its content and timing."
    >
      <div className="rounded-xl border border-border bg-card p-4 shadow-sm">
        <div className="mb-2 flex items-center justify-between gap-2">
          <Badge variant="outline">{ANNOUNCEMENT_KIND_LABELS[announcement.kind] ?? announcement.kind}</Badge>
          <Badge variant={variant}>{label}</Badge>
        </div>
        <p className="text-base font-semibold leading-snug">{announcement.title}</p>
        <p className="mt-1 whitespace-pre-wrap text-sm text-muted-foreground">{announcement.body}</p>
        <p className="mt-3 text-xs text-muted-foreground">
          Visible from {formatDateTime(announcement.starts_at)}
          {announcement.ends_at ? ` to ${formatDateTime(announcement.ends_at)}` : ", open-ended"}
        </p>
      </div>
      {label !== "Live" && (
        <p className="mt-3 text-xs text-muted-foreground">
          Shown here regardless of status — on the tablet itself, drivers only ever see it while it is
          "Live".
        </p>
      )}
    </Modal>
  );
}
