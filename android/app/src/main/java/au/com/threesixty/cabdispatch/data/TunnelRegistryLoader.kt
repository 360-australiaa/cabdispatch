package au.com.threesixty.cabdispatch.data

import android.content.Context
import au.com.threesixty.cabdispatch.domain.location.tunnel.TunnelCorridor
import au.com.threesixty.cabdispatch.domain.location.tunnel.TunnelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Loads `assets/nsw_tunnels.json` into a [TunnelRegistry] -- see [TunnelCorridor]'s doc. */
class TunnelRegistryLoader(private val context: Context, private val assetName: String = ASSET_NAME) {

    suspend fun load(): TunnelRegistry = withContext(Dispatchers.IO) {
        runCatching { parse(context.assets.open(assetName).bufferedReader().use { it.readText() }) }
            .getOrDefault(TunnelRegistry(emptyList()))
    }

    companion object {
        const val ASSET_NAME = "nsw_tunnels.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): TunnelRegistry {
            val doc = json.decodeFromString(AssetDoc.serializer(), text)
            return TunnelRegistry(
                doc.tunnels.filter { it.points.size >= 2 }.map { row ->
                    TunnelCorridor(
                        id = row.id,
                        name = row.name,
                        roadId = row.road,
                        points = row.points.map { it[0] to it[1] },
                    )
                },
            )
        }
    }

    @Serializable
    private class AssetDoc(val tunnels: List<AssetRow> = emptyList())

    @Serializable
    private class AssetRow(
        val id: String,
        val name: String,
        val road: String? = null,
        @SerialName("pts") val points: List<List<Double>> = emptyList(),
    )
}
