# GPS-Blackout Hardware Fallback + Program Plan (2026-09-11)

**Companion document:** `docs/audits/2026-09-11-full-stack-audit.md` — the full findings this plan is built from.

This document has two parts. Part A is the centerpiece — a hardware fallback for GPS blackout, because it's the thing you asked for specifically and because it's the one gap in this product that genuinely distinguishes it from the established NSW competitors. Part B is the prioritized remediation plan for everything else the audit found.

---

# PART A — GPS-blackout hardware fallback

## The problem, precisely

Every fare this app bills is computed from phone GPS. In a real tunnel (Cross City, Lane Cove, NorthConnex, M5 East, the WestConnex tunnels — all in the Sydney CBD/inner-west, i.e. constantly), GPS goes dark. The app already has a real, shipped fix for the *specific* case of "the blackout happened inside a mapped toll tunnel" — `KnownCorridor.kt`, which I finished the server-side mirror of earlier in this session (commit `2531719`, `docs/audits`' Android Part 3). That fix is good and should stay exactly as it is. But it only covers roads already in the toll registry. It does nothing for:

- An underground car park, a multi-storey structure, a dense CBD canyon between tall buildings — anywhere GPS degrades or drops that isn't a mapped toll road.
- A genuinely long blackout where the vehicle changes speed mid-tunnel (the corridor match only knows the entry/exit points and the road's real shape — it can't tell if the vehicle sat in queued traffic for two minutes of that gap).
- Any future tunnel/structure not yet in the toll registry.

The current, correct, conservative default for all of those cases is: **bill nothing for the gap.** That's the right call given the alternative (dead-reckoning from stale GPS speed) was explicitly considered and rejected during the `KnownCorridor.kt` design pass, for good reason — it reopens exactly the "fabricated distance" bug class the fare engine was fixed to close (F2 in the Android audit). But "bill nothing" is also not what you're asking for, and it's not what the regulation asks for either — see below.

## What MTI, SmartMove, and Digitax-class hard meters actually do

I researched this rather than guessing, and it's worth being honest about what's verified public information versus inference:

- **Traditional NSW/AU taxi meters (the ones companies like Digitax, iElectron, Pulsar build) never use GPS to calculate the fare at all.** They wire directly into the vehicle's real Vehicle Speed Sensor (VSS) — the same pulse signal the speedometer reads, proportional to actual wheel rotation. Modern certified units (e.g. Digitax's F4 Slim/F4 Plus, MID/OIML R21/E-Mark certified) use "vehicle impulses **and** GPS validation" — the vehicle pulse is the *primary, authoritative* distance source; GPS is a secondary cross-check used to catch sensor tampering, not the other way around. [digitax.com/f4-slim, digitax.com/how-taxi-meters-work]
- **SmartMove's own installation guide and marketing confirm their in-built soft meter is GPS-based, exactly like this app** ("uses the car's GPS position to calculate the distance travelled"). Their actual answer to tunnel accuracy is that SmartMove Go **can interface with most external hard meters** instead of using the soft meter — i.e. for tunnel-heavy operators, they hand the whole fare calculation over to a genuine wired hardware meter (their installation guide references "Digitax4G" by name) and the tablet just displays/records what that meter says. [smartmovesystems.com.au, SmartMove Installation Guide PDF]
- **MTI's own marketing lists an "Integrated Taximeter" as a separate hardware product** alongside their tablet/dispatch software, consistent with the same pattern — a real hardware meter doing the actual fare calculation, GPS/tablet doing dispatch and display. [mtidispatch.com/our-products/in-vehicle-hardware]

**The honest takeaway: competitors don't out-clever GPS blackout with a better algorithm. They sidestep it by getting the authoritative distance signal off the vehicle itself, via wired VSS access (traditional hard meters) or, increasingly, via the OBD-II port** — every car sold in Australia since 2006 (petrol) / 2007 (diesel) has one, and the same VSS pulse is exposed there as PID `0x0D` (vehicle speed), readable by any commodity Bluetooth ELM327-class OBD-II adapter (~$15-40 AUD, no mechanic required, plugs into the same port a Bunnings-bought diagnostic scanner uses). [taxidepot.com/obd-speed-sensors]

