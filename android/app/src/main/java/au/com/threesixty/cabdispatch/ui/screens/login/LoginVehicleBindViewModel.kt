package au.com.threesixty.cabdispatch.ui.screens.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.UserDto
import au.com.threesixty.cabdispatch.domain.DevicePairingStatus
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
 * secret (committed to the repo, gated to debug builds only), but still gated to debug builds so
 * it never renders in a release build.
 *
 * **Fixed 2026-08-28 (was previously stale):** this used to hold the seeded demo driver's *email*
 * (`driver@lillycabs.test`), which 401s against `POST /v1/auth/driver-login` (that endpoint takes
 * `driver_code` + `pin`, not email + password) — confirmed live against the deployed server
 * (`72.61.107.107:8001`): `{"detail":"Invalid driver code or PIN"}`. Falling through to
 * [au.com.threesixty.cabdispatch.domain.SharedPreferencesDriverAuthRepository]'s offline cache
 * masked the failure in the UI (login "succeeded") but left [AppContainer.accessToken] unset, so
 * every authenticated call after — starting with the tariff fetch — 401'd too and the meter could
 * never actually start via this button. Replaced with `GL2HY` / `123456`, this tenant's real seeded
 * driver code, verified live the same day (`POST /v1/auth/driver-login` → `200 OK`, real access +
 * refresh tokens issued). If a fresh `backend/scripts/seed.py` run ever mints a different code,
 * update these two constants to match its stdout output.
 */
const val DEMO_DRIVER_ID = "GL2HY"
const val DEMO_DRIVER_PIN = "123456"

/**
 * Standard pre-shift check items, per spec B5 S1 ("pre-shift inspection
 * checklist form"). TODO(compliance agent): confirm this list against the
 * actual Compliance Dossier checklist template (spec Part C) — these are
 * placeholder items for now.
 */
