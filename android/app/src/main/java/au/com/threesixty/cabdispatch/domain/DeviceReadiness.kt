package au.com.threesixty.cabdispatch.domain

/**
 * Is this tablet fit to be used as a meter?
 *
 * ### Why a gate exists at all
 * Until 2026-09-08 a tablet that had never been paired to the dashboard ran the meter perfectly
 * happily — it took fares, closed trips and printed tax invoices. The only sign anything was wrong
 * was a small chip in the corner, and this codebase documented, in two separate places, that it
 * deliberately did not block on it (see [DevicePairingStatus]'s class doc for the live onboarding
 * where a driver completed every step with `deviceId` null the whole way, and
 * [au.com.threesixty.cabdispatch.ui.screens.login.LoginVehicleBindViewModel.startShift]'s own note
 * naming itself as the place to add a real block if policy ever changed).
 *
 * The consequence is not cosmetic. An unpaired tablet gets no kiosk lock, no remote locate, no
 * forced update and no heartbeat, so it is invisible to the depot while still earning revenue on a
 * regulated meter. Policy is now: **a tablet must be registered before anyone can use it.**
 *
 * ### What blocks, and what only warns
 * Only two checks block, and both are deliberate choices rather than "everything we can measure":
 *
 * - [ReadinessCheck.Registered] — the requirement itself.
 * - [ReadinessCheck.UpToDate] — and ONLY when the depot has flagged this tablet *and* a newer build
 *   genuinely exists to install. Never on the flag alone: `force_update_pending` latches
 *   server-side with no client-side clear (see
 *   [au.com.threesixty.cabdispatch.ui.overlays.ForceUpdatePendingBanner]'s doc), so blocking on it
 *   by itself would brick a revenue-earning meter that an admin flagged by mistake, with no way
 *   out from the tablet.
 *
 * Everything else — offline maps, a signed tariff, whether the heartbeat is currently getting
 * through — is shown with a fix action and does not stop anyone. In particular the heartbeat is
 * advisory on purpose: a tablet in a basement carpark with no signal is offline, not unregistered,
 * and must still be able to start its shift.
 *
 * ### Why every blocking input is readable synchronously
 * Both blocking checks resolve from state restored during `AppContainer.init` before any screen
 * composes — `SessionHolder.deviceId` from `DevicePairingStore`, and `forceUpdatePending` from the
 * same store's cached command flags. So the gate decision costs no network round-trip and a
 * healthy tablet never flashes a gate on its way to the login screen. The gate SCREEN then does
 * the slow work (checking for a real release, probing the map cache) to render detail and offer
 * fixes.
 *
 * Pure Kotlin, no Android types, so the policy is unit-testable without an emulator — same reason
 * [DevicePairingStatus] is an object rather than logic inlined into a ViewModel.
 */
object DeviceReadiness {

    /** Whether failing a check stops the driver, or merely tells them. */
    enum class Severity { BLOCKING, ADVISORY }

    enum class ReadinessCheck {
        /** Paired with the dashboard, and not since disowned by it. */
        Registered,

        /** Not sitting on a build the depot has flagged as needing replacement. */
        UpToDate,

        /**
         * Location permission granted AND a real fix arriving.
         *
         * A meter that cannot see satellites cannot charge a distance rate, so this is the check a
         * technician most needs before leaving a vehicle — and the one nothing surfaced before.
         * Advisory rather than blocking only because a tablet indoors at a depot legitimately has
         * no fix yet; commissioning shows it as an outstanding warning instead of pretending it
         * passed.
         */
        Location,

        /**
         * Every runtime permission the meter needs, as one row.
         *
         * One row rather than six because a technician reads a checklist, not a manifest — and
         * because the interesting answer is "4 of 6", with the missing ones named. The individual
         * grants are in [Inputs.permissions] so the screen can expand them.
         *
         * This is not housekeeping: two of them break the commissioning screen's OWN fix buttons.
         * Scan QR needs the camera, and Install update needs the install-packages appop. A
         * technician could previously stand in front of a checklist whose buttons silently did
         * nothing.
         */
        Permissions,

