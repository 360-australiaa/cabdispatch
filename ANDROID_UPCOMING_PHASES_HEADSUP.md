# Upcoming backend changes that will touch Android (2026-08-28) -- backend/architecture agent

No action needed yet -- Phases 3 and 4 below have not been built server-side. This is a heads-up
so you can plan/ask questions before the API contract actually changes under you, not a task list.
I will send a real, concrete spec (like the pairing-code one) once each piece actually lands,
same as always.

## Phase 3 -- Shifts and handover (next up on my side)

**The big one: shift handover is a brand-new user-facing flow.** This is the direct answer to the
"two drivers, 12-hour shifts, same vehicle" scenario the whole plan started from. New endpoint
`POST /v1/shifts/{outgoing_shift_id}/handover` will need a real screen: outgoing driver confirms
odometer/fuel/cleanliness/damage notes, then the INCOMING driver re-enters their PIN in the same
request as acknowledgement (this is what makes the handover record defensible -- not just a
button tap). Closes the outgoing shift and opens the incoming one atomically. I will send a full
spec (request/response shape, exact fields, error cases) once it is built and tested -- flagging
now purely so "we need a handover screen, not just an end-shift screen" is on your radar.

**`start_shift` gets real validation it does not have today** -- currently it accepts almost
anything. Once built, expect NEW rejection cases your error handling will need to cover: driver
suitability not clear, driver not on the vehicle's roster (VehicleAssignment, already built
Phase 2), vehicle not operational (assert_vehicle_operational, already built Phase 2), driver or
vehicle already has an open shift elsewhere. Today none of these are checked -- once they are,
calls that used to silently succeed may start getting 4xx responses with reasons. Real error
messages/shapes will come with the concrete spec.

**Odometer capture moves to shift start/end**, not just the existing free-form
`inspection_json` blob -- expect a real `odometer_start` field at shift-start time (you may
already collect this in your inspection checklist UI -- if so, this just gives it a first-class
field instead of burying it in JSON).

## Phase 4 -- Trips and the PtP record

**`TripUpdate` will lose `shift_id`.** `shift_id` becomes server-derived (from the caller's open
shift for that driver+vehicle) rather than client-supplied -- if your trip sync/update payloads
currently send `shift_id`, that field will simply be ignored going forward (not an error, just a
no-op) rather than trusted. Worth checking whether anything on your side actually reads the
server's echoed-back `shift_id` and assumes it matches what you sent -- it always will, just not
because you told it to.

**GPS trace persistence on trip close** -- if your app already sends a GPS trace/breadcrumb array
on trip close (may already exist for the live-tracking feature), no client change needed, the
backend just stops discarding it. If it does NOT currently send one, that is worth knowing about
before this phase lands, since capturing it retroactively is not possible.

**Addresses at trip start/end** -- if you already do reverse-geocoding anywhere client-side, the
server likely takes over doing this itself server-side from lat/lng rather than needing it from
you, but worth flagging in case there is an existing client-side address field this should not
duplicate/conflict with.

## Nothing else in Phases 5-7 (documents, live tracking, dashboard) is expected to need
Android changes -- those are backend/dashboard-only. Will update this file (or send a fresh one)
if that turns out to be wrong once they are actually built.

## Standing asks from earlier notes, still open

- `ANDROID_PAIRING_UX_SPEC.md` -- the real "Pair Meter" screen, whenever you get to it.
- `ANDROID_REMOTE_COMMANDS_QUESTION.md` -- the kiosk-lock/heartbeat mystery, blocking a real
  production issue right now (Lilly Cabs device not responding to remote commands) -- more urgent
  than this file if you are choosing what to look at first.

-- Backend/architecture agent