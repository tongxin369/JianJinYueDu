package com.zhiji.app.data.backup

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.zhiji.app.ZhiJiApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 软件全局备份/恢复管理器。
 *
 * 备份范围：
 *   - Room 数据库 zhiji.db（所有表：BookEntity 书架 + BookAnnotation 注释 + MemoryCard 记忆卡 + 未来所有新表）
 *   - SettingsDataStore：主题 / 阅读偏好 / 未来所有偏好配置
 *
 * 备份格式：ZIP（扩展名 .zbak = ZhiJi Backup），结构：
 *   /db/zhiji.db
 *   /datastore/zhiji_settings.preferences_pb
 *
 * 恢复：兼容旧 .db 单文件备份（只恢复 Room） + 新 .zbak ZIP 全局备份（全量恢复）。
 * 存储位置：/sdcard/Download/Zhiji/ （MediaStore.Downloads API，无需权限）。
 *
 * 自动备份策略：
 *   - auto=true 时启用 30s 节流（@see [lastAutoBackupAt]），避免短时间进进出出后台频繁写盘
 *   - 每次备份成功后自动保留 Download/Zhiji 下最新 30 个 .zbak，更旧的自动删除
 */
object BackupManager {

    // --------- 常量 ---------
    private const val DB_NAME = "zhiji.db"
    private const val DATASTORE_DIR = "datastore"
    private const val DATASTORE_FILE = "zhiji_settings.preferences_pb"
    private const val BACKUP_PREFIX = "zhiji_backup_"
    private const val BACKUP_EXT = ".zbak"  // ZhiJi Backup (ZIP 格式)
    const val LEGACY_DB_EXT = ".db"
    private const val FOLDER_NAME = "Zhiji"
    private const val MIME = "application/octet-stream"

    private const val ZIP_DB_PATH = "db/$DB_NAME"
    private const val ZIP_DS_PATH = "datastore/$DATASTORE_FILE"

    /** 自动备份最小间隔 30 秒，节流防频繁写盘 */
    private const val AUTO_THROTTLE_MS = 30_000L

    /** 只保留最近 30 个备份文件，更旧的自动清理 */
    private const val KEEP_LATEST_COUNT = 30

    @Volatile
    private var lastAutoBackupAt: Long = 0L

    private val timestampFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    private val displayTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    data class BackupResult(val success: Boolean, val message: String, val backupPath: String? = null)

    /** 列表弹窗展示用：MediaStore 扫出来的一条备份文件 */
    data class BackupFileInfo(
        val uri: Uri,
        val displayName: String,
        val sizeBytes: Long,
        val dateAddedMs: Long,
        val isLegacyDb: Boolean
    ) {
        val sizeText: String get() = formatSize(sizeBytes)
        val dateText: String get() = displayTimeFmt.format(Date(dateAddedMs * 1000L))
    }

