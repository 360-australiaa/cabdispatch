---
name: android-app-testing
description: "Use when the user wants to live-test an Android APK end-to-end on an emulator/device and get a works/doesn't-work + UI/UX report — driving the real app, not writing test code. Triggers: \"test my Android app\", \"QA this APK\", \"run the app on the emulator and tell me what breaks\", \"click through every screen\", \"review the app's UX\", \"does my APK work\", \"test the built APK\". Installs/launches the APK, enumerates screens from the accessibility tree, exercises each feature via adb (tap/type/swipe/launch), watches logcat for crashes/ANRs, captures screenshots + UI dumps, and emits a structured report. Primary path is direct local adb; optional richer path via claude-in-mobile. Sibling of web-app-testing and desktop-app-testing (shared report format). Does NOT fire for: building/coding an Android app (use claude-android-ninja / jetpack-compose-expert), iOS, or unit tests."
metadata:
  mcpmarket-version: 1.0.0
---
# android-app-testing

Live, end-to-end testing of a built **Android APK** on an emulator (or device): install, launch,
drive every screen/feature, watch for crashes & ANRs, review UI/UX, and emit a structured
works/doesn't-work report. Companion to `web-app-testing` and `desktop-app-testing` — all three
share one report format (`references/report-format.md`).

## Two paths (use the primary)
- **PRIMARY — direct local adb** (`scripts/androidtest.sh`). Fully validated, no gateway needed.
  Everything below uses this.
- **OPTIONAL — claude-in-mobile** via the Lab gateway (richer semantic locators, autopilot crawler).
  Currently blocked by a container adb gap — see `references/claude-in-mobile-path.md`. Don't reach
  for it unless that's been fixed; the adb path covers the full loop.

## Prerequisites
- **Android SDK** with `adb` + `emulator` (default `~/Android/Sdk/...`; override `ADB`/`EMULATOR`).
- **An AVD** (default `axon_test`; override `AVD`). The emulator does NOT auto-boot — step 1 boots it.
- The APK to test (a path on this host).

## The driver
`scripts/androidtest.sh` wraps the verified adb drive loop. Commands:
```
androidtest.sh boot   [<avd>]            # launch headless, wait for sys.boot_completed
androidtest.sh ready                     # serial + boot/model/android; exit 0 if ready
androidtest.sh install <apk>             # adb install -r -g
androidtest.sh launch <pkg>[/<activity>] # am start (or monkey LAUNCHER if no activity)
androidtest.sh stop   <pkg>              # am force-stop
androidtest.sh shot   <run_dir> <name>   # screencap -> evidence/<name>.png (pulled to host)
androidtest.sh tree   <run_dir> <name>   # uiautomator dump -> evidence/<name>.xml (host)
androidtest.sh taptext <run_dir> <text>  # find text in a fresh UI dump, tap its center
androidtest.sh tapxy  <x> <y>            # input tap
androidtest.sh text   "<string>"         # input text into focused field
androidtest.sh key    <BACK|HOME|ENTER|…># input keyevent
androidtest.sh current                   # current focused activity/package
androidtest.sh logclear                  # logcat -c  (call before a feature)
androidtest.sh crashes                   # grep buffer for FATAL/ANR/SIG/died
```
All primitives are live-validated (2026-05-29) against `axon_test` (Android 15, 1080×2400).

## Workflow

1. **Boot & confirm.** `androidtest.sh boot` (waits for `sys.boot_completed`), then `ready`. Create
   the run dir `~/.agents/docs/sessions/<app>-android-test/run_<id>/`.
2. **Install.** `androidtest.sh install <apk>`. Note the package name
   (`aapt dump badging <apk> | grep package`, or `adb shell pm list packages -3` after install).
3. **Launch & map.** `launch <pkg>`; `shot`/`tree` the first screen. Parse the `uiautomator` XML to
   enumerate clickable elements, text fields, tabs, nav targets. Build the feature checklist (merge
   with any user-supplied spec) — one row per feature in the report. Write `plan.md` in the run dir.
