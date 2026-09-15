package au.com.threesixty.cabdispatch.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Device cache of one `GET /v1/toll-roads/price-pairs` row -- Linkt's own price for one entry
 * point -> exit point trip (the `entry_exit` pricing model, 2026-09-15 "copy all pricing from
 * Linkt"). Replaced wholesale on every registry refresh with the roads and gantries, see
 * [au.com.threesixty.cabdispatch.sync.TollRegistryCache]. No foreign key to `toll_gantries` on
 * purpose: both tables are cleared and refilled in one transaction and the pair is looked up by
 * id at detection time, never joined.
 *
 * [classABandsJson] is the `class_a_bands` list as received (`[{"day","interval","price"}]`),
 * decoded when the snapshot is built.
 */
@Entity(
    tableName = "toll_price_pairs",
    indices = [Index("entryGantryId")],
)
data class TollPricePairEntity(
    @PrimaryKey val id: String,
    val entryGantryId: String,
    val exitGantryId: String,
    val billingName: String,
    val classABandsJson: String,
    val fetchedAt: Long,
)
