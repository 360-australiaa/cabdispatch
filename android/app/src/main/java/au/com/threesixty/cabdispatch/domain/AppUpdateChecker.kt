package au.com.threesixty.cabdispatch.domain

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import au.com.threesixty.cabdispatch.BuildConfig
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.LatestAppReleaseDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * State machine for [AppUpdateChecker]'s real (2026-09-06) self-update flow, driven entirely by
 * [ForceUpdatePendingBanner][au.com.threesixty.cabdispatch.ui.overlays.ForceUpdatePendingBanner].
 * Every state is a fact this process has actually established — nothing here is inferred or
 * assumed. See [AppUpdateChecker]'s class doc for the two real constraints (Knox Manage /
 * one-tap-confirmation) this state machine does NOT and cannot paper over.
 */
sealed interface AppUpdateState {
    /** No check has run yet this process (or [AppUpdateChecker] was just constructed). */
    data object Idle : AppUpdateState

    /** [AppUpdateChecker.checkForUpdate] is in flight. */
    data object Checking : AppUpdateState

    /** The server was reached and its highest active `version_code` is <= [BuildConfig.VERSION_CODE]
     * — genuinely nothing to update to, not a failure. */
    data object UpToDate : AppUpdateState

    /** A real newer build exists server-side. [release] is the exact `GET /v1/app-releases/latest`
     * answer — nothing here is guessed. */
    data class Available(val release: LatestAppReleaseDto) : AppUpdateState

    /** Streaming the APK to local storage. [percent] is `0-100` when the server sent a
     * `Content-Length`; this state simply does not update its percent field further when it
     * didn't (never a fabricated estimate). */
    data class Downloading(val percent: Int) : AppUpdateState

    /** The download finished; its SHA-256 is being compared against [LatestAppReleaseDto.sha256]
     * before this app will ever consider installing it. */
    data object Verifying : AppUpdateState

    /** Downloaded AND verified — [apkFile] is a real on-disk file whose SHA-256 matched
     * [release]'s. The system "install this app?" confirmation has NOT fired yet; that only
     * happens when [AppUpdateChecker.promptInstall] is called (the driver's own "INSTALL" tap) —
     * see that function's doc for why this is never automatic. */
    data class ReadyToInstall(val release: LatestAppReleaseDto, val apkFile: File) : AppUpdateState

    /** A real, user-facing reason this stopped — network error, non-2xx download response, a
     * SHA-256 mismatch, or the system installer refusing to launch. Never a generic "something
     * went wrong". */
    data class Failed(val message: String) : AppUpdateState
}

/**
 * Real Android self-update: checks `GET /v1/app-releases/latest`, downloads and SHA-256-verifies
 * the APK, and hands off to the standard Android package-install flow. Added 2026-09-06,
 * replacing the earlier "this app has no self-update path" state of the world (see
 * `docs/OTA_UPDATE_ROLLOUT.md` and [ForceUpdatePendingBanner][au.com.threesixty.cabdispatch.ui.overlays.ForceUpdatePendingBanner]'s
 * own doc, which drives every method here).
 *
 * ### Two real constraints this class does NOT and cannot paper over
 * 1. **Knox Manage.** These tablets are Samsung Knox Manage device-owner enrolments, and Knox
 *    Manage's current documented fleet policy blocks installs from unknown sources
 *    (`docs/KNOX_LOCKDOWN_RUNBOOK.md` §3.2). Until a Knox Manage admin console policy exception
 *    allowlists this app's package name for unknown-source installs (see
 *    `docs/OTA_UPDATE_ROLLOUT.md` — and confirm Knox Manage actually supports a *per-app*
 *    exception; that doc flags this as unconfirmed against Knox's own documentation), the system
 *    installer [promptInstall] launches will refuse the install on a real locked-down tablet. This
 *    class cannot detect that refusal ahead of time — the PackageInstaller UI itself reports it to
 *    the driver, not this app.
 * 2. **One system confirmation tap, always.** This app is not Android Device Owner, so it holds
 *    no `DevicePolicyManager`/silent-install authority — [promptInstall] always launches
 *    `Intent.ACTION_VIEW` against the APK and lets the OS's own "install this app?" dialog take
 *    over. There is no code path here, however this class is extended, that installs without that
 *    human-confirmed system prompt.
 *
 * ### Storage / verification
 * Downloads land under `context.getExternalFilesDir(null)/updates/` (app-private-external, no
 * runtime storage permission needed on this app's minSdk 29+) — never anywhere a [FileProvider]
 * wouldn't be required to expose them to the system installer, per Android's scoped-storage
 * install-package convention. The SHA-256 is computed while streaming (one pass, not a second
 * read-back) and compared against the server-supplied [LatestAppReleaseDto.sha256] — a mismatch
 * deletes the file and reports [AppUpdateState.Failed] rather than ever hitting [promptInstall].
 *
 * ### Auth
 * `GET /v1/app-releases/latest` and the download it points at both require a valid bearer token
 * (`get_current_user` server-side, same as most of this app's other endpoints) — [okHttpClient]'s
 * existing auth interceptor (see [au.com.threesixty.cabdispatch.data.AppContainer.authInterceptor])
 * attaches it automatically, exactly as it does for every other [apiService] call. Unlike
 * [DeviceCommandHeartbeat]'s heartbeat, there is no device-secret fallback for these two endpoints
 * — a parked/logged-off tablet with no bearer token in memory cannot check for or fetch an update
 * until a driver signs in online, same limitation [DeviceCommandHeartbeat]'s own doc describes for
 * the pre-device-secret heartbeat.
 */
