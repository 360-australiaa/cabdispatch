/**
 * Approximate-circle GeoJSON polygon builder, shared by every place in the
 * dashboard that draws a lat/lng + radius circle on a Mapbox GL map: the
 * Tariff Studio Toll Zones picker (pages/tariffs/TollZoneMapPicker.tsx,
 * previewing a zone's own radius while editing it) and the Live Map's
 * geofence-breach overlay (pages/live-map/FleetMapCanvas.tsx, drawing every
 * fetched `GET /v1/geofences` circle as map context). Extracted here instead
 * of left duplicated in TollZoneMapPicker.tsx once a second caller needed the
 * exact same shape.
 *
 * 64 points is a flat-earth-per-vertex approximation (equirectangular meters-
 * per-degree at the center latitude, same simplifying assumption as the
 * backend's own haversine-only geofence check — see
 * backend/app/services/geofence.py's module doc: "no PostGIS, no spatial
 * index"). Good enough for a visual radius preview at dispatcher zoom levels,
 * not for geodesic-accurate analysis anywhere near the poles or at
 * planet-scale radii — the backend's `point_in_geofence` (haversine distance)
 * remains the actual source of truth for containment.
 */
export function circlePolygon(centerLat: number, centerLng: number, radiusM: number) {
  const points = 64;
  const coords: [number, number][] = [];
  const latRad = (centerLat * Math.PI) / 180;
  const metersPerDegLat = 111_320;
  const metersPerDegLng = 111_320 * Math.cos(latRad);
  for (let i = 0; i <= points; i++) {
    const angle = (i / points) * 2 * Math.PI;
    const dLat = (radiusM * Math.sin(angle)) / metersPerDegLat;
    const dLng = metersPerDegLng === 0 ? 0 : (radiusM * Math.cos(angle)) / metersPerDegLng;
    coords.push([centerLng + dLng, centerLat + dLat]);
  }
  return {
    type: "Feature" as const,
    geometry: { type: "Polygon" as const, coordinates: [coords] },
    properties: {},
  };
}
