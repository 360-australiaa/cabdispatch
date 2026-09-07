package au.com.threesixty.cabdispatch.domain

import android.content.Context
import au.com.threesixty.cabdispatch.BuildConfig
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.remote.DeviceRegisterRequestDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/**
 * Pairing this tablet to a vehicle — the one implementation of it.
 *
 * There are now two places a driver can pair from: Settings ▸ About ▸ Pair Meter, and the
 * device-readiness gate that stands in front of the login screen
 * ([au.com.threesixty.cabdispatch.ui.screens.readiness.DeviceReadinessScreen]). They must behave
 * identically — same normalisation, same persistence, same error text — so the call lives here
 * rather than being copied into a second ViewModel.
 *
 * ### The secret
 * `POST /v1/fleet/devices/register` returns a `device_secret` exactly once, and this is the only
 * code that ever sees it. It goes straight into [DevicePairingStore], and from there into the
 * `X-Device-Secret` header [au.com.threesixty.cabdispatch.data.remote.ApiService.deviceHeartbeat]
 * has always sent. That header had no reader on the server and
 * [DevicePairingStore.saveDeviceSecret] had no caller, so the heartbeat quietly rode the driver's
 * bearer token instead — which is why a parked or logged-off tablet could never be located,
 * kiosk-locked or told to update. With a real secret stored, the heartbeat stands on its own.
 *
 * A device that pairs on an older server gets no secret back. That is not an error: the heartbeat
 * still accepts a bearer token, so such a tablet works exactly as it did before and picks up a
 * secret whenever it is next re-paired against an updated server.
 */
object DevicePairingRepository {

    /** What a pairing attempt produced. [vehicleId] is the vehicle this tablet is now the meter for. */
    sealed interface PairResult {
        data class Success(val vehicleId: String?) : PairResult
        data class Failure(val message: String) : PairResult
    }

    /**
     * Registers this physical tablet against [pairingCode] and persists everything the server hands
     * back. [pairingCode] is the 8-character code an operator generated for a specific vehicle
     * (`POST /v1/fleet/vehicles/{id}/pairing-code` on the dashboard side).
     *
     * Deliberately requires no driver session: the gate runs before anyone logs in, and the backend
     * route takes the pairing code itself as the credential for exactly that reason.
     */
    suspend fun pair(context: Context, pairingCode: String): PairResult {
        val normalized = pairingCode.trim().uppercase()
        if (normalized.isBlank()) return PairResult.Failure("Enter the pairing code first")

        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        )

        return runCatching {
            AppContainer.apiService.registerDevice(
                DeviceRegisterRequestDto(
                    androidId = androidId,
                    pairingCode = normalized,
                    model = android.os.Build.MODEL,
                    appVersion = BuildConfig.VERSION_NAME,
                ),
            )
        }.fold(
            onSuccess = { device ->
                SessionHolder.deviceId = device.id
                AppContainer.devicePairingStore.saveDeviceId(device.id)
                // Null on an older server; see this object's doc for why that is survivable.
                device.deviceSecret?.let { AppContainer.devicePairingStore.saveDeviceSecret(it) }
                PairResult.Success(device.vehicleId)
            },
            onFailure = { PairResult.Failure(errorMessage(it)) },
        )
    }

    /**
     * Extracts the backend's own `{"detail": "..."}` message off a Retrofit
     * [retrofit2.HttpException] — matching this API's error shape everywhere else — so the driver
     * reads the server's real explanation rather than a generic one this app invented. Falls back
     * per status code, and never throws.
     */
    fun errorMessage(error: Throwable): String {
        val http = error as? retrofit2.HttpException
            ?: return error.message ?: "Could not pair — check your connection and try again"
        val body = runCatching { http.response()?.errorBody()?.string() }.getOrNull()
        val detail = body?.let {
            runCatching { cabDispatchJson.decodeFromString<PairErrorDto>(it).detail }.getOrNull()
        }
        return detail ?: when (http.code()) {
            // The backend answers 400 for every bad-code case (not found, already used, expired);
            // 404/410 are kept as defensive fallbacks rather than removed, since this text is the
            // last thing standing between a driver and a blank error at 4am.
            400, 404, 410 -> "Invalid or expired code — check it and try again"
            409 -> "Cannot pair — this vehicle currently has an open shift"
            else -> "Could not pair (server said ${http.code()}) — try again"
        }
    }

    @Serializable
    private data class PairErrorDto(val detail: String? = null)
}
