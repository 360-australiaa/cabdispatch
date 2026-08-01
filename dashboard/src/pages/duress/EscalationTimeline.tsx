import { useEffect, useState, type ReactNode } from "react";
import { Check, Circle, Clock, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { formatDateTime, formatStageLabel, secondsUntil } from "./format";
import { ESCALATION_STAGES, isTerminalStatus, type DuressEvent } from "./types";

/**
 * Renders `escalation_log_json` as a timeline/checklist: the fixed cascade
 * (cancel_window_expired -> notify_dispatch -> sms_emergency_contacts ->
 * present_000_call_script) with completed stages checked off against their
 * logged timestamp/note, the next stage highlighted as pending, and a live
 * countdown while the event is still inside its self-cancel window.
 */
export function EscalationTimeline({ event }: { event: DuressEvent }) {
  const log = event.escalation_log_json ?? {};
  const entries = log.entries ?? [];
  const nextStageIndex = log.next_stage_index ?? 0;
  const isTerminal = isTerminalStatus(event.status);

  const entryFor = (stage: string) => entries.find((e) => e.stage === stage);
  const openedEntry = entryFor("opened");
  const terminalEntry = entries.find((e) => e.stage === "resolved" || e.stage === "cancelled");

  const [now, setNow] = useState(() => Date.now());
  const showCountdown = event.status === "open" && !!log.cancel_deadline_at;
  useEffect(() => {
    if (!showCountdown) return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [showCountdown]);
  const remaining = showCountdown ? secondsUntil(log.cancel_deadline_at, now) : null;

  return (
    <ol className="flex flex-col gap-3">
      <TimelineStep
        state="done"
        label="Opened"
        timestamp={openedEntry?.at ?? event.opened_at}
        note={openedEntry?.detail}
      />

      {ESCALATION_STAGES.map((stage, i) => {
        const entry = entryFor(stage);
        const state: StepState = entry ? "done" : i === nextStageIndex && !isTerminal ? "pending" : "upcoming";
        return (
          <TimelineStep
            key={stage}
            state={state}
            label={formatStageLabel(stage)}
            timestamp={entry?.at}
            note={entry?.note ?? undefined}
            extra={
              state === "pending" && remaining !== null ? (
                <span
                  className={cn(
                    "inline-flex items-center gap-1 text-xs font-medium",
                    remaining <= 3 ? "text-destructive" : "text-muted-foreground",
                  )}
                >
                  <Clock className="h-3 w-3" />
                  cancel window closes in {remaining}s
                </span>
              ) : undefined
            }
          />
        );
      })}

      {terminalEntry && (
        <TimelineStep
          state="done"
          label={formatStageLabel(terminalEntry.stage)}
          timestamp={terminalEntry.at}
          note={terminalEntry.note ?? undefined}
          tone={terminalEntry.stage === "cancelled" ? "muted" : "success"}
        />
      )}
    </ol>
  );
}

type StepState = "done" | "pending" | "upcoming";

function TimelineStep({
  state,
  label,
  timestamp,
  note,
  extra,
  tone = "default",
}: {
  state: StepState;
  label: string;
  timestamp?: string;
  note?: string;
  extra?: ReactNode;
  tone?: "default" | "success" | "muted";
}) {
  return (
    <li className="flex gap-3">
      <div
        className={cn(
          "mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full",
          state === "done" && tone === "success" && "bg-success text-success-foreground",
          state === "done" && tone === "muted" && "bg-muted text-muted-foreground",
          state === "done" && tone === "default" && "bg-brand-primary text-brand-primary-foreground",
          state === "pending" && "bg-brand-accent text-brand-accent-foreground",
          state === "upcoming" && "border border-border text-muted-foreground",
        )}
      >
        {state === "done" && <Check className="h-3 w-3" />}
        {state === "pending" && <Loader2 className="h-3 w-3 animate-spin" />}
        {state === "upcoming" && <Circle className="h-2 w-2 fill-current" />}
      </div>
      <div className="flex-1 pb-1">
        <div className="flex flex-wrap items-center gap-2">
          <span
            className={cn(
              "text-sm font-medium",
              state === "upcoming" ? "text-muted-foreground" : "text-foreground",
            )}
          >
            {label}
          </span>
          {timestamp && (
            <span className="text-xs text-muted-foreground">{formatDateTime(timestamp)}</span>
          )}
        </div>
        {note && <p className="mt-0.5 text-xs text-muted-foreground">Note: {note}</p>}
        {extra && <div className="mt-0.5">{extra}</div>}
      </div>
    </li>
  );
}
