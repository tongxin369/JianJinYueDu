package com.zhiji.app.ui.feature.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiji.app.core.model.ThemeMode
import com.zhiji.app.data.backup.BackupManager
import com.zhiji.app.ui.AppViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「用户」板块：设置中心。
 *   - 外观：日夜模式切换（跟随系统 / 日间 / 夜间）+ 动态取色
 *   - 备份与恢复：自定义备份地址（SAF 目录）、立即备份（弹框提示地址等信息）、恢复备份
 *   - 关于：应用版本号
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSettingsScreen(
    appViewModel: AppViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by appViewModel.appSettings.collectAsStateWithLifecycle()

    var backupResult by remember { mutableStateOf<BackupManager.BackupResult?>(null) }

    // 选择自定义备份目录（SAF 目录选择器）
    val pickDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        appViewModel.setBackupTree(uri)
        Toast.makeText(context, "已设置自定义备份位置", Toast.LENGTH_SHORT).show()
    }

    // 恢复：选择任意位置的备份文件（.zbak / 旧版 .db）
    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = appViewModel.restoreFromUri(uri)
            Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("用户", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ===== 外观设置 =====
            SectionCard(title = "外观", icon = Icons.Filled.DarkMode) {
                Text(
                    "日夜模式",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip("跟随系统", ThemeMode.SYSTEM, settings.themeMode) { appViewModel.setThemeMode(it) }
                    ThemeChip("日间", ThemeMode.LIGHT, settings.themeMode) { appViewModel.setThemeMode(it) }
                    ThemeChip("夜间", ThemeMode.DARK, settings.themeMode) { appViewModel.setThemeMode(it) }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("动态取色", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "使用系统壁纸主色调（Android 12+）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.dynamicColor,
                        onCheckedChange = { appViewModel.setDynamicColor(it) }
                    )
                }
            }

            // ===== 备份与恢复 =====
            SectionCard(title = "备份与恢复", icon = Icons.Filled.Backup) {
                Text(
                    "备份地址",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    appViewModel.backupLocationLabel(settings) +
                        if (settings.backupTreeUri.isNotBlank()) "（自定义）" else "（默认）",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { pickDirLauncher.launch(null) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("选择位置")
                    }
                    if (settings.backupTreeUri.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                appViewModel.clearBackupTree()
                                Toast.makeText(context, "已恢复默认备份位置", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("恢复默认")
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val r = appViewModel.backupManually()
                                backupResult = r
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.SaveAlt, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("立即备份")
                    }
                    OutlinedButton(
                        onClick = { restoreLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Restore, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("恢复备份")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "备份内容：书架书籍、注释、记忆库卡片、词汇掌握度与全部设置。\n" +
                        "备份自动完成，应用进入后台或版本更新时也会自动备份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ===== 关于 =====
            SectionCard(title = "关于", icon = Icons.Filled.Info) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("应用版本", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        appViewModel.versionName.ifBlank { "未知" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    // ===== 备份完成信息提示框（地址 / 大小 / 时间） =====
    backupResult?.let { result ->
        val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
        AlertDialog(
            onDismissRequest = { backupResult = null },
            title = { Text(if (result.success) "备份完成" else "备份失败") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (result.success) {
                        InfoRow("备份地址", result.backupPath ?: "-")
                        InfoRow("文件大小", BackupManager.humanSize(result.sizeBytes))
                        InfoRow("备份时间", fmt.format(Date(result.timeMillis)))
                    } else {
                        Text(result.message)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { backupResult = null }) { Text("好的") }
            }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ThemeChip(
    label: String,
    mode: ThemeMode,
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit
) {
    FilterChip(
        selected = current == mode,
        onClick = { onSelect(mode) },
        label = { Text(label) }
    )
}

@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
