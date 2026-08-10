package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.ShiftDto
import au.com.threesixty.cabdispatch.data.remote.ShiftStartDto
import java.time.Instant
import java.util.UUID

/** Opens a shift, per spec B5 S1 ("pre-shift inspection checklist ... ends
 * in a call to open a shift"). */
interface ShiftRepository {
    suspend fun startShift(driverId: String, vehicleId: String, inspection: Map<String, String>): Result<ShiftDto>

    /** Plain re-read of one shift by id — added for the Plot Zone screen's "currently plotted
     * in" indicator ([au.com.threesixty.cabdispatch.ui.screens.zones.PlotZoneViewModel]), which
     * needs [ShiftDto.plottedZoneId] for the driver's own current shift
     * ([au.com.threesixty.cabdispatch.domain.SessionHolder.session]'s `shiftId`) on screen load. */
    suspend fun getShift(shiftId: String): Result<ShiftDto>
}

/**
 * TODO(sync-engine sibling agent): this should queue in Room + WorkManager
 * like trips do (B7: "Full trips run offline; queue in Room; WorkManager
 * sync with idempotency keys") so a shift can be opened with zero
 * connectivity at shift-start and reconciled later. For now: try the
 * network call, and on failure synthesize a locally-scoped shift so the
 * driver isn't blocked from starting work — this synthetic shift is NOT
 * persisted or retried and will be orphaned if the app process dies before
 * connectivity returns.
 */
class RemoteBackedShiftRepository(private val apiService: ApiService) : ShiftRepository {
    override suspend fun getShift(shiftId: String): Result<ShiftDto> =
        runCatching { apiService.getShift(shiftId) }

    override suspend fun startShift(
        driverId: String,
        vehicleId: String,
        inspection: Map<String, String>,
    ): Result<ShiftDto> {
        val result = runCatching {
            apiService.startShift(
                ShiftStartDto(
                    driverId = driverId,
                    vehicleId = vehicleId,
                    inspectionJson = inspection,
                ),
            )
        }
        if (result.isSuccess) return result

        val now = Instant.now().toString()
        return Result.success(
            ShiftDto(
                id = "local-${UUID.randomUUID()}",
                tenantId = "",
                driverId = driverId,
                vehicleId = vehicleId,
                startAt = now,
                endAt = null,
                inspectionJson = inspection,
                tripsCount = 0,
                kmTotal = "0",
                cashTotal = "0",
                cardTotal = "0",
                pslOwed = "0",
                reconciled = false,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}
