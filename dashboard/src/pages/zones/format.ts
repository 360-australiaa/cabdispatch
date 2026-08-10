import axios from "axios";

/** Extracts a human-readable message from an axios error — same shape
 * handling as the sibling tariffs/format.ts helper. */
export function extractErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const detail = err.response?.data?.detail;
    if (typeof detail === "string") return detail;
    if (Array.isArray(detail)) {
      return detail
        .map((d) => {
          const field = Array.isArray(d?.loc) ? d.loc.at(-1) : undefined;
          return field ? `${field}: ${d.msg}` : (d?.msg ?? JSON.stringify(d));
        })
        .join("; ");
    }
    return err.message;
  }
  return err instanceof Error ? err.message : "Something went wrong";
}

export function formatCoords(lat: number, lng: number): string {
  return `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
}