val PRE_SHIFT_CHECKLIST_ITEMS = listOf(
    // Command Deck v2 checklist (Figma `h0PSsXQ971dOJvt25tN7BA` node `10:111`, 3×3 grid) —
    // keys are free-form as far as the backend is concerned (`POST /v1/shifts/start` accepts
    // inspection_json as an arbitrary map), so the set follows the design's nine cards.
    "tyres" to "Tyres & wheels",
    "lights" to "Lights & indicators",
    "brakes" to "Brakes",
    "meter_tablet" to "Meter tablet",
    "duress" to "Duress button",
    "interior" to "Interior",
    "cameras" to "Cameras / safety equipment",
    "fare_card" to "Fare schedule card",
    "first_aid" to "First-aid & extinguisher",
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
    /** Real fleet-vehicle UUID for [boundVehicleId], resolved in the background by [LoginVehicleBindViewModel.bindVehicle]
     * — see [au.com.threesixty.cabdispatch.domain.DriverSession.vehicleUuid]'s own doc for why
     * this is a separate field and what `null` means here (still resolving, offline, or no match;
     * [LoginVehicleBindViewModel.startShift] does not block on it either way). */
    val resolvedVehicleUuid: String? = null,
    val qrScanAttempted: Boolean = false,
    val checklist: Map<String, Boolean> = PRE_SHIFT_CHECKLIST_ITEMS.associate { it.first to false },
    val isStartingShift: Boolean = false,
    val shiftError: String? = null,
    /** Set from [au.com.threesixty.cabdispatch.data.remote.ShiftDto.deviceMismatchWarning] on a
     * successful [LoginVehicleBindViewModel.startShift] — the shift has ALREADY started at this
     * point (see that field's doc: server-side, advisory only), this is purely informational for
     * the driver. Non-null holds the [LoginStep.INSPECTION] screen on-screen with a dismissible
     * dialog (see [au.com.threesixty.cabdispatch.ui.screens.login.LoginVehicleBindScreen]) instead
     * of continuing straight on to [CabDispatchRoutes.SHIFT_START][au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes.SHIFT_START] —
     * [LoginVehicleBindViewModel.dismissDeviceMismatchWarning] does that once acknowledged. */
    val deviceMismatchWarning: String? = null,
    /**
     * Set the moment [LoginVehicleBindViewModel.bindVehicle] completes on a tablet
     * [DevicePairingStatus.isUnpaired] — i.e. neither the QR-scan nor the manual-rego path just
     * taken has ever registered this device (see [DevicePairingStatus]'s class doc: *neither* path
     * on this screen calls `POST /v1/fleet/devices/register`; both only resolve a rego to a
     * fleet-vehicle UUID). Surfaced as a single dismissible advisory
     * ([au.com.threesixty.cabdispatch.ui.screens.login.LoginVehicleBindScreen]'s
     * `UnpairedDeviceNoticeDialog`) at the one moment in onboarding a driver could actually act on
     * it, rather than only in Settings ▸ About two navigation levels deep. Deliberately advisory,
     * not blocking — see [startShift]'s doc for why pairing is not a hard prerequisite to earning.
     * [dismissUnpairedDeviceNotice] clears it; re-set on every subsequent [bindVehicle] call (e.g.
     * a driver binding again next shift) for as long as the tablet stays unpaired, so this cannot
     * be dismissed once and then forgotten permanently.
     */
    val showUnpairedDeviceNotice: Boolean = false,
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
     * `BuildConfig.DEBUG` gate in [LoginVehicleBindScreen]) — fills this tenant's real seeded
     * driver credentials and submits immediately, so testing on-device doesn't mean retyping the
     * same driver code/PIN every rebuild/reinstall.
     */
    fun quickLoginDemoDriver() {
        // TEMPORARY: seed the offline-login cache so `login()` below succeeds via its existing
        // network-failure fallback even when no backend is reachable at all (see
        // DriverAuthRepository.seedOfflineDemoDriver's doc). Remove once a reachable backend is
        // the normal dev/test setup.
        driverAuthRepository.seedOfflineDemoDriver(DEMO_DRIVER_ID, DEMO_DRIVER_PIN)
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

    fun scanQr(activity: android.app.Activity) {
        viewModelScope.launch {
            val code = AppContainer.qrScanner.scan(activity)
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
        _uiState.update {
            it.copy(
                boundVehicleId = vehicleId,
                resolvedVehicleUuid = null,
                step = LoginStep.INSPECTION,
                // Neither path into this method (QR scan or manual rego, see this screen's own
                // doc) ever registers this tablet as a Device — see DevicePairingStatus's class
                // doc — so this is the one honest moment in onboarding to say so, before the
                // driver is three screens further in and still has no idea.
                showUnpairedDeviceNotice = DevicePairingStatus.isUnpaired(SessionHolder.deviceId),
            )
        }
        // Resolve rego -> real fleet-vehicle UUID in the background (see DriverSession.vehicleUuid's
        // doc for why). Deliberately does not block the bind flow's transition to INSPECTION above —
        // same "don't stall the driver on a background lookup" posture the rest of this app's
        // best-effort network calls already use; a failed/offline lookup just leaves
        // resolvedVehicleUuid null, and startShift() below reads whatever is in state at that point.
        viewModelScope.launch {
            runCatching { AppContainer.apiService.listVehicles() }
                .onSuccess { page ->
                    val match = page.items.firstOrNull { it.rego.equals(vehicleId, ignoreCase = true) }
                    if (match != null) _uiState.update { it.copy(resolvedVehicleUuid = match.id) }
                }
        }
    }

    fun toggleChecklistItem(key: String) {
        _uiState.update { it.copy(checklist = it.checklist + (key to !(it.checklist[key] ?: false))) }
    }

    /** Acknowledges [LoginVehicleBindUiState.showUnpairedDeviceNotice] — a one-off dismiss for this
     * viewing, not a permanent "don't tell me again": see that field's own doc on why it is re-set
     * on every future [bindVehicle] call for as long as the tablet actually stays unpaired. */
    fun dismissUnpairedDeviceNotice() = _uiState.update { it.copy(showUnpairedDeviceNotice = false) }

    /** Non-null only for the brief window between a successful [startShift] that carried a
     * [LoginVehicleBindUiState.deviceMismatchWarning] and the driver dismissing that dialog —
     * see [dismissDeviceMismatchWarning]. The shift itself is already open by the time this is
     * ever set; this only defers the SAME [onShiftStarted] navigation callback [startShift] was
     * given, so the driver sees the warning before moving on to the shift-start screen. */
    private var pendingOnShiftStarted: (() -> Unit)? = null

    /**
     * ### This DOES now block on [DevicePairingStatus.isUnpaired] (2026-09-08 policy change)
     * The long note that used to live here argued the other way, and its reasoning was sound at the
     * time: registering needed a pairing code only an admin could mint AND a driver session to
     * spend it with, so blocking here would have meant "your ability to earn today depends on
     * whether a depot admin is awake" with no way forward from the tablet. That was rejected, and
     * rightly.
     *
     * Both halves of that premise are now gone. The fleet owner has set the policy — a tablet must
     * be registered before it is used — and the mechanism to satisfy it exists: registration is the
     * gate in front of the login screen ([au.com.threesixty.cabdispatch.domain.DeviceReadiness]),
     * and `POST /v1/fleet/devices/register` no longer requires a bearer token, so a driver can pair
     * the tablet themselves from a code read out over the phone before they have signed in at all.
     *
     * This check is the SECOND evaluation point, not the first, and it exists for one narrow case:
     * the tablet passed the gate at cold start and then lost its registration during the two
     * minutes of login, vehicle bind and inspection — an admin deleting or revoking the device
     * mid-onboarding. Without it, that tablet would start a shift the gate had just been added to
     * prevent.
     *
     * What it deliberately does NOT do is interrupt anyone already working. There is no equivalent
     * check on the running meter or at Close & Pay: an open shift and a running fare always finish,
     * because a driver with a passenger in the car must never be locked out of their own meter over
     * a pairing flag. See [au.com.threesixty.cabdispatch.ui.navigation.postAuthDestination], which
     * returns IDLE for an existing session before it consults the gate at all.
     */
    fun startShift(onShiftStarted: () -> Unit) {
        val state = _uiState.value
        val driverId = state.loggedInDriverId ?: return
        val vehicleId = state.boundVehicleId ?: return
        if (!state.allChecklistItemsChecked) {
            _uiState.update { it.copy(shiftError = "Complete every checklist item before starting the shift") }
            return
        }
        val command = AppContainer.deviceCommandHeartbeat.state.value
        if (DevicePairingStatus.isUnpaired(command.deviceId, command.deviceRejected)) {
            // Names a fix the driver can actually carry out on their own: restarting the meter
            // lands them on the readiness gate, which takes a pairing code. An error that only
            // said "not registered" would be a dead end at the last screen before earning.
            _uiState.update {
                it.copy(
                    shiftError = "This tablet is no longer registered with the depot. " +
                        "Close and reopen the meter to enter a pairing code.",
                )
            }
            return
        }
        _uiState.update { it.copy(isStartingShift = true, shiftError = null) }
        viewModelScope.launch {
            val inspectionJson = state.checklist.mapValues { if (it.value) "ok" else "fail" }
            // Send the real fleet-vehicle UUID when the background rego->UUID lookup has resolved
            // (2026-08-28): shifts previously always sent the rego while the position heartbeat
            // sent the UUID — mixed identifiers on the same shift's vehicle. The backend now also
            // canonicalizes rego->UUID server-side (services/shift.py), so the rego fallback here
            // stays safe for the offline/unresolved case.
            val vehicleIdForApi = _uiState.value.resolvedVehicleUuid ?: vehicleId
            // Read fresh, never persisted — same call
            // SettingsViewModel.submitPairingCode uses to register this tablet as a Device. Sent
            // purely so the backend can run its non-blocking device/vehicle mismatch check (see
            // ShiftStartDto.deviceAndroidId's doc); never used to gate starting the shift itself.
            val deviceAndroidId = android.provider.Settings.Secure.getString(
                getApplication<Application>().contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            )
            val result = AppContainer.shiftRepository.startShift(
                driverId,
                vehicleIdForApi,
                inspectionJson,
                deviceAndroidId,
            )
            result.onSuccess { shift ->
                SessionHolder.set(
                    DriverSession(
                        driverId = driverId,
                        driverName = state.loggedInDriverName ?: driverId,
                        vehicleId = vehicleId,
                        vehicleUuid = _uiState.value.resolvedVehicleUuid,
                        shiftId = shift.id,
                        // Feeds the dashboard's shift-duration countdown (2026-08-10 meter-polish
                        // pass) — see DriverSession.shiftStartAt's own doc.
                        shiftStartAt = shift.startAt,
                    ),
                )
                _uiState.update {
                    it.copy(isStartingShift = false, deviceMismatchWarning = shift.deviceMismatchWarning)
                }
                // The shift is already open at this point regardless — a mismatch warning only
                // holds the screen transition so the driver actually sees it (see
                // dismissDeviceMismatchWarning), it never holds up the shift itself.
                if (shift.deviceMismatchWarning == null) {
                    onShiftStarted()
                } else {
                    pendingOnShiftStarted = onShiftStarted
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isStartingShift = false, shiftError = error.message ?: "Could not start shift")
                }
            }
        }
    }

    /** Acknowledges [LoginVehicleBindUiState.deviceMismatchWarning] and proceeds with the
     * navigation [startShift] deferred while it was showing. */
    fun dismissDeviceMismatchWarning() {
        _uiState.update { it.copy(deviceMismatchWarning = null) }
        pendingOnShiftStarted?.invoke()
        pendingOnShiftStarted = null
    }
}
