import { useEffect, useRef, useState } from "react";
import axios from "axios";
import { Camera } from "lucide-react";
import apiClient from "@/lib/apiClient";
import { formatTime } from "./format";
import { SnapshotGallery } from "./SnapshotGallery";

/**
 * Cabin-camera still-frame viewer for a duress event -- see
 * app/models/duress_snapshot.py for why this is a still-frame gallery
 * (GET /v1/duress/{id}/snapshot/latest) rather than continuous video, and
 * useDuressLiveGps for the kind:"snapshot" websocket notification that
 * drives refreshSignal below (no polling needed once that's flowing).
 *
 * The endpoint requires the same bearer auth as every other duress route, so
 * a plain <img src="..."> can't point at it directly (no custom headers on
 * an <img> request) -- this fetches the JPEG via apiClient (blob response),
 * turns it into an object URL, and swaps that URL into an <img> instead.
 * Object URLs are revoked on every replacement and on unmount so repeated
 * refreshes over a long-running incident don't leak memory.
 */
export function CameraSnapshotPanel({
  eventId,
  enabled,
  refreshSignal,
  pollIntervalMs,
}: {
  eventId: string;
  /** Gate matching the read role policy on GET .../snapshot/latest (any
   * authenticated tenant user, same as audio playback) -- pass false only
   * when the panel genuinely shouldn't render for this viewer. */
  enabled: boolean;
  /** Any value that changes when a fresh frame should be fetched -- pass
   * `latestSnapshot?.snapshot_id` from useDuressLiveGps so an incoming
   * kind:"snapshot" websocket notification triggers an immediate refetch. */
  refreshSignal?: string | null;
  /** Fallback refresh cadence for viewers who can't hold the live-GPS
   * websocket (see EventDetailPanel's LIVE_GPS_ROLES gate) -- omit when
   * refreshSignal is already being driven by that socket. */
  pollIntervalMs?: number;
}) {
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [capturedAt, setCapturedAt] = useState<string | null>(null);
  const [state, setState] = useState<"loading" | "ready" | "empty" | "error">("loading");
  const objectUrlRef = useRef<string | null>(null);
  const [pollTick, setPollTick] = useState(0);

  useEffect(() => {
    if (!enabled || !pollIntervalMs) return;
    const id = window.setInterval(() => setPollTick((t) => t + 1), pollIntervalMs);
    return () => window.clearInterval(id);
  }, [enabled, pollIntervalMs]);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    setState((prev) => (prev === "ready" ? prev : "loading"));

    apiClient
      .get(`/v1/duress/${eventId}/snapshot/latest`, { responseType: "blob" })
      .then((res) => {
        if (cancelled) return;
        const url = URL.createObjectURL(res.data as Blob);
        if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
        objectUrlRef.current = url;
        setImageUrl(url);
        setCapturedAt(new Date().toISOString());
        setState("ready");
      })
      .catch((err) => {
        if (cancelled) return;
        if (axios.isAxiosError(err) && err.response?.status === 404) {
          setState("empty");
        } else {
          setState("error");
        }
      });

    return () => {
      cancelled = true;
    };
  }, [eventId, enabled, refreshSignal, pollTick]);

  useEffect(() => {
    return () => {
      if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
    };
  }, []);

  if (!enabled) {
    return (
      <p className="text-xs text-muted-foreground">
        Camera snapshots are restricted to owner/admin/dispatcher roles.
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center justify-between">
        <span className="flex items-center gap-1.5 text-sm font-medium text-foreground">
          <Camera className="h-3.5 w-3.5" /> Cabin camera
        </span>
        {state === "ready" && (
          <span className="text-xs text-muted-foreground">Updated {formatTime(capturedAt)}</span>
        )}
      </div>

      {state === "error" && (
        <p className="text-xs text-destructive">Failed to load the latest camera snapshot.</p>
      )}

      {state === "ready" && imageUrl ? (
        <img
          src={imageUrl}
          alt="Latest cabin camera snapshot"
          className="w-full rounded-md border border-border object-cover"
        />
      ) : (
        <div className="flex h-40 items-center justify-center rounded-md border border-dashed border-border text-xs text-muted-foreground">
          {state === "loading" && "Loading…"}
          {state === "empty" &&
            "No camera snapshot captured for this event yet -- frames only arrive while the tablet has an active duress event open."}
          {state === "error" && "—"}
        </div>
      )}

      <SnapshotGallery eventId={eventId} enabled={enabled} refreshSignal={refreshSignal} />
    </div>
  );
}