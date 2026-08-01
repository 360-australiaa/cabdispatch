# Cab Dispatch — Digital Taxi Meter (SaaS)
## Build Specification & Claude Code Handover Document
**Doc ID:** TCT-METER-01 · **Version:** 1.0 · **Date:** 31 July 2026
**Owner:** Arsalan, 360-Australia Pty Ltd
---

# PART A — RESEARCH FINDINGS (read before building)

## A1. Regulatory position (NSW) — this shapes the whole product

**There is NO meter type-approval regime in NSW.** The old "Commissioner-approved,
lead-sealed meter" era is over. Clause 14 of the Point to Point Transport (Taxis and
Hire Vehicles) Regulation 2017 sets performance-based safety standards instead:

- Display the fare, including any additional fees, charges or **tolls**, in numerals,
  in Australian dollars
- Be **capable of accurately calculating the fare at all times**
- Be **calibrated** so it determines the fare in accordance with the authorised fares
- Be **resistant to tampering and vandalism** and in working order
- Be **securely fixed** to the taxi OR secured in a commercially designed mounting,
  installed so it cannot cause injury under severe acceleration/deceleration
- Be **visible to all passengers** (cl. 14(22) — actively enforced on-street)

**Consequence:** an Android tablet soft-meter is fully legal for NSW taxis provided
it meets cl. 14. The TSP (Cab Dispatch) self-certifies compliance under its
Safety Management System. Remove "submit meter for type approval testing" from the
roadmap; replace with an internal **Meter Compliance Dossier** (accuracy test
evidence, calibration procedure, tamper-resistance measures, mounting spec) filed
under the SMS — same pattern as TCT-SMS-DA-01.

**Related mandatory equipment for Sydney metro rank & hail taxis:**
- Approved **vehicle tracking device** + **duress alarm** (Metropolitan, Newcastle,
  Wollongong transport districts + Central Coast LGA) — per TfNSW Gazette orders
- Approved **security camera system** + surveillance signage (rank & hail)
- Fare schedule displayed inside the taxi, visible to passengers, and on the TSP
  website
- Driver must start the meter immediately on hiring (WAT: only after wheelchair
  secured); driver must stop the meter during multiple-hire payment stops
- Meter must be used and display the correct fare for ALL rank & hail trips

**Booked fares are NOT regulated** (except TTSS trips, which must respect the
maximum fares order). Rank/hail fares are capped by the Fares Order. This split
drives the tariff engine design (A2, C4).

## A2. Current NSW maximum fares — Fares Order 2025 (no.2), effective 3 Nov 2025

These are the DEFAULT tariff values to ship. All amounts GST-INCLUSIVE.

| Component | Urban | Country |
|---|---|---|
| Hiring Charge (flag fall) | $5.00 | $5.11 |
| Peak Time Hiring Charge¹ | $2.56 | — |
| Distance Rate (≥26 km/h) | $2.52/km first 12 km, then $2.29/km | $2.41/km first 12 km, then $3.30/km |
| Night Distance Rate² | $3.00/km first 12 km, then $2.73/km | $2.87/km first 12 km, then $3.93/km |
| Holiday Distance Rate³ | — | $2.87/km first 12 km, then $3.93/km |
| Waiting Time (<26 km/h) | 109.2 c/min ($65.52/hr) | 104.5 c/min ($62.67/hr) |

¹ Added to flag fall for hirings commencing 10pm–6am on Fri, Sat, or night before a
public holiday (Urban only).
² Journeys commencing 10pm–6am, any night (= Distance Rate + 20%).
³ Journeys commencing 6am–10pm on Sundays/public holidays (Country only).

**Other regulated amounts:**
- **Maxi-cab:** up to 150% of fare when 5+ passengers, or when a maxi is requested
  at a Sydney Airport rank (except wheelchair service)
- **Multiple hiring:** 75% of the metered fare demanded from EACH hirer
- **Cleaning fee:** max $120.00 + GST
- **Passenger Service Levy:** $1.32 incl GST, may be added to the fare
- **Non-cash payment surcharge:** capped at **5% incl GST** of amount payable;
  rounding: <0.5c rounds down, ≥0.5c rounds up. This cap covers ALL card-related
  charges combined.
