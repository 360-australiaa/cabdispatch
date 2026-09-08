/**
 * Turns a vehicle/driver UUID into something an operator can read at a
 * glance -- rego or name when the lookup resolved it, otherwise the raw id
 * (never a made-up placeholder) so a miss is visibly a miss rather than
 * silently wrong. See `useDuressLookups`'s doc comment for why a miss can
 * legitimately happen (first-100 cap, or the underlying vehicle/driver was
 * deleted after this event was opened).
 */
export function IdentityLabel({
  id,
  label,
  isLoading,
  kind,
}: {
  id: string;
  label: string | null;
  isLoading: boolean;
  kind: "vehicle" | "driver";
}) {
  if (isLoading) {
    return <span className="text-muted-foreground">Loading…</span>;
  }
  if (label) {
    return (
      <span title={id}>
        {label}
      </span>
    );
  }
  return (
    <span
      className="font-mono text-xs text-muted-foreground"
      title={`${kind === "vehicle" ? "Vehicle" : "Driver"} not found in the first 100 -- showing raw id`}
    >
      {id.slice(0, 8)}…
    </span>
  );
}
