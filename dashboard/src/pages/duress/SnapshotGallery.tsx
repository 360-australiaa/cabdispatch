import { useEffect, useMemo, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight, Images } from "lucide-react";
import { Button } from "@/components/ui";
import { cn } from "@/lib/utils";
import apiClient from "@/lib/apiClient";
import { listDuressSnapshots } from "./api";
import { formatTime } from "./format";

/**
 * Post-incident scrub bar over ALL captured cabin-camera frames for an
 * event -- `CameraSnapshotPanel` only ever shows the single latest frame
 * (`GET /{id}/snapshot/latest`); this reviews the full sequence via
 * `GET /{id}/snapshots` (metadata list) + `GET /{id}/snapshot/{snapshot_id}`
 * (one frame's bytes, fetched on demand as the dispatcher steps through).
 *
 * Frame bytes require the same bearer auth as every other duress route, so
 * -- same reasoning as `CameraSnapshotPanel` -- each selected frame is
 * fetched via `apiClient` as a blob and swapped in as an object URL rather
 * than pointed at directly from an `<img src="...">`. Only the currently
 * selected frame's bytes are ever fetched (not the whole list), so a long
 * incident with hundreds of frames stays cheap to browse.
 */
export function SnapshotGallery({
  eventId,
  enabled,
  refreshSignal,
}: {
  eventId: string;
  /** Gate matching the read role policy on the snapshot endpoints (any
   * authenticated tenant user) -- pass false only when the panel genuinely
   * shouldn't render for this viewer. */
  enabled: boolean;
  /** Any value that changes when a fresh frame has landed -- pass
   * `latestSnapshot?.snapshot_id` from `useDuressLiveGps` so a new
   * `kind:"snapshot"` websocket notification refreshes the list and jumps
   * the scrub position to the newest frame. */
  refreshSignal?: string | null;
}) {
  const snapshotsQuery = useQuery({
    queryKey: ["duress-snapshots", eventId],
    queryFn: () => listDuressSnapshots(eventId),
    enabled,
    // Fallback cadence for viewers with no live socket (mirrors
    // CameraSnapshotPanel's own pollIntervalMs fallback) -- cheap since this
    // is metadata-only, no image bytes.
    refetchInterval: enabled ? 10_000 : false,
  });

  // The list endpoint returns newest-first; reverse to oldest-first so the
  // scrub bar reads left-to-right chronologically, like a timeline.
  const frames = useMemo(
    () => [...(snapshotsQuery.data?.items ?? [])].reverse(),
    [snapshotsQuery.data],
  );

  const [index, setIndex] = useState(0);
  const lastRefreshSignalRef = useRef<string | null | undefined>(undefined);

  // Jump to the newest frame whenever a fresh notification arrives (or on
  // first load once frames exist).
  useEffect(() => {
    if (frames.length === 0) return;
    if (lastRefreshSignalRef.current !== refreshSignal) {
      lastRefreshSignalRef.current = refreshSignal;
      setIndex(frames.length - 1);
    }
  }, [frames.length, refreshSignal]);

  // Keep the selection in bounds if the list shrinks/changes shape.
  useEffect(() => {
    setIndex((prev) => Math.min(prev, Math.max(0, frames.length - 1)));
  }, [frames.length]);

  const current = frames[index] ?? null;

  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [imageState, setImageState] = useState<"idle" | "loading" | "ready" | "error">("idle");
  const objectUrlRef = useRef<string | null>(null);

  useEffect(() => {
    if (!enabled || !current) {
      setImageState("idle");
      return;
    }
    let cancelled = false;
    setImageState("loading");
    apiClient
      .get(`/v1/duress/${eventId}/snapshot/${current.id}`, { responseType: "blob" })
      .then((res) => {
        if (cancelled) return;
        const url = URL.createObjectURL(res.data as Blob);
        if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
        objectUrlRef.current = url;
        setImageUrl(url);
        setImageState("ready");
      })
      .catch(() => {
        if (cancelled) return;
        setImageState("error");
      });

    return () => {
      cancelled = true;
    };
  }, [eventId, current, enabled]);

  useEffect(() => {
    return () => {
      if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
    };
  }, []);

  if (!enabled) return null;
  if (snapshotsQuery.isLoading) {
    return <p className="text-xs text-muted-foreground">Loading frame history…</p>;
  }
  // Nothing captured yet -- CameraSnapshotPanel's own "empty" state already
  // explains this; don't duplicate it here.
  if (frames.length === 0) return null;

  return (
    <div className="flex flex-col gap-2 border-t border-border pt-3">
      <div className="flex items-center justify-between gap-2">
        <span className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
          <Images className="h-3.5 w-3.5" /> Frame history ({frames.length})
        </span>
        <div className="flex items-center gap-1">
          <Button
            type="button"
            variant="outline"
            size="icon"
            className="h-7 w-7"
            disabled={index <= 0}
            onClick={() => setIndex((i) => Math.max(0, i - 1))}
            aria-label="Previous frame"
          >
            <ChevronLeft className="h-3.5 w-3.5" />
          </Button>
          <span className="min-w-[7rem] text-center text-xs text-muted-foreground">
            {current ? formatTime(current.captured_at) : "—"} ({index + 1}/{frames.length})
          </span>
          <Button
            type="button"
            variant="outline"
            size="icon"
            className="h-7 w-7"
            disabled={index >= frames.length - 1}
            onClick={() => setIndex((i) => Math.min(frames.length - 1, i + 1))}
            aria-label="Next frame"
          >
            <ChevronRight className="h-3.5 w-3.5" />
          </Button>
        </div>
      </div>

      <div className="flex gap-1 overflow-x-auto pb-1">
        {frames.map((frame, i) => (
          <button
            key={frame.id}
            type="button"
            onClick={() => setIndex(i)}
            className={cn(
              "shrink-0 whitespace-nowrap rounded border px-2 py-1 font-mono text-[10px] transition-colors",
              i === index
                ? "border-brand-accent bg-brand-accent/10 text-foreground"
                : "border-border text-muted-foreground hover:bg-muted",
            )}
          >
            {formatTime(frame.captured_at)}
          </button>
        ))}
      </div>

      {imageState === "ready" && imageUrl ? (
        <img
          src={imageUrl}
          alt={`Cabin camera frame captured ${current ? formatTime(current.captured_at) : ""}`}
          className="w-full rounded-md border border-border object-cover"
        />
      ) : (
        <div className="flex h-32 items-center justify-center rounded-md border border-dashed border-border text-xs text-muted-foreground">
          {imageState === "loading" && "Loading frame…"}
          {imageState === "error" && "Failed to load this frame."}
        </div>
      )}
    </div>
  );
}
