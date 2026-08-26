# Duress Safety Device — Product Technical Specification & OEM Manufacturing Brief

**Model:** CT-DPD-01 (Captain Taxis Duress Panic Device)
**Version:** 1.0  **Date:** 2026-08  **Prepared for:** TY-EMS — OEM evaluation / DFM & quotation

---

## 1. Product summary
A vehicle-mounted, concealed duress/panic safety device for taxis. Two-part system: a hidden main
unit mounted under the dashboard, plus a wired remote SOS button at the driver's knee. The device
has independent 4G + GNSS with VoLTE voice, a Bluetooth LE link to an in-cab tablet, and an 8–12 h
internal battery backup. On a panic press it sends GPS + audio + an alarm to a cloud server and
auto-answers a covert voice call from a monitoring centre. It operates fully even if the tablet is
off, removed, or destroyed.

## 2. Intended market & use
- **Primary market:** Australia (initially New South Wales, Point-to-Point taxi safety).
- **Use case:** driver duress/panic alarm with live location and audio to a monitoring centre.
- **Environment:** in-vehicle, concealed under the dashboard; matte-black, IP54.

## 3. Functional requirements
- Panic trigger from the wired remote button (single press). Disarm = press-and-hold 3 s + app PIN.
- On trigger: silent local confirmation (vibration); start the 4G alarm to the server; stream GPS;
  record from the onboard microphone; auto-answer a covert inbound VoLTE call to speaker + mic
  (no ring, no visible LED).
- BLE 5 link to the cabin tablet: two-way trigger (device to tablet and tablet to device) plus
  heartbeat/status. BLE carries control messages only — no media.
- Independent operation over its own SIM if the tablet is absent, off, or destroyed.
- 8–12 h operation on the internal battery if vehicle power is removed; boot in under 30 s.
- Silent by default; status LEDs face downward and are invisible to passengers.
- Tamper detection and power-loss reporting.

## 4. System architecture (context)
- Main unit ↔ remote button: wired, 1.2 m lead.
- Main unit ↔ cabin tablet: BLE 5 (control/heartbeat only).
- Main unit ↔ cloud server: 4G (alarm, GPS, audio, health).
- Monitoring centre ↔ device: inbound VoLTE voice call, auto-answered covertly.

A companion document defines the full BLE GATT profile and the device-to-server API contract; it can
be provided to your firmware team.

## 5. Hardware architecture — design direction / reference BOM

| Subsystem | Reference part | Function | Notes |
|---|---|---|---|
| Cellular + GNSS | SIM7600G-H (30×30 LGA) | 4G Cat-4, VoLTE, GNSS | AU bands incl. B28; ~2 A TX peak — 1000 µF bulk cap at VBAT |
| Wireless MCU | ESP32-S3-WROOM-1 | BLE 5 (+ Wi-Fi), dual-core | BLE antenna at enclosure edge; copper keep-out beneath |
| Audio | NAU8810 codec + PAM8403 amp + mic preamp | Speaker + mic path | 28 mm 2 W speaker + MEMS mic; echo cancellation via SIM7600 voice DSP |
| Power management | MP1584 buck; BQ24074 Li-ion charger | 12 V step-down, charging | Battery protection; 3.8 V modem rail |
| Battery | LiPo 3.7 V 3000 mAh (60×50×8 mm) | Backup power | 8–12 h backup |
| SIM | Nano-SIM push-push tray | Cellular identity | Accessible under the bracket |
| Antennas | IPEX → SMA ×2 (off-board) | 4G main + active GPS | Metal dash blocks on-board antennas — keep off-board |
| Connectors | USB-C; JST-PH ×n | Charge/debug; button, battery, 12 V in | 12 V input + ignition-sense |
| Feedback | Vibration motor; downward LEDs | Silent confirm | Lightpipes face down |
| Remote button | Ø40 mm guarded momentary switch | Panic input | Recessed guard ring; 1.2 m lead; screw or 3M VHB mount |

## 6. Mechanical / enclosure
- **Main unit:** 92 × 62 × 22 mm, ~120 g, matte-black ABS/PC, IP54.
- **Mounting:** steel bracket under dash, security screws.
- **Main PCB:** ~84 × 54 mm, components on top side.
- **Stack (top to bottom):** top shell (speaker grille, mic port, LED lightpipes) / 28 mm speaker +
  foam gasket / main PCB / LiPo / bottom shell + steel mounting plate.
- **Cable entry:** rubber grommet with strain relief.
- **Remote button:** Ø40 mm with guard ring to prevent accidental presses.

## 7. Wireless & RF
- 4G Cat-4 VoLTE, Australian bands including B28; GNSS via SIM7600.
- BLE 5 via ESP32-S3, LE Secure Connections bonding.
- Off-board antennas (SMA): cellular main + active GPS.
- Design rules: 1000 µF bulk cap at SIM7600 VBAT; BLE antenna at enclosure edge; antenna keep-outs.

## 8. Power
- Input: 12 V vehicle + ignition sense.
- Backup: internal LiPo 3000 mAh, 8–12 h; boot under 30 s.
- Charging: BQ24074 with protection.

## 9. Environmental & mechanical targets
- Ingress: IP54.
- Temperature (proposed, to confirm): storage -20 to +70 °C, operating -10 to +60 °C.
- Automotive vibration mounting via steel bracket.

## 10. Certification & regulatory (answer to your Question 1)
- **Target market: Australia only at this stage.**
- **Mandatory:** ACMA RCM (Regulatory Compliance Mark) covering EMC (AS/NZS CISPR) and
  radiocommunications, plus electrical safety as applicable.
- **Modules:** the SIM7600G-H and ESP32-S3 carry their own radio-module certifications; the final
  assembled product still requires end-product RCM/EMC and radio compliance.
- **Automotive:** 12 V transient / load-dump protection (ISO 7637) on the input recommended for
  vehicle mounting.
- **Carrier:** SIM7600 network acceptance for Telstra/Optus where required.
- No other regions currently. If EU (CE) or US (FCC) are added later, we will advise so they can be
  scoped into testing.

## 11. Production / DFM considerations
- Off-board antenna leads with SMA panel mounting.
- 1000 µF bulk cap placement at SIM7600 VBAT.
- BLE antenna keep-out zone.
- Nano-SIM (push-push) access within a concealed, security-screwed enclosure.
- Steel bracket + security screws.
- Test points for programming/debug (UART/JTAG); USB-C for firmware flash and debug.
- Manufacture-time provisioning: load device ID, per-device secret key, and the whitelisted
  monitoring-centre number.

## 12. Commercial inputs (answers to your Questions 2 & 3) — to be confirmed
- **Engineering samples:** [ 5–10 ] units.
- **Initial production order:** [ 50–100 ] units (pilot fleet).
- **Mass production:** [ 500–2,000+ ] units/year as fleet rollout scales.
- **Target unit price (mass):** [ A$__ – __ ] — we welcome your DFM-based costing against the
  reference BOM to align on the final figure.

## 13. Documentation status & OEM scope
- **Available now:** industrial design concept, this functional/technical specification, the
  system + wireless + server interface contract, and reference-BOM direction.
- **To be finalised (open to TY-EMS design support under OEM):** detailed schematic, PCB layout /
  Gerbers, firmware (ESP32-S3 + SIM7600 AT/audio + BLE stack), mechanical CAD / enclosure tooling,
  and the production test plan.
- **Request:** please confirm whether TY-EMS provides schematic / PCB / firmware design support
  under the OEM model, or requires our completed manufacturing files for DFM only.