## What NSW law actually requires (so this isn't "add hardware because competitors have it")

I could not fetch the Point to Point Transport Commissioner's fact sheets directly (bot-blocked), but indexed search results confirm the substance: the **Point to Point Transport (Taxis and Hire Vehicles) Regulation 2017** deliberately moved *away* from prescriptive hardware requirements (no more mandatory lead-sealing) toward a technology-neutral, outcome-based standard. The actual legal requirement is that the fare calculation device must, among other things, **"at all times accurately calculate the fare according to the authorised fare (NSW Fares Order)."** [pointtopoint.nsw.gov.au fact sheet, indexed]

That's the real reason this matters, stated plainly: **a GPS blackout that bills the wrong amount — whether zero or a guess — is a live violation of "at all times accurately calculate the fare," not just a UX gap.** The current "bill nothing" default is defensible as the least-wrong option available with GPS alone, but it is still wrong money the moment a real fare should have accrued during that gap. A real, physical vehicle-speed signal is the only way to make "at all times accurate" actually true through a blackout, without reopening the fabrication risk `KnownCorridor.kt` was built to avoid.

## Recommended design

**A Bluetooth LE OBD-II vehicle-speed source, layered as a third, optional tier beneath the two that already exist — never replacing GPS, only covering the gap GPS itself can't see.**

```
Tier 1 (primary, unchanged):  GPS fix age < MAX_FIX_AGE_MS (5s)
                               → bill from real GPS-fused distance, as today.

Tier 2 (new):                 GPS stale AND a paired OBD-II adapter is
                               connected and reporting PID 0x0D
                               → bill from LIVE vehicle-speed-sensor
                                 integration (∫v dt over the gap, using the
                                 REAL, continuously-updating current speed —
                                 not a frozen last-known-GPS-speed guess).

Tier 3 (existing, unchanged): GPS stale, no OBD-II adapter paired
                               → today's behaviour exactly: known-corridor
                                 catch-up on reacquisition (bill the real
                                 mapped-road distance if the gap matches a
                                 known tunnel), else bill nothing for the gap.
```

**Why Tier 2 is not the dead-reckoning approach `KnownCorridor.kt` already rejected.** The rejected approach was extrapolating distance from the vehicle's *last known GPS speed*, frozen — a guess that a vehicle which slowed or stopped mid-tunnel kept going. Tier 2 is fundamentally different in kind: it's a **live, continuously-updating reading of the vehicle's actual current speed**, sourced from the same physical measurement every certified hard meter in the country already trusts as authoritative. It's not a guess extrapolated forward from stale data; it's a real-time sensor, exactly as legitimate a distance signal as GPS itself — arguably more so, since it doesn't suffer GPS's own failure mode (signal loss) at all.

### Where this plugs into the existing architecture

The Android audit's hardware survey (see the companion audit doc, Part 3) found the single most important fact for this design: **a provider-agnostic `SpeedSource` interface already exists**, implemented today by `RealLocationProvider` (GPS) and a `StubSpeedSource`/`SwitchableSpeedSource` used for testing/simulation, wired through `AppContainer.kt`. This is exactly the seam a new `ObdSpeedSource` implementation needs — the fare engine doesn't need to know or care where its speed reading came from, it already consumes an abstraction. That materially de-risks this feature: it's new plumbing (BLE permissions, adapter pairing UI, PID-0D polling, the staleness/fallback logic above), not a fare-engine rewrite.