        /**
         * The tablet is exempt from battery optimisation.
         *
         * ### What this protects, now that A1 has landed (2026-09-08)
         * The premise this check was written on is gone. It used to read "there is no foreground
         * service in this app — the meter's tick loop is a coroutine in the process, so Doze can
         * stop a running fare", and that was true and severe: a trip that stops being charged for
         * while the passenger is still in the car. A1 hoisted the fare engine into
         * [MeterForegroundService], and a started foreground service with a visible notification is
         * not something Doze stops. **A running fare is no longer at risk from this.**
         *
         * What exemption still buys is real but smaller, and worth naming precisely rather than
         * leaving the old blocker-shaped wording in place over a consequence that no longer
         * follows:
         * - The 60 s [DeviceCommandHeartbeat] poll and [LivePositionHeartbeat] position publish run
         *   in the app process, NOT in the foreground service. On an idle, parked, screen-off
         *   tablet Doze batches their timers, so kiosk lock / locate / force-update commands and
         *   Live Map positions arrive late — up to the length of a maintenance window rather than
         *   within a minute. That is a dispatcher-visible fault, not a fare fault.
         * - Between hirings there is no foreground service at all (it is started for a hiring and
         *   stopped at the end of one), so that is exactly when the batching bites.
         *
         * Stays [Severity.ADVISORY], which it always was — the difference is that the severity and
         * the stated consequence now agree with each other.
         */
        BatteryOptimisation,

        /**
         * The tablet is pinned to the meter — Android **screen pinning**, not a kiosk lock.
         *
         * ### Why the wording matters
         * This row said "Kiosk lock" and let a technician sign a tablet off believing something
         * much stronger than what is actually running. [KioskLockController]'s own doc has always
         * been correct about it and the user-facing string was not: this app holds no Device Owner
         * provisioning, so the only mode it can ever *start* is `Activity.startLockTask()` with no
         * DPC allowlist — plain screen pinning. That is:
         * - **escapable by the user** — a long Back + Recents press prompts to unpin, and
         * - **not durable across a reboot** — pinning is task state and dies with the task.
         *
         * [DeviceCommandHeartbeat] re-applies it on every 60 s poll, so an escape self-corrects
         * within a minute rather than lasting the shift; a reboot leaves the tablet unpinned until
         * the first poll after boot. Both are honest limits to state, not defects to hide.
         *
         * "Kiosk lock" is reserved for [KioskState.DpcLocked] — a real Device Owner / Knox
         * allowlisted lock, which this app can observe but never cause. A future Device Owner build
         * is what would earn the word back for everything else.
         */
        Kiosk,

        /** Map tiles cached for the area this tablet works in. */
        OfflineMaps,

        /**
         * The map service is usable at all.
         *
         * A blank `MAPBOX_ACCESS_TOKEN` degrades every map in the app to a grid fallback and makes
         * the offline-map download impossible — silently, which is the problem. A technician who
         * has just tapped "Download offline maps" and watched nothing happen deserves to be told
         * why.
         */
        MapService,

        /**
         * A signed, in-force tariff AND the key that verifies it, both cached locally.
         *
         * Both halves, deliberately: a tablet holding a tariff but no Ed25519 public key cannot
         * verify that tariff's signature offline, and would pass a tariff-only check while being
         * unable to prove the prices it is charging are the ones the depot signed.
         */
        SignedTariff,

        /**
         * The vehicle's class has been declared for this tablet.
         *
         * Maxi taxis are charged at 150% of the metered fare (Fares Order cl 2(d)), and the
         * declaration is a per-device setting a driver could previously only find buried in
         * Settings. Getting it wrong overcharges or undercharges every fare in the vehicle, so it
         * belongs in the technician's hands at install, once, deliberately.
         *
         * Note this is a self-declaration with no backend field behind it — see MaxiVehicleStore's
         * own doc. The check is that someone has ANSWERED, not that the answer is true.
         */
        VehicleClass,

        /** The command heartbeat is currently reaching the server. */
        Heartbeat,
    }

