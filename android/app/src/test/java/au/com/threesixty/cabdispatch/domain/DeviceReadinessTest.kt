package au.com.threesixty.cabdispatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The policy behind the gate that stands in front of the login screen.
 *
 * Two properties matter more than any individual case, and both are easy to get wrong in the
 * direction that hurts a driver rather than the direction that is merely annoying:
 *
 * 1. **Offline is not unregistered.** A paired tablet in a basement carpark, or one whose depot is
 *    unreachable, must still start its shift. Only a tablet that has never paired — or that the
 *    server has actively disowned — is stopped.
 * 2. **Nothing blocks that the driver cannot fix from where they are standing.** In particular the
 *    force-update flag on its own is not enough: it latches server-side with no client-side clear,
 *    so blocking on it without a real build to install would brick a revenue-earning meter with no
 *    way out.
 *
 * Pure JVM, like [DevicePairingStatusTest] next to it — the policy is deliberately free of Android
 * types so it can be exercised over every combination without an emulator.
 */
class DeviceReadinessTest {

    private fun inputs(
        deviceId: String? = "device-1",
        deviceRejected: Boolean = false,
        forceUpdatePending: Boolean = false,
        updateAvailable: Boolean = false,
        heartbeatSucceeding: Boolean? = true,
        offlineMapsPresent: Boolean? = true,
        signedTariffCached: Boolean? = true,
        locationPermissionGranted: Boolean? = true,
        hasLocationFix: Boolean? = true,
    ) = DeviceReadiness.Inputs(
        deviceId = deviceId,
        deviceRejected = deviceRejected,
        forceUpdatePending = forceUpdatePending,
        updateAvailable = updateAvailable,
        heartbeatSucceeding = heartbeatSucceeding,
        offlineMapsPresent = offlineMapsPresent,
        signedTariffCached = signedTariffCached,
        locationPermissionGranted = locationPermissionGranted,
        hasLocationFix = hasLocationFix,
    )

    private fun blockedChecks(inputs: DeviceReadiness.Inputs) =
        DeviceReadiness.blockingFailures(inputs).map { it.check }.toSet()

    // --- registration ---------------------------------------------------------------------

    @Test
    fun `a tablet that has never been paired is blocked`() {
        assertEquals(
            setOf(DeviceReadiness.ReadinessCheck.Registered),
            blockedChecks(inputs(deviceId = null)),
        )
    }

    @Test
    fun `a blank device id is treated as never paired`() {
        // Never produced in practice, but a stray empty string must not read as "paired".
        assertTrue(DeviceReadiness.ReadinessCheck.Registered in blockedChecks(inputs(deviceId = "")))
    }

    @Test
    fun `holding an id the server has disowned is blocked too`() {
        // A device deleted or revoked by the depot. The tablet still has a real, server-assigned id
        // -- which is exactly why this needs its own case; by id alone it looks perfectly paired.
        assertTrue(
            DeviceReadiness.ReadinessCheck.Registered in
                blockedChecks(inputs(deviceId = "device-1", deviceRejected = true)),
        )
    }

    @Test
    fun `a paired tablet with no connectivity at all is NOT blocked`() {
        // The single most important case in this file. Offline is not unregistered, and a driver in
        // a basement carpark must still be able to start their shift. Every check that could
        // possibly be unknown is unknown here.
        val offline = inputs(
            heartbeatSucceeding = false,
            offlineMapsPresent = null,
            signedTariffCached = null,
        )

        assertTrue(DeviceReadiness.blockingFailures(offline).isEmpty())
    }

    // --- forced update --------------------------------------------------------------------

    @Test
    fun `the force-update flag alone does not block`() {
        // force_update_pending latches server-side with no client-side clear (see
        // ForceUpdatePendingBanner's doc). An admin pressing that button when no build has been
        // published must not brick the tablet -- there would be nothing for the driver to install.
        assertTrue(
            DeviceReadiness.blockingFailures(
                inputs(forceUpdatePending = true, updateAvailable = false),
            ).isEmpty(),
        )
    }

    @Test
    fun `an available update alone does not block either`() {
        // A newer build existing is not the same as the depot requiring it. Drivers are not
        // stopped mid-roster by a routine release.
        assertTrue(
            DeviceReadiness.blockingFailures(
                inputs(forceUpdatePending = false, updateAvailable = true),
            ).isEmpty(),
        )
    }

    @Test
    fun `the depot requiring an update that actually exists does block`() {
        assertEquals(
            setOf(DeviceReadiness.ReadinessCheck.UpToDate),
            blockedChecks(inputs(forceUpdatePending = true, updateAvailable = true)),
        )
    }

    // --- advisories ------------------------------------------------------------------------

