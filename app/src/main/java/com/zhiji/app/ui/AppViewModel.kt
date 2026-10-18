package com.zhiji.app.ui

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zhiji.app.ZhiJiApp
import com.zhiji.app.core.model.ThemeMode
import com.zhiji.app.data.backup.BackupManager
import com.zhiji.app.data.datastore.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** 自定义备份目录 Uri（空 = 使用默认 下载/Zhiji） */
    val backupTreeUri: String = "",
    /** 自定义备份目录可读名称 */
    val backupTreeLabel: String = ""
)

class AppViewModel(
    private val settings: SettingsDataStore
) : ViewModel() {

    val appSettings: StateFlow<AppSettings> = combine(
        settings.themeMode,
        settings.dynamicColor,
        settings.backupTreeUri,
        settings.backupTreeLabel
    ) { mode, dynamic, treeUri, treeLabel ->
        AppSettings(mode, dynamic, treeUri, treeLabel)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    /** 当前应用版本号（时间形式，用于设置页展示） */
    val versionName: String = runCatching {
        val pm = ZhiJiApp.instance.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(ZhiJiApp.instance.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ZhiJiApp.instance.packageName, 0)
        }
        info.versionName ?: ""
    }.getOrDefault("")

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }

    // ---- 备份目录 ----
    fun setBackupTree(uri: Uri) {
        viewModelScope.launch {
            val ctx: Context = ZhiJiApp.instance
            val label = runCatching { DocumentFile.fromTreeUri(ctx, uri)?.name }.getOrNull()
                ?: uri.lastPathSegment
                ?: "自定义目录"
            settings.setBackupTree(uri.toString(), label)
        }
    }

    fun clearBackupTree() {
        viewModelScope.launch { settings.clearBackupTree() }
    }

    /** 设置页展示的备份地址文案 */
    fun backupLocationLabel(s: AppSettings): String =
        if (s.backupTreeUri.isBlank()) BackupManager.DEFAULT_LOCATION_LABEL
        else s.backupTreeLabel.ifBlank { "自定义目录" }

    // ---- 备份 / 恢复 ----
    suspend fun backupManually(): BackupManager.BackupResult = withContext(Dispatchers.IO) {
        BackupManager.backup(ZhiJiApp.instance)
    }

    suspend fun restoreFromUri(uri: Uri): BackupManager.BackupResult =
        BackupManager.restore(ZhiJiApp.instance, uri)

    companion object {
        @Suppress("UNCHECKED_CAST")
        val Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = ZhiJiApp.instance
                return AppViewModel(app.settingsDataStore) as T
            }
        }
    }
}
