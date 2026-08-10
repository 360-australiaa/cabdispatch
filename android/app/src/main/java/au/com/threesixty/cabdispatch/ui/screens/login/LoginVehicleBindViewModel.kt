package au.com.threesixty.cabdispatch.ui.screens.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.UserDto
import au.com.threesixty.cabdispatch.domain.DriverLoginResult
import au.com.threesixty.cabdispatch.domain.DriverSession
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.SharedPreferencesDriverAuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoginStep { DRIVER_LOGIN, VEHICLE_BIND, INSPECTION }

/**
 * Debug-only quick-login convenience, see [LoginVehicleBindViewModel.quickLoginDemoDriver]. Not a
 * secret (the PIN is committed to the repo's own seed script), but still gated to debug builds so
 * it never renders in a release build.
 *
 * **Known stale-until-fixed gap:** [DEMO_DRIVER_ID] is the seeded demo driver's *email*
 * (`driver@lillycabs.test`), not its `driver_code` — now that [LoginVehicleBindViewModel.login]
 * calls the real `POST /v1/auth/driver-login` (`driver_code` + `pin`, not email + password), this
 * button will submit the email as a driver code and get a real 401 back. `backend/scripts/seed.py`
 * mints a random `driver_code` per fresh DB and only prints it to stdout at seed time (see that
 * script's final summary) — there is no fixed value to hardcode here. Whoever picks this up next:
 * either read the printed code off your own `uv run python scripts/seed.py` output and paste it in
 * for local testing, or extend seed.py to also write it to a well-known file/env var this constant
 * can read at debug-build time.
 */
const val DEMO_DRIVER_ID = "driver@lillycabs.test"
const val DEMO_DRIVER_PIN = "ChangeMe123!"

/**
 * Standard pre-shift check items, per spec B5 S1 ("pre-shift inspection
 * checklist form"). TODO(compliance agent): confirm this list against the
 * actual Compliance Dossier checklist template (spec Part C) — these are
 * placeholder items for now.
 */
val PRE_SHIFT_CHECKLIST_ITEMS = listOf(
    "tyres" to "Tyres & tread condition",
    "lights" to "Head/tail/indicator lights",
    "brakes" to "Brakes",
    "seatbelts" to "Seatbelts (all seats)",
    "meter_seal" to "Meter seal intact",
    "fire_extinguisher" to "Fire extinguisher present",
    "first_aid" to "First aid kit present",
    "cleanliness" to "Vehicle cleanliness",
    "id_displayed" to "Driver ID/photo displayed",
)

data class LoginVehicleBindUiState(
    val step: LoginStep = LoginStep.DRIVER_LOGIN,
    val driverIdInput: String = "",
    val pinInput: String = "",
    val isLoggingIn: Boolean = false,
    val loginError: String? = null,
    val loggedInDriverName: String? = null,
    val loggedInDriverId: String? = null,
    /** Non-null while awaiting the second MFA step ([LoginVehicleBindViewModel.verifyMfaCode]) —
     * see [au.com.threesixty.cabdispatch.domain.DriverLoginResult.MfaRequired]. Driver accounts
     * with MFA enabled are realistically possible (shared/API_SUMMARY.md documents the
     * driver-login endpoint returning this exact challenge), so this is a real path, not
     * speculative UI. */
    val mfaToken: String? = null,
    val mfaCodeInput: String = "",
    val vehicleIdInput: String = "",
    val boundVehicleId: String? = null,
    val qrScanAttempted: Boolean = false,
    val checklist: Map<String, Boolean> = PRE_SHIFT_CHECKLIST_ITEMS.associate { it.first to false },
    val isStartingShift: Boolean = false,
    val shiftError: String? = null,
) {
    val allChecklistItemsChecked: Boolean get() = checklist.values.all { it }
}

class LoginVehicleBindViewModel(application: Application) : AndroidViewModel(application) {

    private val driverAuthRepository = SharedPreferencesDriverAuthRepository(application, AppContainer.apiService)

    private val _uiState = MutableStateFlow(LoginVehicleBindUiState())
    val uiState: StateFlow<LoginVehicleBindUiState> = _uiState.asStateFlow()

    fun onDriverIdChanged(value: String) = _uiState.update { it.copy(driverIdInput = value, loginError = null) }
    fun onPinChanged(value: String) = _uiState.update { it.copy(pinInput = value, loginError = null) }
    fun onVehicleIdChanged(value: String) = _uiState.update { it.copy(vehicleIdInput = value) }

    /**
     * Debug-build convenience only (see [DEMO_DRIVER_ID]/[DEMO_DRIVER_PIN] and the button's
     * `BuildConfig.DEBUG` gate in [LoginVehicleBindScreen]) — fills the seeded demo driver's
     * credentials (`backend/scripts/seed.py`'s `driver@lillycabs.test`) and submits immediately,
     * so testing on-device doesn't mean retyping the same PIN every rebuild/reinstall.
     */
    fun quickLoginDemoDriver() {
        _uiState.update { it.copy(driverIdInput = DEMO_DRIVER_ID, pinInput = DEMO_DRIVER_PIN) }
        login()
    }

