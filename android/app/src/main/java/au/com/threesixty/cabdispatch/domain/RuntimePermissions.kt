package au.com.threesixty.cabdispatch.domain

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Reading and requesting the runtime permissions the meter needs — in one place.
 *
 * Two screens ask the same questions of the same OS: the technician's commissioning checklist
 * ([au.com.threesixty.cabdispatch.ui.screens.readiness.DeviceReadinessScreen]) and the launch
 * permissions checklist ([au.com.threesixty.cabdispatch.ui.screens.permissions.PermissionsChecklistScreen]).
 * They were drifting: the launch screen carried two cards that reported `granted = true`
 * unconditionally (Bluetooth and file storage — neither permission is even declared in the
 * manifest), and the readiness screen checked location alone, which meant its own "Scan QR" and
 * "Install update" buttons could silently do nothing on a tablet that had never been granted the
 * camera or the install-packages appop.
 *
 * Everything here reports what the OS actually says. Nothing returns a hardcoded `true`.
 *
 * ### The three shapes of "permission" this app needs
 * They are genuinely different mechanisms and cannot be requested the same way:
 *
 * 1. **Ordinary dangerous permissions** (location, camera, microphone, notifications) — a system
 *    dialog, requested together.
 * 2. **Background location** — must be asked for on its own, *after* foreground location is already
 *    granted; bundling it gets the whole request silently denied on Android 11+.
 * 3. **Special access** (install packages, battery-optimisation exemption) — not a dialog at all;
 *    they are Settings screens the user has to be sent to, and the answer only arrives back on
 *    resume.
 */
object RuntimePermissions {

    /** The manifest string(s) behind each [DeviceReadiness.MeterPermission], or null when the
     * permission is not a manifest permission at all (install-packages is an appop). */
    private fun manifestPermission(p: DeviceReadiness.MeterPermission): String? = when (p) {
        DeviceReadiness.MeterPermission.FineLocation -> Manifest.permission.ACCESS_FINE_LOCATION
        DeviceReadiness.MeterPermission.BackgroundLocation -> Manifest.permission.ACCESS_BACKGROUND_LOCATION
        DeviceReadiness.MeterPermission.Camera -> Manifest.permission.CAMERA
        DeviceReadiness.MeterPermission.Microphone -> Manifest.permission.RECORD_AUDIO
        DeviceReadiness.MeterPermission.Notifications ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.POST_NOTIFICATIONS
            } else {
                // Genuinely not a runtime permission before API 33 — notifications are granted at
                // install. Reported as granted below, which is the truth here rather than a
                // convenient default.
                null
            }
        DeviceReadiness.MeterPermission.InstallPackages -> null
    }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Whether the app may install an APK it has downloaded — the appop behind the readiness
     * screen's own "Install update" button, and the thing a Knox unknown-sources exception grants
     * (see docs/OTA_UPDATE_ROLLOUT.md §2). */
    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun isIgnoringBatteryOptimisations(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** The full grant state, for [DeviceReadiness.Inputs.permissions]. */
    fun snapshot(context: Context): Map<DeviceReadiness.MeterPermission, Boolean> =
        DeviceReadiness.MeterPermission.entries.associateWith { p ->
            when (p) {
                DeviceReadiness.MeterPermission.InstallPackages -> canInstallPackages(context)
                else -> manifestPermission(p)
                    // A null manifest permission means the OS grants it without asking (see
                    // Notifications below API 33), which is a genuine pass, not an assumption.
                    ?.let { isGranted(context, it) } ?: true
            }
        }

    /**
     * The dangerous permissions to request in one system dialog.
     *
     * Background location is deliberately absent — see this object's doc; it must follow, alone,
     * once foreground location is held.
     */
    fun foregroundRequestArray(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    val backgroundLocationPermission: String = Manifest.permission.ACCESS_BACKGROUND_LOCATION

    /**
     * The Settings screen for a special-access permission, or null if [p] is an ordinary runtime
     * one. The caller starts it and re-reads on resume — there is no result callback.
     */
    fun settingsIntentFor(context: Context, p: DeviceReadiness.MeterPermission): Intent? = when (p) {
        DeviceReadiness.MeterPermission.InstallPackages ->
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        else -> null
    }

    /**
     * The battery-optimisation exemption screen.
     *
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` shows a direct allow/deny dialog but is
     * policy-flagged on Play; the per-app Settings list is the safe route and lands the technician
     * on the right screen either way.
     */
    fun batteryOptimisationIntent(context: Context): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .takeIf { it.resolveActivity(context.packageManager) != null }
            ?: Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
}
