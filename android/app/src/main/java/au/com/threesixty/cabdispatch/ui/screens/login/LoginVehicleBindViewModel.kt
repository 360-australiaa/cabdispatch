package au.com.threesixty.cabdispatch.ui.screens.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.ShiftConflictDetail
import au.com.threesixty.cabdispatch.data.remote.UserDto
import au.com.threesixty.cabdispatch.domain.DevicePairingStatus
import au.com.threesixty.cabdispatch.domain.DriverAuthRepository
import au.com.threesixty.cabdispatch.domain.DriverLoginResult
import au.com.threesixty.cabdispatch.domain.DriverSession
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.ApiVehicleUuidResolver
import au.com.threesixty.cabdispatch.domain.SharedPreferencesDriverAuthRepository
import au.com.threesixty.cabdispatch.domain.ShiftHandoverConflictException
import au.com.threesixty.cabdispatch.domain.ShiftRepository
import au.com.threesixty.cabdispatch.sync.TariffRefresh
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoginStep { DRIVER_LOGIN, VEHICLE_BIND, INSPECTION }

/**
 * The pre-shift inspection items a driver ticks before a shift opens. **These are still design
 * placeholders, not a compliance-sourced list**, and the paragraphs below say exactly why, because
 * "TODO(compliance agent)" did not tell the next reader anything they could act on.
 *
 * ### Finding (A7, 2026-09-08): the reference spec does not specify these items
 * `docs/TCT-METER-01-spec.md` §A1 was checked line by line, and it does not contain a driver
 * pre-shift vehicle-inspection checklist. What it actually contains is a different thing wearing a
 * similar name:
 *
 * - **§A1 / NSW cl. 14 is about the METER**, not about the vehicle and not about a shift. Its
 *   six requirements are performance standards on the device — display the fare in numerals in
 *   AUD, calculate accurately at all times, be calibrated to the authorised fares, be resistant to
 *   tampering and vandalism, be securely fixed or in a commercially designed mounting, and be
 *   visible to all passengers (cl. 14(22)). Not one of them is something a driver ticks off each
 *   morning; they are properties of an installation.
 * - **The "cl.14 checklist" the spec names twice** (§B4.10 Compliance Vault: "calibration record,
 *   mounting photo, accuracy test result, cl.14 checklist"; launch DoD item 2: "cl.14 checklist
 *   satisfied per vehicle (mount, visibility, tamper measures)") is therefore a **per-vehicle
 *   commissioning artefact filed in the Compliance Vault once**, not a per-shift driver form.
 *   Reusing its three named items here would put "mount / visibility / tamper measures" in front
 *   of a driver every morning and still not be the safety record this screen implies.
 * - **§B5 S1 mentions "pre-shift inspection checklist" as a screen step and enumerates nothing.**
 *   `POST /v1/shifts/start` accepts `inspection_json` as an arbitrary map, so the backend imposes
 *   no vocabulary either — the shape is free and the *content* is the open question.
 *
 * The nine keys below come from the Command Deck v2 design (Figma `h0PSsXQ971dOJvt25tN7BA` node
 * `10:111`, a 3×3 grid) — a UI layout, chosen for a grid that looks right. They are plausible, and
 * plausible is the problem: this screen writes a dated, driver-attributed, server-persisted record
 * asserting a safety inspection happened, which is a regulated artefact. Inventing its contents
 * from a Figma frame would produce a record that reads as authoritative and is not, which is worse
 * than an obviously unfinished one.
 *
 * ### OWNER decision required before this list can be called compliant
 * Someone with the source documents has to name the items. The likely sources, none of which are
 * in this repo: TfNSW's Point to Point Transport (Taxis and Hire Vehicles) Regulation 2017 daily
 * vehicle-check requirements as they apply to the *operator's* Safety Management System; the
 * TCT SMS itself (`TCT-SMS-DA-01` is referenced by the spec but not included here); and the
 * operator's existing paper pre-shift sheet, if one is in use. Until then this list stays as it is
 * and is described as placeholder wherever it is described at all.
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
    /**
     * Set on a `POST /v1/shifts/start` 409: the vehicle just bound to already has another
     * driver's shift open on it (see [au.com.threesixty.cabdispatch.domain.ShiftHandoverConflictException]).
     * Unlike [shiftError] this is not a dead end — [LoginVehicleBindViewModel.confirmHandoverAndStart]
     * retries the same start with `force_handover = true` to end their shift and open this one, and
     * [LoginVehicleBindViewModel.dismissHandoverConflict] backs out to the checklist untouched (e.g.
     * the driver realises it's the wrong vehicle). Mirrors the dashboard's `StartShiftModal` conflict
     * state (`dashboard/src/pages/shifts/StartShiftModal.tsx`) so both surfaces behave identically.
     * While [isStartingShift] is also true, a confirm retry is in flight — see that field's doc.
     */
    val handoverConflict: ShiftConflictDetail? = null,
) {
    val allChecklistItemsChecked: Boolean get() = checklist.values.all { it }
}

