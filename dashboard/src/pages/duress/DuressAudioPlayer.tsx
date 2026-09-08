import { useEffect, useRef, useState } from "react";
import axios from "axios";
import { Volume2 } from "lucide-react";
import { fetchDuressAudioBlob } from "./api";

/**
 * Authenticated playback of a duress event's captured audio recording --
 * `GET /v1/duress/{event_id}/audio` streams `event.audio_ref` (the tablet's
 * own captured recording; see `app/api/v1/duress.py::get_audio`). That route
 * requires the same bearer auth as every other duress route, so a plain
 * `<audio src="...">` can't point at it directly (no custom headers on a
 * media element's request) -- this fetches the file via `apiClient` (blob
 * response), turns it into an object URL, and swaps that into a real
 * `<audio controls>` element instead. The object URL is revoked on every
 * eventId/audioRef change and on unmount so a long dispatch session doesn't
 * leak memory.
 *
 * Renders nothing at all when `audioRef` is absent -- per the audit brief,
 * a missing recording must show nothing, not a broken player.
 *
 * NOTE: there is no equivalent GET endpoint for `device_audio_ref` (the
 * physical CT-DPD-01 device's own captured audio) in this backend pass --
 * only the tablet-side `audio_ref` has a read route. `EventDetailPanel`
 * keeps `device_audio_ref` as a raw text field for that reason.
 */
export function DuressAudioPlayer({
  eventId,
  audioRef,
}: {
  eventId: string;
  audioRef: string | null;
}) {
  const [url, setUrl] = useState<string | null>(null);
  const [state, setState] = useState<"loading" | "ready" | "empty" | "error">("loading");
  const objectUrlRef = useRef<string | null>(null);

  useEffect(() => {
    if (!audioRef) {
      setState("empty");
      return;
    }
    let cancelled = false;
    setState("loading");
    fetchDuressAudioBlob(eventId)
      .then((blob) => {
        if (cancelled) return;
        const objectUrl = URL.createObjectURL(blob);
        if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
        objectUrlRef.current = objectUrl;
        setUrl(objectUrl);
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
  }, [eventId, audioRef]);

  useEffect(() => {
    return () => {
      if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
    };
  }, []);

  if (!audioRef) return null;

  return (
    <div className="flex flex-col gap-1.5">
      <span className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <Volume2 className="h-3.5 w-3.5" /> Captured audio
      </span>
      {state === "ready" && url && (
        // eslint-disable-next-line jsx-a11y/media-has-caption -- a raw incident recording has no caption track
        <audio controls src={url} className="w-full">
          Your browser does not support inline audio playback.
        </audio>
      )}
      {state === "loading" && (
        <p className="text-xs text-muted-foreground">Loading recording…</p>
      )}
      {state === "empty" && (
        <p className="text-xs text-muted-foreground">
          Event has an audio_ref but no recording file was found on the server.
        </p>
      )}
      {state === "error" && (
        <p className="text-xs text-destructive">Failed to load the audio recording.</p>
      )}
    </div>
  );
}
