package com.zhiji.app.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 智能阅读器 - 书架书籍实体（Phase 1 MVP）
 * 后续接入 Readium 后可扩展格式/元数据字段。
 *
 * v39 新增 board 字段：区分所属板块，实现书架数据完全独立
 *   - "smart_reader"   = 智能阅读器板块（默认，老数据兼容）
 *   - "multi_format"   = 多格式阅读器板块（未来扩展 epub/pdf）
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String? = null,
    /** 文件格式：txt / md / epub (后续) / pdf (后续) */
    val format: String,
    /** 内容存储路径：contentResolver Uri 或 app internal 绝对路径 */
    val sourceUri: String,
    /** 纯文本/Markdown 内容缓存（避免 SAF 权限过期）；大文件用 file path */
    val content: String? = null,
    /** 原始文件字节数（SAF DocumentFile.length） */
    val sizeBytes: Long = 0L,
    /** 处理后正文总字符数（String.length，和 readOffset 同单位，用于计算阅读进度） */
    val totalChars: Int = 0,
    /** 当前阅读进度：0 ~ totalChars，Markdown/TXT 用 char offset */
    val readOffset: Int = 0,
    /** v39 所属板块：smart_reader / multi_format（书架数据隔离） */
    val board: String = "smart_reader",
    val lastReadAt: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)
