package com.zhiji.app.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.zhiji.app.core.text.TextEncoding
import com.zhiji.app.data.database.dao.BookDao
import com.zhiji.app.data.database.entity.BookAnnotationEntity
import com.zhiji.app.data.database.entity.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Reader
import java.util.UUID

/**
 * 智能阅读器 - 书架/导入仓库（Phase 1 MVP）
 * 支持通过 SAF 选择 .txt / .md 文件导入：
 *  - 小文件（≤5MB）内容直接缓存进 DB（打开最快）
 *  - 大文件（>5MB）复制一份到应用私有目录（files/books/），打开时读本地文件，
 *    避免每次都走 SAF（远程 provider 读取慢，是大文件打开慢的主因）
 * 后续接入 Readium 时在此处扩展 EPUB/PDF 解析。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderRepository(
    private val context: Context,
    private val bookDao: BookDao
) {

    private val contentMaxCacheBytes = 5 * 1024 * 1024L // 5MB

    /** 大文件本地副本目录（应用私有，随书删除一并清理） */
    private val localBooksDir: File get() = File(context.filesDir, "books")

    /** v39 按板块返回书架流：smart_reader / multi_format */
    fun books(board: String): Flow<List<BookEntity>> = bookDao.getBooksByBoard(board)

    fun getBookFlow(id: String): Flow<BookEntity?> = bookDao.getBookByIdFlow(id)
    suspend fun getBook(id: String): BookEntity? = bookDao.getBookById(id)

    // ---------- Annotations (注释/下划线) ----------

    fun getAnnotationsByBookFlow(bookId: String?): Flow<List<BookAnnotationEntity>> {
        if (bookId.isNullOrBlank()) return flowOf(emptyList())
        return bookDao.getAnnotationsByBook(bookId)
    }

    suspend fun getAnnotationById(id: String): BookAnnotationEntity? = withContext(Dispatchers.IO) {
        bookDao.getAnnotationById(id)
    }

    /** 点某段某字符时查命中的注释（用于下划线弹窗） */
    suspend fun findAnnotationAt(
        bookId: String,
        paragraphIndex: Int,
        charPos: Int
    ): BookAnnotationEntity? = withContext(Dispatchers.IO) {
        bookDao.findAnnotationAtPosition(bookId, paragraphIndex, charPos)
    }

    /** 新建 / 更新注释（按 id REPLACE），返回更新后的 Entity */
    suspend fun upsertAnnotation(
        existingId: String?,
        bookId: String,
        paragraphIndex: Int,
        startInParagraph: Int,
        endInParagraph: Int,
        selectedText: String,
        note: String
    ): BookAnnotationEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entity = BookAnnotationEntity(
            id = existingId?.ifBlank { UUID.randomUUID().toString() } ?: UUID.randomUUID().toString(),
            bookId = bookId,
            paragraphIndex = paragraphIndex,
            startInParagraph = startInParagraph.coerceAtLeast(0),
            endInParagraph = endInParagraph.coerceAtLeast(startInParagraph.coerceAtLeast(0) + 1),
            selectedText = selectedText,
            note = note,
            createdAt = now,
            updatedAt = now
        )
        bookDao.insertAnnotation(entity)
        entity
    }

    suspend fun deleteAnnotation(id: String) = withContext(Dispatchers.IO) {
        bookDao.deleteAnnotationById(id)
    }

    /**
     * 阅读页分段规则（唯一来源）：\r\n 归一 + 按行切 + 每行 trimEnd。
     * 阅读页 LazyColumn / 改写的段落索引 / 进度字符累加全部以此为准，保证三者同域。
     */
    fun splitParagraphs(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return text.replace("\r\n", "\n").lines().map { it.trimEnd() }
    }

    /**
     * 正文「进度字符总数」= 各段长度之和（严格与阅读页 saveProgress 的段落累加对齐）：
     * - replace("\r\n", "\n")：去掉 \r 不计
     * - 每行 trimEnd()：尾部空格不计
     * - 空行 length=0：自动不计入总和
     * 这样 totalChars 与书架% / 阅读页% 分子分母 100% 同域。
     */
    private fun contentCharsOf(text: String): Int {
        if (text.isEmpty()) return 0
        var sum = 0
        for (raw in text.replace("\r\n", "\n").lines()) {
            sum += raw.trimEnd().length
        }
        return sum
    }

    /**
     * 流式统计字符数（与 [contentCharsOf] 完全等价，但不把整本书读进内存）。
     * 用于大文件导入：边复制边统计，避免 20MB 文件在导入时占用几十 MB 堆内存。
     */
    private fun countCharsStreaming(reader: Reader): Int {
        var sum = 0
        val line = StringBuilder()
        val buf = CharArray(64 * 1024)
        var pendingCR = false
        fun flushLine() {
            sum += line.toString().trimEnd().length
            line.setLength(0)
        }
        while (true) {
            val read = reader.read(buf)
            if (read <= 0) break
            for (i in 0 until read) {
                when (val c = buf[i]) {
                    '\n' -> if (!pendingCR) flushLine() else pendingCR = false
                    '\r' -> { flushLine(); pendingCR = true }
                    else -> { line.append(c); pendingCR = false }
                }
            }
        }
        flushLine()
        return sum
    }

    /** v39 导入时写入所属板块 board（智能阅读器默认 smart_reader） */
    suspend fun importFromUri(uri: Uri, board: String = "smart_reader"): BookEntity? = withContext(Dispatchers.IO) {
        val doc = DocumentFile.fromSingleUri(context, uri) ?: return@withContext null
        val name = doc.name ?: "未命名"
        val size = doc.length()
        val ext = name.substringAfterLast('.', "").lowercase()
        val format = when (ext) {
            "md", "markdown" -> "md"
            "txt", "text" -> "txt"
            else -> ext.ifBlank { "txt" }
        }
        val title = name.substringBeforeLast('.').ifBlank { name }

        val id = UUID.randomUUID().toString()
        var sourceUri = uri.toString()
        var content: String? = null
        var totalChars = 0
        if (size in 1..contentMaxCacheBytes) {
            // 小文件：整篇读进内存 + 缓存进 DB（打开时零 IO 等待）。编码感知解码，避免乱码
            content = runCatching {
                context.contentResolver.openInputStream(uri)?.use { TextEncoding.readText(it) }
            }.getOrNull()
            totalChars = if (content.isNullOrBlank()) 0 else contentCharsOf(content)
        } else {
            // 大文件：复制到应用私有目录（本地文件读取远快于 SAF），字符数流式统计不占内存
            runCatching {
                localBooksDir.mkdirs()
                val local = File(localBooksDir, "$id.txt")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    local.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                sourceUri = Uri.fromFile(local).toString()
                context.contentResolver.openInputStream(Uri.fromFile(local))?.use { input ->
                    TextEncoding.reader(input).use { reader -> totalChars = countCharsStreaming(reader) }
                }
            }.onFailure { Log.w("ReaderRepository", "大文件本地副本创建失败，回退直接读 SAF", it) }
        }

        val now = System.currentTimeMillis()
        val entity = BookEntity(
            id = id,
            title = title,
            format = format,
            sourceUri = sourceUri,
            content = content,
            sizeBytes = size,
            totalChars = totalChars,
            board = board,
            createdAt = now,
            updatedAt = now
        )
        bookDao.insert(entity)
        entity
    }

    /**
     * 读取书籍正文：优先缓存内容；大文件/缓存空则从 Uri 重新读取。
     * 大文件导入时 sourceUri 已指向应用私有副本（file://），本地读取远快于 SAF。
     * 防御性修正：如果 totalChars 与当前 contentCharsOf(text) 不一致（旧算法遗留/老书未算），
     * 立即写回正确值，保证书架和阅读页进度百分比完全一致（不会出现 13%→11% 的跳变）。
     */
    suspend fun readBookContent(book: BookEntity): String? = withContext(Dispatchers.IO) {
        val text = if (!book.content.isNullOrBlank()) {
            book.content
        } else {
            runCatching {
                val uri = Uri.parse(book.sourceUri) ?: return@runCatching null
                context.contentResolver.openInputStream(uri)?.use { TextEncoding.readText(it) }
            }.getOrNull()
        }
        if (!text.isNullOrEmpty()) {
            val expected = contentCharsOf(text)
            if (book.totalChars != expected) {
                runCatching { bookDao.updateTotalChars(book.id, expected) }
            }
        }
        text
    }

    /**
     * 乱码自愈：若 DB 缓存正文含替换符（U+FFFD，旧版本按错编码解码所致 → 界面显示一堆「？」），
     * 从 sourceUri 按探测编码重读并回写 content/totalChars。
     * @return 修复后的 BookEntity；无需修复或不修不了时返回 null
     */
    suspend fun repairGarbledContentIfNeeded(book: BookEntity): BookEntity? = withContext(Dispatchers.IO) {
        val cached = book.content
        if (cached.isNullOrEmpty() || !TextEncoding.looksGarbled(cached)) return@withContext null
        val fixed = runCatching {
            val uri = Uri.parse(book.sourceUri) ?: return@runCatching null
            context.contentResolver.openInputStream(uri)?.use { TextEncoding.readText(it) }
        }.getOrNull()
        if (fixed.isNullOrEmpty() || TextEncoding.looksGarbled(fixed)) return@withContext null
        val updated = book.copy(content = fixed, totalChars = contentCharsOf(fixed))
        runCatching { bookDao.update(updated) }
        updated
    }

    /**
     * 读取书籍正文并按阅读页规则切段（全部在 IO 线程完成）。
     * 阅读页只消费切好的段落列表：避免在首帧组合里对整篇大文本做 replace+lines+map 造成打开卡顿。
     */
    suspend fun readBookParagraphs(book: BookEntity): List<String> = withContext(Dispatchers.IO) {
        val text = readBookContent(book) ?: return@withContext emptyList()
        splitParagraphs(text)
    }

    /**
     * 「快速首屏」读取：只读开头 [maxChars] 个字符并按阅读页规则切段。
     * 大文件也无需等全文切段即可渲染第一屏；随后 [readBookParagraphs] 读全文替换，
     * 因结果前缀完全一致，段落索引与字符偏移不会漂移（进度/还原位置都安全）。
     * 若 [maxChars] >= 全文长度则等价于全文。
     */
    suspend fun readBookParagraphsPrefix(book: BookEntity, maxChars: Int): List<String> = withContext(Dispatchers.IO) {
        if (maxChars <= 0) return@withContext emptyList()
        val text: String? = if (!book.content.isNullOrBlank()) {
            val c = book.content
            if (c.length > maxChars) c.substring(0, maxChars) else c
        } else {
            runCatching {
                val uri = Uri.parse(book.sourceUri) ?: return@runCatching null
                context.contentResolver.openInputStream(uri)?.use { input ->
                    TextEncoding.readText(input, maxChars)
                }
            }.getOrNull()
        }
        if (text.isNullOrEmpty()) emptyList() else splitParagraphs(text)
    }

    suspend fun saveProgress(bookId: String, charOffset: Int) {
        bookDao.updateProgress(bookId, charOffset.coerceAtLeast(0), System.currentTimeMillis())
    }

    /**
     * 删除已导入的电子书：清理注释 + 书行 + 大文件本地副本。
     * 本地副本只在确实位于本应用私有 books 目录时才删（绝不误删用户原始文件）。
     */
    suspend fun deleteBook(bookId: String) = withContext(Dispatchers.IO) {
        val book = bookDao.getBookById(bookId)
        runCatching {
            val path = Uri.parse(book?.sourceUri ?: return@runCatching).path
            if (path != null && path.startsWith(localBooksDir.absolutePath + File.separator)) {
                File(path).delete()
            }
        }
        bookDao.deleteAnnotationsByBook(bookId)
        bookDao.deleteById(bookId)
    }
}
