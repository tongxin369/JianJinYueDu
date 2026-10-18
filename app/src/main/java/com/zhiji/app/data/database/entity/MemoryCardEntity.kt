package com.zhiji.app.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 2 - 记忆卡片实体（FSRS v6 调度算法字段一体化存储）
 *
 * 卡片生命周期：
 *   1. 阅读器长按选文字 → 双标记 Sheet「✅会的 / ❓不会的」→ insert 一张 New 状态的 MemoryCard
 *   2. 记忆库界面「待复习」Tab 调 `dueAt <= now` 卡片流
 *   3. 用户选 Again/Hard/Good/Easy → FSRS 算法算下一次 dueAt + 新状态 → update card + 写 ReviewLog
 *
 * 标记语义（status）：
 *   "unknown" → ❓不会的（生词/不熟悉内容，9:1 引擎优先挑它当 10% 新内容）
 *   "known"   → ✅会的（已经掌握，9:1 引擎把它当 90% 背景素材）
 *   不管 known/unknown，只要是一张卡，FSRS 就会正常安排复习，防止「会的」真的遗忘。
 */
@Entity(
    tableName = "memory_cards",
    indices = [
        Index("status"),
        Index("dueAt"),
        Index("bookId"),
        Index("bookParagraphIndex"),
        Index("sourceType")
    ]
)
data class MemoryCardEntity(
    @PrimaryKey val id: String,
    /** 卡片正面（问题/原文/单词），阅读器里选中的那段文字 */
    val front: String,
    /** 卡片背面（释义/翻译/解释/记忆点），Phase 2 用户可手动写，Phase 4 AI 自动生成 */
    val back: String = "",
    /** 卡片类型："word"=单词/短语 "paragraph"=段落句子 "concept"=概念知识点（当前长按都当 paragraph） */
    val cardType: String = "paragraph",
    /** "unknown"=不会的 / "known"=会的（双标记 Sheet 选择结果） */
    val status: String,
    /** FSRS 状态：New=0 Learning=1 Review=2 Relearning=3（FSRS v6 State 枚举值） */
    val fsrsState: Int = 0,
    /** FSRS 难度 Stability (S)：记忆稳定度天数 */
    val fsrsStability: Double = 0.0,
    /** FSRS 难度 Difficulty (D)：难度值 1~10 */
    val fsrsDifficulty: Double = 5.0,
    /** FSRS 已复习次数（= Reviews），不包含学习/再学习阶段 */
    val fsrsReps: Int = 0,
    /** FSRS 连续正确次数 Lapses：复习失败时+1 */
    val fsrsLapses: Int = 0,
    /** 当前剩余间隔 (天)，Again/Hard/Good/Easy 打完分后 scheduler 返回的 I */
    val fsrsIntervalDays: Int = 0,
    /** 下次到期复习的时间戳（毫秒），记忆库"待复习"Tab 查询条件：dueAt <= System.currentTimeMillis() */
    val dueAt: Long = System.currentTimeMillis(),
    /** 最近一次复习时间戳（毫秒），0=从未复习过 */
    val lastReviewAt: Long = 0L,

    // ---- 来源信息（回溯卡片从哪来）----
    /** 来源类型："reader"=智能阅读器  "manual"=记忆库界面手动新增 （后续: ai 对话 / etc） */
    val sourceType: String = "reader",
    /** 来源书 ID (BookEntity.id)，reader 来源非空 */
    val bookId: String? = null,
    /** 来源书标题，做展示冗余（书被删了也能看标题） */
    val bookTitle: String? = null,
    /** 来源段落索引（阅读器段落序号），做定位跳转 */
    val bookParagraphIndex: Int? = null,
    /** 选中文本在段内的起始字符索引（回跳到书里时高亮还原选中态） */
    val bookCharOffsetStart: Int? = null,
    /** 选中文本在段内的结束字符索引 */
    val bookCharOffsetEnd: Int? = null,

    val createdAt: Long,
    val updatedAt: Long
)
