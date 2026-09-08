import { Coffee, StopCircle } from "lucide-react";
import { Badge, Button, useToast } from "@/components/ui";
import { errorMessage, formatDateTime } from "@/lib/format";
import { useEndBreakMutation, useStartBreakMutation } from "../api";
import type { Shift } from "../types";

export interface BreaksTabProps {
  shift: Shift;
  canRecord: boolean;
}

/**
 * Shift page Breaks tab (dashboard command-centre plan §7): record/edit
 * using the SAME `POST /v1/shifts/{id}/break/start` / `/break/end`
 * mutations the Driver page's Shifts tab already calls
 * (`@/pages/shifts/api`'s `useStartBreakMutation`/`useEndBreakMutation`) --
 * not forked, per the workstream brief.
 *
 * The `Shift` row only carries the CURRENT break's `break_started_at` plus
 * a `break_taken` boolean -- there is no history table of every break this
 * shift has had, so a shift that took one break earlier and ended it shows
 * "Break taken" with no timestamps, an honest gap rather than a fabricated
 * start/end pair.
 */
export function BreaksTab({ shift, canRecord }: BreaksTabProps) {
  const toast = useToast();
  const startBreak = useStartBreakMutation();
  const endBreak = useEndBreakMutation();

  async function handleStart() {
    try {
      await startBreak.mutateAsync(shift.id);
      toast.success("Break started");
    } catch (err) {
      toast.error("Failed to start break", { description: errorMessage(err) });
    }
  }

  async function handleEnd() {
    try {
      await endBreak.mutateAsync(shift.id);
      toast.success("Break ended");
    } catch (err) {
      toast.error("Failed to end break", { description: errorMessage(err) });
    }
  }

  const shiftOpen = !shift.end_at;

  return (
    <div className="rounded-lg border border-border p-4">
      {!shiftOpen ? (
        <div className="flex items-center gap-2">
          <Badge variant={shift.break_taken ? "success" : "outline"}>
            {shift.break_taken ? "Break taken" : "No break recorded"}
          </Badge>
          {shift.break_taken && (
            <span className="text-xs text-muted-foreground">
              This shift has ended; exact break start/end times aren't retained on the shift record.
            </span>
          )}
        </div>
      ) : shift.break_started_at ? (
        <div className="flex items-center gap-3">
          <Badge variant="accent">On break since {formatDateTime(shift.break_started_at)}</Badge>
          {canRecord && (
            <Button size="sm" variant="outline" disabled={endBreak.isPending} onClick={handleEnd}>
              <StopCircle className="h-3.5 w-3.5" /> End break
            </Button>
          )}
        </div>
      ) : (
        <div className="flex items-center gap-3">
          <Badge variant={shift.break_taken ? "success" : "outline"}>
            {shift.break_taken ? "Break already taken" : "No break yet"}
          </Badge>
          {canRecord && (
            <Button size="sm" variant="outline" disabled={startBreak.isPending} onClick={handleStart}>
              <Coffee className="h-3.5 w-3.5" /> Record break
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
