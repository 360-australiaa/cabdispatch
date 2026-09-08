/**
 * Non-blocking sanity checks for a typed lat/lng pair.
 *
 * A zone was once saved with the two fields swapped (24.86, 67.0 entered as
 * lat 67.0 / lng 24.86) and landed in Norway. Neither value was out of range,
 * so validation passed. These helpers give the operator a human check instead:
 * a hemisphere-lettered rendering of what they typed, and a hint when the
 * pair looks like it has been transposed.
 */

/** Both numbers parse and are within the valid lat/lng ranges. */
export function parseCoords(latText: string, lngText: string): { lat: number; lng: number } | null {
  if (latText.trim() === "" || lngText.trim() === "") return null;
  const lat = Number(latText);
  const lng = Number(lngText);
  if (Number.isNaN(lat) || Number.isNaN(lng)) return null;
  if (Math.abs(lat) > 90 || Math.abs(lng) > 180) return null;
  return { lat, lng };
}

/**
 * Heuristic, not a rule: this product's fleets operate in Australia and Asia,
 * where latitude stays within roughly ±50 and longitude sits between 60 and
 * 180. A latitude beyond 60 paired with a longitude inside 60 is, for those
 * fleets, almost certainly the two fields the wrong way round.
 */
export function looksSwapped(lat: number, lng: number): boolean {
  return Math.abs(lat) > 60 && Math.abs(lng) <= 60;
}

/** "24.8600° N, 67.0000° E" -- the pair as a human would read it off a map. */
export function formatHumanCoords(lat: number, lng: number): string {
  const ns = lat < 0 ? "S" : "N";
  const ew = lng < 0 ? "W" : "E";
  return `${Math.abs(lat).toFixed(4)}° ${ns}, ${Math.abs(lng).toFixed(4)}° ${ew}`;
}

export const SWAP_HINT =
  "These look swapped — latitude for Australia/Asia is usually between -50 and 50; longitude 60–180.";