- **Tolls:** passed through at cost. Note: northbound Sydney Harbour Bridge/Tunnel
  has no toll; return tolls only chargeable if actually incurred on the journey.
- **Sydney Airport Fixed Fare Trial:** non-booked trips from Sydney Airport Precinct
  to the defined CBD zone: max **$60** standard / **$80** maxi (5+ pax or rank
  request). NO other additions except non-cash surcharge and cleaning fee — no PSL,
  no tolls, no peak charge.

**Urban Area =** Metropolitan, Newcastle, Wollongong transport districts + Blue
Mountains, Central Coast, Shellharbour LGAs + listed townships (Camden, Picton,
Maitland etc.). **Exempt Areas** (Moama, Barham, Tocumwal, Mulwala, Barooga,
Deniliquin) have no fare caps but the 5% surcharge cap still applies.

Fares Orders change roughly annually (IPART review → TfNSW order). The tariff
engine MUST be server-configurable with effective-dated tariff versions — never
hard-code rates.

## A3. CRITICAL fare engine corrections vs. the original spec

The original spec's formula (`BASE + km×rate + min×rate + extras, then +10% GST`)
is **non-compliant** for NSW rank & hail. Two fixes:

1. **Time-OR-distance switching, not time-AND-distance.** NSW meters charge the
   Distance Rate when speed ≥ 26 km/h and Waiting Time when speed < 26 km/h. At any
   instant, exactly ONE of the two accrues. (This is the standard AU "tariff
   switching" meter model.)

2. **GST is inclusive, not additive.** The Fares Order maximums already include
   GST. The meter must never add 10% on top. For BAS/receipts, extract GST as
   `fare ÷ 11` and show it as "includes GST of $X.XX".

Corrected engine (see C4 for full spec):

```
accrual tick (every GPS fix, ~1s):
  if speed >= 26 km/h:  fare += distance_delta_km × distance_rate(current_km_band, time_class)
  else:                 fare += elapsed_min × waiting_rate

FINAL = flag_fall (+ peak_charge if applicable)
      + accrued_distance_and_time
      + tolls + PSL (if applied) + extras
CARD  = FINAL × (1 + surcharge%)   where surcharge ≤ 5%, rounded half-up to cent
GST_shown = FINAL ÷ 11
```

Constraint checks at trip close: total must not exceed the maximum computed from
the Fares Order schedule for the same trip profile (rank/hail only); surcharge must
not exceed 5%; Airport Fixed Fare trips must equal exactly $60/$80.

## A4. Competitor intelligence

### MTI (Mobile Technologies International — ex-MTData taxi division)
- **Position:** largest dispatch/fleet platform in AU/NZ; powers 13cabs, Black &
  White Cabs, Suburban Taxis, Swan Taxis; deployed in 141+ cities globally. Owned
  by A2B/Cabcharge group (acquired 2018), now under ComfortDelGro.
- **Product:** cloud or self-hosted Smart Dispatch; in-cab driver screen/tablet;
  **MTI Integrated Taximeter** with in-built fare selection and **speech
  announcements** of fares (accessibility + transparency); Snapshot camera
  (continuous interior/exterior + audio recording); smartphone/tablet driver app
  with **soft-meter**; ODI (Open Data Interface) API; call centre + IVR; booking
  apps; payments in-vehicle and in-app.
- **Weakness to exploit:** hardware-and-ecosystem sale, tied to the
  Cabcharge/13cabs empire; independent operators dislike feeding data to a
  competitor's parent. No per-vehicle SaaS pricing. Legacy hardware heritage.

### SmartMove (SmartMove Systems, AU)
- **Position:** 100% Australian; 120+ fleets AU/NZ; NSW Taxi Council strategic
  partner; strong with regional & small-medium fleets; 24/7 support; pioneers of
  mobile-phone dispatching (company history back to 1986).
- **Product:** SmartMove GO (modern in-car tablet dispatch, minimal devices);
  **inbuilt soft meter** OR interfaces to most external hard meters to auto-record
  fares at job end; **SmartMove Track** — duress/panic monitoring where base can
  **listen in to the vehicle** during an alarm and then talk to the driver, plus
  job counting for **levy reporting**; automated **Passenger Service Levy
  collection** (debits driver's card, threshold auto-top-up, credit management
  reports, pays levy to the authority on the driver's behalf); Square payments
  integration; SmartHail branded passenger apps + web booker; SmartVoix phone
  system; full fleet reporting at no extra cost, scaled pricing by fleet size.
- **Weakness to exploit:** dated UI; small-fleet focus; not API-first; no
  white-label meter-as-a-product; no built-in Tap to Pay (relies on Square/external).

### eCabs Technologies (Malta — architecture benchmark, not an AU competitor)
- **Position:** €18M platform build; white-label ride-hailing tech for **regulated
  markets**; expanding across Europe via "City Partners".
- **Product:** dispatch engine with **broadcast, queue, tariff-led and hybrid
  matching**; live-traffic dispatch with fallback logic; **taximeter integrations
  and regulated fare models**; dynamic pricing engine; corporate/B2B module —
  dedicated accounts, custom per-account pricing, consolidated invoicing, ERP/
  accounting integration, commission handling for hotels/intermediaries; medical
  transport billing with digital signatures; open APIs, full data ownership,
  Power BI reporting; driver app / passenger app / management portal / dispatch
  portal / fleet portal as separate products.
- **What to copy:** API-first modular portal architecture; corporate accounts as
  the predictable-revenue engine; white-label as a first-class concept (→ Lilly
  Cabs); "regulatory depth as a feature" positioning.

### Adjacent (aware, not targets)
- **Autocab iGo** (UK, in AU; Uber partnership), **iCabbi** (open architecture,
  automation-first), **CabFare Connect** (AU; pay-as-you-go API layer connecting
  any dispatch to any payment — validates the open-integration trend and is a
  potential partner, not a rival).

### Feature parity matrix (updated with research)

| Feature | Cab Dispatch (target) | MTI | SmartMove | eCabs |
|---|---|---|---|---|
| Per-vehicle SaaS pricing | ✅ | ❌ | ⚠️ fleet-scaled | ✅ (B2B) |
| Soft meter (tablet) | ✅ | ✅ | ✅ | via integration |
| NSW cl.14 compliance pack | ✅ shipped as docs | implied | implied | ❌ |
| Duress + listen-in | ✅ (Phase 3) | ✅ | ✅ Track | ❌ |
| PSL automation | ✅ | ⚠️ | ✅ best-in-class | ❌ |
| Tap to Pay on tablet (no reader) | ✅ | ❌ | ❌ | ❌ |
| 5% surcharge cap enforced in code | ✅ | ? | ? | n/a |
| White-label | ✅ (Lilly Cabs) | ❌ | ⚠️ app branding | ✅ |
| Open API | ✅ | ODI | ❌ | ✅ |
| Speech fare announcements | ✅ (parity w/ MTI) | ✅ | ❌ | ❌ |

**Positioning line:** "The only NSW meter you can subscribe to like software —
compliance documents included, card terminal built in, no hardware contracts, and
your data never feeds a competitor's network."

## A5. Payments validation

- **Stripe Terminal — Tap to Pay on Android** is live in Australia: any NFC
  Android device (Android 9+/10+) becomes the card terminal via the Terminal
  Android SDK. Supports Visa, Mastercard, Amex, Google Pay/Apple Pay wallets,
  **eftpos dual-network debit (Australia)** and **PIN entry**. Works on partner
  hardware (Sunmi, Elo) for ruggedised options.
- **Limitation:** Tap to Pay requires an active internet connection — no offline
  card capture. Mitigation: offline trips settle by cash, or generate a Stripe
  payment link/QR that fires when connectivity returns; queue and retry.
- **Stripe Connect** handles the SaaS multi-tenant money flow (fleet = connected
  account; TCT platform fee = application fee).
- **Surcharge compliance:** card price = fare × (1 + s), s ≤ 5% incl GST, rounding
  half-up to the cent, and the surcharge must be disclosed on the fare display and
  receipt. Build the cap as a hard limit in the payment service, not a config
  default.
- Alternatives kept warm: Airwallex (already benchmarked for corporate accounts),
  Square (SmartMove's choice), Mastercard/Cabcharge rails via CabFare-style
  integration later. CabCharge/TTSS acceptance: Phase 4 via manual entry + docket
  capture first; direct integration later.

---

# PART B — PRODUCT ARCHITECTURE

## B1. System overview

```
┌────────────────────────────────────────────────────────────────┐
│  ANDROID METER APP (Kotlin, Jetpack Compose, offline-first)    │
│  Kiosk/lock-task mode · Room DB · Stripe Terminal SDK          │
│  GPS fusion + tariff engine (on-device, authoritative)          │
└───────────────▲───────────────────────────────▲────────────────┘
                │ REST + WebSocket (sync)        │ BLE
┌───────────────┴───────────────┐   ┌───────────┴────────────────┐
│  BACKEND (FastAPI, Python)    │   │  DURESS DEVICE (ESP32-S3)  │
│  Auth · Tenants · Tariffs     │   │  Phase 3 — per TCT-SMS-DA-01│
│  Trips · Shifts · PSL · Duress│   └────────────────────────────┘
│  Billing (Stripe Connect)     │
│  PostgreSQL · Redis · S3      │
└───────────────▲───────────────┘
                │ HTTPS
┌───────────────┴────────────────────────────────────────────────┐
│  FLEET DASHBOARD (React + TS, Vite, Tailwind — TCT brand)      │
│  Live map · Trips · Shifts · Tariff editor · PSL · Duress desk │
│  Compliance vault · Billing · White-label theming              │
└────────────────────────────────────────────────────────────────┘
```

Stack decisions (locked for Claude Code):
- **Backend:** Python 3.12, FastAPI, SQLAlchemy 2, Alembic, PostgreSQL 16, Redis
  (pub/sub for live tracking + duress), S3-compatible storage for receipts/audio,
  JWT auth (driver PIN + device binding), WebSockets for live ops.
- **Android app:** Kotlin, Jetpack Compose, minSdk 29 (Android 10), Room for
  offline store, WorkManager for sync, FusedLocationProvider, Android Enterprise
  lock-task (kiosk), Stripe Terminal SDK, ML Kit / TFLite hotword later (Phase 3).
- **Dashboard:** React 18 + TypeScript + Vite + Tailwind. Brand tokens: indigo
  `#2A1C58`, gold `#F4C300`, lavender `#EFEAF8`, purple `#3A2774`. White-label via
  tenant theme JSON (Lilly Cabs = pink theme swap only).
- **Multi-tenant:** single DB, `tenant_id` on every row, row-level security;
  tenant = fleet; TCT is tenant 0.

## B2. Data model (core tables)

```
tenants(id, name, abn, tsp_number, bsp_number, theme_json, plan, stripe_acct_id)
users(id, tenant_id, role[owner|admin|dispatcher|driver], name, email, phone,
      driver_licence_no, wat_endorsed, pin_hash, status)
vehicles(id, tenant_id, rego, vin, class[standard|premium|maxi|wat],
         camera_serial, tracking_device_id, meter_device_id, status)
devices(id, tenant_id, android_id, model, app_version, vehicle_id,
        kiosk_locked, last_seen_at, battery, network)
tariffs(id, tenant_id NULLABLE→global, name, region[urban|country|exempt],
        effective_from, effective_to, flag_fall, peak_charge,
        dist_rate_1, dist_km_threshold, dist_rate_2,
        night_rate_1, night_rate_2, holiday_rate_1, holiday_rate_2,
        waiting_rate_per_min, speed_threshold_kmh=26,
        maxi_multiplier=1.5, multi_hire_pct=0.75,
        psl_amount=1.32, surcharge_pct_cap=5.0, source[fares_order|custom],
        booked BOOLEAN)  -- booked tariffs are uncapped
extras(id, tariff_id, name, amount, type[fixed|passthrough])  -- tolls, airport
trips(id, tenant_id, vehicle_id, driver_id, shift_id, tariff_id,
      type[rank_hail|booked|airport_fixed|multi_hire], status,
      start_at, end_at, start_lat/lng, end_lat/lng,
      distance_m, moving_s, waiting_s,
      flag_fall, dist_amount, wait_amount, peak_amount, tolls, psl, extras,
      subtotal, surcharge, total, gst_component, payment_method,
      gps_trace_ref(S3), max_fare_check_passed BOOL, receipt_ref)
shifts(id, tenant_id, driver_id, vehicle_id, start_at, end_at,
       inspection_json, trips_count, km_total, cash_total, card_total,
       psl_owed, reconciled BOOL)
payments(id, trip_id, stripe_pi_id, method[tap_to_pay|link|cash|cabcharge|ttss],
         amount, surcharge, status, captured_at)
psl_ledger(id, tenant_id, driver_id, period, trips_count, amount_owed,
           amount_collected, remitted_at)
duress_events(id, tenant_id, vehicle_id, driver_id, trigger[button|gesture|voice|auto],
              opened_at, closed_at, gps_stream_ref, audio_ref, escalation_log_json)
audit_log(id, tenant_id, actor, action, entity, before, after, at)  -- tamper evidence
tariff_change_log — immutable, satisfies cl.14 "calibration" audit trail
```

## B3. API surface (FastAPI, versioned `/v1`)

**Auth & devices**
- `POST /auth/driver/login` (driver_id + PIN + device binding)
- `POST /devices/register` (QR pairing to vehicle), `POST /devices/heartbeat`

**Tariffs**
- `GET /tariffs/active?region=urban&at=…` → effective-dated resolution
- `POST /tariffs` (admin; validates rank/hail tariffs ≤ Fares Order maxima held in
  a global `fares_order` reference tariff; booked tariffs skip the cap)
- `GET /fares-order/current` (global reference, TCT-managed)

**Trips (offline-first: app is source of truth, server validates)**
- `POST /trips` (open), `PATCH /trips/{id}/tick` (batched telemetry),
  `POST /trips/{id}/close` (fare breakdown + GPS trace hash),
  `POST /trips/{id}/sync` (bulk replay after offline period)
- Server-side re-computation: recompute fare from the GPS trace, compare with
  device total, flag >±1% variance for review (tamper detection + cl.14 evidence)

**Shifts** — `POST /shifts/start` (inspection checklist), `POST /shifts/end`
(reconciliation), `GET /shifts/{id}/report` (PDF/CSV)

**Payments** — `POST /payments/tap-to-pay/intent` (enforces 5% cap server-side),
`POST /payments/link`, `POST /payments/cash`, webhook `POST /stripe/webhook`

**PSL** — `GET /psl/ledger`, `POST /psl/topup` (Stripe debit of driver card,
SmartMove-parity auto top-up threshold), `GET /psl/report?period=…`

**Duress** — `POST /duress/trigger` (opens Redis GPS stream + S3 audio chunk
upload), `WS /duress/{event_id}/live` (dashboard listen-in), escalation cascade
per TCT-SMS-DA-01 (10s cancel window → dispatch → SMS contacts → 000 script)

**Live ops** — `WS /fleet/live` (positions, statuses), `GET /vehicles`, `GET /drivers`

**Billing (SaaS)** — Stripe Billing subscriptions per vehicle: Basic $29 /
Pro $49 / Enterprise $79 AUD·mo; Stripe Connect for fleet payment flows.

**Webhooks/API keys** per tenant (Enterprise tier) — eCabs-parity open API.

## B4. Fleet dashboard (React) — module list

1. **Live Map** — vehicle positions, status colours (available/hired/off), trip
   trails; duress events pin red with one-click open of the Duress Desk.
2. **Duress Desk** — live GPS stream, audio listen-in player (chunked S3),
   escalation checklist mirroring TCT-SMS-DA-01, incident export (PDF, branded).
3. **Trips** — searchable ledger, fare breakdown, GPS trace replay, variance
   flags, receipt re-send, refund via Stripe.
4. **Shifts & Reconciliation** — cash vs card, PSL owed, export CSV/PDF.
5. **Tariff Studio** — effective-dated tariff editor; rank/hail tariffs validated
   against the Fares Order reference (cannot exceed); booked tariffs free; change
   log immutable (calibration audit). "Apply Fares Order update" one-click when
   TCT publishes a new global reference.
6. **PSL Centre** — ledger, auto top-up settings, remittance report (SmartMove
   parity).
7. **Fleet & Drivers** — vehicles, devices (battery/network/app version, remote
   kiosk lock, force update), driver onboarding with document expiry alerts
   (links to existing TCT Driver Onboard Policy docs).
8. **Compliance Vault** — meter compliance dossier per vehicle: calibration
   record, mounting photo, accuracy test result, cl.14 checklist; camera + duress
   + tracking device registers. Exports branded PDFs (reuse TCT document
   pipeline).
9. **Billing** — subscription per vehicle, invoices, plan changes.
10. **White-label** — tenant theming (logo, palette, receipt template);
    Lilly Cabs preset.

## B5. Android meter app — screen map (revised from original spec)

S1 **Login/Vehicle bind** — Driver ID + PIN (NFC later), QR vehicle pairing,
   pre-shift inspection checklist → opens shift.
S2 **Idle/For Hire** — AVAILABLE toggle, tariff + region auto-detected (GPS →
   urban/country/exempt polygon), today's stats. (Drop the weather widget — noise.)
S3 **Hired (passenger-visible)** — large high-contrast running fare; itemised
   drawer (flag fall, distance, waiting, peak, tolls, PSL); band indicator
   (Tariff 1 ≤12 km / Tariff 2 >12 km, day/night); speed + accrual mode
   indicator (DISTANCE ↔ WAITING switching at 26 km/h); toll add buttons (M5,
   Harbour southbound, airport toll presets); pause = STOPPED (no accrual —
   multiple-hire payment stops, driver breaks); hidden duress gesture
   (triple-tap corner); optional **spoken fare announcements** (MTI parity,
   WCAG).
S4 **Close & Pay** — fare summary with "includes GST of $X.XX"; buttons: Tap to
   Pay (Stripe full-screen collection UI), Payment Link/QR, Cash (change calc),
   CabCharge/TTSS (manual docket entry Phase 1); surcharge line auto-computed
   ≤5%; receipt → print (BT thermal), SMS, email PDF (branded).
S5 **Shift report** — totals, PSL accrued, reconciliation, submit.
S6 **Settings/Diagnostics** — GPS quality, network, printer pairing, app
   version/force update, admin-PIN factory reset, compliance info screen
   (displays fare schedule to passengers — satisfies cl.15 display requirement).
Airport mode: geofence on Sydney Airport Precinct → offers Fixed Fare Trial
($60/$80) for eligible non-booked CBD trips; locks out extras per the Order.

## B6. Fare engine — authoritative spec (C4 referenced above)

State machine: `FOR_HIRE → HIRED → (STOPPED ⇄ HIRED) → CLOSED`

Tick loop (1 Hz, driven by fused GPS):

```
speed = kalman(fused_location)          // reject jumps >180 km/h, HDOP filter
if state == HIRED:
    if speed >= tariff.speed_threshold:      // 26 km/h
        d = haversine(prev, curr) map-matched  // snap-to-road
        band = trip.distance_km <= 12 ? 1 : 2
        rate = select(rate_table, band, time_class(start_at))  // day|night|holiday
        fare += d * rate
        trip.distance += d; trip.moving_s += dt
    else:
        fare += dt/60 * tariff.waiting_rate
        trip.waiting_s += dt
display = round_cents(flag + peak + fare + tolls + psl + extras)
```

Rules:
- `time_class` fixed at **journey commencement** (per the Order's wording:
  "journey commencing between…").
- Peak Time Hiring Charge: urban, hiring commences 10pm–6am Fri/Sat/pre-holiday.
- Distance band threshold (12 km) applies to cumulative trip distance.
- Multiple hire: meter runs once; at each drop, 75% of current metered fare is
  demanded from that hirer; meter STOPPED during payment/exit.
- Maxi: ×1.5 applied to fare (eligibility flags: pax ≥5 or airport-rank request).
- GST: never added; `gst = total/11` on receipt.
- Booked trips: tenant's booked tariff (uncapped) or fixed quote; meter may run
  in "reference mode" for transparency.
- Accuracy target: server GPS-trace recompute within ±1%; field test vs measured
  course; document in Compliance Dossier (odometer/OBD-II cross-check Phase 5).
- Anti-tamper: signed tariff payloads (server-signed JWS, verified on device);
  sealed kiosk mode; local DB encrypted (SQLCipher); clock from GPS/NTP, not
  user-settable; immutable trip log with hash chain.

## B7. Offline behaviour

- Tariffs, region polygons, fares-order reference cached and signed on device.
- Full trips run offline; queue in Room; WorkManager sync with idempotency keys.
- Card: Tap to Pay unavailable offline → cash or deferred payment link.
- Duress: SMS fallback via device SIM if data down (Twilio when online).

---

# PART C — DELIVERY PLAN FOR CLAUDE CODE

Recommended repo layout (monorepo):

```
captain-meter/
├── backend/          FastAPI app, alembic, tests, seed (Fares Order 2025 no.2)
├── dashboard/        React+TS+Vite+Tailwind, TCT theme tokens
├── android/          Kotlin Compose app
├── shared/           OpenAPI spec (source of truth), tariff JSON schema
└── docs/             this spec, compliance dossier templates
```

**Phase 1 — Fare engine + backend core (2–3 wks)**
Tariff schema + seed with A2 values; trips/shifts/auth APIs; server-side fare
recompute; OpenAPI; unit tests: golden fare test-vectors (short urban day trip,
night >12 km, country holiday, waiting-heavy CBD crawl, multi-hire, maxi,
airport fixed fare, surcharge rounding edge cases). **The golden test-vector
suite IS the compliance evidence — build it first.**

**Phase 2 — Android meter MVP (3–4 wks)**
S1–S4 screens, tick loop, kiosk mode, offline store/sync, region polygons
(urban/country/exempt + airport precinct + CBD trial zone GeoJSON), receipt PDF,
BT printer. Field-test protocol: drive a measured 10 km route, compare device vs
server recompute vs odometer.

**Phase 3 — Payments + PSL (2–3 wks)**
Stripe Terminal Tap to Pay (AU, eftpos + PIN), payment links, 5% cap enforcement
+ rounding, Stripe Connect tenant onboarding, PSL ledger + auto top-up +
remittance report.

**Phase 4 — Dashboard + Duress (3–4 wks)**
Live map (WS), Trips/Shifts/Tariff Studio/PSL Centre/Compliance Vault, duress
trigger (gesture) + escalation cascade + listen-in stream + Duress Desk
(TCT-SMS-DA-01 flows); branded incident/report PDF exports.

**Phase 5 — SaaS + white-label + hardening (2–3 wks)**
Stripe Billing tiers, tenant theming (Lilly Cabs preset), open API keys +
webhooks, OTA/MDM strategy, pen-test pass, ESP32 duress peripheral integration
(BLE) if hardware ready, OBD-II exploration.

**Definition of done for launch (Sydney pilot, 5 vehicles):**
1. Golden fare vectors green + field accuracy within tolerance, documented
2. cl.14 checklist satisfied per vehicle (mount, visibility, tamper measures)
3. Fare schedule display in-app + on TSP website
4. 5% surcharge cap verified in Stripe live mode
5. PSL report reconciles for a full test week
6. Duress cascade end-to-end drill logged
7. Compliance Dossier PDFs generated from the Vault for each pilot vehicle

---

# PART D — OPEN DECISIONS FOR BEN

1. **Tablet hardware:** consumer (Samsung Tab Active5 — rugged, replaceable
   battery, ~$700) vs payment-certified (Sunmi/Elo, Stripe-supported)? Active5
   recommended for pilot.
2. **Ship duress in software first** (gesture + tablet mic/GPS, SmartMove-Track
   parity) and add the ESP32 device in Phase 5, or block launch on hardware?
   Recommend software-first — regulation cares about the approved duress alarm
   fitted to the vehicle; the tablet feature is supplementary until the approved
   device list is confirmed with TfNSW.
3. **Dispatch scope:** meter-first (integrate to third-party dispatch via API,
   CabFare-style) vs building dispatch now? Recommend meter-first; dispatch is a
   later product.
4. **Singapore:** park until NSW pilot; LTA has its own taximeter rules — separate
   research task.