4. **Exercise each feature.** For each: `logclear` → act (`taptext` for semantic taps, `tapxy` for
   coords, `text`/`key` for input, `swipe` via the gateway or `input swipe` coords) → `shot` +
   `tree` for evidence → check `current` (did navigation happen?) and `crashes` (FATAL/ANR?).
   Classify PASS / PARTIAL / FAIL / BLOCKED.
   - Prefer **`taptext`** over raw coordinates — it survives layout changes (coords don't).
5. **Detect failures** after each action:
   - **Crash** — `crashes` shows `FATAL EXCEPTION` / `signal …(SIG…)` / `has died`. → FAIL.
   - **ANR/hang** — `crashes` shows `ANR in`, or `current` stops changing / UI frozen. → FAIL.
   - **Wrong result / no feedback** — screen didn't change when it should, or wrong screen. → PARTIAL/FAIL.
   - **Can't reach** — needs login/data/permissions the run lacks. → BLOCKED.
6. **Reset between independent features** — `stop <pkg>` then `launch` to avoid state leaking.
7. **UX/a11y pass** — score the rubric in the report format from the UI dumps + screenshots. Nodes
   in the `uiautomator` XML with empty `text` AND empty `content-desc` on interactive elements =
   accessibility findings.
8. **Write the report** → `report.md` + `result.json` in the run dir, per
   `references/report-format.md`.

## Evidence
Run-dir layout per `references/report-format.md`: `evidence/*.png` (screencaps), `evidence/*.xml`
(uiautomator dumps), logcat captures, `result.json`. The driver pulls screenshots/dumps from the
device to the host run dir automatically.

## Gotchas (live-validated)
- The emulator drops its adb registration if `adb kill-server` is run — avoid it; if a device
  disappears, the qemu process is usually still alive and re-registers, or just `boot` a fresh one.
- `am start -n pkg/.Activity` needs the real activity; if unknown, use `launch <pkg>` (monkey
  LAUNCHER) which resolves the launcher activity.
- `input text` can't type spaces directly — the driver encodes them (`%s`); for complex strings
  prefer per-field taps.
- Headless GPU: `-gpu swiftshader_indirect` is reliable for CI-style headless; custom-rendered
  (game/Canvas/Flutter-impeller) UIs may expose little in `uiautomator` → fall back to screenshot +
  coordinate taps and flag reduced confidence.

## References
- `references/report-format.md` — shared cross-platform report spec, run-dir layout, verdicts.
- `references/claude-in-mobile-path.md` — the optional gateway path + its current blocker + fix.

---

## LOCAL ADDENDUM — cabdispatch bench (added 2026-09-19, verified this session)

The skill above assumes a Linux host driving a fresh **emulator**. This repo's bench is a **real
Samsung SM-T575 tablet** on **Windows/Git Bash**, and several of the instructions above do not hold
here. Verified by driving the full login → inspection → shift → GPS-simulator → forced-403 loop on
the physical device.

- **`scripts/androidtest.sh` and `references/` are NOT shipped with this skill file.** Every
  `androidtest.sh <cmd>` above is a description of a command that does not exist on this machine
  yet. Use the raw adb equivalents until the driver is written.
- **Steps 1–2 do not apply.** There is no AVD and no `emulator`; the tablet is already attached
  (`adb devices` → `R52TB07AQVL`). Skip `boot`; go straight to `install`/`launch`.
- **Prefix every adb call with `MSYS_NO_PATHCONV=1`** in Git Bash, or `/sdcard/...` paths are
  rewritten into Windows paths and the command fails confusingly.
- **`uiautomator dump /dev/tty` returns only the "dumped to" line here** — no XML. Dump to
  `/sdcard/ui.xml`, `adb pull` it, and parse the file.
- **`launch <pkg>` via monkey opens LeakCanary**, which registers its own LAUNCHER activity in debug
  builds. Use `am start -n au.com.threesixty.cabdispatch/.MainActivity`.
- **`androidtest.sh text` / `input text` DOES NOT WORK on the sign-in screen.** The app draws its
  own in-app Compose keyboard rather than using the system IME, so there is no focused IME field to
  type into. You must tap key coordinates read from the UI dump.
- **Keypad geometry changes between fields.** The driver-code pad is a 6-column alphanumeric grid;
  focusing the PIN field swaps in a 3×4 numeric pad with entirely different row positions, and the
  Sign In button moves with it. Re-dump after every focus change — reusing coordinates across pads
  silently types the wrong characters into the wrong field.
- **Screenshot pixels are not tap coordinates.** The panel is physically portrait (1200×1920) while
  the app renders landscape (1920×1200), so coordinates derived from a screenshot miss. `uiautomator`
  bounds ARE in the space `input tap` uses — this is why step 4's "prefer taptext over coordinates"
  matters far more here than the skill implies.
- **`screencap` returns 0 bytes on Close & Pay and PIN screens** (FLAG_SECURE). Use the UI dump for
  evidence on those screens; a blank PNG is not a crash.
- **Server round-trips gate the UI.** Driver sign-in and admin-PIN verification are network calls
  (~0.5–2.5s). Tapping through them fast leaves stale digits and yields a spurious "Incorrect PIN".
  Add a delay after submit and confirm via logcat (`okhttp` lines) rather than by screenshot timing.
- **Step 6's "reset between features" is expensive here.** A force-stop drops the session, and
  getting back to an on-shift state costs a full login + inspection + shift start. Batch anything
  that needs an open shift into one run.
- **This tablet is paired to PRODUCTION.** Starting a shift or publishing positions writes real rows
  to the live fleet database. Clean up after a run (end the shift, stop the GPS simulator) and never
  leave a synthetic shift open.