    @Test
    fun `maps, tariff, location and heartbeat never block, however bad they look`() {
        val everythingAdvisoryFailing = inputs(
            heartbeatSucceeding = false,
            offlineMapsPresent = false,
            signedTariffCached = false,
            locationPermissionGranted = false,
            hasLocationFix = false,
        )

        assertTrue(DeviceReadiness.blockingFailures(everythingAdvisoryFailing).isEmpty())

        val advisories = DeviceReadiness.evaluate(everythingAdvisoryFailing)
            .filter { !it.passed }
        assertEquals(4, advisories.size)
        assertTrue(advisories.all { it.severity == DeviceReadiness.Severity.ADVISORY })
    }

    // --- location -------------------------------------------------------------------------

    @Test
    fun `holding the location permission is not the same as having a fix`() {
        // The distinction a technician needs and nothing surfaced before. A tablet can hold the
        // permission and still never see a satellite -- a dead aerial, a faulty unit -- and that
        // tablet cannot charge a distance rate. Passing this check on the permission alone would
        // have signed off exactly the vehicle that then bills every trip at flagfall.
        val permittedButBlind = inputs(locationPermissionGranted = true, hasLocationFix = false)

        val row = DeviceReadiness.evaluate(permittedButBlind)
            .first { it.check == DeviceReadiness.ReadinessCheck.Location }

        assertFalse(row.passed)
        assertTrue(row.detail.contains("no GPS fix"))
    }

    @Test
    fun `a denied permission and a missing fix read differently`() {
        // They need different actions from the technician -- a dialog versus a walk outside -- so
        // the row must not collapse them into one message.
        fun detailFor(granted: Boolean?, fix: Boolean?) = DeviceReadiness
            .evaluate(inputs(locationPermissionGranted = granted, hasLocationFix = fix))
            .first { it.check == DeviceReadiness.ReadinessCheck.Location }
            .detail

        assertTrue(detailFor(false, false).contains("permission denied"))
        assertTrue(detailFor(true, false).contains("outside"))
        assertEquals("GPS fix acquired", detailFor(true, true))
        assertEquals("Not checked", detailFor(null, null))
    }

    @Test
    fun `location passes only when permission AND a real fix are both present`() {
        assertTrue(
            DeviceReadiness.evaluate(inputs(locationPermissionGranted = true, hasLocationFix = true))
                .first { it.check == DeviceReadiness.ReadinessCheck.Location }
                .passed,
        )
        for (combination in listOf(true to false, false to true, false to false)) {
            val (granted, fix) = combination
            assertFalse(
                "granted=$granted fix=$fix",
                DeviceReadiness.evaluate(inputs(locationPermissionGranted = granted, hasLocationFix = fix))
                    .first { it.check == DeviceReadiness.ReadinessCheck.Location }
                    .passed,
            )
        }
    }

    @Test
    fun `an unchecked probe is reported as unchecked, not as a failure`() {
        // "Not checked" and "failed" are different facts and the driver-facing text must say which
        // one it is -- a gate that cries wolf about a check it never ran teaches drivers to ignore
        // it.
        val unchecked = DeviceReadiness.evaluate(inputs(offlineMapsPresent = null))
            .first { it.check == DeviceReadiness.ReadinessCheck.OfflineMaps }

        assertFalse(unchecked.passed)
        assertEquals("Not checked", unchecked.detail)
    }

    // --- the whole set ----------------------------------------------------------------------

    @Test
    fun `a healthy tablet blocks on nothing and reports every check`() {
        val results = DeviceReadiness.evaluate(inputs())

        assertTrue(DeviceReadiness.blockingFailures(inputs()).isEmpty())
        assertTrue(results.all { it.passed })
        assertEquals(DeviceReadiness.ReadinessCheck.entries.size, results.size)
    }

    @Test
    fun `both blocking failures are reported together, not one at a time`() {
        // A driver holding an unregistered tablet that ALSO needs an update should see both, so
        // they fix them in one visit rather than clearing one and being stopped again.
        assertEquals(
            setOf(
                DeviceReadiness.ReadinessCheck.Registered,
                DeviceReadiness.ReadinessCheck.UpToDate,
            ),
            blockedChecks(
                inputs(deviceId = null, forceUpdatePending = true, updateAvailable = true),
            ),
        )
    }

    @Test
    fun `only registration and software are ever capable of blocking`() {
        // Pins the policy itself rather than one combination: if a future change marks another
        // check BLOCKING, this fails and forces the decision to be made deliberately.
        val blockingChecks = DeviceReadiness.evaluate(inputs())
            .filter { it.severity == DeviceReadiness.Severity.BLOCKING }
            .map { it.check }
            .toSet()

        assertEquals(
            setOf(
                DeviceReadiness.ReadinessCheck.Registered,
                DeviceReadiness.ReadinessCheck.UpToDate,
            ),
            blockingChecks,
        )
    }
}