    /**
     * A runtime permission the meter needs, and what breaks without it.
     *
     * [critical] separates "the meter cannot do its job" from "a feature is degraded", so the
     * screen can rank them and the summary line can say something more useful than a bare count.
     */
    enum class MeterPermission(val label: String, val critical: Boolean, val needs: String) {
        FineLocation("Location", true, "measuring distance — the meter cannot charge a distance rate without it"),
        BackgroundLocation("Background location", true, "keeping the fare running when the screen is off"),
        Camera("Camera", false, "scanning a pairing QR code and duress cabin capture"),
        Microphone("Microphone", false, "duress audio capture"),
        Notifications("Notifications", false, "dispatch offers, sync and duress alerts"),
        InstallPackages("Install updates", false, "installing a meter update the depot pushes"),
    }

    /**
     * One check's outcome. [detail] is the driver-facing explanation and must say what is actually
     * true — "not checked yet" is a real and different answer from "failed", and neither may be
     * dressed up as the other.
     */
    data class ReadinessResult(
        val check: ReadinessCheck,
        val passed: Boolean,
        val severity: Severity,
        val detail: String,
    )

    /**
     * The inputs the policy is a function of. A plain value type rather than reads of singletons so
     * the rules can be exercised over every combination without an `Application`.
     *
     * [updateAvailable] is "the server has confirmed a newer build exists". Unknown — not checked
     * yet, or the check failed — is `false`, which fails open: an unreachable release server must
     * never be what stops a driver working.
     */
    data class Inputs(
        val deviceId: String?,
        val deviceRejected: Boolean,
        val forceUpdatePending: Boolean,
        val updateAvailable: Boolean,
        val heartbeatSucceeding: Boolean?,
        val offlineMapsPresent: Boolean?,
        val signedTariffCached: Boolean?,
        /** True once the location permission is granted; false when it is not; null when nothing
         * has looked yet. */
        val locationPermissionGranted: Boolean? = null,
        /** True once a real fix has actually arrived — permission granted is not the same thing as
         * a working GPS, and a technician needs to know which one they are looking at. */
        val hasLocationFix: Boolean? = null,
        /** Grant state per permission. A permission absent from the map has not been looked at;
         * an empty map means nothing has been checked at all. */
        val permissions: Map<MeterPermission, Boolean> = emptyMap(),
        val batteryOptimisationExempt: Boolean? = null,
        val kiosk: KioskState? = null,
        val mapTokenPresent: Boolean? = null,
        val tariffSigningKeyCached: Boolean? = null,
        val vehicleClassDeclared: Boolean? = null,
    )

    /**
     * What the tablet's screen-pinning state actually is, against what the depot asked for.
     *
     * Kept as a small enum rather than two booleans so the screen renders one honest sentence per
     * state instead of composing one from flags. Mirrors the shape KioskLockController.decideAction
     * already uses for its own truth table.
     */
    enum class KioskState {
        /** A DPC (Knox) holds the device in lock-task mode. The strongest state, and one this app
         * provably did not cause — it holds no Device Owner. */
        DpcLocked,

        /** Pinned by this app via startLockTask, at the depot's request. */
        Pinned,

        /** The depot flagged this tablet for kiosk, but the OS reports it unpinned. The one state
         * a technician can and should fix on the spot. */
        DepotWantsItButNotPinned,

        /** Not pinned, and the depot has not asked for it. Not a fault. */
        NotRequested,
    }

    /**
     * Every check, in the order a driver should read them: what is stopping you, then what you
     * should know about.
     */
    fun evaluate(inputs: Inputs): List<ReadinessResult> = listOf(
        registered(inputs),
        upToDate(inputs),
        permissions(inputs),
        location(inputs),
        batteryOptimisation(inputs),
        kiosk(inputs),
        offlineMaps(inputs),
        mapService(inputs),
        signedTariff(inputs),
        vehicleClass(inputs),
        heartbeat(inputs),
    )

    /** The permissions still missing, worst first — for the screen's expandable sub-list and for
     * naming them on the Finish setup button. */
    fun missingPermissions(inputs: Inputs): List<MeterPermission> =
        MeterPermission.entries
            .filter { inputs.permissions[it] == false }
            .sortedByDescending { it.critical }

