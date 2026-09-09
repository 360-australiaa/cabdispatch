package au.com.threesixty.cabdispatch.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local cache of one live NSW traffic hazard (`GET /v1/traffic/hazards&active_only=true` — see
 * [au.com.threesixty.cabdispatch.sync.TrafficCache]). Same "informational only, never a fare
 * input" status as [TrafficCameraEntity]. [id] is the backend's own hazard id (see
 * `app.models.traffic.TrafficHazard`'s doc for why the source feed's own ids are normalized to a
 * plain string there already).
 */
@Entity(tableName = "traffic_hazards")
data class TrafficHazardEntity(
    @PrimaryKey val id: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val headline: String?,
    val closureType: String?,
    val direction: String?,
    val speedLimit: Int?,
    val expectedDelayMinutes: Int?,
    val fetchedAt: Long,
)
