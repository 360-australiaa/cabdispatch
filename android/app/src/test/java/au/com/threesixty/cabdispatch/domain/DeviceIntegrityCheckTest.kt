package au.com.threesixty.cabdispatch.domain

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * [DeviceIntegrityCheck] — Robolectric (a real [android.content.pm.PackageManager]/
 * `Settings.Global` need a real `Context`), same reasoning as `VehicleFrameCalibratorTest`'s own
 * identical setup in this module.
 *
 * [hasSuBinary] is exercised only for its "nothing found" branch: fabricating a file at one of the
 * fixed absolute system paths it checks (e.g. `/system/bin/su`) is neither possible nor desirable
 * from a JVM unit test, so the "found" branch is unverified here — the check itself is a plain
 * `File(path).exists()`, about as low-risk of logic error as this file gets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class DeviceIntegrityCheckTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    // --- hasTestKeysBuildTag ------------------------------------------------------------------

    @Test
    fun `no test-keys tag reports false`() {
        ReflectionHelpers.setStaticField(Build::class.java, "TAGS", "release-keys")
        assertFalse(DeviceIntegrityCheck.hasTestKeysBuildTag())
    }

    @Test
    fun `test-keys build tag is detected`() {
        ReflectionHelpers.setStaticField(Build::class.java, "TAGS", "test-keys,dev-keys")
        assertTrue(DeviceIntegrityCheck.hasTestKeysBuildTag())
    }

    // --- hasSuBinary ---------------------------------------------------------------------------

    @Test
    fun `no su binary on a plain test environment`() {
        assertFalse(DeviceIntegrityCheck.hasSuBinary())
    }

    // --- hasRootManagementApp ------------------------------------------------------------------

    @Test
    fun `no root manager installed reports false`() {
        assertFalse(DeviceIntegrityCheck.hasRootManagementApp(context.packageManager))
    }

    @Test
    fun `magisk manager installed is detected`() {
        val packageInfo = PackageInfo().apply {
            packageName = "com.topjohnwu.magisk"
            applicationInfo = ApplicationInfo().apply { packageName = "com.topjohnwu.magisk" }
        }
        shadowOf(context.packageManager).installPackage(packageInfo)

        assertTrue(DeviceIntegrityCheck.hasRootManagementApp(context.packageManager))
    }

    // --- hasAdbEnabledOnNonUserBuild -----------------------------------------------------------

    @Test
    fun `adb disabled never flags regardless of build type`() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
        ReflectionHelpers.setStaticField(Build::class.java, "TYPE", "userdebug")
        assertFalse(DeviceIntegrityCheck.hasAdbEnabledOnNonUserBuild(context))
    }

    @Test
    fun `adb enabled on a production user build does not flag`() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
        ReflectionHelpers.setStaticField(Build::class.java, "TYPE", "user")
        assertFalse(DeviceIntegrityCheck.hasAdbEnabledOnNonUserBuild(context))
    }

    @Test
    fun `adb enabled on a non-production build flags`() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
        ReflectionHelpers.setStaticField(Build::class.java, "TYPE", "userdebug")
        assertTrue(DeviceIntegrityCheck.hasAdbEnabledOnNonUserBuild(context))
    }

    // --- evaluate() --------------------------------------------------------------------------

    @Test
    fun `evaluate is clean when every signal is clean`() {
        ReflectionHelpers.setStaticField(Build::class.java, "TAGS", "release-keys")
        ReflectionHelpers.setStaticField(Build::class.java, "TYPE", "user")
        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)

        val result = DeviceIntegrityCheck.evaluate(context)

        assertFalse(result.compromised)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun `evaluate names every signal that fired`() {
        ReflectionHelpers.setStaticField(Build::class.java, "TAGS", "test-keys")
        ReflectionHelpers.setStaticField(Build::class.java, "TYPE", "userdebug")
        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)

        val result = DeviceIntegrityCheck.evaluate(context)

        assertTrue(result.compromised)
        assertEquals(2, result.reasons.size)
    }
}
