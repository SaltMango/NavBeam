package io.github.saltmango.navbeam.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.github.saltmango.navbeam.data.SettingsPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionState { Connected, Searching, Offline }
enum class ThemeMode { On, Off, Auto }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val _conn = MutableStateFlow(ConnectionState.Connected)
    val conn = _conn.asStateFlow()

    private val _themeMode = MutableStateFlow(
        try {
            ThemeMode.valueOf(SettingsPrefs.getTheme(app))
        } catch (e: Exception) {
            ThemeMode.On
        }
    )
    val themeMode = _themeMode.asStateFlow()

    fun setConn(state: ConnectionState) { _conn.value = state }
    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        SettingsPrefs.setTheme(getApplication(), mode.name)
    }
}
