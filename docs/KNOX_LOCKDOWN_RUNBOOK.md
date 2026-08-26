# Knox Lockdown Runbook -- In-Cab Tablet

Step-by-step operational runbook for locking down the in-cab Samsung tablet via Samsung Knox
Manage (Knox Suite) so it can run only Google Maps, Waze, and the Cab Dispatch meter app
(`au.com.threesixty.cabdispatch`). Written for the person doing the physical enrollment and
lockdown, not for the app developers -- see
**[`DURESS_DEVICE_INTEGRATION.md`](./DURESS_DEVICE_INTEGRATION.md)** for the system this tablet is
part of, and Section 6 of this document for why Bluetooth must stay on despite the otherwise
strict lockdown.

---

## 1. Prerequisites

- **Samsung Knox Suite / Knox Manage licence** -- one licence seat per enrolled tablet, active in
  the Knox Manage console (`https://portal.samsungknox.com` or your region's Knox Manage tenant).
- **A Samsung Galaxy tablet on the Knox-supported device list.** Not every Android tablet supports
  Knox -- confirm the exact model/firmware is Knox-compatible before ordering fleet hardware, not
  after.
- **One of the two enrollment relationships below, depending on fleet size:**
  - **Knox Mobile Enrollment (KME)** -- a reseller relationship with a Samsung-authorized reseller
    or carrier who registers the tablets' IMEIs against your Knox Manage tenant at the point of
    sale. This is the bulk/production path -- it enrolls a tablet automatically the first time it
    is factory-reset and connected to the internet, with zero manual steps at the vehicle. Needs
    the reseller relationship set up before you can use it.
  - **Manual Device Owner enrollment** -- no reseller relationship required. Works on any
    Knox-supported tablet via a QR code or the `afw#setup` token typed into the initial
    factory-reset setup screen. This is the pilot/small-batch path -- use it for early units before
    a KME reseller relationship exists, and expect to walk through Section 2's manual procedure by
    hand for each tablet.

A pilot fleet will very likely start on the manual path and move to KME once volume and a reseller
relationship justify it. Both paths land the tablet in the same place -- a Knox Manage-managed
Device Owner enrollment -- so the kiosk profile in Section 3 applies identically either way.

---

## 2. Enrollment steps

Both procedures start the same way: **factory reset the tablet.** Device Owner mode (which Knox
Manage's kiosk lockdown depends on) can only be established on a tablet with no existing Google
account signed in and no prior app data -- if the tablet has ever been used, wipe it first
(Settings > General management > Reset > Factory data reset, or Knox Manage's own remote-wipe
action if it is already enrolled under a different profile).

### 2a. Bulk/reseller path -- Knox Mobile Enrollment (KME)

Use this once a KME reseller relationship is in place.

1. Confirm with your reseller that the tablet's IMEI is registered against your organization's
   Knox Manage tenant *before* the device reaches a vehicle -- this is normally done automatically
   as part of the purchase, but confirm it for the first batch.
2. Power on the (factory-reset) tablet and connect it to Wi-Fi or a SIM data connection during the
   initial setup screens.
3. KME detects the registered IMEI automatically and pushes the Device Owner enrollment with no
   further prompts -- the tablet reboots into Knox Manage-managed mode on its own.
4. In the Knox Manage console, confirm the new device appears under your organization's device
   list, then assign it to the fleet's kiosk profile (Section 3).

No QR code, no `afw#setup` token, and no manual tapping through setup screens are needed on this
path -- that is the entire point of KME for a bulk rollout.

### 2b. Pilot/small-batch path -- manual Device Owner (QR / afw#setup)

Use this for early pilot units before a KME reseller relationship exists.

1. Factory reset the tablet if it is not already blank.
2. Power it on and proceed through the initial setup screens until you reach the Wi-Fi selection
   screen. Connect to Wi-Fi (required to download the Knox Manage agent).
3. On the next screen (the one that would normally prompt for a Google account), tap the same spot
   **six times in quick succession** -- this is the standard Android "special enrollment" gesture
   that reveals the enrollment entry point without a Google sign-in. Depending on Android version
   this either opens a QR scanner directly or a text field.
4. Provide the Knox Manage enrollment token one of two ways:
   - **QR code:** generate a QR provisioning code for this tenant/profile from the Knox Manage
     console (Device enrollment > QR code), display it on another screen, and scan it with the
     tablet's camera at this step.
   - **Text token:** type `afw#setup` into the text field if that is the path offered, then follow
     the prompts to enter your Knox Manage tenant's enrollment details when asked.
5. The tablet downloads and installs the Knox Manage agent as the device's Device Owner app, then
   reboots into managed mode.
6. In the Knox Manage console, confirm the new device appears under your organization's device
   list, then assign it to the fleet's kiosk profile (Section 3).

Do this once per tablet for a pilot batch -- it is a few minutes of manual work per unit, which is
exactly why KME (2a) is worth setting up once volume grows.

---

## 3. Kiosk profile configuration (Knox Manage console)

Create one kiosk profile and assign it to every fleet tablet (both KME- and manually-enrolled
units use the same profile).

### 3.1 App allowlist

In the Knox Manage console, create a Kiosk (or "Multi-App Mode") policy with an app allowlist
containing **exactly these three packages**:

| Package | App | Role |
|---|---|---|
| `com.google.android.apps.maps` | Google Maps | Navigation |
| `com.waze` | Waze | Navigation |
| `au.com.threesixty.cabdispatch` | Cab Dispatch meter app | Set as the kiosk home/launcher app |

No other package should be on the allowlist. `au.com.threesixty.cabdispatch` must specifically be
set as the **kiosk home app / launcher** (the app the tablet returns to and cannot be dismissed
from) -- Knox Manage's kiosk policy has a distinct "home app" field separate from the allowlist
itself; set it there, not just added to the list.

### 3.2 Policy toggles

Set the following in the same kiosk/restriction policy:

| Toggle | Setting | Why |
|---|---|---|
| Status bar | Hidden | Prevents pulling down notifications/quick settings to reach other controls. |
| Recents / navigation bar | Hidden | Prevents switching to a backgrounded app outside the allowlist. |
| Settings app access | Blocked | Prevents a driver or passenger from reconfiguring the device. |
| USB debugging | Disabled | Closes an ADB-based bypass of the lockdown. |
| Factory Reset Protection (FRP) | Enabled | Ties the device to the enrolled Samsung/Google account so a factory reset by someone outside the fleet cannot re-provision the tablet as a personal device. |
| App install/uninstall | Blocked from any source | No sideloading, no Play Store installs, no uninstalling the three allowlisted apps. |
| Unknown-sources installs | Blocked | Belt-and-suspenders with the install/uninstall block above -- closes the APK-sideload path specifically. |
| **Bluetooth** | **Kept ON** | **Required for the duress device BLE link -- do not disable it.** See Section 6. |
| Location | Kept ON | Required for Maps/Waze navigation and the meter app's own GPS use (fare calculation, live position, duress GPS streaming). |
| Safe mode boot | Disabled | Booting into Android Safe Mode disables all third-party apps including the kiosk launcher itself, which would otherwise bypass the entire lockdown. |
| Power menu -- shutdown/reboot | Blocked, where the policy allows it | Prevents a driver from power-cycling out of the kiosk session to reach the normal home screen during the brief reboot window. Some Knox Manage/Android versions cannot fully suppress this option without also blocking a genuinely needed reboot path -- apply the strictest setting the console offers for your enrolled Android version and note if it cannot be fully blocked. |

Every restriction above is compatible with the Bluetooth-on requirement -- there is no policy
conflict between "locked down to three apps" and "Bluetooth stays enabled."

---

## 4. Verification checklist -- after applying the profile

Run this on a real tablet after the kiosk profile is applied, before it goes into a vehicle:

- [ ] Powering on / unlocking the tablet shows only the Cab Dispatch meter app -- no other home
      screen, launcher, or app drawer reachable.
- [ ] From inside the meter app, there is no way to reach Google Maps or Waze except through
      whatever in-app mechanism the meter app itself provides (if any) -- confirm Maps and Waze
      are launchable only via that path, not via a system app switcher.
- [ ] Swiping for recents/navigation gesture does not reveal a task switcher or any other app.
- [ ] Attempting to reach Settings (via any known gesture, notification-shade shortcut, or
      long-press) is blocked.
- [ ] Pulling down from the top of the screen does not reveal a working notification shade/quick
      settings panel with device controls.
- [ ] Power off and back on: the tablet boots directly back into the locked kiosk state with no
      window to reach a normal home screen during boot.
- [ ] Bluetooth is confirmed ON in whatever status indicator the kiosk profile leaves visible (or
      via the meter app's own Bluetooth-status display, if it has one).
- [ ] Pairing a duress device (CT-DPD-01) to the tablet over Bluetooth succeeds and the bond
      persists across a reboot -- confirms Section 3.2's Bluetooth-on setting has not been
      silently overridden by a stricter unrelated policy.
- [ ] GPS/location-based features in the meter app (live position, duress GPS) still function --
      confirms Location was not accidentally disabled alongside the rest of the lockdown.

Do not release a tablet to a vehicle until every item above is ticked on that specific unit --
policies can apply inconsistently across Android/Knox firmware versions, so verify per-device, not
just per-profile.

---

## 5. Recovery / exception procedure

**Temporarily lifting the lock for maintenance:** an authorized admin does this from the Knox
Manage console, not on the device itself (Settings is blocked by design -- see Section 3.2).
In the console, locate the device under its fleet group and apply a temporary profile change
(either unassign the kiosk profile or assign a separate "maintenance" profile with the
restrictions relaxed) for the duration of the maintenance work, then re-apply the standard kiosk
profile from Section 3 afterward and re-run the Section 4 verification checklist before the
tablet goes back into service. Do not leave a tablet in an unlocked/maintenance state in a vehicle
between visits.

**Factory-reset / re-enrolling a bricked or lost tablet:**

1. If the tablet is still reachable in the Knox Manage console (online, enrolled), issue a remote
   wipe / factory-reset command from the console first -- this also clears Factory Reset
   Protection tied to the fleet's account, which a manual factory reset from the device (if even
   reachable) will not do cleanly.
2. If the tablet is lost, stolen, or unreachable, mark it lost/wiped in the Knox Manage console so
   it is removed from the active fleet count and its licence seat can be reassigned once
   confirmed gone.
3. Once wiped (remotely or physically recovered and reset), re-enroll it from scratch using
   whichever path applies -- Section 2a if it is IMEI-registered under an active KME reseller
   relationship, Section 2b (manual QR / afw#setup) otherwise -- then reassign the Section 3
   kiosk profile and re-run the Section 4 verification checklist before it returns to service.

---

## 6. Why Bluetooth must stay on

This lockdown is deliberately strict everywhere except one radio: **Bluetooth stays enabled.**
The in-cab duress panic device (CT-DPD-01) pairs to this tablet over Bluetooth LE to exchange
small trigger/heartbeat control messages -- the tablet learns a device-side panic press happened
(and starts its own camera/GPS/upload response) over that link, and can arm the device's own
independent cellular alarm the same way in reverse. Disabling Bluetooth as part of a
"lock everything down" pass would silently break that link without breaking anything else visibly
-- the device would still alarm on its own SIM regardless, but the tablet would stop learning
about device-side triggers and stop being able to arm the device from its own side. See
**[`DURESS_DEVICE_INTEGRATION.md`](./DURESS_DEVICE_INTEGRATION.md)**, Section 3, for the full BLE
contract this depends on. Every other radio and access restriction in Section 3.2 above is safe to
apply at maximum strictness; Bluetooth is the one deliberate exception.
