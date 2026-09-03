package au.com.threesixty.cabdispatch.ui.screens.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.ComplianceDossierDto
import au.com.threesixty.cabdispatch.data.remote.UserDto
import au.com.threesixty.cabdispatch.data.remote.VehicleDto
import au.com.threesixty.cabdispatch.domain.SessionHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface ComplianceUiState {
    data object Loading : ComplianceUiState
    data class Error(val message: String) : ComplianceUiState
    data class Loaded(val dossier: ComplianceDossierDto) : ComplianceUiState
}

/** Row 20/21's new companion state (2026-08-10 driver-photo pass) - the Profile identity header's
 * circular avatar. [NoPhoto] covers both "genuinely never uploaded one" and "the GET 404'd/failed"
 * identically, same as [ComplianceUiState.Error]'s own fallback-to-placeholder philosophy elsewhere
 * on this screen: a driver looking at their own profile should never see a broken-image icon or a
 * crash, only the initials avatar it would have shown before this pass existed. */
sealed interface ProfilePhotoUiState {
    data object NoPhoto : ProfilePhotoUiState
    data object Loading : ProfilePhotoUiState
    data class Loaded(val bitmap: Bitmap) : ProfilePhotoUiState
}

/**
 * Row 20 - Profile > Compliance (spec section 8: "Compliance vault ... matching the dashboard's
 * own Compliance Vault concept from the backend, just read-only on this device"). Reads the real
 * `GET /v1/compliance/vehicles/{vehicleId}/dossier` cl.14-checklist summary - any authenticated
 * user may read it server-side (`backend/app/api/v1/compliance.py`: only upload/edit/delete are
 * role-gated to owner/admin/dispatcher), so this is a genuine live read, not a placeholder, as
 * long as [SessionHolder] has a bound vehicle.
 *
 * Deliberately a separate `ViewModel` from [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel]
 * rather than folded into it - [ProfileScreen] hosts both as independent tabs/sections and the
 * task instructions are explicit not to touch/duplicate `SettingsViewModel`'s own logic, only
 * relocate its screen behind this new entry point (see [ProfileScreen]'s doc).
 *
 * 2026-08-10 driver-photo pass: also owns the identity header's photo capture/upload/preview
 * state ([photoState]/[isUploadingPhoto]/[photoUploadError]) - kept on this same `ViewModel`
 * rather than a third one, since the photo avatar lives in [ProfileScreen]'s shared
 * `IdentityHeader`, above both tabs, not inside either [ComplianceSection] or the embedded
 * `SettingsScreen`. Uses [AppContainer.apiService] directly (no dedicated repository, same
 * shallow-network-only shape [loadCompliance] already uses below) - a photo upload is a single
 * fire-and-forget multipart call, not something that needs Room/offline-queue treatment like a
 * trip does.
 *
 * CORRECTION (Phase H, 2026-09-03, re-checked against `backend/app/api/v1/users.py`): the note
 * this doc used to carry here - that `POST /v1/users/{userId}/photo` is staff-role-gated and a
 * driver's own upload is expected to 403 - is no longer true of this codebase. That endpoint's
 * gate is now "self-or-staff" (`caller.id == user_id` OR owner/admin/dispatcher), specifically so
 * a driver can upload their own photo; see that endpoint's own doc for the fix. [uploadPhoto]
 * below still surfaces any failure honestly (it never assumes success), it's just not expected to
 * be a 403 for a normal driver account any more. This is also why [ProfileScreen] adds no separate
 * "EDIT PROFILE" button/action beyond the existing photo tap: `PATCH /v1/users/{id}` (name/phone/
 * email/etc.) stays owner/admin-only (`require_role("owner", "admin")`), so photo is the one field
 * a driver can genuinely self-edit today, and that affordance already exists.
 */
class ProfileViewModel : ViewModel() {

    private val _complianceState = MutableStateFlow<ComplianceUiState>(ComplianceUiState.Loading)
    val complianceState: StateFlow<ComplianceUiState> = _complianceState.asStateFlow()

    private val _photoState = MutableStateFlow<ProfilePhotoUiState>(ProfilePhotoUiState.Loading)
    val photoState: StateFlow<ProfilePhotoUiState> = _photoState.asStateFlow()

