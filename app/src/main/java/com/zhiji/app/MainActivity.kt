package com.zhiji.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiji.app.ui.AppViewModel
import com.zhiji.app.ui.MainScreen
import com.zhiji.app.ui.theme.ZhiJiTheme
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ⚠️ 全局未捕获异常兜底（日志落盘，下次闪退即使 logcat buffer 冲掉也能拿到完整堆栈）
        //    - 写入 /sdcard/Android/data/com.zhiji.app/files/crash_yyyyMMdd_HHmmss.txt
        //    - 保持原系统默认 handler，不吞 ANR/System 崩溃
        installGlobalCrashFileLogger()

        enableEdgeToEdge()
        setContent {
            val appViewModel: AppViewModel = viewModel(factory = AppViewModel.Factory)
            val settings by appViewModel.appSettings.collectAsStateWithLifecycle()

            ZhiJiTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        appViewModel = appViewModel
                    )
                }
            }
        }
    }

    /** 安装全局崩溃日志文件记录器：任何未捕获 Throwable → 立即写入本地文件永久保留 */
    private fun installGlobalCrashFileLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
                val dir = getExternalFilesDir(null) ?: filesDir
                val f = File(dir, "crash_$ts.txt")
                val sw = StringWriter(4096)
                e.printStackTrace(PrintWriter(sw))

                // 不用 BuildConfig（AGP 8 默认禁用），改用 packageManager 动态拿当前包版本
                val pkgInfo = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getPackageInfo(packageName, 0)
                    }
                }.getOrNull()
                val verName = pkgInfo?.versionName ?: "unknown"
                val verCode = pkgInfo?.let {
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toString() else it.versionCode.toString()
                } ?: "unknown"

                f.writeText(
                    buildString {
                        append("=== ZhiJi Global Crash Log ===\n")
                        append("time  : ").append(ts).append('\n')
                        append("thread: ").append(t.name).append(" (id=").append(t.id).append(")\n")
                        append("pkg_ver: ").append(verName).append(" (code=").append(verCode).append(")\n")
                        append("\n--- exception ---\n").append(sw.toString())
                        append("\n--- cause chain ---\n")
                        var cause: Throwable? = e.cause
                        var depth = 0
                        while (cause != null && depth < 8) {
                            append(" [").append(depth).append("] ").append(cause::class.java.name).append(": ").append(cause.message).append('\n')
                            cause = cause.cause; depth++
                        }
                    }
                )
            }
            // 必须再交给原 handler（AMS 要杀进程、弹用户崩溃对话框）
            prev?.uncaughtException(t, e)
        }
    }
}
