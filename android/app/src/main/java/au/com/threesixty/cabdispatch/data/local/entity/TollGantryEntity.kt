package au.com.threesixty.cabdispatch.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local cache of one real NSW toll gantry's coordinates (`GET /v1/toll-roads/{id}` — see
 * [au.com.threesixty.cabdispatch.sync.TollRegistryCache]). 141 rows total for the whole registry —
 * cheap to hold entirely in Room and load wholesale once per trip
 * ([au.com.threesixty.cabdispatch.sync.TollRegistryCache.snapshot]), same "no spatial index, just
 * haversine over a small table" trade-off the backend's own `find_nearby_gantries` accepts.
 *
 * [id] is the real dataset's own `gantry_id` (already globally unique across all 141 rows — see
 * `app.models.toll.TollGantry.id`'s own doc), not a synthetic UUID.
 */
@Entity(
    tableName = "toll_gantries",
    indices = [Index("tollRoadId")],
    foreignKeys = [
        ForeignKey(
            entity = TollRoadEntity::class,
            parentColumns = ["id"],
            childColumns = ["tollRoadId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TollGantryEntity(
    @PrimaryKey val id: String,
    val tollRoadId: String,
    val latitude: Double,
    val longitude: Double,
    val fetchedAt: Long,
)