    // ==================================================================
    //  列出 Download/Zhiji 下的所有备份（.zbak + 旧版 .db），按添加时间倒序
    // ==================================================================
    suspend fun listBackups(context: Context): List<BackupFileInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            // Android 10+ 用 RELATIVE_PATH 精确过滤 Download/Zhiji
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME%")
            val sortOrder = "${MediaStore.Downloads.DATE_ADDED} DESC"
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_ADDED
            )
            val out = mutableListOf<BackupFileInfo>()
            resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cur ->
                val idCol = cur.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = cur.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeCol = cur.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val dateCol = cur.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
                while (cur.moveToNext()) {
                    val id = cur.getLong(idCol)
                    val name = cur.getString(nameCol) ?: continue
                    if (!name.endsWith(BACKUP_EXT, ignoreCase = true)
                        && !name.endsWith(LEGACY_DB_EXT, ignoreCase = true)
                    ) continue
                    out += BackupFileInfo(
                        uri = Uri.withAppendedPath(collection, id.toString()),
                        displayName = name,
                        sizeBytes = runCatching { cur.getLong(sizeCol) }.getOrDefault(0L),
                        dateAddedMs = runCatching { cur.getLong(dateCol) }.getOrDefault(0L),
                        isLegacyDb = name.endsWith(LEGACY_DB_EXT, ignoreCase = true)
                    )
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    // ==================================================================
    //  备份：全局打包 ZIP → Download/Zhiji → 保留最新 30 份
    //  @param auto  true=自动备份触发（启用30s节流）；false=用户手动点击（不限频）
    // ==================================================================
    suspend fun backup(context: Context, auto: Boolean = false): BackupResult = withContext(Dispatchers.IO) {
        // 自动备份节流：30s 内重复触发直接跳过
        if (auto) {
            val now = System.currentTimeMillis()
            if (now - lastAutoBackupAt < AUTO_THROTTLE_MS) {
                return@withContext BackupResult(true, "自动备份已跳过（30秒内已备份过）")
            }
            lastAutoBackupAt = now
        }
        runCatching {
            // 1. Room DB checkpoint：合并 WAL → 主 db 完整，再拷贝
            runCatching {
                ZhiJiApp.instance.database.openHelper.writableDatabase
                    .execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            }

            val dbFile = context.getDatabasePath(DB_NAME)
                ?: return@withContext BackupResult(false, "找不到数据库文件")
            if (!dbFile.exists()) {
                return@withContext BackupResult(false, "数据库为空，无需备份")
            }
            val dsFile = datastoreFile(context)

            // 2. 先把 ZIP 写 cache 临时文件，再一次性提交 MediaStore
            val tmpZip = File(context.cacheDir, "backup_${timestampFmt.format(Date())}$BACKUP_EXT")
            ZipOutputStream(FileOutputStream(tmpZip)).use { zos ->
                addZipEntry(zos, ZIP_DB_PATH, dbFile)
                if (dsFile.exists()) addZipEntry(zos, ZIP_DS_PATH, dsFile)
            }

            val filename = buildString {
                append(BACKUP_PREFIX)
                append(timestampFmt.format(Date()))
                append(BACKUP_EXT)
            }

            // 3. MediaStore 写入 → Download/Zhiji
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME")
                put(MediaStore.Downloads.MIME_TYPE, MIME)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: return@withContext BackupResult(false, "创建 Download/Zhiji 目录失败，请检查存储")

            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(tmpZip).use { it.copyTo(out) }
            } ?: return@withContext BackupResult(false, "打开输出流失败")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            tmpZip.delete()

            // 4. 清理：只保留最新 30 个 .zbak，更旧的删除
            runCatching { cleanupOldBackups(context) }

            BackupResult(true, "全局备份成功：$FOLDER_NAME/$filename", "$FOLDER_NAME/$filename")
        }.getOrElse { t ->
            BackupResult(false, "备份失败：${describeThrowable(t)}")
        }
    }

    // ==================================================================
    //  恢复：ZIP / 旧版 DB → 覆盖 → 冷重启
    //  支持传入 SAF Uri 或 MediaStore query 得到的 Uri（listBackups 项点击即走这里）
    // ==================================================================
    suspend fun restore(context: Context, backupUri: Uri): BackupResult = withContext(Dispatchers.IO) {
        runCatching {
            // 1. 把外部文件整份读入 cache tmpIn（SAF Uri 直接开 ZipInputStream 可能不支持 mark/reset）
            val tmpIn = File(context.cacheDir, "restore_tmp_${System.currentTimeMillis()}")
            context.contentResolver.openInputStream(backupUri)?.use { input ->
                tmpIn.outputStream().use { out -> input.copyTo(out) }
            } ?: return@withContext BackupResult(false, "无法读取备份文件，请确认文件存在且有权限")

            if (tmpIn.length() <= 0L) {
                cleanup(listOf(tmpIn))
                return@withContext BackupResult(false, "备份文件为空，可能已损坏")
            }

            val isZip = isZipFile(tmpIn)

            val dbDir = context.getDatabasePath(DB_NAME).parentFile
                ?: return@withContext BackupResult(false, "内部数据库目录不存在")
            val dsDir = datastoreDir(context)
            if (!dsDir.exists() && !dsDir.mkdirs()) {
                return@withContext BackupResult(false, "无法创建 datastore 目录：$dsDir")
            }

            // 2. 解包 → 放 cache 临时文件（解包后再 copy 到目标，避免中间失败污染目标目录）
            val tmpDb = File(context.cacheDir, "restore_db_tmp_${System.currentTimeMillis()}")
            val tmpDs = File(context.cacheDir, "restore_ds_tmp_${System.currentTimeMillis()}")
            var hasDs = false

            if (isZip) {
                // ZIP 新版：提取 db + datastore
                ZipInputStream(FileInputStream(tmpIn)).use { zis ->
                    var entry: ZipEntry?
                    while (zis.nextEntry.also { entry = it } != null) {
                        val en = entry!!
                        when {
                            en.name.equals(ZIP_DB_PATH, ignoreCase = true) ||
                                en.name.equals("db/$DB_NAME", ignoreCase = true) -> {
                                FileOutputStream(tmpDb).use { zis.copyTo(it) }
                            }
                            en.name.equals(ZIP_DS_PATH, ignoreCase = true) ||
                                en.name.endsWith("/$DATASTORE_FILE", ignoreCase = true) -> {
                                FileOutputStream(tmpDs).use { zis.copyTo(it) }
                                hasDs = true
                            }
                        }
                    }
                }
                if (!tmpDb.exists() || tmpDb.length() <= 0L) {
                    cleanup(listOf(tmpIn, tmpDb, tmpDs))
                    return@withContext BackupResult(false, "ZIP 内未找到 db/zhiji.db，文件可能损坏")
                }
            } else {
                // 旧版 .db 单文件：直接作为临时 DB，DataStore 不动
                tmpIn.copyTo(tmpDb, overwrite = true)
            }

            // 3. 关闭 Room，清空单例（防止 DB 句柄占用导致覆盖失败）
            runCatching { ZhiJiApp.instance.database.close() }
            runCatching {
                val instanceField = Class.forName("com.zhiji.app.data.database.AppDatabase\$Companion")
                    .getDeclaredField("INSTANCE").apply { isAccessible = true }
                instanceField.set(null, null)
            }

            // 4. 删除旧 DB / WAL / SHM
            listOf("", "-wal", "-shm").forEach { suffix ->
                val f = File(dbDir, DB_NAME + suffix)
                if (f.exists()) f.delete()
            }

            // 5. 新 DB → 就位（renameTo 失败就 copyTo 兜底）
            val targetDb = File(dbDir, DB_NAME)
            if (!tmpDb.renameTo(targetDb)) {
                tmpDb.copyTo(targetDb, overwrite = true)
                tmpDb.delete()
            }

            // 6. 新 DataStore → 就位（如有）
            if (isZip && hasDs && tmpDs.exists() && tmpDs.length() > 0L) {
                val oldDs = File(dsDir, DATASTORE_FILE)
                // DataStore 伴随文件：<name>.preferences_pb.bak / .journal 也一起删避免脏
                dsDir.listFiles()?.forEach { f ->
                    if (f.name.startsWith(DATASTORE_FILE)) runCatching { f.delete() }
                }
                if (!tmpDs.renameTo(oldDs)) {
                    tmpDs.copyTo(oldDs, overwrite = true)
                    tmpDs.delete()
                }
            }

            cleanup(listOf(tmpIn, tmpDb, tmpDs))

            // 7. 冷重启（清内存中 StateFlow / 单例缓存，保证恢复后不读脏数据）
            val pm = context.packageManager
            val launch = pm.getLaunchIntentForPackage(context.packageName)
                ?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            if (launch != null) {
                context.startActivity(launch)
                Runtime.getRuntime().exit(0)
            }
            BackupResult(true, "全局恢复成功，正在重启应用…")
        }.getOrElse { t ->
            BackupResult(false, "恢复失败：${describeThrowable(t)}")
        }
    }

    // ==================================================================
    //  私有工具
    // ==================================================================
    private fun datastoreDir(context: Context): File =
        File(context.filesDir, DATASTORE_DIR).apply {
            if (!exists()) runCatching { mkdirs() }
        }

    private fun datastoreFile(context: Context): File =
        File(datastoreDir(context), DATASTORE_FILE)

    private fun addZipEntry(zos: ZipOutputStream, path: String, file: File) {
        val entry = ZipEntry(path)
        entry.size = file.length()
        zos.putNextEntry(entry)
        FileInputStream(file).use { it.copyTo(zos) }
        zos.closeEntry()
    }

    /** ZIP 文件头魔数 = PK\x03\x04 */
    private fun isZipFile(f: File): Boolean {
        if (f.length() < 4) return false
        val magic = ByteArray(4)
        FileInputStream(f).use { it.read(magic) }
        return magic[0] == 0x50.toByte() /* P */
            && magic[1] == 0x4B.toByte() /* K */
            && magic[2] == 0x03.toByte()
            && magic[3] == 0x04.toByte()
    }

    /** 把异常变成「类型 + 可读消息 + 根因」三段，避免 FileNotFoundException 只显示半截路径用户看不懂 */
    private fun describeThrowable(t: Throwable): String {
        val simple = t.javaClass.simpleName
        val causeMsg = t.cause?.message?.takeIf { it.isNotBlank() }
        val msg = t.message?.takeIf { it.isNotBlank() }
            ?: causeMsg
            ?: "(无错误信息)"
        return buildString {
            append(simple)
            append("：")
            append(msg.take(180)) // 限制长度防止 Toast 超出
            if (causeMsg != null && causeMsg != msg) {
                append(" 原因：").append(causeMsg.take(80))
            }
        }
    }

    private fun cleanup(files: List<File>) {
        files.forEach { runCatching { if (it.exists()) it.delete() } }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1fMB".format(bytes / (1024.0 * 1024))
        else -> "%.2fGB".format(bytes / (1024.0 * 1024 * 1024))
    }

    /**
     * 清理 Download/Zhiji 下备份：按 DATE_ADDED 降序取前 [KEEP_LATEST_COUNT] 保留，其余删除。
     * 只管理 .zbak（新格式），旧版 .db 不删，用户可能手动存的。
     */
    private fun cleanupOldBackups(context: Context) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME%")
        val sortOrder = "${MediaStore.Downloads.DATE_ADDED} DESC"
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME
        )
        val toDelete = mutableListOf<Long>()
        resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cur ->
            val idCol = cur.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cur.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            var idx = 0
            while (cur.moveToNext()) {
                idx++
                val name = cur.getString(nameCol) ?: continue
                if (!name.endsWith(BACKUP_EXT, ignoreCase = true)) continue
                if (idx > KEEP_LATEST_COUNT) toDelete += cur.getLong(idCol)
            }
        }
        toDelete.forEach { id ->
            runCatching {
                val uri = Uri.withAppendedPath(collection, id.toString())
                resolver.delete(uri, null, null)
            }
        }
    }
}
