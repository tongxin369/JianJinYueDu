package com.zhiji.app.data.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Phase 3 - 中英混排改写持久化缓存（每段一行，双模式预存 + 统计明细）
 *
 * 用途：
 *  1. 书导入/打开后，后台整篇改写生成并落库 → 改写后界面切换页/切换模式无需重算，秒开
 *  2. 阅读时按 600 字范围实时检查改写，缺/过期段落即时计算回写
 *  3. 记忆库卡片变化（版本号递增）后，旧缓存失效 → 重新生成，贴合用户最新掌握水平
 *
 * 双模式：
 *  - replace  替换式：中文词 → 英文
 *  - annotate 对照式：中文词 → 中文(英文)
 *
 * @param memoryVersion 生成时的记忆库版本快照；卡片变化后递增，与当前版本不一致即视为过期
 * @param wordsJson     本段替换明细（JSON 序列化的 ZhEnRewriter.Replacement 列表），供「本页替换」统计弹窗查看
 */
@Entity(
    tableName = "book_rewrites",
    primaryKeys = ["bookId", "paragraphIndex"],
    indices = [Index("bookId")]
)
data class BookRewriteEntity(
    val bookId: String,
    val paragraphIndex: Int,
    val replace: String,
    val annotate: String,
    val memoryVersion: Long,
    val wordsJson: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
