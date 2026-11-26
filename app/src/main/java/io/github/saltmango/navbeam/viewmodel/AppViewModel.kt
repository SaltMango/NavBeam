package io.github.saltmango.navbeam.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.saltmango.navbeam.dash.profile.DashProfile
import io.github.saltmango.navbeam.dash.profile.ProfileFileImporter
import io.github.saltmango.navbeam.data.SettingsPrefs
import io.github.saltmango.navbeam.util.AppDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ConnectionState { Connected, Searching, Offline }
enum class ThemeMode { Light, Dark, System }

sealed interface IncomingProfileImportState {
    data object Idle : IncomingProfileImportState
    data class Loading(val sourceLabel: String) : IncomingProfileImportState
    data class Ready(val profile: DashProfile, val sourceLabel: String) : IncomingProfileImportState
    data class Error(val message: String, val sourceLabel: String) : IncomingProfileImportState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val _conn = MutableStateFlow(ConnectionState.Connected)
    val conn = _conn.asStateFlow()

    private val _themeMode = MutableStateFlow(
        try {
            ThemeMode.valueOf(SettingsPrefs.getTheme(app))
        } catch (e: Exception) {
            ThemeMode.System
        }
    )
    val themeMode = _themeMode.asStateFlow()

    private val _incomingProfileImport = MutableStateFlow<IncomingProfileImportState>(IncomingProfileImportState.Idle)
    val incomingProfileImport = _incomingProfileImport.asStateFlow()

    fun setConn(state: ConnectionState) { _conn.value = state }
    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        SettingsPrefs.setTheme(getApplication(), mode.name)
    }

    fun importProfileFromUri(uri: Uri) {
        val label = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "Shared profile"
        _incomingProfileImport.value = IncomingProfileImportState.Loading(label)
        viewModelScope.launch {
            val app = getApplication<Application>()
            val outcome = withContext(AppDispatchers.networking) {
                ProfileFileImporter.importFromUri(app.contentResolver, uri)
            }
            _incomingProfileImport.value = outcome.fold(
                onSuccess = { payload ->
                    IncomingProfileImportState.Ready(
                        profile = payload.profile,
                        sourceLabel = payload.sourceLabel,
                    )
                },
                onFailure = { err ->
                    IncomingProfileImportState.Error(
                        message = err.message ?: "Unable to read the selected profile file.",
                        sourceLabel = label,
                    )
                },
            )
        }
    }

    fun clearIncomingProfileImport() {
        _incomingProfileImport.value = IncomingProfileImportState.Idle
    }
}
