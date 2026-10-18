package com.zhiji.app.ui.feature.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zhiji.app.core.tts.TtsModelStore
import kotlinx.coroutines.launch

/**
 * 离线语音包按需下载弹窗。
 *
 * 使用方式：在「朗读」入口判断 [TtsModelStore.isReady]，
 * 未就绪时把 [visible] 置 true；下载成功后回调 [onReady]，由调用方继续执行朗读。
 */
@Composable
fun TtsDownloadDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onReady: () -> Unit
) {
    if (!visible) return

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var percent by remember { mutableIntStateOf(-1) }
    var label by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("下载离线语音包") },
        text = {
            Column {
                Text(
                    "朗读功能需要一个离线语音包（约 ${TtsModelStore.APPROX_SIZE_MB} MB），" +
                        "仅需下载一次，之后可完全离线朗读，不再消耗流量。"
                )
                if (downloading || percent >= 0 || error != null) {
                    androidx.compose.foundation.layout.Spacer(Modifier.height(14.dp))
                    if (percent in 0..100) {
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    Text(label, style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
                    Text(
                        "下载失败：$it",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !downloading,
                onClick = {
                    downloading = true
                    error = null
                    percent = 0
                    label = "准备中…"
                    scope.launch {
                        val result = TtsModelStore.install(ctx) { p, l ->
                            percent = p
                            label = l
                        }
                        downloading = false
                        result
                            .onSuccess { onReady() }
                            .onFailure {
                                percent = -1
                                error = it.message ?: "未知错误"
                            }
                    }
                }
            ) { Text(if (error == null) "下载" else "重试") }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = onDismiss) { Text("取消") }
        }
    )
}
