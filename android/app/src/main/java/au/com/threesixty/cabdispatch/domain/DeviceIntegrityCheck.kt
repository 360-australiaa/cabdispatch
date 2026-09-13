package au.com.threesixty.cabdispatch.domain

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Is this tablet's own operating system trustworthy enough to run a regulated taxi meter on?
 *
 * ### Why this exists (W8 release readiness, master plan P2.4)
 * [SecurePrefs]'s own doc already says the quiet part out loud: Android-Keystore-backed encrypted
 * storage "protects nothing at all once the device is rooted, unlocked with `adb` on a debug
 * build". A rooted tablet can read the bearer token, the device secret and the offline PIN hash
 * straight out of the Keystore-wrapped file (an app with root can ask the Keystore to decrypt on
 * its behalf, the same way this app does), can patch the running process to report a fabricated
 * GPS fix to [au.com.threesixty.cabdispatch.domain.location.RealLocationProvider] and bill a fare
 * for a trip that never happened, and can disable [SecurePrefs]'s own tamper-evidence entirely.
 * This is the check that tells a technician, and the depot, when that ground has shifted under a
 * given tablet.
 *
 * ### What this is NOT
 * This is not an exhaustive root-detection library (SafetyNet/Play Integrity, or a commercial
 * RASP SDK, do a great deal more: syscall hooking detection, Xposed/Frida fingerprinting, kernel
 * module scanning). Deliberately, per the plan's own instruction: pick a small number of
 * well-established, low-false-positive signals and say plainly what each one actually detects, not
 * chase every possible bypass. A determined attacker with root can defeat any client-side check by
 * definition (they control the process doing the checking) — this exists to catch the common,
 * un-hidden case (an off-the-shelf rooting tool, a development-config device pressed into service
 * as a field tablet by mistake), not to win an arms race with someone deliberately hiding root from
 * this specific app.
 *
 * ### The four signals, and exactly what each one detects
 * 1. **[Build.TAGS] contains `"test-keys"`.** A production/retail Android build is signed by the
 *    device manufacturer's own release key and reports `"release-keys"` here; `"test-keys"` means
 *    the running system image was signed with the public AOSP test key instead — i.e. this is a
 *    custom or unofficial ROM, not the manufacturer's shipped software. This is the same signal
 *    Google's own SafetyNet historically used as its first, cheapest check. False-negative: a
 *    custom ROM built by someone who re-signs with their own release key evades this; not every
 *    custom ROM is rooted either way, though almost none intended for a stock retail fleet exist.
 * 2. **A well-known root-management app is installed.** [PackageManager] is asked for a handful of
 *    package names that exist for exactly one purpose — managing an installed root grant — most
 *    prominently Magisk Manager (`com.topjohnwu.magisk`, the dominant modern systemless-root tool)
 *    and a couple of older SU managers. Finding one of these installed means the device owner
 *    *deliberately* rooted the device and is managing that root through the tool's own UI. False
 *    negative: a Magisk install can hide its own manager app / rename its package
 *    ("Magisk Hide"/"Zygisk DenyList") specifically to evade a check exactly like this one.
 * 3. **A root shell binary exists on the filesystem.** The standard `su` binary that a rooted
 *    device's root-granting layer installs is looked for at the handful of paths it has
 *    historically shipped at (`/system/bin/su`, `/system/xbin/su`, `/sbin/su`, and their
 *    `/system/sd/xbin` and `/data/local` cousins). Its mere presence on the filesystem, not
 *    an attempt to invoke it, is the signal — invoking `su` would itself require the very
 *    permission this check exists to flag as absent on a real, unrooted retail tablet. False
 *    negative: a modern systemless (Magisk) root does not necessarily place a bare `su` at any of
 *    these fixed paths, favouring its own bind-mount/overlay mechanism instead — signal 2 is this
 *    check's real coverage for that case.
 * 4. **ADB debugging is enabled AND the OS build itself is not a `"user"` build.** [Build.TYPE] is
 *    `"user"` on every retail device Google ships; `"userdebug"`/`"eng"` mark a development or
 *    engineering build variant with debug affordances (including looser SELinux enforcement in
 *    some configurations) that has no business running a fleet meter. Requiring
 *    [Settings.Global.ADB_ENABLED] on top, rather than the build type alone, keeps this from ever
 *    flagging a stock retail device on which a technician has legitimately turned on USB debugging
 *    for commissioning — that combination alone is common and NOT itself suspicious; it is the
 *    *build type* that is the real signal here, ADB is corroboration that someone is actively
 *    working the device at the shell level right now. False positive: vanishingly rare on a real
 *    manufacturer image, since `userdebug`/`eng` builds are not what OEMs ship to retail.
 *
 * [evaluate] treats ANY one of the four as "compromised" — this is a coarse yes/no gate, not a
 * graded score; [reasons] names exactly which signals fired so the readiness screen and a
 * technician reviewing a flagged tablet can see why, never just a bare pass/fail.
 *
 * Pure enough to unit-test the pieces that do not need a real `Context`/`PackageManager`
 * ([hasTestKeysBuildTag], [hasSuBinary] read only static [Build] fields / the filesystem) while
 * [evaluate] itself takes a [Context] for the two checks that genuinely need one — matching this
 * codebase's own [DeviceReadiness] convention of keeping policy decisions plain and taking Android
 * types only where a real platform answer is unavoidable.
 */
