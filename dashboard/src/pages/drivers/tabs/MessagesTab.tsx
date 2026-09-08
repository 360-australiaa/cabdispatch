import { useMemo } from "react";
import { ThreadPanel } from "@/pages/messages/ThreadPanel";
import type { DriverOption } from "@/pages/messages/types";

/**
 * Driver page Messages tab (dashboard command-centre plan §4.7): this
 * driver's thread and reply box, reusing `ThreadPanel` verbatim -- the same
 * component the Messages page renders once a driver is picked from its
 * list -- rather than forking a second send form. `GET /v1/messages?driver_id=`
 * and `POST /v1/messages` are both real (confirmed against
 * `backend/app/api/v1/messages.py`).
 *
 * `ThreadPanel`'s own close (X) button has nothing to close back to on this
 * page (there is no driver picker to return to -- the driver is fixed by
 * the route), so it is a no-op here.
 */
export function MessagesTab({ id, name, phone, onShift }: { id: string; name: string; phone: string | null; onShift: boolean }) {
  const driver: DriverOption = useMemo(() => ({ id, name, phone, on_shift: onShift }), [id, name, phone, onShift]);
  return <ThreadPanel driver={driver} onClose={() => {}} />;
}
