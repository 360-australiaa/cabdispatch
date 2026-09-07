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
    /** Non-null only on a `per_point` road — which named toll point (and so which price) this
     * gantry charges. Deliberately NOT a Room `ForeignKey` to `toll_points`, unlike [tollRoadId]:
     * the registry is replaced wholesale in one transaction
     * ([au.com.threesixty.cabdispatch.data.local.dao.TollRegistryDao.replaceAll]), and a second FK
     * would only add another ordering constraint to that swap for a column whose dangling value
     * is already handled — [au.com.threesixty.cabdispatch.domain.fare.onFix] treats a toll point
     * it cannot resolve as unpriced, never as a charge. */
    val tollPointId: String?,
    val latitude: Double,
    val longitude: Double,
    val fetchedAt: Long,
)
