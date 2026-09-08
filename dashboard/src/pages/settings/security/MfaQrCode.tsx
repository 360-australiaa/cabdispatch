import { useEffect, useState } from "react";

/**
 * Renders an `otpauth://` URI as a scannable QR code.
 *
 * This component itself is loaded via `React.lazy` from `index.tsx`, and the
 * `qrcode` library is loaded via a SECOND, INNER dynamic `import()` inside
 * the effect below — not a static top-level `import`. Two separate reasons:
 *
 *   1. Nobody visiting `/settings/security` needs a QR renderer on first
 *      paint — most visits never open the "enable two-factor" flow at all.
 *      A static import would pull `qrcode` into this page's own chunk
 *      (already lazy via the route table) and fetch it the moment the
 *      Security page loads, not the moment a QR code is actually needed.
 *   2. It must never be named in `vite.config.ts`'s `manualChunks` — doing so
 *      would make it a *static* dependency of the entry bundle instead of a
 *      chunk fetched on demand (see that file's own comment on `recharts`
 *      for the identical reasoning). It stays unlisted, so Rollup gives it
 *      its own on-demand chunk purely from this dynamic `import()` call.
 *
 * Renders to a `data:` URL via `QRCode.toDataURL` rather than drawing to a
 * `<canvas>` directly — simpler to test (an `<img src>` is just a string
 * assertion) and trivially themeable (dark modules on a white square reads
 * fine in both light and dark mode without extra CSS).
 */
export default function MfaQrCode({ otpauthUri }: { otpauthUri: string }) {
  const [dataUrl, setDataUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;

    // Deliberately no synchronous setDataUrl(null)/setFailed(false) reset
    // here: `otpauthUri` only ever changes if a user cancels setup and
    // restarts it (a new secret), in which case briefly showing the
    // previous QR for one tick until the new one resolves is harmless —
    // and avoiding the reset keeps this effect free of a setState call
    // outside the async continuation below.
    import("qrcode")
      .then((QRCode) => QRCode.toDataURL(otpauthUri, { width: 200, margin: 1 }))
      .then((url) => {
        if (!cancelled) {
          setDataUrl(url);
          setFailed(false);
        }
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });

    return () => {
      cancelled = true;
    };
  }, [otpauthUri]);

  if (failed) {
    // The manual-entry secret shown alongside this component in index.tsx
    // is still a complete fallback — a QR render failure never blocks setup.
    return (
      <p className="text-xs text-muted-foreground">
        Couldn't render a QR code — use the manual-entry secret below instead.
      </p>
    );
  }

  if (!dataUrl) {
    return (
      <div
        className="h-[200px] w-[200px] animate-pulse rounded-md bg-muted"
        role="status"
        aria-label="Generating QR code"
      />
    );
  }

  return (
    <img
      src={dataUrl}
      alt="Scan with your authenticator app to add this account"
      width={200}
      height={200}
      className="rounded-md border border-border"
    />
  );
}
