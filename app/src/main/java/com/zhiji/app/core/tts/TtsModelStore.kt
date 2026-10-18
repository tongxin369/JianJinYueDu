package com.zhiji.app.core.tts

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** 离线语音包尚未下载时抛出（UI 侧据此引导用户下载）。 */
class TtsModelMissingException : Exception("离线语音包尚未下载")

/**
 * 离线语音包（sherpa-onnx vits-melo-tts-zh_en）的按需下载与本地管理。
 *
 * - 语音包不再随 APK 打包（原本约 169MB，是安装包体积的主要来源）
 * - 用户首次点击「朗读」时按需下载，仅一次，之后完全离线可用
 * - 下载后解压到 filesDir/tts/vits-melo-tts-zh_en，供 [com.zhiji.app.ui.feature.ai.OfflineTtsSpeaker] 从文件系统加载
 */
object TtsModelStore {

    const val MODEL_DIR_NAME = "vits-melo-tts-zh_en"

    /** 官方发布地址（k2-fsa/sherpa-onnx releases，tts-models 目录） */
    const val DOWNLOAD_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2"

    /** 语音包大致体积（MB），仅用于 UI 提示；实际约 159MB 压缩包 / 169MB 解压后 */
    const val APPROX_SIZE_MB = 160

    /** 判定「已安装」需要齐备的文件 */
    private val REQUIRED_FILES = listOf(
        "model.onnx", "lexicon.txt", "tokens.txt",
        "date.fst", "number.fst", "phone.fst", "new_heteronym.fst"
    )

    private fun root(context: Context) = File(context.filesDir, "tts")

    /** 语音包最终落地目录（绝对路径） */
    fun modelDir(context: Context): File = File(root(context), MODEL_DIR_NAME)

    /** 语音包是否已下载且完整 */
    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        return REQUIRED_FILES.all { File(dir, it).let { f -> f.isFile && f.length() > 0L } }
    }

    /** 已安装语音包占用的字节数（0 表示未安装） */
    fun installedSizeBytes(context: Context): Long {
        val dir = modelDir(context)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** 删除已下载语音包（释放空间） */
    fun delete(context: Context): Boolean {
        val dir = modelDir(context)
        return !dir.exists() || dir.deleteRecursively()
    }

    /**
     * 下载并解压官方语音包。
     *
     * @param onProgress 进度回调：(下载百分比 0..100，-1 表示长度未知；阶段文案)
     * 返回成功即代表 [isReady] 为 true。
     */
    suspend fun install(
        context: Context,
        onProgress: (percent: Int, label: String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val root = root(context)
        val target = modelDir(context)
        val tmpArchive = File(context.cacheDir, "$MODEL_DIR_NAME.tar.bz2")
        val staging = File(context.cacheDir, "tts_staging")
        try {
            staging.deleteRecursively()
            onProgress(0, "正在连接服务器…")
            download(DOWNLOAD_URL, tmpArchive) { read, total ->
                val pct = if (total > 0L) ((read * 100) / total).toInt().coerceIn(0, 100) else -1
                onProgress(pct, if (pct >= 0) "正在下载语音包 $pct%" else "正在下载语音包…")
            }

            onProgress(100, "正在解压语音包…")
            staging.mkdirs()
            extractTarBz2(tmpArchive, staging)

            // 兼容两种打包结构：带顶层目录 或 平铺文件
            val onnx = staging.walkTopDown().firstOrNull { it.isFile && it.name == "model.onnx" }
            val stagedModel = onnx?.parentFile
            if (stagedModel == null) {
                return@withContext Result.failure(IllegalStateException("语音包内容不完整"))
            }

            target.deleteRecursively()
            root.mkdirs()
            if (stagedModel != staging && !stagedModel.renameTo(target)) {
                stagedModel.copyRecursively(target, overwrite = true)
                stagedModel.deleteRecursively()
            } else if (stagedModel == staging) {
                staging.renameTo(target)
            }

            if (!isReady(context)) return@withContext Result.failure(IllegalStateException("语音包安装失败"))
            onProgress(100, "语音包已就绪")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { tmpArchive.delete() }
            runCatching { staging.deleteRecursively() }
        }
    }

    private fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "YueJian/1.0")
        }
        try {
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) error("服务器返回 HTTP $code")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var read = 0L
                    var lastNotify = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (read - lastNotify >= 1_000_000L) {
                            onProgress(read, total)
                            lastNotify = read
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 解压 .tar.bz2（commons-compress 提供 bzip2 + tar 支持），带目录穿越防护。 */
    private fun extractTarBz2(archive: File, destRoot: File) {
        val basePath = destRoot.canonicalPath + File.separator
        FileInputStream(archive).use { fis ->
            BZip2CompressorInputStream(BufferedInputStream(fis)).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        val out = File(destRoot, entry.name)
                        if (!out.canonicalPath.startsWith(basePath)) continue
                        if (entry.isDirectory) {
                            out.mkdirs()
                            continue
                        }
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos -> tar.copyTo(fos) }
                    }
                }
            }
        }
    }
}
