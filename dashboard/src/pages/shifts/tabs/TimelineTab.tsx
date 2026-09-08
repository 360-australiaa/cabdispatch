import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, Coffee, Flag, MapPin, Play, Square } from "lucide-react";
import apiClient from "@/lib/apiClient";
import { EntityLink } from "@/components/EntityLink";
import { ErrorBanner, Skeleton } from "@/components/ui";
import { errorMessage, formatDateTime } from "@/lib/format";
import { useTripsQuery } from "@/hooks/useTrips";
import type { Shift } from "../types";

export interface TimelineTabProps {
  shift: Shift;
}

interface FatigueAlertRow {
  id: string;
  kind: string;
  triggered_at: string;
}

interface DuressEventRow {
  id: string;
  trigger: string;
  opened_at: string;
}

interface TimelineEntry {
  at: string;
  icon: typeof Play;
  label: string;
  tone: "default" | "warning";
  link?: { kind: "trip"; id: string };
}

/** `GET /v1/fatigue-alerts?driver_id=` with no `acknowledged` filter (the
 * Driver page's own hook, `useDriverFatigueAlerts`, always forces
 * `acknowledged=false` for its "open alerts" card -- this tab wants the
 * full history within the shift's window, acknowledged or not). */
function useFatigueAlertsInWindow(driverId: string) {
  return useQuery({
    queryKey: ["shift-timeline", "fatigue-alerts", driverId],
    queryFn: async () => {
      const { data } = await apiClient.get<{ items: FatigueAlertRow[]; total: number }>(
        "/v1/fatigue-alerts",
        { params: { driver_id: driverId, skip: 0, limit: 50 } },
      );
      return data.items;
    },
  });
}

/** `GET /v1/duress?driver_id=` -- real, driver-scoped (`backend/app/api/v1/duress.py`). */
function useDuressEventsInWindow(driverId: string) {
  return useQuery({
    queryKey: ["shift-timeline", "duress", driverId],
    queryFn: async () => {
      const { data } = await apiClient.get<{ items: DuressEventRow[]; total: number }>("/v1/duress", {
        params: { driver_id: driverId, limit: 50, offset: 0 },
      });
      return data.items;
    },
  });
}

function withinWindow(at: string, startMs: number, endMs: number): boolean {
  const t = new Date(at).getTime();
  return Number.isFinite(t) && t >= startMs && t <= endMs;
}

/**
 * Shift page Timeline tab (dashboard command-centre plan §7): shift start,
 * breaks, trips, and (if reachable) duress/fatigue-alert events for this
 * driver inside this shift's time window, on one plain chronological list
 * -- deliberately not a fancy visual timeline, per the workstream brief
 * ("a plain ordered list of {time, event, link} rows is a perfectly good
 * v1; don't over-build this").
 */
export function TimelineTab({ shift }: TimelineTabProps) {
  const windowEnd = shift.end_at ?? new Date().toISOString();
  const tripsQuery = useTripsQuery({
    driver_id: shift.driver_id,
    start_from: shift.start_at,
    start_to: windowEnd,
    limit: 200,
  });
  const fatigueQuery = useFatigueAlertsInWindow(shift.driver_id);
  const duressQuery = useDuressEventsInWindow(shift.driver_id);

  const isLoading = tripsQuery.isLoading || fatigueQuery.isLoading || duressQuery.isLoading;

  const entries = useMemo<TimelineEntry[]>(() => {
    const startMs = new Date(shift.start_at).getTime();
    const endMs = new Date(windowEnd).getTime();
    const list: TimelineEntry[] = [
      { at: shift.start_at, icon: Play, label: "Shift started", tone: "default" },
    ];

    if (shift.end_at) {
      list.push({ at: shift.end_at, icon: Square, label: "Shift ended", tone: "default" });
    }

    if (shift.break_started_at) {
      list.push({ at: shift.break_started_at, icon: Coffee, label: "Break started", tone: "default" });
    }

    for (const trip of (tripsQuery.data?.items ?? []).filter((t) => t.shift_id === shift.id)) {
      list.push({
        at: trip.start_at,
        icon: MapPin,
        label: `Trip started (${trip.id.slice(0, 8)})`,
        tone: "default",
        link: { kind: "trip", id: trip.id },
      });
      if (trip.end_at) {
        list.push({
          at: trip.end_at,
          icon: MapPin,
          label: `Trip ended (${trip.id.slice(0, 8)})`,
          tone: "default",
          link: { kind: "trip", id: trip.id },
        });
      }
    }

    for (const alert of fatigueQuery.data ?? []) {
      if (!withinWindow(alert.triggered_at, startMs, endMs)) continue;
      list.push({
        at: alert.triggered_at,
        icon: AlertTriangle,
        label: `Fatigue alert: ${alert.kind.replace(/_/g, " ")}`,
        tone: "warning",
      });
    }

    for (const event of duressQuery.data ?? []) {
      if (!withinWindow(event.opened_at, startMs, endMs)) continue;
      list.push({
        at: event.opened_at,
        icon: Flag,
        label: `Duress event opened (${event.trigger})`,
        tone: "warning",
      });
    }

    return list.sort((a, b) => new Date(a.at).getTime() - new Date(b.at).getTime());
  }, [shift, tripsQuery.data, fatigueQuery.data, duressQuery.data, windowEnd]);

  if (isLoading) {
    return <Skeleton className="h-40 w-full" />;
  }

  if (tripsQuery.isError) {
    return (
      <ErrorBanner message={`Failed to load this shift's trips: ${errorMessage(tripsQuery.error)}`} />
    );
  }

  return (
    <div className="flex flex-col gap-2">
      {(fatigueQuery.isError || duressQuery.isError) && (
        <p className="text-xs text-muted-foreground">
          Fatigue alerts and/or duress events could not be loaded for this window -- the timeline below
          may be missing those rows.
        </p>
      )}
      <ol className="flex flex-col gap-1.5">
        {entries.map((entry, i) => {
          const Icon = entry.icon;
          return (
            <li
              key={i}
              className={`flex items-center gap-2.5 rounded-md border border-border p-2 text-sm ${
                entry.tone === "warning" ? "bg-destructive/5" : ""
              }`}
            >
              <Icon className={`h-4 w-4 shrink-0 ${entry.tone === "warning" ? "text-destructive" : "text-muted-foreground"}`} />
              <span className="w-40 shrink-0 text-xs text-muted-foreground">{formatDateTime(entry.at)}</span>
              <span className="flex-1">
                {entry.link ? (
                  <EntityLink kind={entry.link.kind} id={entry.link.id} name={entry.label} />
                ) : (
                  entry.label
                )}
              </span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