object DeviceIntegrityCheck {

    /** One well-known root/bootloader-unlock package. Only the manager app itself is checked —
     * this app never attempts to invoke `su` or otherwise use the grant. */
    private val KNOWN_ROOT_MANAGEMENT_PACKAGES = listOf(
        // Magisk Manager — the dominant modern systemless-root tool. Package name has shipped
        // under this identifier since Magisk's earliest public releases.
        "com.topjohnwu.magisk",
        // Older, largely superseded root managers, kept for coverage on an old/rarely-updated
        // device rather than because they are common today.
        "eu.chainfire.supersu",
        "com.noshufou.android.su",
        "com.koushikdutta.superuser",
    )

    /** Where a root-granting layer has historically installed the `su` binary. Presence alone is
     * the signal — this app never executes any file at these paths. */
    private val KNOWN_SU_BINARY_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/bin/failsafe/su",
        "/su/bin/su",
    )

    data class Result(val compromised: Boolean, val reasons: List<String>)

    fun evaluate(context: Context): Result {
        val reasons = buildList {
            if (hasTestKeysBuildTag()) add("System image signed with AOSP test-keys, not a release key")
            if (hasRootManagementApp(context.packageManager)) add("A root-management app is installed")
            if (hasSuBinary()) add("A root shell (su) binary is present on the filesystem")
            if (hasAdbEnabledOnNonUserBuild(context)) add("ADB debugging is enabled on a non-production OS build")
        }
        return Result(compromised = reasons.isNotEmpty(), reasons = reasons)
    }

    /** Signal 1 — see class doc. */
    internal fun hasTestKeysBuildTag(): Boolean = Build.TAGS?.contains("test-keys") == true

    /** Signal 2 — see class doc. `getPackageInfo` throwing [PackageManager.NameNotFoundException]
     * is the expected, common case (the package is simply not installed), not a real error. */
    internal fun hasRootManagementApp(packageManager: PackageManager): Boolean =
        KNOWN_ROOT_MANAGEMENT_PACKAGES.any { packageName ->
            try {
                @Suppress("DEPRECATION") // getPackageInfo(String, Int) is fine for this minSdk 29 floor.
                packageManager.getPackageInfo(packageName, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

    /** Signal 3 — see class doc. */
    internal fun hasSuBinary(): Boolean = KNOWN_SU_BINARY_PATHS.any { java.io.File(it).exists() }

    /** Signal 4 — see class doc. */
    internal fun hasAdbEnabledOnNonUserBuild(context: Context): Boolean {
        val adbEnabled = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (_: Settings.SettingNotFoundException) {
            false
        }
        return adbEnabled && Build.TYPE != "user"
    }
}