    private val _isUploadingPhoto = MutableStateFlow(false)
    val isUploadingPhoto: StateFlow<Boolean> = _isUploadingPhoto.asStateFlow()

    private val _photoUploadError = MutableStateFlow<String?>(null)
    val photoUploadError: StateFlow<String?> = _photoUploadError.asStateFlow()

    /**
     * Phase H (2026-09-03) additions — the signed-in driver's own [UserDto] (`GET /v1/auth/me`,
     * the same call [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s header
     * already makes for the VERIFIED badge) and their bound vehicle's full [VehicleDto] row
     * (`GET /v1/fleet/vehicles`, matched client-side — see [loadVehicleDetail]). Both back the
     * Identity card's make/model/phone/member-since rows and the Documents tab's real
     * Verified/Expiring soon/Expired status rows. `null` covers "not loaded yet" AND "the call
     * failed" identically — same fallback-to-honest-omission posture as [photoState]'s [ProfilePhotoUiState.NoPhoto]
     * above: the Identity card and Documents tab must render their existing "—"/omitted fallback
     * for a `null` value here, never a fabricated one.
     */
    private val _userDetail = MutableStateFlow<UserDto?>(null)
    val userDetail: StateFlow<UserDto?> = _userDetail.asStateFlow()

    private val _vehicleDetail = MutableStateFlow<VehicleDto?>(null)
    val vehicleDetail: StateFlow<VehicleDto?> = _vehicleDetail.asStateFlow()

    init {
        loadCompliance()
        loadPhoto()
        loadUserDetail()
        loadVehicleDetail()
    }

    /** `GET /v1/auth/me` — any authenticated user may read their own record, no role gate. A
     * network/decode failure lands on `null`, same "degrade to omission" contract [userDetail]'s
     * own doc describes. */
    private fun loadUserDetail() {
        viewModelScope.launch {
            _userDetail.value = runCatching { AppContainer.apiService.me() }.getOrNull()
        }
    }

    /**
     * Resolves this device's bound vehicle to its full [VehicleDto] row by fetching
     * `GET /v1/fleet/vehicles` and matching client-side — the same page-then-match pattern
     * [au.com.threesixty.cabdispatch.ui.screens.login.LoginVehicleBindViewModel.bindVehicle]
     * already uses, see [au.com.threesixty.cabdispatch.data.remote.ApiService.listVehicles]'s own
     * doc. Prefers matching on [au.com.threesixty.cabdispatch.domain.DriverSession.vehicleUuid]
     * (the real fleet-vehicle id) when it's bound, falling back to a case-insensitive rego match
     * against [au.com.threesixty.cabdispatch.domain.DriverSession.vehicleId] for a session that
     * predates that field (or whose UUID lookup failed at bind time) — see that field's own doc.
     * No vehicle bound, or no match found, or the call fails: [vehicleDetail] stays `null`.
     */
    private fun loadVehicleDetail() {
        val session = SessionHolder.session.value ?: return
        viewModelScope.launch {
            val page = runCatching { AppContainer.apiService.listVehicles() }.getOrNull()
            val items = page?.items.orEmpty()
            _vehicleDetail.value = items.firstOrNull { it.id == session.vehicleUuid }
                ?: items.firstOrNull { it.rego.equals(session.vehicleId, ignoreCase = true) }
        }
    }

    private fun loadCompliance() {
        val vehicleId = SessionHolder.session.value?.vehicleId
        if (vehicleId == null) {
            _complianceState.value = ComplianceUiState.Error("No vehicle bound to this device yet.")
            return
        }
        _complianceState.value = ComplianceUiState.Loading
        viewModelScope.launch {
            val result = runCatching { AppContainer.apiService.getComplianceDossier(vehicleId) }
            result.onSuccess { dossier ->
                _complianceState.value = ComplianceUiState.Loaded(dossier)
            }.onFailure { error ->
                _complianceState.value = ComplianceUiState.Error(
                    error.message ?: "Could not load compliance status - check connection.",
                )
            }
        }
    }

    fun retry() = loadCompliance()

