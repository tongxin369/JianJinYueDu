package com.zhiji.app.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书籍注释（下划线 + 注释正文），支持：
 *  - 选字后点「注释」按键 → 建一条记录，对应段落内区间 (paragraphIndex, startInParagraph..endInParagraph)
 *  - 已有的注释在对应段落文字上用下划线 Span 渲染
 *  - 点击下划线区间内任意字符 → 直接弹注释编辑窗
 *  Book 删除时通过 Repository 级联删除所有 annotations（避免手动外键兼容 Room 多版本差异）。
 */
@Entity(
    tableName = "book_annotations",
    indices = [
        Index("bookId"),
        Index("bookId", "paragraphIndex"),
        Index("bookId", "paragraphIndex", "startInParagraph"),
        Index("updatedAt")
    ]
)
data class BookAnnotationEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val paragraphIndex: Int,
    val startInParagraph: Int,
    val endInParagraph: Int,
    val selectedText: String,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long
)
