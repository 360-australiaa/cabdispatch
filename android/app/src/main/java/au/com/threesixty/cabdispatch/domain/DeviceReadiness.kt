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

        /** Map tiles cached for the area this tablet works in. */
        OfflineMaps,

        /** A signed, in-force tariff cached locally. */
        SignedTariff,

        /** The command heartbeat is currently reaching the server. */
        Heartbeat,
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
    )

    /**
     * Every check, in the order a driver should read them: what is stopping you, then what you
     * should know about.
     */
    fun evaluate(inputs: Inputs): List<ReadinessResult> = listOf(
        registered(inputs),
        upToDate(inputs),
        location(inputs),
        offlineMaps(inputs),
        signedTariff(inputs),
        heartbeat(inputs),
    )

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
        passed = inputs.signedTariffCached == true,
        severity = Severity.ADVISORY,
        detail = when (inputs.signedTariffCached) {
            true -> "Signed tariff cached"
            false -> "No signed tariff cached yet — the meter will fetch one before the first fare"
            null -> "Not checked"
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
