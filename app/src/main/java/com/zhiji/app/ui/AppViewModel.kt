package com.zhiji.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zhiji.app.ZhiJiApp
import com.zhiji.app.core.model.ThemeMode
import com.zhiji.app.data.datastore.SettingsDataStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true
)

class AppViewModel(
    private val settings: SettingsDataStore
) : ViewModel() {

    val appSettings: StateFlow<AppSettings> = kotlinx.coroutines.flow.combine(
        settings.themeMode,
        settings.dynamicColor
    ) { mode, dynamic ->
        AppSettings(mode, dynamic)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = ZhiJiApp.instance
                return AppViewModel(app.settingsDataStore) as T
            }
        }
    }
}
