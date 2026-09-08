import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import apiClient from "@/lib/apiClient";
import { errorMessage } from "@/lib/format";
import { Button, EmptyState, ErrorBanner } from "@/components/ui";
import { FleetMapCanvas, type VehicleMapState } from "@/pages/live-map/FleetMapCanvas";
import { TrailControls } from "@/pages/live-map/TrailControls";
import { useLiveMapGeofencesQuery } from "@/pages/live-map/useGeofences";
import { usePositionHistory } from "@/pages/live-map/usePositionHistory";
import { usePositionHistoryQuery } from "@/pages/live-map/useVehiclePositionHistory";
import { useVehicleDetailQuery } from "@/pages/live-map/useVehicleDetail";
import { geofencesContaining } from "@/pages/live-map/utils";
import { POLL, pollingQueryOptions } from "@/lib/pollIntervals";
import type { DuressEventListResponse } from "@/pages/live-map/types";

export interface LiveTabProps {
  vehicleId: string;
}

/**
 * Plan §5.1 -- `FleetMapCanvas` selected+following on this one vehicle, the
 * opt-in history trail (`TrailControls`), and the always-on "driving
 * signals" readout ported from `VehicleDetailModal`.
 *
 * PROMOTION DECISION (see the environment brief's own "your call" on this):
 * this reuses the Mapbox trail layer (`FleetMapCanvas`'s `trail`/`trailCursor`
 * props + `TrailControls`) that a prior live-map workstream already built to
 * replace the old idea of a second, disconnected SVG projection -- see
 * `TrailControls`'s own doc comment ("Drawing it as a real line layer
 * answers the question an operator actually has ... which a live pin never
 * can"). `VehicleDetailModal`'s `ReplayMiniMap` (a static SVG, deliberately
 * NOT wired into the real map -- see its own doc for why) is therefore NOT
 * ported here: promoting the replay to the real map was already done at the
 * live-map layer, and this tab just reuses that componentry for a single
 * vehicle instead of re-implementing the SVG version a second time.
 */
