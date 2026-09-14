package au.com.threesixty.cabdispatch.data

import android.content.Context
import au.com.threesixty.cabdispatch.domain.SpeedCamera
import au.com.threesixty.cabdispatch.domain.SpeedCameraType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The on-device NSW speed-camera list -- see [SpeedCamera]'s doc for why it is a bundled asset.
 * Parsed once per process on first use (a ~90 KB JSON file, ~420 cameras; well under a
 * millisecond of work per fix afterwards since the advisor only ever scans a plain list). The
 * asset's compact key names are a deliberate size choice, decoded here into the readable domain
 * shape so nothing downstream has to know them.
 *
 * Refreshing the data is a build-time step: re-download TfNSW's `speed_cameras_data.{csv,geojson}`
 * (the exact source URL is recorded in the asset's own `source` field) and regenerate the file.
 */
class SpeedCameraRegistry(private val context: Context, private val assetName: String = ASSET_NAME) {

    @Volatile private var loaded: List<SpeedCamera>? = null
    private val loadLock = Mutex()

    /** Every camera, or an empty list if the asset is missing/corrupt (never a crash on the meter
     * screen for an advisory layer). Safe to call repeatedly; only the first call does the work. */
    suspend fun cameras(): List<SpeedCamera> {
        loaded?.let { return it }
        return loadLock.withLock {
            loaded ?: withContext(Dispatchers.IO) {
                runCatching { parse(context.assets.open(assetName).bufferedReader().use { it.readText() }) }
                    .getOrDefault(emptyList())
            }.also { loaded = it }
        }
    }

    companion object {
        const val ASSET_NAME = "nsw_speed_cameras.json"

        private val json = Json { ignoreUnknownKeys = true }

        /** Pure, so a JVM test can hold the real asset file to the same contract the app uses. */
        fun parse(text: String): List<SpeedCamera> =
            json.decodeFromString(AssetDoc.serializer(), text).cameras.map { row ->
                val path = row.path?.map { it[0] to it[1] }?.takeIf { it.size >= 2 }
                SpeedCamera(
                    id = row.id,
                    name = row.name,
                    type = if (row.type == "RLS") SpeedCameraType.RED_LIGHT_SPEED else SpeedCameraType.FIXED,
                    latitude = row.lat,
                    longitude = row.lng,
                    summary = row.summary?.takeIf { it.isNotBlank() },
                    schoolZone = row.schoolZone,
                    tunnel = row.tunnel,
                    path = path,
                )
            }
    }

    @Serializable
    private class AssetDoc(val cameras: List<AssetRow> = emptyList())

    // LongParameterList: a flat JSON row, one property per asset key -- a constructor is the shape.
    @Suppress("LongParameterList")
    @Serializable
    private class AssetRow(
        val id: Int,
        @SerialName("n") val name: String,
        @SerialName("t") val type: String,
        val lat: Double,
        val lng: Double,
        @SerialName("s") val summary: String? = null,
        @SerialName("sz") val schoolZone: Boolean = false,
        @SerialName("tu") val tunnel: Boolean = false,
        @SerialName("p") val path: List<List<Double>>? = null,
    )
}