    /** The failures that actually stop a driver. Empty means the tablet may be used. */
    fun blockingFailures(inputs: Inputs): List<ReadinessResult> =
        evaluate(inputs).filter { !it.passed && it.severity == Severity.BLOCKING }

    private fun registered(inputs: Inputs): ReadinessResult {
        // Reuses DevicePairingStatus rather than restating the rule, so "rejected by the server"
        // keeps counting as unregistered here exactly as it does for the banner. Holding an id the
        // server disowns is not a weaker form of paired.
        val paired = !DevicePairingStatus.isUnpaired(inputs.deviceId, inputs.deviceRejected)
        return ReadinessResult(
            check = ReadinessCheck.Registered,
            passed = paired,
            severity = Severity.BLOCKING,
            detail = when {
                paired -> "Paired with the depot"
                inputs.deviceRejected ->
                    "The depot no longer recognises this tablet — enter a new pairing code"
                else -> "This tablet has never been paired — ask the depot for a pairing code"
            },
        )
    }

    private fun upToDate(inputs: Inputs): ReadinessResult {
        // Both halves are required. The flag alone means an admin pressed a button; it does not
        // mean a build exists to install, and this app cannot clear the flag itself.
        val blocked = inputs.forceUpdatePending && inputs.updateAvailable
        return ReadinessResult(
            check = ReadinessCheck.UpToDate,
            passed = !blocked,
            severity = Severity.BLOCKING,
            detail = when {
                blocked -> "The depot requires a newer meter build — install it to continue"
                inputs.forceUpdatePending ->
                    "The depot flagged this tablet, but no newer build has been published"
                else -> "Running the current build"
            },
        )
    }

    private fun location(inputs: Inputs) = ReadinessResult(
        check = ReadinessCheck.Location,
        // Permission alone is not enough. A tablet can hold the permission and still never see a
        // satellite -- a dead aerial, a faulty unit, a factory-reset location setting -- and that
        // tablet cannot charge a distance rate. The technician needs to see a real fix arrive.
        passed = inputs.locationPermissionGranted == true && inputs.hasLocationFix == true,
        severity = Severity.ADVISORY,
        detail = when {
            inputs.locationPermissionGranted == false ->
                "Location permission denied — the meter cannot measure distance without it"
            inputs.hasLocationFix == true -> "GPS fix acquired"
            inputs.hasLocationFix == false ->
                "Permission granted, but no GPS fix yet — take the tablet outside"
            else -> "Not checked"
        },
    )

    private fun permissions(inputs: Inputs): ReadinessResult {
        val checked = MeterPermission.entries.filter { inputs.permissions.containsKey(it) }
        val granted = checked.count { inputs.permissions[it] == true }
        val missing = missingPermissions(inputs)
        return ReadinessResult(
            check = ReadinessCheck.Permissions,
            passed = checked.size == MeterPermission.entries.size && missing.isEmpty(),
            severity = Severity.ADVISORY,
            detail = when {
                checked.isEmpty() -> "Not checked"
                missing.isEmpty() -> "All ${MeterPermission.entries.size} granted"
                // Names them rather than counting them. "2 missing" sends a technician hunting;
                // "Location, Camera" is something they can act on without opening another screen.
                else -> "$granted of ${MeterPermission.entries.size} granted — missing " +
                    missing.joinToString(", ") { it.label }
            },
        )
    }

    private fun batteryOptimisation(inputs: Inputs) = ReadinessResult(
        check = ReadinessCheck.BatteryOptimisation,
        passed = inputs.batteryOptimisationExempt == true,
        severity = Severity.ADVISORY,
        // Copy rewritten 2026-09-08 (A7) to match what A1's foreground service actually changed —
        // see ReadinessCheck.BatteryOptimisation's doc. The old "may stop a running fare" line was
        // a blocker-severity claim on an advisory row, and is no longer true either way.
        detail = when (inputs.batteryOptimisationExempt) {
            true -> "Exempt — depot commands and Live Map positions stay on time when parked"
            false ->
                "Not exempt — a running fare is safe (it holds a foreground service), but depot " +
                    "commands and Live Map positions can be delayed while the tablet sits idle"
            null -> "Not checked"
        },
    )

