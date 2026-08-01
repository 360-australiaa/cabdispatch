package au.com.threesixty.cabdispatch.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.ComplianceDossierDto
import au.com.threesixty.cabdispatch.domain.SessionHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ComplianceUiState {
    data object Loading : ComplianceUiState
    data class Error(val message: String) : ComplianceUiState
    data class Loaded(val dossier: ComplianceDossierDto) : ComplianceUiState
}

/**
 * Row 20 — Profile > Compliance (spec §8: "Compliance vault ... matching the dashboard's own
 * Compliance Vault concept from the backend, just read-only on this device"). Reads the real
 * `GET /v1/compliance/vehicles/{vehicleId}/dossier` cl.14-checklist summary — any authenticated
 * user may read it server-side (`backend/app/api/v1/compliance.py`: only upload/edit/delete are
 * role-gated to owner/admin/dispatcher), so this is a genuine live read, not a placeholder, as
 * long as [SessionHolder] has a bound vehicle.
 *
 * Deliberately a separate `ViewModel` from [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel]
 * rather than folded into it — [ProfileScreen] hosts both as independent tabs/sections and the
 * task instructions are explicit not to touch/duplicate `SettingsViewModel`'s own logic, only
 * relocate its screen behind this new entry point (see [ProfileScreen]'s doc).
 */
class ProfileViewModel : ViewModel() {

    private val _complianceState = MutableStateFlow<ComplianceUiState>(ComplianceUiState.Loading)
    val complianceState: StateFlow<ComplianceUiState> = _complianceState.asStateFlow()

    init {
        loadCompliance()
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
                    error.message ?: "Couldn't load compliance status — check connection.",
                )
            }
        }
    }

    fun retry() = loadCompliance()
}
