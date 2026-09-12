package au.com.threesixty.cabdispatch.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One GPS blackout the meter observed and billed (or deliberately did not bill) during a trip —
 * the audit trail G3/G1 of the GPS-blackout optimisation program (2026-09-12,
 * `docs/plans/2026-09-12-android-meter-optimisation-and-gps-blackout-plan.md` §1.2) calls for.
 *
 * Emitted by [au.com.threesixty.cabdispatch.domain.FareEngineImpl.tick]'s existing blackout
 * detection (the `blackoutEntryFix`/`blackoutEntryWasMoving` state that already drives the
 * known-corridor catch-up — see that class's own doc) via a `BlackoutEvent` the fare engine
 * publishes on `Started`/`Ended`, persisted here by
 * [au.com.threesixty.cabdispatch.data.repository.TripRepository] the same way an ordinary tick is.
 *
 * This is evidence, not a billing input: nothing reads this table back to decide what to charge —
 * the fare engine already made that decision in real time and folded it into the trip's ordinary
 * `tolls`/`movingS`/`waitingS`/`distanceM` totals. This table exists so a disputed fare — "why did
 * the meter keep running/stop running through that tunnel" — has an on-device, then server-synced
 * ([TripSyncItemDto.gpsBlackoutSegments]), timestamped record of exactly what the meter believed
 * and why, instead of forcing a reconstruction from the raw GPS trace after the fact.
 *
 * One row per blackout, written twice: once when [endedAtIso] is still null (the blackout is
 * still open — lets [au.com.threesixty.cabdispatch.domain.MeterController.restoreOpenTripIfAny]
 * re-seed the fare engine's in-memory blackout state after a process death mid-tunnel, rather than
 * silently losing the pending known-corridor catch-up), and again when it closes.
 */
@Entity(
    tableName = "trip_blackout_segments",
    indices = [Index("tripClientUuid")],
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["clientUuid"],
            childColumns = ["tripClientUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TripBlackoutSegmentEntity(
    @PrimaryKey val clientUuid: String,
    val tripClientUuid: String,
    val startedAtIso: String,
    /** Null while the blackout is still open — see class doc's "written twice". */
    val endedAtIso: String? = null,
    val entryLat: Double,
    val entryLng: Double,
    /** Null until reacquisition; see [endedAtIso]. */
    val exitLat: Double? = null,
    val exitLng: Double? = null,
    /** Whether the vehicle was moving (>= the tariff's speed threshold) at the instant signal was
     * lost — the same flag [au.com.threesixty.cabdispatch.domain.FareEngineImpl] uses to decide
     * whether this segment could ever qualify for known-corridor catch-up at all (a blackout that
     * began stationary bills waiting time throughout, never a corridor distance — see that
     * class's "known-corridor blackout catch-up" doc). */
    val entryWasMoving: Boolean,
    /** How this segment was resolved on reacquisition (or "STOPPED" for one that never needed
     * reacquisition because the driver pressed the meter's Stop control during it) —
     * [BlackoutResolution]'s own doc has the full list and what each one means for billing. Stored
     * as its enum name; Room has no first-class enum column type. */
    val resolution: String = BlackoutResolution.NONE.name,
    /** Real road distance billed for this segment, if any — the corridor length on a CORRIDOR
     * resolution, zero for NONE/STOPPED. Decimal-as-string, this project's universal money/
     * distance wire convention (see [TripEntity.tolls]'s own doc for why). */
    val billedDistanceKm: String = "0",
    /** The toll-registry road this segment's corridor match resolved to, when [resolution] is
     * CORRIDOR — null otherwise. */
    val corridorRoadId: String? = null,
    /** W2 (inertial dead-reckoning, 2026-09-12): the running total the inertial estimate billed
     * tick-by-tick, before [au.com.threesixty.cabdispatch.domain.location.inertial
     * .BlackoutReconciler]'s correction. Null for every resolution this column predates
     * (NONE/CORRIDOR/STATIONARY) and for a segment where inertial billing never engaged. Decimal-
     * as-string, same convention as [billedDistanceKm]. */
    val estimatedDistanceKm: String? = null,
    /** The road-path distance the reconciler matched this segment against, when
     * [referenceSource] is `"ROAD_PATH"` — null otherwise. */
    val referenceDistanceKm: String? = null,
    /** `billedDistanceKm - estimatedDistanceKm`, the signed correction applied at reacquisition.
     * Null for every non-INERTIAL resolution. */
    val correctionKm: String? = null,
    /** `"ROAD_PATH"` or `"CHORD_BOUNDED"` — [au.com.threesixty.cabdispatch.domain.location.inertial
     * .BlackoutReconciler.ReferenceSource] name. Null for every non-INERTIAL resolution. */
    val referenceSource: String? = null,
    /** The estimator's confidence tier at the point this segment resolved — null unless inertial
     * billing engaged for this segment (INERTIAL or UNCALIBRATED). */
    val confidence: String? = null,
    /** How many zero-velocity updates fired during this segment — null unless inertial billing
     * engaged. */
    val zuptCount: Int? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * How a [TripBlackoutSegmentEntity] was resolved — see that class's own doc for the full audit-
 * trail rationale. Referenced by name (Room string column), not the enum type itself.
 */
enum class BlackoutResolution {
    /** No known corridor matched on reacquisition, and the vehicle was moving at loss — the
     * conservative default: nothing was billed for the gap. */
    NONE,

    /** The blackout was fully explained by a real, mapped toll-road corridor between the entry
     * and exit fixes — [TripBlackoutSegmentEntity.billedDistanceKm] is that road's real length,
     * billed once through the ordinary distance-accrual path. */
    CORRIDOR,

    /** The vehicle was already stationary (below the tariff's speed threshold) when signal was
     * lost — waiting time accrued throughout, as it would have with a live fix. */
    STATIONARY,

    /** W2 (2026-09-12): billed tick-by-tick from the tablet's own calibrated inertial sensors for
     * at least part of this segment, reconciled at reacquisition against a road-path match or a
     * bounded straight-line chord — see [au.com.threesixty.cabdispatch.domain.location.inertial
     * .BlackoutReconciler]. Only ever reached when `BuildConfig.INERTIAL_BILLING_ENABLED` is on
     * (owner gate G3); off by default, in which case this segment resolves exactly as it always
     * has (NONE/CORRIDOR/STATIONARY). */
    INERTIAL,

    /** Inertial billing engaged for at least one tick of this segment, then the estimator itself
     * invalidated (calibration lost — e.g. the tablet was disturbed in its mount — or the error
     * budget exceeded [au.com.threesixty.cabdispatch.domain.location.inertial
     * .InertialSpeedEstimator]'s 120s UNRELIABLE bound) before reacquisition. Whatever was already
     * billed stands (it went through the ordinary distance/waiting accrual path, tick by tick, the
     * same as any other billing); no further reconciliation is attempted against a source this
     * untrustworthy, and the fallback W1 rule (nothing more billed if moving, waiting if
     * stationary) governs the rest of the segment. */
    UNCALIBRATED,
}