Concretely:
1. **`domain/hardware/ObdSpeedSource.kt`** (new) — implements `SpeedSource`; on construction, scans/connects to a paired BLE OBD-II adapter (standard ELM327 AT-command handshake), polls PID `0x0D` at ~1Hz, publishes the same `LocationFix`-shaped reading (speed + `receivedAtNanos`) the GPS source already does — so it plugs into `FareEngine.tick()`'s existing `MAX_FIX_AGE_MS` staleness logic with zero changes to that logic.
2. **`FareEngine.kt`'s blackout branch** (the code around the existing `knownCorridorDistanceLookup` call) gets a new precondition check: before falling through to the known-corridor lookup, ask whether a live, non-stale reading is available from a paired `ObdSpeedSource`; if so, integrate real distance from it instead.
3. **Pairing UI** — a new item under Settings ▸ Diagnostics (which already lists "GPS quality, network, printer pairing" per the product spec) for OBD-II adapter pairing, following the same pattern the (currently unimplemented) Bluetooth printer pairing was scaffolded for.
4. **Anti-tamper / evidence trail** — treat an OBD-II-sourced distance segment the same way `Trip.gps_blackout_events` (shipped this session, backend) already treats a known-corridor segment: record it as its own audit-trail entry (`source: "obd"`, distance, duration) so a disputed fare has the same on-file evidence a known-corridor match gets. A live OBD speed reading wildly inconsistent with the last GPS speed before blackout onset (e.g. implies the vehicle teleported, or a disconnected/spoofed dongle reporting garbage) should flag for review rather than bill silently — same "corroboration, not blind trust" instinct the existing toll-gantry confirmation logic already uses.
5. **Backend mirror** — `known_corridor_distance_km`'s server-side counterpart (just shipped) assumes the device's own recorded `gps_trace` is the only signal; if a trip closes with OBD-sourced segments, the sync payload needs an extra field carrying those segments' distance so `recompute_from_trace`'s independent recompute doesn't wrongly treat that stretch as an unmatched blackout and zero it out, disagreeing with the device's own (correct) total. This is a real, scoped addition to the sync schema, not a redesign.

### Rollout — optional, not a hardware mandate

This should ship as **opt-in hardware**, not a requirement, for three reasons: it keeps faith with the product's own stated competitive positioning ("no hardware contracts" vs. MTI's hardware-tied model — see `docs/TCT-METER-01-spec.md` Part A4); a $20-40 Bluetooth dongle a driver can self-install in minutes is a genuinely different (and better) cost/friction story than a professionally-wired legacy hard meter, so it doesn't actually undercut that positioning the way a mandatory hardware requirement would; and most trips never go near a GPS blackout at all, so forcing the dependency on every vehicle buys accuracy nobody needs for 95% of driving.

Practical targeting: drivers/fleets who work the CBD/inner-west corridor (where the mapped tunnels already live, and where unmapped underground structures are common) are the ones who benefit immediately and should be offered the dongle first.

### Do this alongside, not after: two directly adjacent gaps

