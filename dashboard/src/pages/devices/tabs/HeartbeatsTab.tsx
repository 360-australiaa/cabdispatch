/**
 * `/devices/:id?tab=heartbeats` -- deliberately NOT built out, per the
 * dashboard command-centre plan §6.2 and this workstream's own scope: it
 * needs a `device_heartbeats` history table and a `GET
 * /v1/fleet/devices/{id}/heartbeats?from&to` endpoint that do not exist yet
 * (the backend heartbeat handler updates the `Device` row in place -- there
 * is no durable per-heartbeat history to chart battery/connectivity from).
 * That is an explicit future workstream, not something to fake here: no
 * placeholder chart, no estimated figures, just an honest "not yet" that
 * still gives this tab the right shape (a real component, a real tab in the
 * URL) for when the backend lands.
 */
export function HeartbeatsTab() {
  return (
    <div className="rounded-lg border border-dashed border-border p-6 text-sm text-muted-foreground">
      <p className="font-medium text-foreground">Not yet available — needs backend history storage.</p>
      <p className="mt-1">
        Heartbeat history is not yet recorded server-side — this tab will show battery/connectivity
        charts once that lands.
      </p>
    </div>
  );
}
