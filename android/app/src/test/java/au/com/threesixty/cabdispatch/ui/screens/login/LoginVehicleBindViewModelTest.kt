package au.com.threesixty.cabdispatch.ui.screens.login

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import au.com.threesixty.cabdispatch.data.remote.ShiftConflictDetail
import au.com.threesixty.cabdispatch.data.remote.ShiftDto
import au.com.threesixty.cabdispatch.domain.DriverAuthRepository
import au.com.threesixty.cabdispatch.domain.DriverLoginResult
import au.com.threesixty.cabdispatch.domain.ShiftHandoverConflictException
import au.com.threesixty.cabdispatch.domain.ShiftRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the urgent handover-conflict fix's ViewModel half: a 409 from `POST /v1/shifts/start`
 * must become [LoginVehicleBindUiState.handoverConflict] (a real, actionable fork — confirm or
 * cancel), never dumped into the dead-end [LoginVehicleBindUiState.shiftError] text a driver
 * cannot do anything about.
 *
 * ### Why Robolectric
 * [LoginVehicleBindViewModel] is an [androidx.lifecycle.AndroidViewModel] that reads
 * `getApplication<Application>().contentResolver` inside [LoginVehicleBindViewModel.startShift]
 * (for `ShiftStartDto.deviceAndroidId`) and constructs a `SharedPreferencesDriverAuthRepository`
 * eagerly in its own constructor — both need a real (or Robolectric-shadowed) [Application], not
 * just a value that type-checks. This is this codebase's first ViewModel test; the pattern
 * (`@RunWith(RobolectricTestRunner::class)`, `@Config(manifest = Config.NONE, sdk = [34],
 * application = Application::class)`) is copied verbatim from
 * [au.com.threesixty.cabdispatch.data.local.RoomMigrationTest], the project's only other
 * Robolectric test, including its reason for pinning `application = Application::class` (skip
 * the real `CabDispatchApp.onCreate()`, which touches the Mapbox SDK's native token setter —
 * unrelated to anything under test here).
 *
 * ### Why not drive `login()`/`bindVehicle()` for setup
 * Both make real network calls ([AppContainer.apiService]/`ApiVehicleUuidResolver`) this test has
 * no reason to fake just to reach the [LoginStep.INSPECTION] step — see
 * [LoginVehicleBindViewModel.seedReadyToStartShiftForTesting]'s own doc. [ShiftRepository] and the
 * unpaired-tablet check are injected directly (constructor params added alongside this fix,
 * defaulted to the real [AppContainer] wiring so every existing call site is unaffected).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
class LoginVehicleBindViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(shiftRepository: ShiftRepository): LoginVehicleBindViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        // isDeviceUnpairedForShift = { false }: this test is entirely about the handover-conflict
        // fork, not the separate unpaired-tablet gate startShift also checks. driverAuthRepository
        // is a throwing fake -- login() is a different, untested-here flow, and AppContainer.apiService
        // (the real class's own default for this param) has a `private set` a test cannot satisfy
        // without standing up the whole container.
        return LoginVehicleBindViewModel(application, shiftRepository, { false }, ThrowingDriverAuthRepository)
    }

    // ========================================================================
    // 409 conflict -> handoverConflict, not shiftError
    // ========================================================================

    @Test
    fun `a conflict 409 sets handoverConflict and not shiftError`() {
        val conflict = conflictDetail()
        val repo = FakeShiftRepository().apply { result = Result.failure(ShiftHandoverConflictException(conflict)) }
        val vm = viewModel(repo)
        vm.seedReadyToStartShiftForTesting(DRIVER_ID, "Driver One", VEHICLE_ID)

        vm.startShift(onShiftStarted = {})

        val state = vm.uiState.value
        assertEquals("the conflict must be surfaced as structured state", conflict, state.handoverConflict)
        assertNull("a real conflict must not also land in the generic error text", state.shiftError)
        assertFalse(state.isStartingShift)
    }

    @Test
    fun `a non-conflict failure still falls back to the plain shiftError message exactly as before`() {
        // Regression guard: this fix must not change behaviour for every OTHER kind of failure.
        val repo = FakeShiftRepository().apply { result = Result.failure(RuntimeException("HTTP 422 Conflict")) }
        val vm = viewModel(repo)
        vm.seedReadyToStartShiftForTesting(DRIVER_ID, "Driver One", VEHICLE_ID)

        vm.startShift(onShiftStarted = {})

        val state = vm.uiState.value
        assertEquals("HTTP 422 Conflict", state.shiftError)
        assertNull("a non-conflict failure must not fabricate a handover prompt", state.handoverConflict)
        assertFalse(state.isStartingShift)
    }

    // ========================================================================
    // confirm — retries with forceHandover=true, clears the conflict
    // ========================================================================

    @Test
    fun `confirming re-calls with forceHandover=true and clears the conflict on success`() {
        val conflict = conflictDetail()
        val repo = FakeShiftRepository().apply { result = Result.failure(ShiftHandoverConflictException(conflict)) }
        val vm = viewModel(repo)
        vm.seedReadyToStartShiftForTesting(DRIVER_ID, "Driver One", VEHICLE_ID)

        var started = false
        vm.startShift(onShiftStarted = { started = true })
        assertEquals(conflict, vm.uiState.value.handoverConflict)
        assertEquals(listOf(false), repo.forceHandoverRequests)

        repo.result = Result.success(shiftDto())
        vm.confirmHandoverAndStart()

        assertEquals(
            "the retry must carry force_handover=true",
            listOf(false, true),
            repo.forceHandoverRequests,
        )
        assertNull("a successful retry must clear the conflict", vm.uiState.value.handoverConflict)
        assertFalse(vm.uiState.value.isStartingShift)
        assertTrue("the deferred onShiftStarted callback must still fire once the retry succeeds", started)
    }

    @Test
    fun `confirming with nothing pending is a no-op`() {
        val repo = FakeShiftRepository()
        val vm = viewModel(repo)
        vm.seedReadyToStartShiftForTesting(DRIVER_ID, "Driver One", VEHICLE_ID)

        vm.confirmHandoverAndStart()

        assertTrue("there was no conflict to confirm, so the API must not be called", repo.forceHandoverRequests.isEmpty())
    }

    // ========================================================================
    // dismiss — backs out without calling the API
    // ========================================================================

    @Test
    fun `dismissing clears the conflict without calling the API again`() {
        val conflict = conflictDetail()
        val repo = FakeShiftRepository().apply { result = Result.failure(ShiftHandoverConflictException(conflict)) }
        val vm = viewModel(repo)
        vm.seedReadyToStartShiftForTesting(DRIVER_ID, "Driver One", VEHICLE_ID)

        vm.startShift(onShiftStarted = {})
        assertEquals(1, repo.forceHandoverRequests.size)
        assertEquals(conflict, vm.uiState.value.handoverConflict)

        vm.dismissHandoverConflict()

        assertNull("dismiss must clear the conflict", vm.uiState.value.handoverConflict)
        assertEquals("dismiss must not touch the API", 1, repo.forceHandoverRequests.size)

        // A stray confirm after dismissing has nothing left to retry.
        vm.confirmHandoverAndStart()
        assertEquals(1, repo.forceHandoverRequests.size)
    }

    // ========================================================================
    // A second tap while a request is in flight must not fire a second one
    // ========================================================================

    @Test
    fun `startShift is a no-op while a request is already in flight`() {
        val repo = FakeShiftRepository().apply { result = Result.success(shiftDto()) }
        val vm = viewModel(repo)
        // Seeds isStartingShift = true directly -- UnconfinedTestDispatcher runs the fake
        // repository's (non-suspending-in-practice) startShift to completion synchronously, so
        // there is no real window to race a second call against; this exercises the same
        // `if (state.isStartingShift) return` guard a genuine slow request would rely on.
        vm.seedReadyToStartShiftForTesting(DRIVER_ID, "Driver One", VEHICLE_ID, isStartingShift = true)

        vm.startShift(onShiftStarted = {})

        assertTrue("a request already in flight must suppress a second one", repo.forceHandoverRequests.isEmpty())
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun conflictDetail() = ShiftConflictDetail(
        message = "This vehicle already has an open shift for Jane Smith, started 2026-09-08T20:00:00Z. " +
            "Set force_handover=true to end their shift and start yours.",
        conflictingShiftId = "shift-99",
        conflictingDriverId = "driver-2",
        conflictingDriverName = "Jane Smith",
        conflictingShiftStartAt = "2026-09-08T20:00:00Z",
    )

    private fun shiftDto() = ShiftDto(
        id = "shift-1",
        tenantId = "tenant-1",
        driverId = DRIVER_ID,
        vehicleId = VEHICLE_ID,
        startAt = "2026-09-09T00:00:00Z",
        endAt = null,
        inspectionJson = emptyMap(),
        tripsCount = 0,
        kmTotal = "0",
        cashTotal = "0",
        cardTotal = "0",
        pslOwed = "0",
        reconciled = false,
        createdAt = "2026-09-09T00:00:00Z",
        updatedAt = "2026-09-09T00:00:00Z",
    )

    /** Records every `forceHandover` value [LoginVehicleBindViewModel] actually sent, in call
     * order — the thing [confirmHandoverAndStart]'s test above needs to check. */
    private class FakeShiftRepository : ShiftRepository {
        var result: Result<ShiftDto> = Result.failure(IllegalStateException("test did not configure a result"))
        val forceHandoverRequests = mutableListOf<Boolean>()

        override suspend fun startShift(
            driverId: String,
            vehicleId: String,
            inspection: Map<String, String>,
            deviceAndroidId: String?,
            forceHandover: Boolean,
        ): Result<ShiftDto> {
            forceHandoverRequests.add(forceHandover)
            return result
        }

        override suspend fun getShift(shiftId: String): Result<ShiftDto> =
            error("not exercised by this test")
    }

    /** login()/completeMfaLogin() are a separate flow this test never drives — see
     * [viewModel]'s own doc for why a throwing stub, not a real
     * `SharedPreferencesDriverAuthRepository`, is what every scenario here needs. */
    private object ThrowingDriverAuthRepository : DriverAuthRepository {
        override suspend fun login(driverId: String, pin: String): DriverLoginResult =
            error("not exercised by this test")

        override suspend fun completeMfaLogin(
            driverId: String,
            pin: String,
            mfaToken: String,
            code: String,
        ) = error("not exercised by this test")
    }

    private companion object {
        const val DRIVER_ID = "driver-1"
        const val VEHICLE_ID = "veh-1"
    }
}