class AppUpdateChecker(
    private val apiService: ApiService,
    private val okHttpClient: OkHttpClient,
    private val appContext: Context,
) {
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    /** Manual "check for updates" pull — also what
     * [ForceUpdatePendingBanner][au.com.threesixty.cabdispatch.ui.overlays.ForceUpdatePendingBanner]
     * calls the first time it appears, and what its "RETRY" action re-calls after a [AppUpdateState.Failed]. */
    suspend fun checkForUpdate() {
        _state.value = AppUpdateState.Checking
        val release = runCatching { apiService.latestAppRelease() }.getOrElse { error ->
            _state.value = AppUpdateState.Failed(error.message ?: "Could not reach the update server")
            return
        }
        _state.value = if (release.versionCode > BuildConfig.VERSION_CODE) {
            AppUpdateState.Available(release)
        } else {
            AppUpdateState.UpToDate
        }
    }

    /**
     * Downloads [release]'s APK (OkHttp streaming GET against
     * [au.com.threesixty.cabdispatch.data.remote.LatestAppReleaseDto.downloadUrl], resolved against
     * `BuildConfig.API_BASE_URL`), verifies its SHA-256, and — only once that matches — lands in
     * [AppUpdateState.ReadyToInstall]. Never calls [promptInstall] itself; that is always a separate,
     * later, explicit call driven by the driver's own tap (see this class's doc, point 2).
     */
    suspend fun downloadAndVerify(release: LatestAppReleaseDto) {
        _state.value = AppUpdateState.Downloading(0)

        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val url = BuildConfig.API_BASE_URL.trimEnd('/') + release.downloadUrl
                val request = Request.Builder().url(url).build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Download failed: HTTP ${response.code}")
                    }
                    val body = response.body ?: error("Empty download response")
                    val totalBytes = body.contentLength()

                    val updatesDir = File(appContext.getExternalFilesDir(null), "updates").apply {
                        mkdirs()
                    }
                    val apkFile = File(updatesDir, "cabdispatch-${release.versionCode}.apk")
                    val digest = MessageDigest.getInstance("SHA-256")

                    body.byteStream().use { input ->
                        apkFile.outputStream().use { output ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                            var readSoFar = 0L
                            var lastReportedPercent = -1
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                readSoFar += read
                                // Only report a real percent — never fabricated when the server
                                // omitted Content-Length (totalBytes <= 0).
                                if (totalBytes > 0) {
                                    val percent = ((readSoFar * 100) / totalBytes).toInt()
                                    if (percent != lastReportedPercent) {
                                        lastReportedPercent = percent
                                        _state.value = AppUpdateState.Downloading(percent)
                                    }
                                }
                            }
                        }
                    }

                    val sha256Hex = digest.digest().joinToString("") { byte ->
                        "%02x".format(byte.toInt() and 0xFF)
                    }
                    apkFile to sha256Hex
                }
            }
        }

        val (apkFile, sha256Hex) = outcome.getOrElse { error ->
            _state.value = AppUpdateState.Failed(error.message ?: "Download failed")
            return
        }

        _state.value = AppUpdateState.Verifying
        if (!sha256Hex.equals(release.sha256, ignoreCase = true)) {
            // Never install an unverified file — delete it and fail loudly rather than silently
            // falling back to installing anyway.
            apkFile.delete()
            _state.value = AppUpdateState.Failed(
                "Downloaded file failed integrity check (SHA-256 mismatch) — refusing to install it.",
            )
            return
        }

        _state.value = AppUpdateState.ReadyToInstall(release, apkFile)
    }

    /**
     * Launches the standard Android "install this app?" system confirmation for [apkFile] — the
     * one unavoidable human-confirmed step (see this class's doc, point 2). [activity] is required
     * (not [appContext]) because `Intent.ACTION_VIEW` against a `content://` APK Uri needs an
     * Activity context to resolve/launch reliably from within an app process, same
     * "caller supplies the current Activity" convention as [QrScanner.scan][QrScanner].
     *
     * Does not itself change [state] to anything beyond [AppUpdateState.Failed] on a launch
     * failure (e.g. no Package Installer available, or — per this class's doc point 1 — Knox
     * Manage's unknown-sources policy refusing the intent before any UI even appears) — a
     * successful hand-off leaves [state] at [AppUpdateState.ReadyToInstall] since this app has no
     * way to observe what the driver does inside the system installer next (accept, cancel, or the
     * OS silently refusing it).
     */
    fun promptInstall(activity: Activity, apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivity(intent) }.onFailure { error ->
            _state.value = AppUpdateState.Failed(
                "Could not start the system installer: ${error.message ?: "unknown error"}",
            )
        }
    }

    /** Resets back to [AppUpdateState.Idle] — not currently called anywhere (a driver's only path
     * back to a fresh check today is [checkForUpdate]'s own RETRY action, which overwrites
     * whatever state came before), kept for a future caller that wants to explicitly dismiss a
     * [AppUpdateState.Failed]/[AppUpdateState.UpToDate] state without immediately re-checking. */
    fun reset() {
        _state.value = AppUpdateState.Idle
    }

    private companion object {
        const val DOWNLOAD_BUFFER_BYTES = 8 * 1024
    }
}
