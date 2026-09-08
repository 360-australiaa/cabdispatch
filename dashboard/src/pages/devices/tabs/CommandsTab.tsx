/**
 * `/devices/:id?tab=commands` -- deliberately NOT built out, per the
 * dashboard command-centre plan §6.3 and this workstream's own scope: it
 * needs a `device_commands` log table and a `GET
 * /v1/fleet/devices/{id}/commands` endpoint that do not exist yet. The
 * backend today only tracks the CURRENT pending/acked flags on the `Device`
 * row itself (`locate_requested`, `force_update_pending`, `kiosk_locked`,
 * `command_acked_at`/`last_acked_command`) -- which is exactly what the
 * Status tab already shows -- with no durable log of every command ever
 * issued or its pending→acked timing. That is an explicit future
 * workstream, not something to fake here.
 */
export function CommandsTab() {
  return (
    <div className="rounded-lg border border-dashed border-border p-6 text-sm text-muted-foreground">
      <p className="font-medium text-foreground">Not yet available — needs backend history storage.</p>
      <p className="mt-1">
        There is no persisted command log server-side yet — this tab will show the pending → acked
        timing for every kiosk-lock, restart, force-update and locate command once that lands. The
        Status tab shows each command's current state in the meantime.
      </p>
    </div>
  );
}