export function LiveTab({ vehicleId }: LiveTabProps) {
  const liveQuery = useVehicleDetailQuery(vehicleId, { poll: true });
  const live = liveQuery.data;

  const geofencesQuery = useLiveMapGeofencesQuery();
  const geofences = useMemo(() => geofencesQuery.data ?? [], [geofencesQuery.data]);

  // This vehicle's own open duress events -- same endpoint/params the Live
  // Map page polls fleet-wide, narrowed to one vehicle so this tab doesn't
  // have to fetch (and filter down from) every other vehicle's alarms.
  const duressQuery = useQuery({
    queryKey: ["vehicle-page", "duress", vehicleId],
    queryFn: async () => {
      const res = await apiClient.get<DuressEventListResponse>("/v1/duress", {
        params: { vehicle_id: vehicleId, open_only: true, limit: 10 },
      });
      return res.data;
    },
    ...pollingQueryOptions(POLL.LIVE_POSITIONS),
  });
  const duressEvents = duressQuery.data?.items ?? [];

  const vehiclesForIdle = useMemo(() => (live ? [live] : []), [live]);
  const getIdleInfo = usePositionHistory(vehiclesForIdle);

  const vehicleMapStates: VehicleMapState[] = useMemo(() => {
    if (!live) return [];
    return [
      {
        ...live,
        idleInfo: getIdleInfo(live),
        insideGeofences: live.lat != null && live.lng != null ? geofencesContaining(live.lat, live.lng, geofences) : [],
      },
    ];
  }, [live, getIdleInfo, geofences]);

  const [follow, setFollow] = useState(true);

  // Opt-in history trail -- off by default, same reasoning as the Live Map
  // page's own TrailControls (a trail is something you ask for).
  const [trailHours, setTrailHours] = useState<number | null>(null);
  const [trailCursor, setTrailCursor] = useState<number | null>(null);
  const trailSince = useMemo(
    () => (trailHours == null ? undefined : new Date(Date.now() - trailHours * 3600_000).toISOString()),
    [trailHours],
  );
  const trailQuery = usePositionHistoryQuery(trailHours == null ? null : vehicleId, trailSince);
  const trail = useMemo(
    () =>
      (trailQuery.data?.items ?? []).map((p) => ({
        lat: p.lat,
        lng: p.lng,
        speedKmh: p.speed_kmh,
        recordedAt: p.recorded_at,
      })),
    [trailQuery.data],
  );
  useEffect(() => {
    setTrailCursor(null);
  }, [trailHours]);

  // Always-on driving-signals readout -- ported from VehicleDetailModal
  // verbatim (same default retention window, same counts, same captions),
  // independent of whether a trail window above is selected.
  const signalsQuery = usePositionHistoryQuery(vehicleId);

  if (liveQuery.isLoading) {
    return (
      <div className="flex items-center gap-2 py-6 text-sm text-muted-foreground">
        <Loader2 className="h-4 w-4 animate-spin" /> Loading live status…
      </div>
    );
  }

  if (liveQuery.isError) {
    return <ErrorBanner message={`Failed to load this vehicle's live status: ${errorMessage(liveQuery.error)}`} />;
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="rounded-lg border border-border p-3">
        {live && live.lat != null && live.lng != null ? (
          <>
            <FleetMapCanvas
              vehicles={vehicleMapStates}
              duressEvents={duressEvents}
              geofences={geofences}
              onSelectVehicle={() => setFollow(true)}
              selectedVehicleId={vehicleId}
              follow={follow}
              onFollowInterrupted={() => setFollow(false)}
              trail={trail}
              trailCursor={trailCursor}
            />
            <div className="mt-2 flex items-center gap-3 text-xs text-muted-foreground">
              <Button variant={follow ? "primary" : "outline"} size="sm" onClick={() => setFollow((f) => !f)}>
                {follow ? "Following" : "Follow"}
              </Button>
              <span>
                {follow
                  ? "The camera stays on this vehicle. Drag the map to take it back."
                  : "The camera is yours. Turn Follow on to track this vehicle."}
              </span>
            </div>
            <TrailControls
              hours={trailHours}
              onHoursChange={setTrailHours}
              trail={trail}
              cursor={trailCursor}
              onCursorChange={setTrailCursor}
              loading={trailQuery.isLoading}
              harshBrakes={trailQuery.data?.harsh_brake_events ?? 0}
              rapidAccels={trailQuery.data?.rapid_accel_events ?? 0}
            />
          </>
        ) : (
          <EmptyState
            title="No position reported yet"
            description="This vehicle hasn't published a GPS fix. It will appear on the map as soon as its tablet reports one."
          />
        )}
      </div>

      {/* Driving signals -- plain telematics counts, captioned exactly as
          honestly as VehicleDetailModal's own version and the backend's own
          docstring insists (app/services/live_ops.py: "HONESTLY-LABELED
          informational telematics signals ... NOT presented as a
          certified/legal safety score"). */}
      {signalsQuery.isLoading && (
        <div className="flex items-center gap-2 py-2 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" /> Loading driving signals…
        </div>
      )}
      {signalsQuery.isError && (
        <p className="text-sm text-destructive">Failed to load driving signals: {errorMessage(signalsQuery.error)}</p>
      )}
      {signalsQuery.data && (
        <div className="rounded-lg border border-border p-3">
          <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Driving signals</p>
          <div className="grid grid-cols-1 gap-x-4 gap-y-3 text-sm sm:grid-cols-2">
            <div>
              <p className="text-xs text-muted-foreground">Harsh braking</p>
              <p className="font-medium text-foreground">
                {signalsQuery.data.harsh_brake_events} event{signalsQuery.data.harsh_brake_events === 1 ? "" : "s"}{" "}
                (delta greater than {signalsQuery.data.threshold_kmh_per_s} km/h/s)
              </p>
            </div>
            <div>
              <p className="text-xs text-muted-foreground">Rapid acceleration</p>
              <p className="font-medium text-foreground">
                {signalsQuery.data.rapid_accel_events} event{signalsQuery.data.rapid_accel_events === 1 ? "" : "s"}{" "}
                (delta greater than {signalsQuery.data.threshold_kmh_per_s} km/h/s)
              </p>
            </div>
          </div>
          <p className="mt-2 text-xs text-muted-foreground">
            Informational telematics signal, not a certified safety score -- based on a standard
            threshold over the default retention window (about 72h, a technical default, not a fixed
            policy), not a disciplinary rating.
          </p>
        </div>
      )}
    </div>
  );
}
