package com.droidcast

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class CastViewModel(application: Application) : AndroidViewModel(application) {
    val uiState: StateFlow<CastUiState> = CastSessionCoordinator.uiState
    val events: SharedFlow<CastActionEvent> = CastSessionCoordinator.events
    val logs: StateFlow<List<DiagnosticLogEntry>> = DroidLog.entries

    init {
        CastSessionCoordinator.initialize(application)
    }

    fun onManualAddressChanged(value: String) = CastSessionCoordinator.updateManualAddress(value)
    fun connectManual(explicitInput: String? = null) = CastSessionCoordinator.connectManualAddress(explicitInput)
    fun connectEndpoint(endpoint: DiscoveredEndpoint) = CastSessionCoordinator.connectToEndpoint(endpoint)
    fun refreshDiscovery() = CastSessionCoordinator.refreshDiscovery()
    fun selectReceiver(id: String) = CastSessionCoordinator.selectReceiver(id)
    fun setQualityPreset(preset: QualityPreset) = CastSessionCoordinator.setQualityPreset(preset)
    fun setIncludeSystemAudio(enabled: Boolean) = CastSessionCoordinator.setIncludeSystemAudio(enabled)
    fun startCasting() = CastSessionCoordinator.startCastingFlow()
    fun stopCasting() = CastSessionCoordinator.stopCasting()
    fun onMediaProjectionResult(resultCode: Int, data: Intent?) =
        CastSessionCoordinator.onMediaProjectionResult(resultCode, data)
    fun dismissError() = CastSessionCoordinator.dismissError()
    fun retryConnection() = CastSessionCoordinator.retryLastConnection()
}
