/**
 * Orders a road's toll points along the road so a corridor line can be drawn
 * through them, and resolves which geometry -- real or reconstructed -- a
 * given road's corridor line should actually use.
 *
 * `resolveCorridorSegments` is the entry point `TollGantryMap.tsx` calls: it
 * prefers the real official road geometry cloned from the NSW Government's
 * own Sydney Motorways Toll Calculator map (`officialTollGeometry.ts`)
 * wherever that source covers a road, and falls back to `orderAlongRoad`'s
 * reconstruction below only for a road the source doesn't cover at all (today:
 * M8, and the toll_free/unpriced M12, Rozelle Interchange and Iron Cove Link --
 * see officialTollGeometry.ts's own doc comment for why).
 *
 * `orderAlongRoad` itself remains an APPROXIMATION, kept only for that
 * fallback case: the registry lists gantries in no geographic order (by ramp
 * name, by direction, by the order the operator's PDF happened to use), and
 * joining them as listed once drew Westlink M7 as a spider's web of chords
 * across western Sydney on the Tariff Studio map -- before this app had any
 * real road geometry to draw instead.
 *
 * Pure nearest-neighbour chaining: start from the point farthest from the
 * cloud's centroid (an end of the road, for anything road-shaped), then
 * always step to the nearest unvisited point. Not a shortest-path solver --
 * a toll road is close to a line, and for that shape greedy chaining is the
 * right answer at a few dozen points and no cost. Equirectangular distance
 * with a cos(lat) correction is plenty at Sydney's scale.
 */
import { officialGeometryFor } from "./officialTollGeometry";

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

/**
 * The corridor segment(s) to actually draw for one road: real official
 * geometry when `officialTollGeometry.ts` has it (as-is, in one or more
 * segments -- never re-ordered, it's already the real road shape), or the
 * `orderAlongRoad` reconstruction of this road's own gantries, as a single
 * segment, when it doesn't. Returns no segments for a road with fewer than 2
 * gantries and no official geometry -- there is nothing to draw a line
 * through.
 */
export function resolveCorridorSegments<T extends RoadPoint>(
  tollRoadId: string,
  gantries: T[],
): readonly RoadPoint[][] {
  const official = officialGeometryFor(tollRoadId);
  if (official) return official;
  if (gantries.length < 2) return [];
  return [orderAlongRoad(gantries)];
}
