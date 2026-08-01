package au.com.threesixty.cabdispatch.ui.screens.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
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

    fun login() {
        val state = _uiState.value
        if (state.driverIdInput.isBlank() || state.pinInput.isBlank()) {
            _uiState.update { it.copy(loginError = "Enter driver ID and PIN") }
            return
        }
        _uiState.update { it.copy(isLoggingIn = true, loginError = null) }
        viewModelScope.launch {
            val result = driverAuthRepository.login(state.driverIdInput.trim(), state.pinInput)
            result.onSuccess { user ->
                _uiState.update {
                    it.copy(
                        isLoggingIn = false,
                        loggedInDriverName = user.name,
                        loggedInDriverId = user.id,
                        step = LoginStep.VEHICLE_BIND,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isLoggingIn = false, loginError = error.message ?: "Login failed — check driver ID/PIN")
                }
            }
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
