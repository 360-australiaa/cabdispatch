import { useEffect, useRef } from "react";
import { Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { useDriverPhoto } from "./api";
import { initials } from "./format";

/**
 * Circular driver avatar. Tries `GET /v1/users/{id}/photo`; while that's
 * pending shows a spinner, on success shows the image, and on error (404 =
 * no photo uploaded) falls back to the same initials-on-lavender treatment
 * used for driver avatars in Messages (`src/pages/messages/index.tsx`).
 */
export function DriverAvatar({
  userId,
  name,
  size = "h-9 w-9",
}: {
  userId: string;
  name: string;
  size?: string;
}) {
  const photoQuery = useDriverPhoto(userId);
  const objectUrlRef = useRef<string | null>(null);

  useEffect(() => {
    objectUrlRef.current = photoQuery.data ?? null;
    return () => {
      if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
    };
  }, [photoQuery.data]);

  if (photoQuery.data) {
    return (
      <img
        src={photoQuery.data}
        alt={name}
        className={cn(size, "shrink-0 rounded-full border border-border object-cover")}
      />
    );
  }

  if (photoQuery.isLoading) {
    return (
      <div
        className={cn(
          "flex shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground",
          size,
        )}
      >
        <Loader2 className="h-3.5 w-3.5 animate-spin" />
      </div>
    );
  }

  return (
    <div
      className={cn(
        "flex shrink-0 items-center justify-center rounded-full bg-brand-lavender text-xs font-semibold text-brand-primary",
        size,
      )}
    >
      {initials(name)}
    </div>
  );
}
