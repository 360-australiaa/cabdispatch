package au.com.threesixty.cabdispatch.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local cache of one named toll point of a `per_point` toll road (`GET /v1/toll-roads` —
 * see [au.com.threesixty.cabdispatch.sync.TollRegistryCache]).
 *
 * Only three real roads have these — Hills M2 (6 points), Cross City Tunnel and Lane Cove Tunnel
 * (2 each) — but between them they carry the real prices for some of Sydney's busiest tolled
 * corridors, and those roads have no meaningful road-level price at all: the road's own min/max is
 * a descriptive range ACROSS its points, never what a crossing is charged. Without this table the
 * meter can detect an M2 crossing and still not know what it costs.
 *
 * Flattened to the CURRENT price only, exactly like [TollRoadEntity] — see that entity's own doc
 * for why a live meter never needs price history on-device.
 *
 * [id] is the registry's own natural key ("M2:north_ryde"), not a synthetic UUID.
 */
@Entity(
    tableName = "toll_points",
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
data class TollPointEntity(
    @PrimaryKey val id: String,
    val tollRoadId: String,
    val name: String,
    /** Current Class A price, decimal-as-string. `null` when the source data never resolved one —
     * such a point is FLAGGED for manual entry, never charged a guessed figure. */
    val priceClassA: String?,
    /** Current revision's `confidence`, or `null` if this point has no cached revision at all.
     * Both cases are treated identically by
     * [au.com.threesixty.cabdispatch.domain.fare.onFix]: never auto-charge. */
    val confidence: String?,
    val fetchedAt: Long,
)
