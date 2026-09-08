/**
 * Orders a road's toll points along the road so a corridor line can be drawn
 * through them. The registry lists gantries in no geographic order (by ramp
 * name, by direction, by the order the operator's PDF happened to use), and
 * joining them as listed drew Westlink M7 as a spider's web of chords across
 * western Sydney on the Tariff Studio map.
 *
 * Pure nearest-neighbour chaining: start from the point farthest from the
 * cloud's centroid (an end of the road, for anything road-shaped), then
 * always step to the nearest unvisited point. Not a shortest-path solver --
 * a toll road is close to a line, and for that shape greedy chaining is the
 * right answer at a few dozen points and no cost. Equirectangular distance
 * with a cos(lat) correction is plenty at Sydney's scale.
 */
export interface RoadPoint {
  latitude: number;
  longitude: number;
}

function distance2(a: RoadPoint, b: RoadPoint, cosLat: number): number {
  const dLat = a.latitude - b.latitude;
  const dLng = (a.longitude - b.longitude) * cosLat;
  return dLat * dLat + dLng * dLng;
}

export function orderAlongRoad<T extends RoadPoint>(points: T[]): T[] {
  if (points.length < 3) return points.slice();
  const meanLat = points.reduce((s, p) => s + p.latitude, 0) / points.length;
  const meanLng = points.reduce((s, p) => s + p.longitude, 0) / points.length;
  const cosLat = Math.cos((meanLat * Math.PI) / 180);
  const centroid = { latitude: meanLat, longitude: meanLng };

  let startIndex = 0;
  let farthest = -1;
  points.forEach((p, i) => {
    const d = distance2(p, centroid, cosLat);
    if (d > farthest) {
      farthest = d;
      startIndex = i;
    }
  });

  const remaining = points.map((p, i) => ({ p, i })).filter(({ i }) => i !== startIndex);
  const ordered: T[] = [points[startIndex]];
  while (remaining.length) {
    const last = ordered[ordered.length - 1];
    let bestIdx = 0;
    let best = Infinity;
    remaining.forEach(({ p }, idx) => {
      const d = distance2(last, p, cosLat);
      if (d < best) {
        best = d;
        bestIdx = idx;
      }
    });
    ordered.push(remaining.splice(bestIdx, 1)[0].p);
  }
  return ordered;
}