    /** Loads whatever photo (if any) is already on file for the signed-in driver
     * (`GET /v1/users/{userId}/photo`, no role gate on this side per `ApiService.getUserPhoto`'s
     * doc). A 404 (never uploaded, or the on-disk file is missing server-side) and any other
     * network failure both land on [ProfilePhotoUiState.NoPhoto] - deliberately not distinguished
     * in the UI, same reasoning [ComplianceSection]'s own fallback list already documents: a
     * driver offline mid-shift shouldn't see an error where a calm "no photo yet" placeholder
     * would do. */
    private fun loadPhoto() {
        val driverId = SessionHolder.session.value?.driverId
        if (driverId == null) {
            _photoState.value = ProfilePhotoUiState.NoPhoto
            return
        }
        _photoState.value = ProfilePhotoUiState.Loading
        viewModelScope.launch {
            val bitmap = runCatching {
                AppContainer.apiService.getUserPhoto(driverId).byteStream().use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()
            _photoState.value = if (bitmap != null) ProfilePhotoUiState.Loaded(bitmap) else ProfilePhotoUiState.NoPhoto
        }
    }

    /**
     * Uploads a newly captured/picked photo (JPEG bytes - [ProfileScreen] converts whatever
     * `ActivityResultContracts.TakePicturePreview`/`GetContent` returned into bytes before calling
     * this, so this class stays UI-toolkit-agnostic, same "ViewModel doesn't touch
     * Activity/Bitmap-decoding-from-Uri concerns" split every other screen in this module already
     * keeps). Multipart convention mirrors
     * [au.com.threesixty.cabdispatch.domain.RemoteBackedDuressRepository.uploadAudio] - the one
     * other multipart upload already in this codebase (`file.asRequestBody`/
     * `MultipartBody.Part.createFormData("file", ...)`), just built from an in-memory `ByteArray`
     * instead of a `File` since there's no on-disk temp file for a captured/picked image here.
     *
     * On success, decodes the exact bytes just uploaded straight back into the preview [Bitmap]
     * rather than re-fetching over the network - cheaper, and avoids a confusing moment where the
     * new photo doesn't show yet because a re-fetch raced/failed. On failure, [photoUploadError]
     * is set and [photoState] is left exactly as it was (never blanked/replaced by a failed
     * upload) - see this class's own CORRECTION doc note above: a genuine `driver`-role account
     * uploading their own photo is a legitimate self-or-staff-gated call now, not an expected 403.
     */
    fun uploadPhoto(bytes: ByteArray) {
        val driverId = SessionHolder.session.value?.driverId
        if (driverId == null) {
            _photoUploadError.value = "No driver signed in - cannot upload a photo yet."
            return
        }
        if (bytes.isEmpty()) {
            _photoUploadError.value = "Could not read that image - try again."
            return
        }
        _isUploadingPhoto.value = true
        _photoUploadError.value = null
        viewModelScope.launch {
            val result = runCatching {
                val body = bytes.toRequestBody("image/jpeg".toMediaType())
                val part = MultipartBody.Part.createFormData("file", "profile.jpg", body)
                AppContainer.apiService.uploadUserPhoto(driverId, part)
            }
            _isUploadingPhoto.value = false
            result.onSuccess {
                val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                if (bitmap != null) _photoState.value = ProfilePhotoUiState.Loaded(bitmap)
            }.onFailure { error ->
                // See ApiService.uploadUserPhoto's own doc - this app has no HttpException-specific
                // status-code handling anywhere yet (grepped project-wide), so a 403 from the
                // staff-role gate surfaces as this generic message rather than a tailored
                // "ask your dispatcher" one; the underlying cause is still real and worth fixing
                // server-side-contract-first, not silently swallowed here.
                _photoUploadError.value = error.message ?: "Could not upload photo - check connection."
            }
        }
    }

    /** Clears a shown [photoUploadError] once the driver has seen/dismissed it (e.g. picking a new
     * image after a failed attempt) - same "explicit dismiss, no auto-timeout" pattern the rest of
     * this module's error states already use (e.g. `LoginVehicleBindUiState.loginError`). */
    fun dismissPhotoError() {
        _photoUploadError.value = null
    }
}
