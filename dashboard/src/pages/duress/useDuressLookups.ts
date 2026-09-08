import { useQuery } from "@tanstack/react-query";
import { listDriverOptionsForTrigger, listVehicleOptionsForDeviceLink } from "./api";
import type { DuressDriverOption, DuressVehicleOption } from "./api";

/** `id -> rego` / `id -> name` lookups shared by everything on this page that
 * has to turn a `vehicle_id`/`driver_id` UUID into something a dispatcher can
 * actually read during a live event -- the events table, the trigger-event
 * form's `<Select>`s, and the event detail panel. One fetch each, cached by
 * React Query's default `staleTime` (not polled: a rego or a driver's name
 * does not change mid-shift, and this is a fleet-wide first-100 lookup, not
 * per-event data).
 *
 * Deliberately honest about the unpaginated cap both underlying lookups
 * share (`GET /v1/fleet/vehicles` / `GET /v1/drivers`, first 100): a fleet
 * bigger than that will show a raw UUID for anything outside the first page
 * rather than a silently wrong name. `resolveVehicle`/`resolveDriver` return
 * `null` for anything not found so callers can render that fact instead of
 * papering over it. */
export function useDuressLookups() {
  const vehiclesQuery = useQuery({
    queryKey: ["duress-lookup-vehicles"],
    queryFn: listVehicleOptionsForDeviceLink,
  });
  const driversQuery = useQuery({
    queryKey: ["duress-lookup-drivers"],
    queryFn: listDriverOptionsForTrigger,
  });

  const vehicles = vehiclesQuery.data ?? [];
  const drivers = driversQuery.data ?? [];

  const vehicleById = new Map(vehicles.map((v) => [v.id, v]));
  const driverById = new Map(drivers.map((d) => [d.id, d]));

  return {
    vehicles,
    drivers,
    isLoading: vehiclesQuery.isLoading || driversQuery.isLoading,
    resolveVehicle: (id: string): DuressVehicleOption | null => vehicleById.get(id) ?? null,
    resolveDriver: (id: string): DuressDriverOption | null => driverById.get(id) ?? null,
  };
}
