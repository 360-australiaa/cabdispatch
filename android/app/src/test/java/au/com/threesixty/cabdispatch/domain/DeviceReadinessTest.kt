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
        permissions: Map<DeviceReadiness.MeterPermission, Boolean> =
            DeviceReadiness.MeterPermission.entries.associateWith { true },
        batteryOptimisationExempt: Boolean? = true,
        kiosk: DeviceReadiness.KioskState? = DeviceReadiness.KioskState.Pinned,
        mapTokenPresent: Boolean? = true,
        tariffSigningKeyCached: Boolean? = true,
        vehicleClassDeclared: Boolean? = true,
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
        permissions = permissions,
        batteryOptimisationExempt = batteryOptimisationExempt,
        kiosk = kiosk,
        mapTokenPresent = mapTokenPresent,
        tariffSigningKeyCached = tariffSigningKeyCached,
        vehicleClassDeclared = vehicleClassDeclared,
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
            tariffSigningKeyCached = null,
            permissions = emptyMap(),
            batteryOptimisationExempt = null,
            kiosk = null,
            mapTokenPresent = null,
            vehicleClassDeclared = null,
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
    fun `every advisory can fail at once and still block nobody`() {
        // The whole point of the severity split. A tablet in the worst state the advisories can
        // describe -- no permissions, no maps, no tariff, no GPS, not pinned, dozing, no map token,
        // undeclared -- still lets a driver work, because none of that is something they can fix at
        // 4am and none of it stops the meter charging correctly.
        val everythingAdvisoryFailing = inputs(
            heartbeatSucceeding = false,
            offlineMapsPresent = false,
            signedTariffCached = false,
            tariffSigningKeyCached = false,
            locationPermissionGranted = false,
            hasLocationFix = false,
            permissions = DeviceReadiness.MeterPermission.entries.associateWith { false },
            batteryOptimisationExempt = false,
            kiosk = DeviceReadiness.KioskState.DepotWantsItButNotPinned,
            mapTokenPresent = false,
            vehicleClassDeclared = false,
        )

        assertTrue(DeviceReadiness.blockingFailures(everythingAdvisoryFailing).isEmpty())

        val advisories = DeviceReadiness.evaluate(everythingAdvisoryFailing).filter { !it.passed }
        // Every check except the two blocking ones.
        assertEquals(
            DeviceReadiness.ReadinessCheck.entries.size - 2,
            advisories.size,
        )
        assertTrue(advisories.all { it.severity == DeviceReadiness.Severity.ADVISORY })
    }

    // --- permissions ----------------------------------------------------------------------

    @Test
    fun `missing permissions are named, not counted`() {
        // "2 missing" sends a technician hunting through Settings; naming them is something they
        // can act on without leaving the screen.
        val partial = inputs(
            permissions = mapOf(
                DeviceReadiness.MeterPermission.FineLocation to true,
                DeviceReadiness.MeterPermission.BackgroundLocation to true,
                DeviceReadiness.MeterPermission.Camera to false,
                DeviceReadiness.MeterPermission.Microphone to true,
                DeviceReadiness.MeterPermission.Notifications to true,
                DeviceReadiness.MeterPermission.InstallPackages to false,
            ),
        )

        val row = DeviceReadiness.evaluate(partial)
            .first { it.check == DeviceReadiness.ReadinessCheck.Permissions }

        assertFalse(row.passed)
        assertTrue(row.detail.contains("Camera"))
        assertTrue(row.detail.contains("Install updates"))
    }

    @Test
    fun `critical permissions are listed before optional ones`() {
        // A technician fixes the top of the list first, so the two that stop the meter measuring
        // distance must not sit below the one that scans QR codes.
        val missing = DeviceReadiness.missingPermissions(
            inputs(permissions = DeviceReadiness.MeterPermission.entries.associateWith { false }),
        )

        val firstOptional = missing.indexOfFirst { !it.critical }
        val lastCritical = missing.indexOfLast { it.critical }
        assertTrue(lastCritical < firstOptional)
    }

    @Test
    fun `a permission nobody has looked at is not a failure`() {
        val row = DeviceReadiness.evaluate(inputs(permissions = emptyMap()))
            .first { it.check == DeviceReadiness.ReadinessCheck.Permissions }

        assertFalse(row.passed)
        assertEquals("Not checked", row.detail)
    }

    // --- kiosk ----------------------------------------------------------------------------

    @Test
    fun `kiosk passes when pinned, when DPC-locked, and when the depot never asked`() {
        // NotRequested passes deliberately: a depot that has not asked for kiosk mode has not
        // misconfigured anything, and a row that cried about it would teach technicians to ignore
        // the whole checklist.
        for (state in listOf(
            DeviceReadiness.KioskState.Pinned,
            DeviceReadiness.KioskState.DpcLocked,
            DeviceReadiness.KioskState.NotRequested,
        )) {
            assertTrue(
                state.name,
                DeviceReadiness.evaluate(inputs(kiosk = state))
                    .first { it.check == DeviceReadiness.ReadinessCheck.Kiosk }
                    .passed,
            )
        }
    }

    @Test
    fun `a tablet the depot flagged but the OS never pinned is the one kiosk failure`() {
        // The silent misconfiguration this row exists for: the depot believes the driver cannot
        // leave the meter, and they can.
        val row = DeviceReadiness.evaluate(
            inputs(kiosk = DeviceReadiness.KioskState.DepotWantsItButNotPinned),
        ).first { it.check == DeviceReadiness.ReadinessCheck.Kiosk }

        assertFalse(row.passed)
        assertTrue(row.detail, row.detail.contains("pinned"))
        assertTrue(row.detail, row.detail.contains("tap to pin it"))
    }

    /**
     * The honesty rule the A7 copy pass added: this app holds no Device Owner provisioning, so
     * everything it can do itself is Android screen pinning — escapable, and gone after a reboot.
     * Only [DeviceReadiness.KioskState.DpcLocked], a lock this app can observe but never cause, is
     * allowed to use the word "kiosk". A technician reading "Kiosk lock" against a merely pinned
     * tablet signs off something stronger than what is running.
     */
    @Test
    fun `only a real DPC lock is allowed to call itself a kiosk lock`() {
        fun detailFor(state: DeviceReadiness.KioskState) = DeviceReadiness.evaluate(inputs(kiosk = state))
            .first { it.check == DeviceReadiness.ReadinessCheck.Kiosk }
            .detail

        for (state in listOf(
            DeviceReadiness.KioskState.Pinned,
            DeviceReadiness.KioskState.DepotWantsItButNotPinned,
            DeviceReadiness.KioskState.NotRequested,
        )) {
            val detail = detailFor(state)
            assertFalse("$state: $detail", detail.contains("kiosk", ignoreCase = true))
        }

        assertTrue(detailFor(DeviceReadiness.KioskState.DpcLocked).contains("Kiosk-locked"))
    }

    /**
     * And the limits are stated, not merely implied. A pinned tablet's row must say both true
     * things about screen pinning: a driver can escape it, and a reboot clears it.
     */
    @Test
    fun `the pinned row states that it is escapable and does not survive a reboot`() {
        val detail = DeviceReadiness.evaluate(inputs(kiosk = DeviceReadiness.KioskState.Pinned))
            .first { it.check == DeviceReadiness.ReadinessCheck.Kiosk }
            .detail

        assertTrue(detail, detail.contains("unpin"))
        assertTrue(detail, detail.contains("reboot"))
    }

    // --- battery optimisation ---------------------------------------------------------------

    /**
     * A1 hoisted the fare engine into a foreground service, so Doze no longer stops a running
     * fare. This row used to assert exactly that as its consequence while carrying only ADVISORY
     * severity — a blocker-shaped claim on an advisory row, and, after A1, simply not true. It must
     * not overstate the risk in either direction: the fare is safe, the 60 s depot poll and the
     * Live Map publish (which run in the app process, not the service) are what get delayed.
     */
    @Test
    fun `the battery row no longer claims Doze can stop a running fare`() {
        val detail = DeviceReadiness.evaluate(inputs(batteryOptimisationExempt = false))
            .first { it.check == DeviceReadiness.ReadinessCheck.BatteryOptimisation }
            .detail

        assertFalse(detail, detail.contains("stop a running fare"))
        assertTrue(detail, detail.contains("foreground service"))
        assertTrue(detail, detail.contains("delayed"))
    }

    // --- signed tariff needs its verifying key --------------------------------------------

    @Test
    fun `a tariff without the key that verifies it does not pass`() {
        // Such a tablet cannot check the signature offline -- it would be charging prices it
        // cannot prove the depot signed.
        val row = DeviceReadiness.evaluate(
            inputs(signedTariffCached = true, tariffSigningKeyCached = false),
        ).first { it.check == DeviceReadiness.ReadinessCheck.SignedTariff }

        assertFalse(row.passed)
        assertTrue(row.detail.contains("key"))
    }

    // --- vehicle class --------------------------------------------------------------------

    @Test
    fun `vehicle class must be answered, and both answers count as answered`() {
        // "Not a maxi" is the common case and still has to have been DECIDED -- a 150% rate rides
        // on it, so an untouched default is not the same as a technician's "no".
        assertTrue(
            DeviceReadiness.evaluate(inputs(vehicleClassDeclared = true))
                .first { it.check == DeviceReadiness.ReadinessCheck.VehicleClass }
                .passed,
        )
        assertFalse(
            DeviceReadiness.evaluate(inputs(vehicleClassDeclared = false))
                .first { it.check == DeviceReadiness.ReadinessCheck.VehicleClass }
                .passed,
        )
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
