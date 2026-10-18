package com.zhiji.app

import android.app.Application
import android.content.pm.PackageInfo
import android.os.Build
import android.util.Log
import com.zhiji.app.data.backup.BackupManager
import com.zhiji.app.data.database.AppDatabase
import com.zhiji.app.data.datastore.SettingsDataStore
import com.zhiji.app.data.repository.EcdictRepository
import com.zhiji.app.data.repository.MemoryRepository
import com.zhiji.app.data.repository.ReaderRepository
import com.zhiji.app.data.repository.VocabTestRepository
import com.zhiji.app.data.repository.ZhEnRewriteRepository
import com.zhiji.app.ui.feature.ai.OfflineTtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ZhiJiApp : Application() {

    val database by lazy { AppDatabase.getInstance(this) }
    val settingsDataStore by lazy { SettingsDataStore(this) }
    val readerRepository by lazy { ReaderRepository(this, database.bookDao()) }
    /** 离线英汉词典（ECDICT 精简版，注释窗「词典释义」Tab 使用） */
    val ecdictRepository by lazy { EcdictRepository(this) }
    /** Phase 2 记忆库：卡片 CRUD + FSRS 调度 */
    val memoryRepository by lazy { MemoryRepository(database.memoryDao()) }
    /** Phase 3 中英混排改写：中文词→英文（记忆库联动 + 等级注入未测试词 + 用户可调难度档位 + 600字一页 + 全篇后台改写持久化） */
    val zhEnRewriteRepository by lazy {
        ZhEnRewriteRepository(
            ecdictRepository, memoryRepository, database.bookRewriteDao(),
            database.bookPageCacheDao(), database.vocabProficiencyDao(), settingsDataStore
        )
    }
    /** 词汇掌握度测试：自适应诊断 + 实时写入记忆库 + CEFR 等级/历史(成长曲线)存储 */
    val vocabTestRepository by lazy {
        VocabTestRepository(ecdictRepository, database.memoryDao(), database.vocabProficiencyDao(), database.vocabHistoryDao())
    }

    /**
     * 离线朗读引擎（全局单例）：
     * 在 App 启动时就后台预加载模型，进入阅读页朗读时无需等待模型加载。
     */
    val ttsSpeaker by lazy { OfflineTtsSpeaker(this) }

    /**
     * 全局协程作用域（与 Application 同生命周期），用于：
     *   - onTrimMemory 进后台触发自动备份
     *   - onCreate 版本升级检测触发自动备份
     * SupervisorJob：单个子协程崩溃不会影响其他任务
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 词库掌握度重算的防抖任务（连续多次卡片变化只算一次） */
    private var masteryRefreshJob: kotlinx.coroutines.Job? = null

    private fun scheduleMasteryRefresh() {
        masteryRefreshJob?.cancel()
        masteryRefreshJob = appScope.launch {
            kotlinx.coroutines.delay(800)
            runCatching { vocabTestRepository.refreshGradeFromMastery() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // App 启动即后台预加载 TTS 模型（首个朗读响应更快）
        ttsSpeaker.preload()

        // 记忆库卡片变化 → 防抖重算词库掌握等级（不抽卡也知道词变多了也能看到进步）
        runCatching { memoryRepository.onCardsChanged = { scheduleMasteryRefresh() } }
        // 启动时先刷新一次等级（老数据/已有记忆库也能立刻得到词库掌握进度）
        appScope.launch {
            runCatching {
                vocabTestRepository.ensureReady()
                vocabTestRepository.refreshGradeFromMastery()
            }
        }

        // 全局未捕获异常处理器：先把堆栈写进 logcat(tag=ZhiJiCrash) 再交给系统，便于排查闪退
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("ZhiJiCrash", "UNCAUGHT on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable) ?: run { throw throwable }
        }

        // ---- 应用版本变化 → 自动备份（应对自动更新/覆盖安装） ----
        appScope.launch {
            runCatching {
                val currentVersion = currentLongVersionCode()
                val lastVersion = settingsDataStore.getLastBackupVersion()
                if (currentVersion > 0 && currentVersion != lastVersion) {
                    Log.i("ZhiJiApp", "检测到版本变化 $lastVersion → $currentVersion，触发自动备份")
                    val r = BackupManager.backup(this@ZhiJiApp, auto = true)
                    if (r.success) {
                        settingsDataStore.setLastBackupVersion(currentVersion)
                    }
                    Log.i("ZhiJiApp", "版本升级自动备份结果：${r.message}")
                }
            }
        }
    }

    /**
     * 应用进入后台（用户按 Home / 切换其他 App / 最近任务划走）：
     * Android 保证一定会回调 TRIM_MEMORY_UI_HIDDEN（远比 onDestroy 可靠），
     * 在此触发自动备份，应对「用户以为关了其实闪退 / 系统杀进程」等突发情况。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_UI_HIDDEN) {
            appScope.launch {
                runCatching {
                    val r = BackupManager.backup(this@ZhiJiApp, auto = true)
                    Log.i("ZhiJiApp", "进后台自动备份：${r.message}")
                    // 后台备份成功后也更新版本号，避免 onCreate 又重复触发
                    if (r.success && "已跳过" !in r.message) {
                        runCatching { settingsDataStore.setLastBackupVersion(currentLongVersionCode()) }
                    }
                }
            }
        }
    }

    private fun currentLongVersionCode(): Long {
        return runCatching {
            val pm = packageManager
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        }.getOrDefault(0L)
    }

    companion object {
        lateinit var instance: ZhiJiApp
            private set
    }
}
