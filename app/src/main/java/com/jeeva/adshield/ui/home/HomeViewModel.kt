package com.jeeva.adshield.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jeeva.adshield.core.detector.AppDetector
import com.jeeva.adshield.core.detector.TargetAppStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val appStatuses: Map<String, TargetAppStatus> = emptyMap(),
    val isLoading: Boolean = true,
)

/** ViewModel for the home dashboard; runs app detection on the IO dispatcher. */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val detector = AppDetector(application.packageManager)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** Re-scans all target apps and updates [uiState]. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val statuses = detector.detectAll()
            _uiState.update { it.copy(appStatuses = statuses, isLoading = false) }
        }
    }
}