    private fun kiosk(inputs: Inputs) = ReadinessResult(
        check = ReadinessCheck.Kiosk,
        // NotRequested passes: a depot that has not asked for kiosk mode has not misconfigured
        // anything, and a checklist that cries about it would teach technicians to ignore the row.
        passed = inputs.kiosk == KioskState.DpcLocked ||
            inputs.kiosk == KioskState.Pinned ||
            inputs.kiosk == KioskState.NotRequested,
        severity = Severity.ADVISORY,
        // "Kiosk lock" is reserved for DpcLocked — see ReadinessCheck.Kiosk's doc. Every other
        // state says "pinned", and the states a technician can act on say what the limits are, so
        // nobody signs a tablet off believing it is locked down harder than it is.
        detail = when (inputs.kiosk) {
            KioskState.DpcLocked -> "Kiosk-locked to the meter by the fleet policy — survives a reboot"
            KioskState.Pinned ->
                "Screen pinned to the meter — a driver can unpin it (hold Back + Recents), and a " +
                    "reboot clears it until the next depot check-in, about a minute later"
            KioskState.DepotWantsItButNotPinned ->
                "The depot asked for the meter to be pinned but this tablet is not — tap to pin it"
            KioskState.NotRequested -> "Not pinned; the depot has not asked for it"
            null -> "Not checked"
        },
    )

    private fun mapService(inputs: Inputs) = ReadinessResult(
        check = ReadinessCheck.MapService,
        passed = inputs.mapTokenPresent == true,
        severity = Severity.ADVISORY,
        detail = when (inputs.mapTokenPresent) {
            true -> "Map service configured"
            false -> "No map token in this build — maps and offline downloads will not work"
            null -> "Not checked"
        },
    )

    private fun vehicleClass(inputs: Inputs) = ReadinessResult(
        check = ReadinessCheck.VehicleClass,
        passed = inputs.vehicleClassDeclared == true,
        severity = Severity.ADVISORY,
        detail = when (inputs.vehicleClassDeclared) {
            true -> "Declared for this vehicle"
            false -> "Not declared — a maxi taxi charges 150%, so this must be answered"
            null -> "Not checked"
        },
    )

    private fun offlineMaps(inputs: Inputs) = ReadinessResult(
        check = ReadinessCheck.OfflineMaps,
        passed = inputs.offlineMapsPresent == true,
        severity = Severity.ADVISORY,
        detail = when (inputs.offlineMapsPresent) {
            true -> "Map tiles cached for this area"
            false -> "No offline map — navigation will need a data connection"
            null -> "Not checked"
        },
    )

    private fun signedTariff(inputs: Inputs) = ReadinessResult(
        check = ReadinessCheck.SignedTariff,
        // Both halves. A tariff without its verifying key cannot be checked offline, so a
        // tariff-only test would go green on a tablet that cannot prove the prices it charges are
        // the ones the depot signed.
        passed = inputs.signedTariffCached == true && inputs.tariffSigningKeyCached == true,
        severity = Severity.ADVISORY,
        detail = when {
            inputs.signedTariffCached == null || inputs.tariffSigningKeyCached == null -> "Not checked"
            inputs.signedTariffCached == false ->
                "No signed tariff cached yet — the meter will fetch one before the first fare"
            inputs.tariffSigningKeyCached == false ->
                "Tariff cached, but not the key that verifies it — signatures cannot be checked offline"
            else -> "Signed tariff and verifying key cached"
        },
    )

    private fun heartbeat(inputs: Inputs) = ReadinessResult(
        check = ReadinessCheck.Heartbeat,
        passed = inputs.heartbeatSucceeding == true,
        severity = Severity.ADVISORY,
        detail = when (inputs.heartbeatSucceeding) {
            true -> "Reporting to the depot"
            // Advisory on purpose: offline is not unregistered. A tablet in a basement carpark
            // must still be able to start its shift.
            false -> "Cannot reach the depot right now — remote commands will arrive when it can"
            null -> "No heartbeat yet this session"
        },
    )
}