class LoginVehicleBindViewModel @JvmOverloads constructor(
    application: Application,
    /** Injectable seam for [LoginVehicleBindViewModelTest] — production always gets
     * [AppContainer.shiftRepository], the same singleton every other call site uses.
     * [AppContainer.shiftRepository] is a `by lazy` bound to real Retrofit/Room dependencies, so
     * a test cannot swap what it resolves to after the first access anywhere in the process;
     * injecting it here instead lets a test supply a fake with no [AppContainer] setup at all.
     * `@JvmOverloads` keeps the single-arg `(Application)` constructor
     * [androidx.lifecycle.viewmodel.compose.viewModel]'s default factory looks up via reflection,
     * so every existing call site is unaffected. */
    private val shiftRepository: ShiftRepository = AppContainer.shiftRepository,
    /** Same reasoning as [shiftRepository]: [startShift] has always gated on
     * `DevicePairingStatus.isUnpaired(AppContainer.deviceCommandHeartbeat.state.value...)`, but
     * [AppContainer.deviceCommandHeartbeat] is a real device-command poll loop wired to a live
     * [au.com.threesixty.cabdispatch.data.remote.ApiService]/[android.content.Context] — nothing
     * a unit test should have to stand up just to answer one boolean. Production default is the
     * exact same check, unchanged. */
    private val isDeviceUnpairedForShift: () -> Boolean = {
        val command = AppContainer.deviceCommandHeartbeat.state.value
        DevicePairingStatus.isUnpaired(command.deviceId, command.deviceRejected)
    },
    /** Same reasoning again: [AppContainer.apiService] has a `private set` (only
     * [AppContainer.init] may assign it), which a test cannot do without standing up the whole
     * container — so the object built from it is injected instead of the property that feeds it. */
    private val driverAuthRepository: DriverAuthRepository = SharedPreferencesDriverAuthRepository(application, AppContainer.apiService),
) : AndroidViewModel(application) {

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
        // S5: login is the third tariff-refresh trigger (with reconnect and the periodic backstop).
        // It is the one moment we know the tablet is online AND about to start billing, so it is
        // the last chance to notice the depot changed the rates since this tablet last looked.
        // Best-effort and non-blocking — see [TariffRefresh]; a failure just means the existing
        // cached tariff stays in use, exactly as before.
        viewModelScope.launch { TariffRefresh.refreshBestEffort() }

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
            // Same resolver the heartbeat uses to REPAIR a binding that has gone stale
            // (`domain/VehicleBinding.kt`), rather than a second hand-rolled rego match here. Two
            // copies of "which row of the roster is this rego" is exactly how one of them ends up
            // trimming whitespace and the other not.
            ApiVehicleUuidResolver(AppContainer.apiService).resolve(vehicleId)
                ?.let { uuid -> _uiState.update { it.copy(resolvedVehicleUuid = uuid) } }
        }
    }

    fun toggleChecklistItem(key: String) {
        _uiState.update { it.copy(checklist = it.checklist + (key to !(it.checklist[key] ?: false))) }
    }

    /**
     * Test-only seam: puts the state [startShift] requires (a logged-in driver, a bound vehicle,
     * every checklist item ticked, on [LoginStep.INSPECTION]) directly, skipping [login]'s and
     * [bindVehicle]'s own real network calls — [LoginVehicleBindViewModelTest] exercises
     * [startShift]/[confirmHandoverAndStart]/[dismissHandoverConflict] in isolation and needs only
     * the fields those three read, not a full sign-on run. `internal` rather than `private`: AGP
     * compiles this module's `test` source set with a friend-path to `main`, so this is visible
     * there and nowhere outside the module.
     */
    internal fun seedReadyToStartShiftForTesting(
        driverId: String,
        driverName: String,
        vehicleId: String,
        isStartingShift: Boolean = false,
    ) {
        _uiState.update {
            it.copy(
                step = LoginStep.INSPECTION,
                loggedInDriverId = driverId,
                loggedInDriverName = driverName,
                boundVehicleId = vehicleId,
                checklist = it.checklist.mapValues { true },
                isStartingShift = isStartingShift,
            )
        }
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

    /** The [onShiftStarted] callback of the [startShift] call that most recently hit a
     * [LoginVehicleBindUiState.handoverConflict] — kept only so [confirmHandoverAndStart] can
     * re-run the identical attempt with `forceHandover = true` without the caller (the screen)
     * having to remember and re-pass it. Cleared by [dismissHandoverConflict] and on any
     * [startShift] outcome that isn't a fresh conflict. */
    private var pendingHandoverOnShiftStarted: (() -> Unit)? = null

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
    fun startShift(onShiftStarted: () -> Unit, forceHandover: Boolean = false) {
        val state = _uiState.value
        // Guards against a second tap firing a second request while one is already in flight —
        // this covers both the plain Continue button and the handover-conflict dialog's "End
        // their shift & start mine" button, which calls back in here via confirmHandoverAndStart.
        if (state.isStartingShift) return
        val driverId = state.loggedInDriverId ?: return
        val vehicleId = state.boundVehicleId ?: return
        if (!state.allChecklistItemsChecked) {
            _uiState.update { it.copy(shiftError = "Complete every checklist item before starting the shift") }
            return
        }
        if (isDeviceUnpairedForShift()) {
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
        // Kept regardless of forceHandover so a fresh conflict on the retry (someone else grabbed
        // the vehicle in the meantime) can still be re-armed for a further confirm.
        pendingHandoverOnShiftStarted = onShiftStarted
        // handoverConflict is deliberately left as-is here: on the forceHandover retry the dialog
        // stays visible showing "Ending their shift…" (isStartingShift) rather than disappearing
        // and reappearing, matching the dashboard's StartShiftModal footer-swap behaviour.
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
            val result = shiftRepository.startShift(
                driverId,
                vehicleIdForApi,
                inspectionJson,
                deviceAndroidId,
                forceHandover,
            )
            result.onSuccess { shift ->
                pendingHandoverOnShiftStarted = null
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
                    it.copy(
                        isStartingShift = false,
                        handoverConflict = null,
                        deviceMismatchWarning = shift.deviceMismatchWarning,
                    )
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
                if (error is ShiftHandoverConflictException) {
                    // A real, actionable refusal — not the generic dead-end message. Stays on the
                    // checklist screen with the confirm dialog showing; see
                    // LoginVehicleBindUiState.handoverConflict's own doc.
                    _uiState.update { it.copy(isStartingShift = false, handoverConflict = error.conflict) }
                } else {
                    _uiState.update {
                        it.copy(
                            isStartingShift = false,
                            handoverConflict = null,
                            shiftError = error.message ?: "Could not start shift",
                        )
                    }
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

    /** The driver has confirmed a real shift-changeover on [LoginVehicleBindUiState.handoverConflict]:
     * re-runs the exact same [startShift] attempt with `forceHandover = true`, which ends the
     * other driver's shift on this vehicle server-side and opens this one. No-ops if there is no
     * conflict in flight (e.g. a stray second tap after it already resolved). */
    fun confirmHandoverAndStart() {
        val onShiftStarted = pendingHandoverOnShiftStarted ?: return
        startShift(onShiftStarted, forceHandover = true)
    }

    /** Backs out of [LoginVehicleBindUiState.handoverConflict] without forcing anything — e.g. the
     * driver realises this is the wrong vehicle. Returns the driver to the checklist exactly as it
     * was; no request is sent. */
    fun dismissHandoverConflict() {
        _uiState.update { it.copy(handoverConflict = null) }
        pendingHandoverOnShiftStarted = null
    }
}