1. **N1 (Android audit) — the GPS-simulator admin-PIN gate is built but switched off** (`SettingsViewModel.kt:131`, `SIMULATOR_REQUIRES_ADMIN_PIN = false`). This is the mirror-image fraud risk to GPS blackout: instead of losing real fare to a signal gap, a driver can *fabricate* a fare with fake GPS today, no PIN required. Flip this constant before hardware-fallback ships to a real fleet — fixing "GPS blackout loses money" while leaving "fake GPS fabricates money" open is an inconsistent risk posture on the exact same subsystem.
2. **T1/F3 (backend audit) — the meter's STOPPED state doesn't exist server-side.** The blackout logic (both the existing known-corridor fix and this new OBD tier) currently routes an entire gap's elapsed time into the fare engine's tick, assuming the vehicle was "hired and moving or waiting" the whole time. If a genuine multi-hire payment stop or driver break happens to coincide with a GPS blackout (parked in an underground car park during a break, say), that time should accrue *nothing*, not waiting charge. Design the wire-format addition for OBD-segment reporting (point 5, above) and the `hired`/`paused` telemetry signal (T1/F3's own recommended fix) together — they touch the same code paths (`recompute_from_trace`, `TelemetryPoint`/`TripSyncItem` schema) and should land in one pass rather than two.

### What this explicitly does NOT do

- Does not touch GPS as the primary signal for 95%+ of every trip — this is additive.
- Does not attempt phone-accelerometer/gyroscope dead reckoning — already correctly rejected, stays rejected.
- Does not require NMI/trade-measurement hardware certification — the 2017 NSW regulation's technology-neutral standard doesn't require it, and self-certification (already this product's model per the TCT-METER-01 spec's cl.14 compliance pack) covers a well-evidenced software+hardware-signal design the same way it already covers the pure-GPS one.
- Does not become the single point of failure — GPS stays primary; OBD is a fallback for the gap only; known-corridor catch-up stays as the final fallback when no dongle is paired.

---

# PART B — Prioritized program plan (everything else the audit found)

Grouped by theme, most urgent first. Finding IDs reference the companion audit doc. Where a workstream spans backend+dashboard+Android, that's called out — several of the biggest issues (the two IDOR findings, the voucher race) are single-endpoint backend fixes with no client-side work needed at all.

## Wave 5.1 — Money and compliance blockers (do first)

These are all either wrong money moving today, or a live regulatory-compliance gap. None require design discussion — every one has a concrete, scoped fix already spelled out in the audit.

1. **Voucher double-spend** (`P1`/`M6`, backend) — row-lock or conditional-update the redemption check. Found independently by two separate audit passes; highest-confidence finding in the whole report.
2. **Non-cash surcharge cap has no hard ceiling** (`F1`, backend) — a tariff edit can currently push card surcharges past the legal 5% cap.
3. **Airport Fixed Fare has no server-side geofence check** (`T2`, backend) — any client can currently claim the flat $60/$80 fare on any trip.
4. **Airport Fixed Fare surcharge absorption** (`F4`, backend) — confirm against the trial's actual terms; likely needs to be additive, not absorbed.
5. **Meter STOPPED state doesn't exist** (`T1/F3`, backend — design together with the hardware-fallback wire changes above) — multi-hire pauses/driver breaks currently bill as waiting time.
6. **Negative money fields accepted by schema** (`F2`/`A1`, backend) — `surcharge_pct`, `cleaning_fee`, `tolls`, `extras`, sync `device_total` all need `ge=0`.
7. **Offline-synced trips trust client toll figures with no detection** (`M1`, backend) — replay tolls through `apply_toll_detection` in `recompute_from_trace`.
8. **Dashboard: closed trips editable without fare recompute** (`TRP-1`) — gate fare-affecting fields on `status`, both sides.
9. **Dashboard: reconciled shifts permanently deletable** (`SFT-1`) — mirror the trip-delete 409 rule.
10. **Dashboard: 9 of 13 real toll roads have no working repricing UI** (`TAR-1`) — add the missing schema fields + per-point endpoint.
11. **PSL accrual is entirely manual, no auto-topup** (`M9`, backend+dashboard `PSL-3`) — wire automatic ledger accrual on trip close; build the auto-topup threshold the spec calls for.

## Wave 5.2 — Security blockers

12. **Duress driver-to-driver IDOR** (`S1`, backend) — any driver can view/silence/eavesdrop on another driver's panic event.
13. **Messages driver-to-driver IDOR** (`A1`, security audit) — same pattern, different feature; the fix already exists in the WS sibling handler, just needs porting to REST.
14. **Duress escalation cascade has no scheduler** (`M10`, backend) — a real panic event can sit open forever if nobody has the dashboard open. This is a life-safety gap; treat as equal priority to the two IDORs above.
15. **Duress escalation race can drop/double-fire a cascade stage** (`D1`, backend) — including potentially double-firing a real 000 call. Lock the escalation read-modify-write.
16. **Stripe webhook: no idempotency, fails open with no signing secret** (`M7`/`M8`, backend).
17. **Billing read endpoints have no role gate at all** (`A2`, security audit).
18. **`GET /v1/users` has no role gate** (`S2`, backend) — any driver can read every coworker's PII.
19. **Live Map: Publish Position is gated client-side only** (`LM-5`) — any driver token can spoof another vehicle's position server-side.
20. **N1 — flip the GPS-simulator PIN gate on** before any real-fleet rollout (do this alongside Part A above, not separately).
21. **Fleet list panels have zero client-side role gating** (`FLT-001`) and **Vehicle delete tooltip states a rule the backend doesn't enforce** (`VEH-001`) — UX-only (server is correct in both), but actively misleading.
22. **Audit-log "Verify chain" gate the sidebar comment claims exists doesn't** (`AUD-1`).

## Wave 5.3 — Data integrity and concurrency

23. Missing FK constraints now that all domains share one `Base.metadata` (`D2`).
24. No row lock on `Trip` during concurrent `/tick` batches (`T3/M4`) — lost-update risk on a flaky mobile network retry.
25. Shift/vehicle double-booking is a TOCTOU race with no DB constraint (`T4`) — the fix pattern already exists elsewhere in this codebase for the identical shape of bug.
26. Missing index on `Trip.start_at`/`Shift.start_at` (`D3`) — full-scan hot path for every report.
27. Tariff overlap has no DB-level protection (`F5`).
28. Directional toll gantries: prefer recorded `direction` over bearing-classification when available (`M2`).

## Wave 5.4 — Dashboard hardening (everything-screen sweep)

29. The recurring "silent cap, no pagination" pattern — invoices (`BIL-1`), vehicle trips/tolls (`VEH-002`), PSL ledger (`PSL-2`), message driver picker (`MSG-4`), tariff change log (`TAR-2`) — fix once as a shared pattern (server pagination or a visible "showing N of TOTAL"), apply everywhere it recurs.
30. Two live-feed hooks missing the reconnect/backoff pattern `useLiveMap.ts` already has: Messages (`MSG-1`) and Duress GPS (`DUR-2`).
31. Duress Desk feature gaps against the original spec: device-health visibility (`DUR-3`), device-originated audio playback (`DUR-4`), branded PDF incident export (`DUR-5`) — the most safety-critical dashboard module, also the least tested (19/20 files).
32. Dispatch: a job can be created with no real coordinates (`DSP-1`), and backend validation errors are swallowed (`DSP-2`) — both on the one code path with zero test coverage of the real (non-fallback) case.
33. Dashboard `Trip` TS type missing `tip_amount`/`negotiated_total` (`C1`, security audit) — silently hides real money on every trip detail/reconciliation view; fix alongside the negotiated-fare plausibility check (`P2`) since they compound each other.
34. Sweep the remaining minors in one low-risk pass: form-label style inconsistency (`GEN-1`), Zones' pre-copy-pass typography (`ZON-1`), several stale comments (`FLT-005/005b/005c/006`), dead exports (`MSG-8/9`, `DUR-9`, `AUD-3/4`, `PSL-1`).

## Wave 5.5 — Android cleanup

35. Delete the two orphaned zones screens (`N2`).
36. Add tests for `TariffSignatureVerifier`/`TariffCanonicalPayload`/`TariffCache` — the last genuine blocker-severity test gap remaining from the original audit.
37. `LivePositionHeartbeat`'s flat 5s cadence with no screen-off backoff (`N5`) — still the largest unaddressed network-cost item, three audits running now.
38. Sync the per-road toll audit trail to the server for dispute evidence (`T5`/`N4`) — make it an explicit product decision either way, not an implicit gap.
39. Clean up the `tenantId=""` footgun in the offline shift-start synthetic DTO (`N3`).

## Wave 5.6 — Code health (do opportunistically, not as a blocking pass)

40. Split the two backend god-files: `app/api/v1/fleet.py` (1,136 lines, four unrelated surfaces) and `app/services/trips.py` (1,019 lines) — `C1`/`C2`.
41. Extract shared `geo.py` for the duplicated haversine/distance math between `trips.py` and `tolls.py`.
42. Add the missing test coverage the dashboard audit's module-by-module table calls out — highest value first: Trips/Shifts money-handling modals, then PSL/Vouchers (zero tests, real money), then Fleet list panels (zero tests, exactly where the role-gating gaps were found), then Duress Desk's incident state machine.

---

## Suggested sequencing

Waves 5.1 and 5.2 are both genuinely urgent (money and safety respectively) and mostly independent of each other — they can run in parallel as two workstreams. Part A (hardware fallback) is a bigger, more architecturally interesting piece of work than any single item in 5.1/5.2, but nothing in it blocks or is blocked by them except the two explicit interaction points called out above (N1, T1/F3) — start Part A whenever there's a driver/engineer available for it, in parallel with the fix waves, not after them.

Everything in 5.3-5.6 is real but none of it is a blocker in the "wrong money or lost data right now" sense — sequence it behind 5.1/5.2/Part A, and treat 5.6 (code health) as background work folded into whichever wave happens to touch that file next, not its own dedicated pass.
</content>
