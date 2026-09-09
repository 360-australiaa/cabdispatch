package au.com.threesixty.cabdispatch.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local cache of one live NSW traffic camera (`GET /v1/traffic/cameras` — see
 * [au.com.threesixty.cabdispatch.sync.TrafficCache]). Informational-only reference data for the
 * meter's own map ([au.com.threesixty.cabdispatch.ui.screens.hired.MeterBackdropMap]) — unlike
 * [au.com.threesixty.cabdispatch.data.local.entity.TollGantryEntity]/[AirportZoneEntity], nothing
 * here ever feeds the fare engine.
 *
 * [id] is the backend's own camera id (itself the source feed's natural GUID — see
 * `app.models.traffic.TrafficCamera`), so a re-fetch upserts the same row instead of duplicating.
 */
@Entity(tableName = "traffic_cameras")
data class TrafficCameraEntity(
    @PrimaryKey val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val direction: String?,
    val imageUrl: String?,
    val region: String?,
    val fetchedAt: Long,
)
