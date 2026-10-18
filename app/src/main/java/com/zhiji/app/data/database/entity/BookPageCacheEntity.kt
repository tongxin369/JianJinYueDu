package com.zhiji.app.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 翻页式「整屏分页」结果的持久化缓存（每本书一行）。
 *
 * 为什么要持久化：大文件的分页用 StaticLayout 逐段实测真实高度，段落多时测量成本高。
 * 缓存后再次打开同一本书、同一排版参数时直接命中，无需重新分页。
 *
 * @param signature 排版参数指纹（段落数 + 总字数 + 可用宽高 px + 字体缩放）。
 *                  指纹不一致（换字体/换机型宽度/书内容变化）→ 视为失效，重新测量并覆盖。
 * @param pagesJson 分页区间序列化（"first:last,..."）。
 */
@Entity(tableName = "book_pages")
data class BookPageCacheEntity(
    @PrimaryKey val bookId: String,
    val signature: String,
    val pagesJson: String,
    val updatedAt: Long = System.currentTimeMillis()
)
