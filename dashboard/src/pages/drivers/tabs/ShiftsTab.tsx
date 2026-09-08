import { useState } from "react";
import { Coffee, StopCircle } from "lucide-react";
import { EntityLink } from "@/components/EntityLink";
import { Badge, Button, EmptyState, Pagination, Table, useToast, type TableColumn } from "@/components/ui";
import { errorMessage } from "@/lib/format";
import { useEndBreakMutation, useShiftsQuery, useStartBreakMutation } from "@/pages/shifts/api";
import type { Shift } from "@/pages/shifts/types";
import {
  formatDateTime,
  formatDurationMinutes,
  formatKm,
  formatMoney,
  reconciledBadgeVariant,
  shiftStatusBadgeVariant,
  shiftStatusLabel,
} from "@/pages/shifts/format";

const PAGE_SIZE = 15;

function durationMinutes(shift: Shift): number {
  const start = new Date(shift.start_at).getTime();
  const end = shift.end_at ? new Date(shift.end_at).getTime() : Date.now();
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return 0;
  return (end - start) / 60000;
}

/**
 * Driver page Shifts tab (dashboard command-centre plan §4.2): this
 * driver's shift history, server-paginated and server-filtered by
 * `driver_id` (`GET /v1/shifts?driver_id=`, confirmed real). A "Record
 * break" action appears on whichever row is the currently open shift with
 * no break yet in progress or taken, wired to the real
 * `POST /v1/shifts/{id}/break/start` / `/break/end` endpoints -- present in
 * the backend and, per the command-centre plan's inventory, never called
 * from the dashboard before this tab.
 */
export function ShiftsTab({ driverId, canRecordBreak }: { driverId: string; canRecordBreak: boolean }) {
  const [offset, setOffset] = useState(0);
  const toast = useToast();
  const shiftsQuery = useShiftsQuery({ driver_id: driverId, offset, limit: PAGE_SIZE });
  const startBreak = useStartBreakMutation();
  const endBreak = useEndBreakMutation();

  const shifts = shiftsQuery.data?.items ?? [];
  const total = shiftsQuery.data?.total ?? 0;
  const page = Math.floor(offset / PAGE_SIZE);
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  async function handleStartBreak(id: string) {
    try {
      await startBreak.mutateAsync(id);
      toast.success("Break started");
    } catch (err) {
      toast.error("Failed to start break", { description: errorMessage(err) });
    }
  }

  async function handleEndBreak(id: string) {
    try {
      await endBreak.mutateAsync(id);
      toast.success("Break ended");
    } catch (err) {
      toast.error("Failed to end break", { description: errorMessage(err) });
    }
  }

  const columns: TableColumn<Shift>[] = [
    {
      key: "id",
      header: "Shift",
      render: (s) => <EntityLink kind="shift" id={s.id} name={s.id.slice(0, 8)} />,
    },
    { key: "start_at", header: "Started", render: (s) => formatDateTime(s.start_at) },
    { key: "end_at", header: "Ended", render: (s) => (s.end_at ? formatDateTime(s.end_at) : "—") },
    {
      key: "status",
      header: "Status",
      render: (s) => <Badge variant={shiftStatusBadgeVariant(s)}>{shiftStatusLabel(s)}</Badge>,
    },
    { key: "duration", header: "Duration", render: (s) => formatDurationMinutes(durationMinutes(s)) },
    { key: "trips_count", header: "Trips", render: (s) => s.trips_count },
    { key: "km_total", header: "Distance", render: (s) => formatKm(s.km_total) },
    { key: "cash_total", header: "Cash", render: (s) => formatMoney(s.cash_total) },
    { key: "card_total", header: "Card", render: (s) => formatMoney(s.card_total) },
    {
      key: "reconciled",
      header: "Reconciled",
      render: (s) => <Badge variant={reconciledBadgeVariant(s.reconciled)}>{s.reconciled ? "Yes" : "No"}</Badge>,
    },
    {
      key: "break",
      header: "Break",
      render: (s) => {
        if (s.end_at) return "—";
        if (s.break_started_at) {
          return (
            <div className="flex items-center gap-2">
              <Badge variant="accent">On break</Badge>
              {canRecordBreak && (
                <Button
                  size="sm"
                  variant="outline"
                  disabled={endBreak.isPending}
                  onClick={() => handleEndBreak(s.id)}
                >
                  <StopCircle className="h-3.5 w-3.5" /> End break
                </Button>
              )}
            </div>
          );
        }
        return canRecordBreak ? (
          <Button size="sm" variant="outline" disabled={startBreak.isPending} onClick={() => handleStartBreak(s.id)}>
            <Coffee className="h-3.5 w-3.5" /> Record break
          </Button>
        ) : (
          "—"
        );
      },
    },
  ];

  if (shiftsQuery.isError) {
    return (
      <EmptyState
        title="Couldn't load shifts"
        description={`GET /v1/shifts?driver_id= failed: ${errorMessage(shiftsQuery.error)}`}
      />
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <Table
        columns={columns}
        data={shifts}
        rowKey={(s) => s.id}
        isLoading={shiftsQuery.isLoading}
        label="Shifts"
        emptyState={<EmptyState title="No shifts" description="This driver has no recorded shifts yet." />}
      />
      {total > PAGE_SIZE && (
        <Pagination
          page={page}
          pageCount={pageCount}
          onPageChange={(p) => setOffset(p * PAGE_SIZE)}
          summary={
            <>
              {offset + 1}–{Math.min(total, offset + PAGE_SIZE)} of {total} (page {page + 1} of {pageCount})
            </>
          }
        />
      )}
    </div>
  );
}