    fun login() {
        val state = _uiState.value
        if (state.driverIdInput.isBlank() || state.pinInput.isBlank()) {
            _uiState.update { it.copy(loginError = "Enter driver ID and PIN") }
            return
        }
        _uiState.update { it.copy(isLoggingIn = true, loginError = null) }
        viewModelScope.launch {
            when (val result = driverAuthRepository.login(state.driverIdInput.trim(), state.pinInput)) {
                is DriverLoginResult.Success -> onLoggedIn(result.user)
                is DriverLoginResult.MfaRequired -> _uiState.update {
                    it.copy(isLoggingIn = false, mfaToken = result.mfaToken, mfaCodeInput = "")
                }
                is DriverLoginResult.Failure -> _uiState.update {
                    it.copy(
                        isLoggingIn = false,
                        loginError = result.error.message ?: "Login failed — check driver ID/PIN",
                    )
                }
            }
        }
    }

    /**
     * Step two, only reachable once [login] has put [LoginVehicleBindUiState.mfaToken] into
     * state — the driver account has MFA (TOTP) enabled, see that field's doc.
     */
    fun onMfaCodeChanged(value: String) = _uiState.update { it.copy(mfaCodeInput = value, loginError = null) }

    /** Abandons the MFA challenge and returns to driver ID/PIN entry (e.g. wrong account). */
    fun cancelMfaChallenge() = _uiState.update { it.copy(mfaToken = null, mfaCodeInput = "", loginError = null) }

    fun verifyMfaCode() {
        val state = _uiState.value
        val mfaToken = state.mfaToken ?: return
        if (state.mfaCodeInput.isBlank()) {
            _uiState.update { it.copy(loginError = "Enter the 6-digit code") }
            return
        }
        _uiState.update { it.copy(isLoggingIn = true, loginError = null) }
        viewModelScope.launch {
            val result = driverAuthRepository.completeMfaLogin(
                driverId = state.driverIdInput.trim(),
                pin = state.pinInput,
                mfaToken = mfaToken,
                code = state.mfaCodeInput.trim(),
            )
            result.onSuccess(::onLoggedIn).onFailure { error ->
                _uiState.update {
                    it.copy(isLoggingIn = false, loginError = error.message ?: "Invalid or expired code")
                }
            }
        }
    }

    private fun onLoggedIn(user: UserDto) {
        _uiState.update {
            it.copy(
                isLoggingIn = false,
                loggedInDriverName = user.name,
                loggedInDriverId = user.id,
                mfaToken = null,
                mfaCodeInput = "",
                step = LoginStep.VEHICLE_BIND,
            )
        }
    }

    fun scanQr() {
        viewModelScope.launch {
            val code = AppContainer.qrScanner.scan()
            _uiState.update {
                it.copy(
                    qrScanAttempted = true,
                    vehicleIdInput = code ?: it.vehicleIdInput,
                )
            }
        }
    }

    fun bindVehicle() {
        val vehicleId = _uiState.value.vehicleIdInput.trim()
        if (vehicleId.isBlank()) return
        _uiState.update { it.copy(boundVehicleId = vehicleId, step = LoginStep.INSPECTION) }
    }

    fun toggleChecklistItem(key: String) {
        _uiState.update { it.copy(checklist = it.checklist + (key to !(it.checklist[key] ?: false))) }
    }

    fun startShift(onShiftStarted: () -> Unit) {
        val state = _uiState.value
        val driverId = state.loggedInDriverId ?: return
        val vehicleId = state.boundVehicleId ?: return
        if (!state.allChecklistItemsChecked) {
            _uiState.update { it.copy(shiftError = "Complete every checklist item before starting the shift") }
            return
        }
        _uiState.update { it.copy(isStartingShift = true, shiftError = null) }
        viewModelScope.launch {
            val inspectionJson = state.checklist.mapValues { if (it.value) "ok" else "fail" }
            val result = AppContainer.shiftRepository.startShift(driverId, vehicleId, inspectionJson)
            result.onSuccess { shift ->
                SessionHolder.set(
                    DriverSession(
                        driverId = driverId,
                        driverName = state.loggedInDriverName ?: driverId,
                        vehicleId = vehicleId,
                        shiftId = shift.id,
                        // Feeds the dashboard's shift-duration countdown (2026-08-10 meter-polish
                        // pass) — see DriverSession.shiftStartAt's own doc.
                        shiftStartAt = shift.startAt,
                    ),
                )
                _uiState.update { it.copy(isStartingShift = false) }
                onShiftStarted()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isStartingShift = false, shiftError = error.message ?: "Could not start shift")
                }
            }
        }
    }
}
