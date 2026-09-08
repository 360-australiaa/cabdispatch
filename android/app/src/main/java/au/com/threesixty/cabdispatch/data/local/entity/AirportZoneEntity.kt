package au.com.threesixty.cabdispatch.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local cache of one `kind="airport"` geofence (`GET /v1/geofences?kind=airport` — see
 * [au.com.threesixty.cabdispatch.sync.AirportZoneCache]): a terminal taxi-rank circle plus the
 * access fee a hiring that STARTS inside it attracts. A handful of rows (three for Sydney
 * Airport's T1 / T2 / T3 ranks) — cheap to hold entirely in Room and in memory, same "no
 * spatial index, just haversine over a tiny table" trade-off [TollGantryEntity] documents.
 *
 * [id] is the backend's own geofence id. [feeAmount] is decimal-as-string, this project's
 * money convention (never `REAL`).
 */
@Entity(tableName = "airport_zones")
data class AirportZoneEntity(
    @PrimaryKey val id: String,
    val name: String,
    val centerLat: Double,
    val centerLng: Double,
    val radiusM: Double,
    val feeAmount: String,
    val fetchedAt: Long,
)
