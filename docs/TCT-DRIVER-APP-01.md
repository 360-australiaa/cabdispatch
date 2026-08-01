# The Captain Taxis — Driver App (Android)
## Design & Product Handover for Kotlin Build
**Doc ID:** TCT-DRIVER-APP-01 · **Version:** 1.0 · **Date:** 1 Aug 2026
**Companion doc:** `TCT-METER-BUILD-SPEC.md` (backend, fare engine detail, NSW compliance) — this doc covers the driver-facing app specifically; read both.
**Reference prototypes:** `driver-dashboard-full-prototype.html` (working wheel + payment flow), Figma file `Captain Taxis — Meter Design System` (jsP6ZNpvHknAKXR1Grm4ME)
---
## 1. Core concept
A driver-facing tablet app built around **one physical metaphor: a rotating wheel**, mounted right side of screen (RHD-correct), that both selects menu content and doubles as the Off Duty/Available state toggle. A **live map is the permanent background** of the whole app — nothing is ever a blank screen, there's always spatial context. A dedicated **Start Meter button, bottom-center**, is deliberately separate from the wheel because it must be visible to the passenger, not just reachable by the driver.
## 2. Ergonomics — non-negotiable constraints
- **Vehicle is RHD (driver seated right).** The driver's left hand naturally falls toward the **right side** of a center-mounted tablet — this is why the wheel lives on the right, not the left. (This was corrected mid-project after an initial wrong assumption — worth remembering when reviewing any legacy asset that shows the wheel on the left.)
- **Thumb-zone tiers:** primary (constant, one-handed reach) → secondary (occasional, small stretch) → glance-only (read, never touched while driving).
- **During an active/Hired trip, all navigation disappears.** No wheel, no menu — only the fare screen and the hidden duress gesture are live. This is a safety rule, not a style choice.
- **Start Meter is the one exception to "right-side thumb zone."** It sits bottom-center specifically because the passenger needs to see it too — a deliberate trade-off of driver-reach convenience for passenger trust/visibility.
## 3. The Wheel — full mechanical spec
- **6 fixed slots, 60° apart:** Off Duty/Available · Available Trips · Messages · Trips · Earnings · Shift
- **Free rotation from anywhere on the wheel** (drag any point on the ring, not just a slot) — this was an explicit correction; an earlier version only responded to tapping a slot directly, which was wrong
- **Click-stop / detent snap on release** — never leaves the wheel at an arbitrary angle. Not free-spinning after release; this is a physics/precision decision for a moving vehicle, not just aesthetic
- **Selector is fixed; content rotates under it.** The highlighted/active position does not move on screen — the ring of items rotates underneath it. (In the Figma static states, the selector sits at the top of the ring; in the HTML prototype it's effectively the top position too — keep the fixed-selector, rotating-content model regardless of exact angle chosen in final art.)
- **Snap easing:** `cubic-bezier(.34, 1.56, .64, 1)` (a "back-out" curve with slight overshoot bounce) — in Android, approximate with a custom `PathInterpolator(0.34f, 1.56f, 0.64f, 1f)` or `OvershootInterpolator(1.2f–1.5f)` if PathInterpolator control points aren't convenient
- **Snap duration:** ~450–500ms
- **Speed lock:** wheel becomes non-interactive (greyed, no drag response) when vehicle speed ≥ 26 km/h, sourced from the same GPS feed as the fare engine's tariff-switching threshold. This is a real, marketable safety feature, not cosmetic.
- **Selected slot visual state:** larger (60dp vs 44dp), gold ring highlight, glow (`box-shadow`/elevation equivalent), bolder label text
- **Radius geometry used in prototypes (1280×800 canvas, scale proportionally):** icon ring radius ≈140px from wheel center, label radius ≈185–190px, outer rim ≈176–180px. Wheel center positioned so its reach stays within the primary thumb-zone arc from the bottom-right pivot.
## 4. Left content pane — must show real content, not summaries
Each wheel slot loads **actual detail content** on the left when selected — a title + one-liner is not sufficient (this was flagged and fixed once already). Minimum content per slot:
- **Off Duty/Available:** status message only (this one *is* just a short status, not a list)
- **Available Trips:** list of job cards — route, distance, estimated fare, time since requested
- **Messages:** list of dispatch messages — sender, preview text, timestamp
- **Trips:** trip history rows — route, time, payment method, fare amount
- **Earnings:** stat grid — today's total, card split, cash split, trip count
- **Shift:** stat grid — hours on shift, trip count, cash to reconcile, card settled, + Submit Shift action
Content swap should be fast (~130–150ms fade) and happen only after the wheel snap settles (~420–450ms after release), not during the drag itself.
## 5. Dashboard chrome (always present, outside the wheel/content)
- **Top status strip:** GPS, 4G/network, Printer, Battery — each a colored dot (green=ok, amber=warning) + label. Time/date left-aligned.
- **Identity card, top-left:** avatar/photo, name, driver ID, vehicle rego — tappable, opens **Profile** (Compliance + Settings, demoted off the wheel since they're low-frequency)
- **Quick stats, top-right:** today's trips / hours / earnings — read-only, glance zone
- **Map background:** persistent, full-bleed behind everything; driver's live position marker
## 6. Start Meter → Active Trip flow
1. Bottom-center gold button, always visible when Off Duty/Available (hidden once Hired)
2. Tap → transition **originates visually from the button's own position** (scale+fade from that point, not a generic slide) — this was a specific "out of the box" request, not a default pattern
3. Fare ramps from $0.00 up to the flag fall amount, then a green **"METER STARTED"** confirmation banner shows briefly (~2s) and fades
4. Live accrual begins — alternates between **Distance mode** (≥26 km/h) and **Waiting mode** (<26 km/h), each with its own color-coded pill indicator, matching the fare engine's tariff-switching logic (see `TCT-METER-BUILD-SPEC.md` Part A3/C4 — this is NOT additive time+distance, it's one or the other at any instant)
5. **Fare digits are styled as a classic LED taxi meter: red, monospace/segmented.** This project intentionally does NOT use the app's normal type scale here — it's a deliberate callback to physical taxi meters for passenger legibility and trust. Use a true 7-segment font if available (DSEG7, Digital-7); fall back to a bold monospace in red with a subtle glow/text-shadow if not.
6. **PAUSE** freezes accrual (frozen fare shown dimmer red); **STOP METER** ends the trip and moves to payment
7. Toll quick-add chips available during the trip (secondary zone, left side — tolls are added occasionally, not primary-frequency)
8. **Hidden duress gesture** (triple-tap top-right corner) is active throughout — never shown as a visible control, only documented/annotated for the design team
## 7. Payment flow (after Stop Meter)
1. **Method picker:** total fare + breakdown at top, then TAP TO PAY (primary, gold), CASH, PAYMENT LINK, CABCHARGE/TTSS (secondary buttons)
2. Selecting a method → brief **processing state** (spinner + "Processing [method]…")
3. **Receipt screen:** green checkmark, "Payment received," total, method used, breakdown including GST line, EMAIL RECEIPT / SMS RECEIPT actions, DONE button
4. DONE resets the whole session — fare back to $0.00, wheel back to position 0, dashboard back to Off Duty
**Note on Tap to Pay specifically:** that screen is the Stripe Terminal SDK's own UI, not ours to design — don't build a custom card-entry screen, just the transition into and out of the SDK call.
## 8. Full screen inventory — 30 total
| # | Group | Screens | Status |
|---|---|---|---|
| 1–5 | System | Splash, PIN login, vehicle QR pairing, pre-shift inspection, shift start | **Not yet designed** |
| 6 | Dashboard | Map + wheel + chrome (persistent) | ✅ Figma + HTML |
| 7–10 | Trip lifecycle | Available, Hired/active fare, Paused, Multiple-hire | ✅ Figma + HTML (Hired/dashboard in HTML; Paused/Multi in Figma only) |
| 11–12 | Available Trips | Job queue list, offer accept/decline | ✅ Figma only — not yet merged into the HTML prototype |
| 13–14 | Messages | Thread list (as wheel content), message detail/quick-reply | List ✅ HTML; detail **not designed** |
| 15–16 | Trips | History list (as wheel content), trip detail | List ✅ HTML; detail **not designed** |
| 17 | Earnings | Summary (as wheel content) | ✅ HTML |
| 18–19 | Shift | Reconciliation (as wheel content), submit confirmation | Summary ✅ HTML; submit confirmation **not designed** |
| 20–21 | Profile | Compliance vault, Settings | **Not yet designed** |
| 22–27 | Payment flow | Fare summary, Tap to Pay (SDK), payment link, cash calculator, CabCharge/TTSS entry, receipt | Method picker + receipt ✅ HTML; cash calculator + CabCharge/TTSS entry form **not designed** |
| 28–30 | Contextual overlays | Navigate, Duress triggered, Duress active | **Not yet designed** (duress gesture zone annotated only) |
**13 of 30 are built and interactive somewhere (Figma and/or HTML). 17 remain undesigned** — the Android team will either need those designed first or will need to design them inline during the build, matching the existing system.
## 9. Dispatch scope (backend implication, not just UI)
Full parity was chosen over meter-only: **live map, job offers (Available Trips), messages, and navigation are all in scope for v1**, matched via an in-house broadcast/queue engine (eCabs-style), not manual dispatch or a third-party integration. This means the backend needs, beyond the meter API surface already documented in `TCT-METER-BUILD-SPEC.md`:
- Job intake + broadcast/queue matching worker
- Continuous (not just per-trip) driver location streaming
- A messaging service (dispatch↔driver threads)
- New tables: `jobs`, `job_offers`, `driver_locations` (live), `messages`, `driver_status`
## 10. Design tokens (source of truth values)
```
Background (dark):     #0D0A18
Surface:                #1C1730
Surface raised:         #2A1C58
Surface sunken:          #3A2774
Text primary:            #FFFFFF
Text secondary:          #9F94C9
Text muted:              #7C7594
Border:                  rgba(255,255,255,0.08)
Border strong:           rgba(255,255,255,0.18)
Gold (primary accent):   #F4C300
Gold soft:                #F8DA66
Available (green):       #2E9E4F
Waiting (amber):          #E0932B
Duress (red):             #D8352E
Meter LED red:             #FF2A28   ← distinct from duress red, do not reuse
```
Full primitive/semantic token set (27 primitives + 15 semantic colors × Light/Dark modes, plus Spacing/Radius/Layout collections) lives in the Figma file's Variables panel — export from there for a complete token JSON rather than retyping from this doc.
**Light mode exists in the token system but has not been the design focus** — almost all screens above were built and reviewed in Dark mode per explicit preference. Confirm before assuming Light mode is production-ready.
## 11. Known gaps / honest caveats for the Android team
- The "red LED" fare digits have only been approximated with monospace+color in both Figma and HTML — nobody has confirmed a true 7-segment font choice yet
- Wheel snap/drag logic has a **working JS reference implementation** in the HTML prototype but has **not been ported to Kotlin/Compose** — treat the JS as the spec, not as reusable code
- The live accrual shown in the HTML prototype is a **visual simulation only** (random mode-switching for demo purposes) — the real tariff-switching logic must come from actual GPS speed, per the fare engine spec
- Two rendering bugs were caught and fixed during the Figma build (auto-layout height collapsing to 10px in nested frames) — not an Android concern, but indicates the source files were hand-verified, not just generated blind
