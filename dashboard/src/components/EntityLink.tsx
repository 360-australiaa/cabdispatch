import { type ReactNode } from "react";
import { Link } from "react-router-dom";
import { cn } from "@/lib/utils";

export type EntityLinkKind = "driver" | "vehicle" | "device" | "trip" | "shift";

const ROUTE_PREFIX: Record<EntityLinkKind, string> = {
  driver: "/drivers",
  vehicle: "/vehicles",
  device: "/devices",
  trip: "/trips",
  shift: "/shifts",
};

export interface EntityLinkProps {
  kind: EntityLinkKind;
  id: string;
  /** Display text -- usually a name, rego, or android id, not the raw id. */
  name: ReactNode;
  className?: string;
}

/**
 * Router link to an entity's full page (`/drivers/:id`, `/vehicles/:id`,
 * `/devices/:id`, `/trips/:id`, `/shifts/:id` -- see `EntityPage`). Every
 * table cell or modal field that names one of these entities should render
 * it through this component rather than plain text, so the whole app gets
 * the benefit of an entity existing the moment its page does.
 *
 * Styling: `Table.tsx` has no existing link convention of its own to match,
 * so this uses the plain fallback the F1 workstream calls for -- no colour
 * of its own (Tailwind's preflight makes an `<a>` inherit `color` from its
 * cell), underline only on hover. A couple of other pages (e.g.
 * `RatingsPage`) style their own trip links in the brand colour; this is
 * deliberately quieter so it reads as "this cell is also a link", not as a
 * differently-coloured column.
 *
 * `stopPropagation`: every one of this workstream's three wiring spots sits
 * inside something else that reacts to a click on the same row/field (a
 * table's `onRowClick`, or a modal's own field). Without stopping
 * propagation, clicking the entity's name would fire the row/field's click
 * handler as well as (or before) the navigation -- e.g. Overview's "Fleet
 * right now" table opens `/live-map?vehicle=` on any row click, so a rego
 * click would race the two navigations.
 */
export function EntityLink({ kind, id, name, className }: EntityLinkProps) {
  return (
    <Link
      to={`${ROUTE_PREFIX[kind]}/${id}`}
      onClick={(e) => e.stopPropagation()}
      className={cn("underline-offset-2 outline-none hover:underline focus-visible:underline", className)}
    >
      {name}
    </Link>
  );
}
